from __future__ import annotations

import base64
from datetime import UTC, datetime, timedelta
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import types
from unittest import mock
import zipfile
import zlib

import httpx
import pytest
from model.two_model_runtime import DEFAULT_RUNTIME_CONFIG, UNIFIED_WALKSAFE_CLASSES

from scripts.check_walksafe_release_evidence_20260711 import (
    EvidenceFailure,
    required_checks,
    validate_agency_submission_receipt,
    validate_backup_manifest,
    validate_backup_storage_evidence,
    validate_web_accessibility_evidence,
    validate_live_detector_environment,
    validate_live_release_environment,
    validate_live_rbac_environment,
    validate_check,
    validate_registered_deployment,
    validate_release_artifacts,
    required_absolute_environment_path,
    validate_running_backend_source,
    validate_running_web_build,
    run_detector_smoke,
    validate_source_revision,
    validate_restore_drill_receipt,
    validate_report_retention_manifest,
    validate_retention_receipt,
    _validate_submission_privacy_receipt_chain as validate_submission_privacy_receipt,
    validate_design_document_manifest,
)
import scripts.check_walksafe_release_evidence_20260711 as release_gate
import scripts.walksafe_external_check_receipt as external_receipt


WEB_BUILD_ENVIRONMENT = {
    "NEXT_PUBLIC_DETECTOR_MODE": "server-v2",
    "NEXT_PUBLIC_WALKSAFE_PWA_ENABLED": "true",
    "NEXT_PUBLIC_WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING": "false",
    "NEXT_PUBLIC_WALKSAFE_TEST_CAPTURE_PANEL": "false",
}
WEB_QUALITY_RECEIPT_NAMES = (
    "node-toolchain",
    "node-toolchain-post",
    "npm-ci",
    "npm-audit",
    "npm-lint",
    "npm-typecheck",
    "npm-test",
    "npm-build",
    "runtime-trace",
    "browser-lifecycle",
)


def write_json(path: Path, payload: dict[str, object]) -> Path:
    path.write_text(json.dumps(payload), encoding="utf-8")
    return path


def write_web_archive(path: Path, build_root: Path) -> Path:
    with tarfile.open(path, "w:gz") as archive:
        archive.add(build_root, arcname=".")
    return path


def artifact_record(path: Path, base_dir: Path, *, name: str | None = None) -> dict[str, object]:
    record: dict[str, object] = {
        "path": path.relative_to(base_dir).as_posix(),
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "bytes": path.stat().st_size,
    }
    if name is not None:
        record["name"] = name
    return record


DEBUG_BUILD_CONFIG_TEMPLATE_COMMIT = b"a" * 40
DEBUG_BUILD_CONFIG_TEMPLATE_MARKER = b"walksafe-debug-v1"
DEBUG_BUILD_CONFIG_DEX_TEMPLATE = base64.b64decode(
    "ZGV4CjAzOADfqCOnEuP2di3qGZxhDE+Nio0t8OVw1XmMAwAAcAAAAHhWNBIAAAAAAAAAAOwCAAAPAAAAcAAAAAUAAACsAAAAAQAA"
    "AMAAAAAEAAAAzAAAAAIAAADsAAAAAQAAAPwAAABwAgAAHAEAADgBAABAAQAATAEAAF4BAABlAQAAeQEAAI0BAAC6AQAAvQEAANQB"
    "AADsAQAA7wEAABkCAAAgAgAAMwIAAAQAAAAFAAAABgAAAAcAAAAKAAAABwAAAAMAAAAAAAAAAgABAAEAAAACAAQAAwAAAAIAAQAI"
    "AAAAAgABAAkAAAAAAAAAAAAAAAIAAAAAAAAAAgAAABEAAAAAAAAAAAAAAAIAAAAAAAAA0gIAAOQCAAABAAEAAQAAADQBAAAEAAAA"
    "cBAAAAAADgADAA4ABjxpbml0PgAKQlVJTERfVFlQRQAQQnVpbGRDb25maWcuamF2YQAFREVCVUcAEkxqYXZhL2xhbmcvT2JqZWN0"
    "OwASTGphdmEvbGFuZy9TdHJpbmc7ACtMa3IvY28vaGFuaXVtL2RyZWFtdXAvd2Fsa3NhZmUvQnVpbGRDb25maWc7AAFWABVXQUxL"
    "U0FGRV9CVUlMRF9NQVJLRVIAFldBTEtTQUZFX1NPVVJDRV9DT01NSVQAAVoAKGFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFh"
    "YWFhYWFhYWFhYWEABWRlYnVnABF3YWxrc2FmZS1kZWJ1Zy12MQCcAX5+RDh7ImJhY2tlbmQiOiJkZXgiLCJjb21waWxhdGlvbi1t"
    "b2RlIjoiZGVidWciLCJoYXMtY2hlY2tzdW1zIjpmYWxzZSwibWluLWFwaSI6MjYsInNoYS0xIjoiNzUwYTIxYjRmNDI4MWIxZjQ1"
    "M2I2NDllMGI4NGYxYmE5YzA0ZjRmYyIsInZlcnNpb24iOiI5LjAuMy1kZXYifQAEAAEAABkBGQEZARkBgYAEnAIEFww/Fw0XCw0A"
    "AAAAAAAAAQAAAAAAAAABAAAADwAAAHAAAAACAAAABQAAAKwAAAADAAAAAQAAAMAAAAAEAAAABAAAAMwAAAAFAAAAAgAAAOwAAAAG"
    "AAAAAQAAAPwAAAABIAAAAQAAABwBAAADIAAAAQAAADQBAAACIAAADwAAADgBAAAAIAAAAQAAANICAAAFIAAAAQAAAOQCAAAAEAAA"
    "AQAAAOwCAAA="
)


def debug_field_apk_bytes(
    source_commit: str = "a" * 40,
    build_marker: str = "walksafe-debug-v1",
) -> bytes:
    commit = source_commit.encode("ascii")
    marker = build_marker.encode("ascii")
    assert len(commit) == len(DEBUG_BUILD_CONFIG_TEMPLATE_COMMIT)
    assert len(marker) == len(DEBUG_BUILD_CONFIG_TEMPLATE_MARKER)
    dex = bytearray(
        DEBUG_BUILD_CONFIG_DEX_TEMPLATE.replace(
            DEBUG_BUILD_CONFIG_TEMPLATE_COMMIT,
            commit,
        ).replace(
            DEBUG_BUILD_CONFIG_TEMPLATE_MARKER,
            marker,
        )
    )
    dex[12:32] = hashlib.sha1(dex[32:]).digest()
    dex[8:12] = (zlib.adler32(dex[12:]) & 0xFFFFFFFF).to_bytes(4, "little")
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, payload in (
            ("AndroidManifest.xml", b"walksafe-field-manifest"),
            ("classes.dex", bytes(dex)),
        ):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            archive.writestr(info, payload)
    return output.getvalue()


DEFAULT_FIELD_APK_BYTES = debug_field_apk_bytes()
DEFAULT_FIELD_APK_SHA256 = hashlib.sha256(DEFAULT_FIELD_APK_BYTES).hexdigest()


def field_apk_with_extra_entry(name: str, payload: bytes = b"unexpected") -> bytes:
    output = io.BytesIO(DEFAULT_FIELD_APK_BYTES)
    with zipfile.ZipFile(output, "a", compression=zipfile.ZIP_STORED) as archive:
        info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        info.external_attr = 0o100644 << 16
        archive.writestr(info, payload)
    return output.getvalue()


def configure_fake_android_tools(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    java = tmp_path / "java-home" / "bin" / "java"
    apksigner = tmp_path / "build-tools" / "apksigner"
    apksigner_jar = apksigner.parent / "lib" / "apksigner.jar"
    apkanalyzer = tmp_path / "cmdline-tools" / "bin" / "apkanalyzer"
    apkanalyzer_classpath = apkanalyzer.parent.parent / "lib" / "apkanalyzer-classpath.jar"
    for tool in (java, apksigner, apkanalyzer):
        tool.parent.mkdir(parents=True, exist_ok=True)
        tool.write_text("#!/bin/sh\n", encoding="utf-8")
        tool.chmod(0o700)
    for support in (apksigner_jar, apkanalyzer_classpath):
        support.parent.mkdir(parents=True, exist_ok=True)
        support.write_bytes(b"test support archive\n")
    monkeypatch.setattr(release_gate, "_validate_system_managed_java_home", lambda _path: None)
    monkeypatch.setenv("WALKSAFE_JAVA_BIN", str(java))
    monkeypatch.setenv("WALKSAFE_JAVA_SHA256", hashlib.sha256(java.read_bytes()).hexdigest())
    monkeypatch.setenv("WALKSAFE_APKSIGNER_BIN", str(apksigner))
    monkeypatch.setenv("WALKSAFE_APKSIGNER_SHA256", hashlib.sha256(apksigner.read_bytes()).hexdigest())
    monkeypatch.setenv(
        "WALKSAFE_APKSIGNER_SUPPORT_SHA256",
        release_gate._capture_android_support_closure(apksigner_jar.parent).sha256,
    )
    monkeypatch.setenv("WALKSAFE_APKANALYZER_BIN", str(apkanalyzer))
    monkeypatch.setenv("WALKSAFE_APKANALYZER_SHA256", hashlib.sha256(apkanalyzer.read_bytes()).hexdigest())
    monkeypatch.setenv(
        "WALKSAFE_APKANALYZER_SUPPORT_SHA256",
        release_gate._capture_android_support_closure(apkanalyzer_classpath.parent).sha256,
    )


def web_provenance(tmp_path: Path) -> tuple[dict[str, object], list[dict[str, object]]]:
    web_source = tmp_path / "apps" / "web"
    web_source.mkdir(parents=True, exist_ok=True)
    package_json = web_source / "package.json"
    package_lock = web_source / "package-lock.json"
    node_lock_source = tmp_path / "configs" / "walksafe_node_toolchain_lock_20260715.json"
    package_json.write_text('{"name":"walksafe-test"}\n', encoding="utf-8")
    package_lock.write_text('{"lockfileVersion":3}\n', encoding="utf-8")
    node_lock_source.parent.mkdir(parents=True, exist_ok=True)
    node_lock_source.write_bytes(
        Path("configs/walksafe_node_toolchain_lock_20260715.json").read_bytes()
    )
    node_lock = json.loads(node_lock_source.read_text(encoding="utf-8"))
    provenance_dir = tmp_path / "web-provenance"
    provenance_dir.mkdir(exist_ok=True)
    preserved_package_json = provenance_dir / "package.json"
    preserved_package_lock = provenance_dir / "package-lock.json"
    preserved_node_lock = provenance_dir / "walksafe_node_toolchain_lock_20260715.json"
    preserved_package_json.write_bytes(package_json.read_bytes())
    preserved_package_lock.write_bytes(package_lock.read_bytes())
    preserved_node_lock.write_bytes(node_lock_source.read_bytes())
    receipt_dir = tmp_path / "web-quality"
    receipt_dir.mkdir(exist_ok=True)
    receipts = []
    for name in WEB_QUALITY_RECEIPT_NAMES:
        receipt = receipt_dir / f"{name}.log"
        if name in {"node-toolchain", "node-toolchain-post"}:
            receipt.write_text(
                json.dumps(
                    {
                        "schema_version": "walksafe.node-toolchain-attestation.v2",
                        "lock_sha256": hashlib.sha256(
                            preserved_node_lock.read_bytes()
                        ).hexdigest(),
                        "official_archive": node_lock["official_archive"],
                        "platform": node_lock["platform"],
                        "root": node_lock["root"],
                        "node": node_lock["node"],
                        "npm": node_lock["npm"],
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                )
                + "\n",
                encoding="utf-8",
            )
        else:
            receipt.write_text(f"{name} PASS\n", encoding="utf-8")
        receipts.append(artifact_record(receipt, tmp_path, name=name))
    return (
        {
            "package_json": artifact_record(preserved_package_json, tmp_path),
            "package_lock": artifact_record(preserved_package_lock, tmp_path),
            "node_toolchain_lock": artifact_record(preserved_node_lock, tmp_path),
        },
        receipts,
    )


def structured_external_receipt(
    check_id: str,
    *,
    observed_at: datetime,
    source_commit: str = "a" * 40,
    release_artifacts_sha256: str = "b" * 64,
    android_apk_sha256: str = "d" * 64,
    field_apk_sha256: str = DEFAULT_FIELD_APK_SHA256,
    field_apk: dict[str, object] | None = None,
    field_session_summary: dict[str, object] | None = None,
) -> dict[str, object]:
    check_values = {
        "pwa_mobile_install_update": {
            "environment": {
                "device": "Pixel 9 physical phone",
                "os": "Android 16",
                "browser": "Chrome 138",
                "deployed_url": "https://walksafe.invalid/",
            },
            "steps": ("page_loaded", "install_prompt_accepted", "standalone_launched", "update_applied"),
            "criteria": ("installable", "standalone_launch", "updated_release_active"),
            "result": {
                "installed": True,
                "standalone_launch": True,
                "service_worker_update_observed": True,
                "initial_source_commit": "c" * 40,
                "updated_source_commit": source_commit,
            },
        },
        "voice_report_microphone_e2e": {
            "environment": {
                "device": "Pixel 9 physical phone",
                "os": "Android 16",
                "browser": "Chrome 138",
                "microphone": "built-in microphone",
                "deployed_url": "https://walksafe.invalid/",
            },
            "steps": (
                "microphone_permission_granted",
                "command_spoken",
                "transcript_confirmed",
                "report_submitted",
                "report_verified",
            ),
            "criteria": ("live_microphone_capture", "voice_intent_recognized", "report_persisted"),
            "result": {
                "report_created": True,
                "report_id": "field-report-42",
                "recognized_intent": "report_hazard",
            },
        },
        "web_phone_outdoor_navigation": {
            "environment": {
                "device": "Pixel 9 physical phone",
                "os": "Android 16",
                "browser": "Chrome 138",
                "deployed_url": "https://walksafe.invalid/",
                "route_label": "privacy-safe campus route A",
                "gps_accuracy_m": 8.5,
                "weather": "clear and dry",
            },
            "steps": (
                "geolocation_permission_granted",
                "route_requested",
                "navigation_started",
                "guidance_received",
                "destination_arrived",
            ),
            "criteria": ("physical_outdoor_gps", "tmap_route_followed", "guidance_reached_destination"),
            "result": {
                "navigation_session_id": "navigation-session-42",
                "route_provider": "tmap_pedestrian",
                "guidance_event_count": 4,
                "destination_outcome": "arrived",
            },
        },
        "web_non_metric_advisory_field": {
            "environment": {
                "device": "Pixel 8 physical phone",
                "os": "Android 16",
                "browser": "Chrome 138",
                "deployed_url": "https://walksafe.invalid/",
                "device_kind": "physical_phone",
                "camera_facing": "rear",
                "route_provider": "tmap_pedestrian",
            },
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
            "result": {
                "physical_device": True,
                "camera_facing": "rear",
                "route_provider": "tmap_pedestrian",
                "tmap_navigation_active": True,
                "distinct_frame_count": 3,
                "stabilization_ms": 700,
                "advisory_tier": "CAMERA_NON_METRIC_ADVISORY",
                "advisory_level": "low",
                "advisory_direction": "front",
                "metric_authority": False,
                "distance_authority": False,
                "step_authority": False,
                "report_authority": False,
                "route_change_authority": False,
                "haptic_observed": False,
                "fallback_after_gate_loss": "TMAP_ONLY",
            },
        },
        "web_accessibility_tts_haptic": {
            "environment": {
                "device": "Pixel 8 Android 16",
                "os": "Android 16",
                "browser": "Chrome 138",
                "screen_reader": "TalkBack 15",
                "deployed_url": "https://walksafe.invalid/",
                "device_kind": "physical_phone",
            },
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
            "result": {
                "tts_verified": True,
                "haptic_verified": True,
                "risk_priority_verified": True,
                "unavailable_state_verified": True,
            },
        },
        "android_arcore_unsupported_camera_field": {
            "environment": {
                "device": "Galaxy A physical unsupported phone",
                "os": "Android 15",
                "application_id": "kr.co.hanium.dreamup.walksafe",
                "device_kind": "physical_phone",
                "camera_api": "CameraX",
                "imu_source": "physical_sensors",
                "route_provider": "tmap_pedestrian",
            },
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
            "result": {
                "field_apk_sha256": field_apk_sha256,
                "release_apk_sha256": android_apk_sha256,
                "field_apk_build_type": "debug",
                "physical_evidence_scope": "SAME_COMMIT_DEBUG_FIELD_APK",
                "release_apk_physically_executed": False,
                "sampling_contract": "AT_MOST_1HZ_MIN_60_OVER_15M_MAX_GAP_30S",
                "installed": True,
                "cold_start": True,
                "physical_device": True,
                "arcore_unsupported_origin": "arcore_availability_unsupported",
                "arcore_availability_state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
                "camera_api": "CameraX",
                "imu_fresh": True,
                "route_provider": "tmap_pedestrian",
                "tmap_navigation_active": True,
                "continuity_seconds": 900,
                "advisory_level": "low",
                "advisory_direction": "CENTER",
                "accessibility_delivery": "talkback",
                "vibration_observed": False,
                "fallback_after_gate_loss": "TMAP_ONLY",
            },
        },
        "android_signed_release_physical_smoke": {
            "environment": {
                "device": "Galaxy A physical unsupported phone",
                "os": "Android 15",
                "application_id": "kr.co.hanium.dreamup.walksafe",
                "device_kind": "physical_phone",
                "camera_api": "CameraX",
                "route_provider": "tmap_pedestrian",
                "deployment": "signed_release",
            },
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
            "result": {
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
                "advisory_direction": "CENTER",
                "metric": False,
                "tmap_authoritative": True,
                "distance_authority": False,
                "step_authority": False,
                "route_change_authority": False,
                "reports_allowed": False,
                "accessibility_delivery": "talkback",
                "vibration_observed": False,
                "fallback_after_gate_loss": "TMAP_ONLY",
            },
        },
        "tmap_live_route_smoke": {
            "environment": {
                "provider": "tmap_pedestrian",
                "endpoint": "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1",
                "deployment": "production backend release",
            },
            "steps": ("request_sent", "response_received", "route_contract_checked"),
            "criteria": ("provider_success", "pedestrian_geometry_present", "guide_instructions_present"),
            "result": {
                "provider": "tmap_pedestrian",
                "http_status": 200,
                "route_id": "provider-route-42",
                "coordinate_count": 12,
                "instruction_count": 3,
            },
        },
    }[check_id]
    steps = check_values["steps"]
    started_at = observed_at - timedelta(
        minutes=16 if check_id == "android_arcore_unsupported_camera_field" else 2
    )
    observation_offsets = (
        [0, 10, 20, 30, 40, 50, 900, 910, 920, 930, 940, 950]
        if check_id == "android_arcore_unsupported_camera_field"
        else [index * 10 for index in range(len(steps))]
    )
    observations = [
        {
            "step_id": step_id,
            "observed_at": (started_at + timedelta(seconds=observation_offsets[index])).isoformat(),
            "latency_ms": 100 + index,
            "status": "passed",
            "note": f"operator directly observed {step_id}",
        }
        for index, step_id in enumerate(steps)
    ]
    criteria = [
        {
            "criterion_id": criterion_id,
            "passed": True,
            "observation_step_ids": list(steps) if index == 0 else [steps[index % len(steps)]],
        }
        for index, criterion_id in enumerate(check_values["criteria"])
    ]
    receipt = {
        "schema_version": "walksafe.external-check-receipt.v1",
        "check_id": check_id,
        "result_status": "passed",
        "source_commit": source_commit,
        "release_artifacts_sha256": release_artifacts_sha256,
        "operator": "field-operator-01",
        "started_at": started_at.isoformat(),
        "completed_at": observed_at.isoformat(),
        "environment": check_values["environment"],
        "observations": observations,
        "pass_criteria": criteria,
        "result": check_values["result"],
    }
    if check_id == "android_arcore_unsupported_camera_field":
        receipt["field_apk"] = field_apk
        receipt["field_session_summary"] = field_session_summary
    return receipt


def validate_structured_check(
    tmp_path: Path,
    check_id: str,
    receipt: dict[str, object],
    *,
    android_apk_sha256: str | None = None,
    check_overrides: dict[str, object] | None = None,
) -> None:
    receipt_path = write_json(tmp_path / f"{check_id}.json", receipt)
    observed_at = datetime.fromisoformat(str(receipt["completed_at"]))
    check: dict[str, object] = {
        "passed": True,
        "observed_at": observed_at.isoformat(),
        "evidence_ref": receipt_path.name,
        "evidence_sha256": hashlib.sha256(receipt_path.read_bytes()).hexdigest(),
        "environment": "direct external release observation",
        "source_commit": "a" * 40,
        "release_artifacts_sha256": "b" * 64,
    }
    if check_id == "web_accessibility_tts_haptic":
        environment = receipt["environment"]
        result = receipt["result"]
        assert isinstance(environment, dict) and isinstance(result, dict)
        check["details"] = {
            "device": environment["device"],
            "browser": environment["browser"],
            "screen_reader": environment["screen_reader"],
            **result,
        }
    if check_overrides:
        check.update(check_overrides)

    def run_validation() -> None:
        validate_check(
            check_id,
            check,
            now=observed_at,
            max_age=timedelta(days=1),
            evidence_root=tmp_path,
            source_commit="a" * 40,
            release_artifacts_sha256="b" * 64,
            android_apk_sha256=android_apk_sha256,
        )

    def run_semantic_validation() -> None:
        with mock.patch.object(
            external_receipt,
            "UNATTESTED_ANDROID_PHYSICAL_CHECKS",
            frozenset(),
        ):
            run_validation()

    if check_id != "android_arcore_unsupported_camera_field":
        if check_id in external_receipt.UNATTESTED_ANDROID_PHYSICAL_CHECKS:
            run_semantic_validation()
        else:
            run_validation()
        return
    with mock.patch.object(
        release_gate,
        "_validate_android_field_apk_sdk",
        return_value={
            "signer_certificate_sha256": "f" * 64,
            "application_id": "kr.co.hanium.dreamup.walksafe",
            "version_code": "1",
            "version_name": "0.1.0",
            "min_sdk": "26",
            "target_sdk": "36",
            "debuggable": "true",
        },
    ):
        run_semantic_validation()


def android_field_summary(
    *,
    source_commit: str = "a" * 40,
    apk_sha256: str = DEFAULT_FIELD_APK_SHA256,
) -> dict[str, object]:
    samples = [
        {
            "elapsed_realtime_ms": 100_000 + index * 15_000,
            "inference_ms": 480 + index,
            "detection_count": 2,
            "capability_tier": "CAMERA_IMU_NON_METRIC",
            "camera_permission_granted": True,
            "camera_fallback_running": True,
            "detector_available": True,
            "imu_fresh": True,
            "tmap_route_active": True,
            "metric": False,
            "reports_allowed": False,
            "state": "detector_succeeded",
        }
        for index in range(62)
    ]
    samples[-1]["capability_tier"] = "TMAP_ONLY"
    samples[-1]["imu_fresh"] = False
    return {
        "schema_version": "android.field_summary.v1",
        "summary_available": True,
        "input_format": "current_device_records_v1",
        "integrity": {
            "summary_available": True,
            "legacy_plaintext_history_only": False,
            "encrypted_aead_envelope_detected": False,
            "session_count": 1,
            "malformed_or_unknown_record_count": 0,
            "errors": [],
            "privacy_violation_count": 0,
            "privacy_violations": [],
            "missing_provenance_count": 0,
            "missing_provenance_sessions": [],
            "duplicate_session_id_count": 0,
            "duplicate_session_ids": [],
            "strict_mode": "camera-non-metric",
            "camera_advisory_required": True,
            "arcore_unsupported_required": True,
            "evidence_scope": "ARCORE_UNSUPPORTED_FIELD",
            "arcore_unsupported_verified": True,
            "strict_selected_session_id": "unsupported-field-01",
            "expected_source_commit": source_commit,
            "expected_apk_sha256": apk_sha256,
            "strict_failure_reasons": [],
        },
        "sessions": [
            {
                "session_id": "unsupported-field-01",
                "status": "completed",
                "provenance": {
                    "source_commit": source_commit,
                    "apk_sha256": apk_sha256,
                    "model_config_sha256": "e" * 64,
                },
                "strict_evidence": {
                    "malformed_or_unknown_record_count": 0,
                    "errors": [],
                    "privacy_violation_count": 0,
                    "privacy_violations": [],
                    "camera_non_metric": {
                        "session_started_contracts": [
                            {
                                "loaded_model": "unified_walksafe",
                                "model_fallback_used": False,
                                "metric": False,
                                "reports_allowed": False,
                                "reason": "arcore_availability_unsupported",
                                "state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
                            }
                        ],
                        "frame_analyzed_contracts": [
                            {
                                "loaded_model": "unified_walksafe",
                                "model_fallback_used": False,
                                "metric": False,
                                "reports_allowed": False,
                                "state": "detector_succeeded",
                            }
                        ],
                        "advisory_emitted_contracts": [
                            {
                                "direction": "CENTER",
                                "loaded_model": "unified_walksafe",
                                "model_fallback_used": False,
                                "metric": False,
                                "tmap_authoritative": True,
                                "reports_allowed": False,
                            }
                        ],
                        "inference_sample_contracts": samples,
                    },
                },
            }
        ],
        "camera_non_metric": {
            "strict_selected_session_samples": {
                "sample_count": 62,
                "sample_span_ms": 915_000,
                "min_sample_gap_ms": 15_000,
                "max_sample_gap_ms": 15_000,
                "invalid_sample_count": 0,
                "elapsed_contract_valid": True,
                "elapsed_sequence_valid": True,
                "recorded_at_contract_valid": True,
            }
        },
    }


def android_structured_receipt(
    tmp_path: Path,
    observed_at: datetime,
    *,
    summary: dict[str, object] | None = None,
) -> dict[str, object]:
    (tmp_path / "android-field").mkdir(exist_ok=True)
    field_apk_path = tmp_path / "android-field" / "walksafe-field-debug.apk"
    field_apk_path.write_bytes(DEFAULT_FIELD_APK_BYTES)
    summary_path = write_json(
        tmp_path / "android-field" / "field_session_summary.json",
        summary or android_field_summary(),
    )
    return structured_external_receipt(
        "android_arcore_unsupported_camera_field",
        observed_at=observed_at,
        field_apk_sha256=DEFAULT_FIELD_APK_SHA256,
        field_apk=artifact_record(field_apk_path, tmp_path),
        field_session_summary=artifact_record(summary_path, tmp_path),
    )


def test_android_physical_receipt_remains_unattested_not_eligible(
    tmp_path: Path,
) -> None:
    observed_at = datetime.now(UTC)
    receipt_path = write_json(
        tmp_path / "android-field-receipt.json",
        android_structured_receipt(tmp_path, observed_at),
    )

    with pytest.raises(
        release_gate.ExternalCheckReceiptFailure,
        match="UNATTESTED_NOT_ELIGIBLE.*NOT_RUN",
    ):
        release_gate.validate_external_check_receipt(
            "android_arcore_unsupported_camera_field",
            receipt_path,
            source_commit="a" * 40,
            release_artifacts_sha256="b" * 64,
            observed_at=observed_at,
            android_apk_sha256="d" * 64,
        )


def replace_android_field_apk(
    tmp_path: Path,
    receipt: dict[str, object],
    payload: bytes,
) -> None:
    field_record = receipt["field_apk"]
    summary_record = receipt["field_session_summary"]
    result = receipt["result"]
    assert isinstance(field_record, dict) and isinstance(summary_record, dict) and isinstance(result, dict)
    field_path = tmp_path / str(field_record["path"])
    field_path.write_bytes(payload)
    field_sha256 = hashlib.sha256(payload).hexdigest()
    receipt["field_apk"] = artifact_record(field_path, tmp_path)
    result["field_apk_sha256"] = field_sha256

    summary_path = tmp_path / str(summary_record["path"])
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    summary["integrity"]["expected_apk_sha256"] = field_sha256
    summary["sessions"][0]["provenance"]["apk_sha256"] = field_sha256
    write_json(summary_path, summary)
    receipt["field_session_summary"] = artifact_record(summary_path, tmp_path)


def test_release_evidence_example_tracks_the_gate_contract() -> None:
    example = json.loads((Path(__file__).resolve().parents[1] / "docs/testing/release_evidence.example.json").read_text())
    assert "source_commit" in example
    assert set(example["release_artifacts"]) == {"web_build", "android_apk"}
    assert "signer_certificate_sha256" in example["release_artifacts"]["android_apk"]
    expected_checks = (
        set(release_gate.WEB_EXTERNAL_CHECKS)
        | set(release_gate.ANDROID_RESEARCH_CHECKS)
        | set(release_gate.FULL_PHYSICAL_ACCEPTANCE_CHECKS)
        | {"tmap_live_route_smoke"}
    )
    assert expected_checks <= set(example["checks"])
    assert all("evidence_sha256" in example["checks"][check_id] for check_id in expected_checks)
    assert all("source_commit" in example["checks"][check_id] for check_id in expected_checks)
    assert all("release_artifacts_sha256" in example["checks"][check_id] for check_id in expected_checks)
    structured_checks = {
        "pwa_mobile_install_update",
        "voice_report_microphone_e2e",
        "web_phone_outdoor_navigation",
        "web_non_metric_advisory_field",
        "web_accessibility_tts_haptic",
        "android_arcore_unsupported_camera_field",
        "android_signed_release_physical_smoke",
        "tmap_live_route_smoke",
    }
    assert all(example["checks"][check_id]["evidence_ref"].endswith(".json") for check_id in structured_checks)
    assert all(
        example["checks"][check_id]["details"]["receipt_schema_version"]
        == "walksafe.external-check-receipt.v1"
        for check_id in structured_checks
    )
    assert (
        "scripts/walksafe_external_check_receipt.py"
        in release_gate.EXPECTED_DESIGN_IMPLEMENTATION_BINDINGS
    )
    android_details = example["checks"]["android_arcore_unsupported_camera_field"]["details"]
    assert android_details["required_result_binding"] == {
        "field_apk_build_type": "debug",
        "physical_evidence_scope": "SAME_COMMIT_DEBUG_FIELD_APK",
        "release_apk_physically_executed": False,
        "sampling_contract": "AT_MOST_1HZ_MIN_60_OVER_15M_MAX_GAP_30S",
    }
    assert android_details["required_field_apk_artifact"]["sha256"].endswith(
        "distinct from signed release APK"
    )
    signed_release_details = example["checks"]["android_signed_release_physical_smoke"]["details"]
    assert signed_release_details["required_result_binding"] == {
        "release_apk_build_type": "release",
        "release_apk_physically_executed": True,
        "arcore_unsupported_origin": "arcore_availability_unsupported",
        "arcore_availability_state": "UNSUPPORTED_DEVICE_NOT_CAPABLE",
    }
    detector_details = example["checks"]["detector_runtime_live"]["details"]
    assert {
        "runtime_config_sha256",
        "unified_model_sha256",
        "smoke_fixture_sha256",
        "smoke_expected_class",
        "backend_source_commit",
    } <= set(detector_details)


def test_tmap_release_check() -> None:
    tmap = required_checks("web-release", "tmap_pedestrian", [])

    assert "tmap_live_route_smoke" in tmap
    assert "account_rbac_configured" in tmap
    with pytest.raises(EvidenceFailure, match="only the tmap_pedestrian"):
        required_checks("web-release", "kakao_mobility", [])


def test_non_metric_field_receipts_are_required_by_their_profiles() -> None:
    web = required_checks("web-release", "tmap_pedestrian", [])
    android = required_checks("android-research", "tmap_pedestrian", [])
    full = required_checks("full", "tmap_pedestrian", [])

    assert "web_non_metric_advisory_field" in web
    assert "web_accessibility_tts_haptic" in web
    assert "android_arcore_unsupported_camera_field" in android
    assert "android_signed_release_physical_smoke" not in web
    assert "android_signed_release_physical_smoke" not in android
    assert {
        "web_non_metric_advisory_field",
        "web_accessibility_tts_haptic",
        "android_arcore_unsupported_camera_field",
        "android_signed_release_physical_smoke",
    } <= set(full)


def test_generic_check_is_bound_to_a_real_evidence_file(tmp_path: Path) -> None:
    evidence_file = tmp_path / "phone-smoke.log"
    evidence_file.write_text("observed on phone", encoding="utf-8")
    digest = hashlib.sha256(evidence_file.read_bytes()).hexdigest()
    value = {
        "passed": True,
        "observed_at": datetime.now(UTC).isoformat(),
        "evidence_ref": evidence_file.name,
        "evidence_sha256": digest,
        "environment": "Pixel 9, production URL",
        "source_commit": "a" * 40,
        "release_artifacts_sha256": "b" * 64,
    }

    validate_check(
        "phone_smoke",
        value,
        now=datetime.now(UTC),
        max_age=timedelta(days=1),
        evidence_root=tmp_path,
        source_commit="a" * 40,
        release_artifacts_sha256="b" * 64,
    )
    evidence_file.write_text("tampered", encoding="utf-8")
    with pytest.raises(EvidenceFailure, match="SHA-256 does not match"):
        validate_check(
            "phone_smoke",
            value,
            now=datetime.now(UTC),
            max_age=timedelta(days=1),
            evidence_root=tmp_path,
            source_commit="a" * 40,
            release_artifacts_sha256="b" * 64,
        )


def test_generic_check_rejects_evidence_from_another_build(tmp_path: Path) -> None:
    evidence_file = tmp_path / "phone-smoke.log"
    evidence_file.write_text("old build", encoding="utf-8")
    value = {
        "passed": True,
        "observed_at": datetime.now(UTC).isoformat(),
        "evidence_ref": evidence_file.name,
        "evidence_sha256": hashlib.sha256(evidence_file.read_bytes()).hexdigest(),
        "environment": "physical phone",
        "source_commit": "c" * 40,
        "release_artifacts_sha256": "d" * 64,
    }

    with pytest.raises(EvidenceFailure, match="source_commit"):
        validate_check(
            "phone_smoke",
            value,
            now=datetime.now(UTC),
            max_age=timedelta(days=1),
            evidence_root=tmp_path,
            source_commit="a" * 40,
            release_artifacts_sha256="b" * 64,
        )


@pytest.mark.parametrize(
    "check_id",
    [
        "pwa_mobile_install_update",
        "voice_report_microphone_e2e",
        "web_phone_outdoor_navigation",
        "web_non_metric_advisory_field",
        "web_accessibility_tts_haptic",
        "tmap_live_route_smoke",
    ],
)
def test_external_checks_require_complete_structured_receipts(tmp_path: Path, check_id: str) -> None:
    observed_at = datetime.now(UTC)
    validate_structured_check(tmp_path, check_id, structured_external_receipt(check_id, observed_at=observed_at))


@pytest.mark.parametrize(
    "check_id",
    [
        "pwa_mobile_install_update",
        "voice_report_microphone_e2e",
        "web_phone_outdoor_navigation",
        "web_non_metric_advisory_field",
        "web_accessibility_tts_haptic",
        "android_arcore_unsupported_camera_field",
        "android_signed_release_physical_smoke",
        "tmap_live_route_smoke",
    ],
)
def test_external_checks_reject_one_line_log_files(tmp_path: Path, check_id: str) -> None:
    observed_at = datetime.now(UTC)
    evidence = tmp_path / f"{check_id}.log"
    evidence.write_text("operator says this passed\n", encoding="utf-8")
    with pytest.raises(EvidenceFailure, match="must be a JSON file"):
        validate_check(
            check_id,
            {
                "passed": True,
                "observed_at": observed_at.isoformat(),
                "evidence_ref": evidence.name,
                "evidence_sha256": hashlib.sha256(evidence.read_bytes()).hexdigest(),
                "environment": "direct external release observation",
                "source_commit": "a" * 40,
                "release_artifacts_sha256": "b" * 64,
            },
            now=observed_at,
            max_age=timedelta(days=1),
            evidence_root=tmp_path,
            source_commit="a" * 40,
            release_artifacts_sha256="b" * 64,
        )


@pytest.mark.parametrize("missing_field", ["observations", "pass_criteria", "result"])
def test_external_receipt_rejects_missing_structured_fields(tmp_path: Path, missing_field: str) -> None:
    observed_at = datetime.now(UTC)
    receipt = structured_external_receipt("tmap_live_route_smoke", observed_at=observed_at)
    del receipt[missing_field]
    with pytest.raises(EvidenceFailure, match=missing_field):
        validate_structured_check(tmp_path, "tmap_live_route_smoke", receipt)


@pytest.mark.parametrize("binding_field", ["source_commit", "release_artifacts_sha256"])
def test_external_receipt_keeps_release_bindings(tmp_path: Path, binding_field: str) -> None:
    observed_at = datetime.now(UTC)
    receipt = structured_external_receipt("tmap_live_route_smoke", observed_at=observed_at)
    receipt[binding_field] = "c" * (40 if binding_field == "source_commit" else 64)
    with pytest.raises(EvidenceFailure, match=binding_field):
        validate_structured_check(tmp_path, "tmap_live_route_smoke", receipt)


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        ("schema_version", "walksafe.external-check-receipt.v0", "schema_version"),
        ("result_status", "failed", "passed"),
    ],
)
def test_external_receipt_rejects_schema_or_status_mutation(
    tmp_path: Path,
    field: str,
    value: str,
    message: str,
) -> None:
    observed_at = datetime.now(UTC)
    receipt = structured_external_receipt("web_non_metric_advisory_field", observed_at=observed_at)
    receipt[field] = value
    with pytest.raises(EvidenceFailure, match=message):
        validate_structured_check(tmp_path, "web_non_metric_advisory_field", receipt)


@pytest.mark.parametrize(
    "endpoint",
    [
        "https://apis.openapi.sk.com.evil/tmap/routes/pedestrian?version=1",
        "https://apis.openapi.sk.com/tmap/routes/pedestrian/extra?version=1",
        "https://apis.openapi.sk.com:444/tmap/routes/pedestrian?version=1",
        "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1#fragment",
        "https://apis.openapi.sk.com:notaport/tmap/routes/pedestrian",
        "https://[invalid/tmap/routes/pedestrian",
        "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=2",
        "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&appKey=secret",
        "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&version=1",
        "https://apis.openapi.sk.com/tmap/routes/pedestrian?appKey=secret",
    ],
)
def test_tmap_receipt_requires_exact_endpoint_and_normalizes_parse_failures(
    tmp_path: Path,
    endpoint: str,
) -> None:
    observed_at = datetime.now(UTC)
    receipt = structured_external_receipt("tmap_live_route_smoke", observed_at=observed_at)
    environment = receipt["environment"]
    assert isinstance(environment, dict)
    environment["endpoint"] = endpoint
    with pytest.raises(EvidenceFailure, match="endpoint"):
        validate_structured_check(tmp_path, "tmap_live_route_smoke", receipt)


@pytest.mark.parametrize(
    "endpoint",
    [
        "https://apis.openapi.sk.com/tmap/routes/pedestrian",
        "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1",
    ],
)
def test_tmap_receipt_accepts_only_empty_or_exact_version_one_query(
    tmp_path: Path,
    endpoint: str,
) -> None:
    observed_at = datetime.now(UTC)
    receipt = structured_external_receipt("tmap_live_route_smoke", observed_at=observed_at)
    environment = receipt["environment"]
    assert isinstance(environment, dict)
    environment["endpoint"] = endpoint
    validate_structured_check(tmp_path, "tmap_live_route_smoke", receipt)


def test_android_receipt_requires_ordered_fifteen_minute_observation(tmp_path: Path) -> None:
    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at)
    observations = receipt["observations"]
    assert isinstance(observations, list)
    observations[0]["step_id"], observations[1]["step_id"] = (
        observations[1]["step_id"],
        observations[0]["step_id"],
    )
    with pytest.raises(EvidenceFailure, match="step order"):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )

    receipt = android_structured_receipt(tmp_path, observed_at)
    observations = receipt["observations"]
    assert isinstance(observations, list)
    started_at = datetime.fromisoformat(str(receipt["started_at"]))
    continuity = next(item for item in observations if item["step_id"] == "continuity_15m_completed")
    continuity["observed_at"] = (started_at + timedelta(seconds=899)).isoformat()
    with pytest.raises(EvidenceFailure, match="at least 15 minutes"):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )


def test_android_unsupported_field_requires_bound_strict_summary(tmp_path: Path) -> None:
    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at)
    validate_structured_check(
        tmp_path,
        "android_arcore_unsupported_camera_field",
        receipt,
        android_apk_sha256="d" * 64,
    )


def test_android_unsupported_field_accepts_route_inactive_samples_as_context(
    tmp_path: Path,
) -> None:
    summary = android_field_summary()
    sessions = summary["sessions"]
    assert isinstance(sessions, list)
    strict = sessions[0]["strict_evidence"]
    assert isinstance(strict, dict)
    camera = strict["camera_non_metric"]
    assert isinstance(camera, dict)
    samples = camera["inference_sample_contracts"]
    assert isinstance(samples, list)
    for sample in samples:
        assert isinstance(sample, dict)
        sample["tmap_route_active"] = False

    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at, summary=summary)
    validate_structured_check(
        tmp_path,
        "android_arcore_unsupported_camera_field",
        receipt,
        android_apk_sha256="d" * 64,
    )


def test_android_signed_release_physical_smoke_accepts_exact_release_hash(tmp_path: Path) -> None:
    observed_at = datetime.now(UTC)
    receipt = structured_external_receipt(
        "android_signed_release_physical_smoke",
        observed_at=observed_at,
    )
    validate_structured_check(
        tmp_path,
        "android_signed_release_physical_smoke",
        receipt,
        android_apk_sha256="d" * 64,
    )


@pytest.mark.parametrize(
    ("field", "unsafe"),
    [
        ("release_apk_sha256", "e" * 64),
        ("installed_apk_sha256", "e" * 64),
        ("release_apk_build_type", "debug"),
        ("release_apk_physically_executed", False),
        ("installed", False),
        ("cold_start", False),
        ("physical_device", False),
        ("arcore_unsupported_origin", "debug_forced_supported"),
        ("arcore_availability_state", "SUPPORTED_INSTALLED"),
        ("camera_api", "ARCore"),
        ("camera_fallback_running", False),
        ("detector_available", False),
        ("route_provider", "local_route"),
        ("tmap_navigation_active", False),
        ("advisory_level", "high"),
        ("advisory_direction", "front"),
        ("metric", True),
        ("tmap_authoritative", False),
        ("distance_authority", True),
        ("step_authority", True),
        ("route_change_authority", True),
        ("reports_allowed", True),
        ("accessibility_delivery", "none"),
        ("vibration_observed", True),
        ("fallback_after_gate_loss", "CAMERA_NON_METRIC_ADVISORY"),
    ],
)
def test_android_signed_release_physical_smoke_rejects_unsafe_result(
    tmp_path: Path,
    field: str,
    unsafe: object,
) -> None:
    observed_at = datetime.now(UTC)
    receipt = structured_external_receipt(
        "android_signed_release_physical_smoke",
        observed_at=observed_at,
    )
    result = receipt["result"]
    assert isinstance(result, dict)
    result[field] = unsafe
    with pytest.raises(EvidenceFailure, match=field):
        validate_structured_check(
            tmp_path,
            "android_signed_release_physical_smoke",
            receipt,
            android_apk_sha256="d" * 64,
        )


@pytest.mark.parametrize(
    ("field", "unsafe"),
    [
        ("application_id", "kr.co.hanium.dreamup.walksafe.debug"),
        ("device_kind", "emulator"),
        ("camera_api", "ARCore"),
        ("route_provider", "local_route"),
        ("deployment", "debug"),
    ],
)
def test_android_signed_release_physical_smoke_rejects_wrong_environment(
    tmp_path: Path,
    field: str,
    unsafe: str,
) -> None:
    observed_at = datetime.now(UTC)
    receipt = structured_external_receipt(
        "android_signed_release_physical_smoke",
        observed_at=observed_at,
    )
    environment = receipt["environment"]
    assert isinstance(environment, dict)
    environment[field] = unsafe
    with pytest.raises(EvidenceFailure, match=field):
        validate_structured_check(
            tmp_path,
            "android_signed_release_physical_smoke",
            receipt,
            android_apk_sha256="d" * 64,
        )


def test_android_signed_release_physical_smoke_requires_verified_apk_hash(tmp_path: Path) -> None:
    observed_at = datetime.now(UTC)
    receipt = structured_external_receipt(
        "android_signed_release_physical_smoke",
        observed_at=observed_at,
    )
    with pytest.raises(EvidenceFailure, match="verified Android APK SHA-256"):
        validate_structured_check(
            tmp_path,
            "android_signed_release_physical_smoke",
            receipt,
        )


def test_android_field_apk_is_distinct_from_bound_release_apk(tmp_path: Path) -> None:
    receipt = android_structured_receipt(tmp_path, datetime.now(UTC))
    result = receipt["result"]
    assert isinstance(result, dict)
    assert result["field_apk_sha256"] == DEFAULT_FIELD_APK_SHA256
    assert result["release_apk_sha256"] == "d" * 64
    assert result["field_apk_sha256"] != result["release_apk_sha256"]


@pytest.mark.parametrize(
    ("payload", "message"),
    [
        (debug_field_apk_bytes("c" * 40), "WALKSAFE_SOURCE_COMMIT"),
        (
            debug_field_apk_bytes(build_marker="walksafe-febug-v1"),
            "WALKSAFE_BUILD_MARKER",
        ),
        (field_apk_with_extra_entry("classes1.dex"), "noncanonical root DEX"),
        (
            field_apk_with_extra_entry("classes2.dex", DEBUG_BUILD_CONFIG_DEX_TEMPLATE),
            "exactly one WalkSafe BuildConfig",
        ),
    ],
)
def test_android_field_apk_rejects_wrong_source_or_build_marker(
    tmp_path: Path,
    payload: bytes,
    message: str,
) -> None:
    receipt = android_structured_receipt(tmp_path, datetime.now(UTC))
    replace_android_field_apk(tmp_path, receipt, payload)
    with pytest.raises(EvidenceFailure, match=message):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )


def test_android_field_apk_sdk_proves_signature_package_and_debuggable(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    apk = tmp_path / "walksafe-field-debug.apk"
    apk.write_bytes(DEFAULT_FIELD_APK_BYTES)
    signer_digest = "f" * 64
    configure_fake_android_tools(tmp_path, monkeypatch)

    def fake_tool(command, **_kwargs):
        if "verify" in command:
            output = (
                "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                "Number of signers: 1\n"
                f"Signer #1 certificate SHA-256 digest: {signer_digest}\n"
            )
        elif command[1:3] == ["apk", "summary"]:
            output = "kr.co.hanium.dreamup.walksafe\t1\t0.1.0\n"
        elif command[1:3] == ["manifest", "min-sdk"]:
            output = "26\n"
        elif command[1:3] == ["manifest", "target-sdk"]:
            output = "36\n"
        elif command[1:3] == ["manifest", "debuggable"]:
            output = "true\n"
        elif command[1:3] == ["dex", "list"]:
            output = "classes.dex\n"
        else:
            raise AssertionError(command)
        return subprocess.CompletedProcess(command, 0, stdout=output, stderr="")

    monkeypatch.setattr(release_gate.subprocess, "run", fake_tool)
    verified = release_gate.validate_android_field_apk(
        apk,
        source_commit="a" * 40,
        expected_sha256=DEFAULT_FIELD_APK_SHA256,
    )

    assert verified["signer_certificate_sha256"] == signer_digest
    assert verified["application_id"] == "kr.co.hanium.dreamup.walksafe"
    assert verified["debuggable"] == "true"
    assert verified["evidence_scope"] == "SAME_COMMIT_DEBUG_FIELD_APK"


@pytest.mark.parametrize(
    ("failure", "message"),
    [
        ("unsigned", "Signature Scheme v2 or v3"),
        ("multiple_signers", "exactly one signer"),
        ("wrong_package", "application id"),
        ("not_debuggable", "debuggable=true"),
    ],
)
def test_android_field_apk_sdk_rejects_uninstallable_or_wrong_build(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    failure: str,
    message: str,
) -> None:
    apk = tmp_path / "walksafe-field-debug.apk"
    apk.write_bytes(DEFAULT_FIELD_APK_BYTES)
    signer_digest = "f" * 64
    configure_fake_android_tools(tmp_path, monkeypatch)

    def fake_tool(command, **_kwargs):
        if "verify" in command:
            output = (
                "Verified using v2 scheme (APK Signature Scheme v2): "
                f"{'false' if failure == 'unsigned' else 'true'}\n"
                f"Number of signers: {'2' if failure == 'multiple_signers' else '1'}\n"
                f"Signer #1 certificate SHA-256 digest: {signer_digest}\n"
            )
        elif command[1:3] == ["apk", "summary"]:
            package_id = "example.invalid" if failure == "wrong_package" else "kr.co.hanium.dreamup.walksafe"
            output = f"{package_id}\t1\t0.1.0\n"
        elif command[1:3] == ["manifest", "min-sdk"]:
            output = "26\n"
        elif command[1:3] == ["manifest", "target-sdk"]:
            output = "36\n"
        elif command[1:3] == ["manifest", "debuggable"]:
            output = "false\n" if failure == "not_debuggable" else "true\n"
        elif command[1:3] == ["dex", "list"]:
            output = "classes.dex\n"
        else:
            raise AssertionError(command)
        return subprocess.CompletedProcess(command, 0, stdout=output, stderr="")

    monkeypatch.setattr(release_gate.subprocess, "run", fake_tool)
    with pytest.raises(EvidenceFailure, match=message):
        release_gate.validate_android_field_apk(
            apk,
            source_commit="a" * 40,
            expected_sha256=DEFAULT_FIELD_APK_SHA256,
        )


def test_android_field_apk_record_rejects_missing_or_mutated_file(tmp_path: Path) -> None:
    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at)
    receipt.pop("field_apk")
    with pytest.raises(EvidenceFailure, match="field_apk"):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )

    receipt = android_structured_receipt(tmp_path, observed_at)
    field_record = receipt["field_apk"]
    assert isinstance(field_record, dict)
    field_record["sha256"] = "e" * 64
    with pytest.raises(EvidenceFailure, match="sha256 does not match"):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )


@pytest.mark.parametrize(
    ("field", "unsafe"),
    [
        ("physical_device", False),
        ("distinct_frame_count", 2),
        ("stabilization_ms", 699),
        ("advisory_level", "high"),
        ("metric_authority", True),
        ("distance_authority", True),
        ("step_authority", True),
        ("report_authority", True),
        ("route_change_authority", True),
        ("haptic_observed", True),
        ("fallback_after_gate_loss", "CAMERA_NON_METRIC_ADVISORY"),
    ],
)
def test_web_non_metric_receipt_rejects_unsafe_authority_or_range(
    tmp_path: Path,
    field: str,
    unsafe: object,
) -> None:
    observed_at = datetime.now(UTC)
    receipt = structured_external_receipt("web_non_metric_advisory_field", observed_at=observed_at)
    result = receipt["result"]
    assert isinstance(result, dict)
    result[field] = unsafe
    with pytest.raises(EvidenceFailure, match=field):
        validate_structured_check(tmp_path, "web_non_metric_advisory_field", receipt)


@pytest.mark.parametrize("section", ["receipt", "environment", "observation", "criterion", "result"])
def test_external_receipt_rejects_unexpected_fields(tmp_path: Path, section: str) -> None:
    observed_at = datetime.now(UTC)
    receipt = structured_external_receipt("web_non_metric_advisory_field", observed_at=observed_at)
    if section == "receipt":
        receipt["unexpected"] = True
    elif section == "environment":
        assert isinstance(receipt["environment"], dict)
        receipt["environment"]["unexpected"] = True
    elif section == "observation":
        assert isinstance(receipt["observations"], list)
        receipt["observations"][0]["unexpected"] = True
    elif section == "criterion":
        assert isinstance(receipt["pass_criteria"], list)
        receipt["pass_criteria"][0]["unexpected"] = True
    else:
        assert isinstance(receipt["result"], dict)
        receipt["result"]["unexpected"] = True
    with pytest.raises(EvidenceFailure, match="unexpected"):
        validate_structured_check(tmp_path, "web_non_metric_advisory_field", receipt)


def test_accessibility_details_must_match_structured_receipt(tmp_path: Path) -> None:
    observed_at = datetime.now(UTC)
    receipt = structured_external_receipt("web_accessibility_tts_haptic", observed_at=observed_at)
    environment = receipt["environment"]
    result = receipt["result"]
    assert isinstance(environment, dict) and isinstance(result, dict)
    mismatched_details = {
        "device": "different physical phone",
        "browser": environment["browser"],
        "screen_reader": environment["screen_reader"],
        **result,
    }
    with pytest.raises(EvidenceFailure, match="exactly match"):
        validate_structured_check(
            tmp_path,
            "web_accessibility_tts_haptic",
            receipt,
            check_overrides={"details": mismatched_details},
        )


@pytest.mark.parametrize(
    ("field", "unsafe"),
    [
        ("field_apk_sha256", "d" * 64),
        ("release_apk_sha256", "e" * 64),
        ("field_apk_build_type", "release"),
        ("physical_evidence_scope", "FINAL_RELEASE_APK"),
        ("release_apk_physically_executed", True),
        ("sampling_contract", "EXACT_1HZ"),
        ("installed", False),
        ("cold_start", False),
        ("arcore_unsupported_origin", "debug_forced_supported"),
        ("camera_api", "legacy-camera"),
        ("imu_fresh", False),
        ("tmap_navigation_active", False),
        ("continuity_seconds", 899),
        ("advisory_level", "high"),
        ("accessibility_delivery", "none"),
        ("vibration_observed", True),
        ("fallback_after_gate_loss", "CAMERA_IMU_NON_METRIC"),
    ],
)
def test_android_unsupported_receipt_rejects_unsafe_result(
    tmp_path: Path,
    field: str,
    unsafe: object,
) -> None:
    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at)
    result = receipt["result"]
    assert isinstance(result, dict)
    result[field] = unsafe
    with pytest.raises(EvidenceFailure, match=field if field != "arcore_unsupported_origin" else "origin"):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )


def test_android_summary_path_must_be_safe_relative_and_not_symlink(tmp_path: Path) -> None:
    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at)
    summary_record = receipt["field_session_summary"]
    assert isinstance(summary_record, dict)
    summary_record["path"] = "../field_session_summary.json"
    with pytest.raises(EvidenceFailure, match="safe relative path"):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )


@pytest.mark.parametrize("link_kind", ["symlink", "hardlink"])
def test_android_receipt_source_rejects_links(tmp_path: Path, link_kind: str) -> None:
    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at)
    target = write_json(tmp_path / "android-field-receipt-source.json", receipt)
    linked = tmp_path / "android-field-receipt-linked.json"
    if link_kind == "symlink":
        linked.symlink_to(target)
    else:
        os.link(target, linked)

    with pytest.raises(release_gate.ExternalCheckReceiptFailure, match="single-link"):
        release_gate.validate_external_check_receipt(
            "android_arcore_unsupported_camera_field",
            linked,
            source_commit="a" * 40,
            release_artifacts_sha256="b" * 64,
            observed_at=observed_at,
            android_apk_sha256="d" * 64,
        )


@pytest.mark.parametrize(
    ("record_name", "link_name"),
    [
        ("field_apk", "walksafe-field-hardlink.apk"),
        ("field_session_summary", "field-session-summary-hardlink.json"),
    ],
)
def test_android_bound_artifacts_reject_hardlinks(
    tmp_path: Path,
    record_name: str,
    link_name: str,
) -> None:
    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at)
    record = receipt[record_name]
    assert isinstance(record, dict)
    target = tmp_path / str(record["path"])
    linked = target.parent / link_name
    os.link(target, linked)
    record["path"] = linked.relative_to(tmp_path).as_posix()

    with pytest.raises(EvidenceFailure, match="single-link"):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )


def test_android_summary_record_requires_exact_bytes_and_regular_file(tmp_path: Path) -> None:
    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at)
    summary_record = receipt["field_session_summary"]
    assert isinstance(summary_record, dict)
    summary_record["bytes"] = int(summary_record["bytes"]) + 1
    with pytest.raises(EvidenceFailure, match="bytes does not match"):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )

    receipt = android_structured_receipt(tmp_path, observed_at)
    summary_record = receipt["field_session_summary"]
    assert isinstance(summary_record, dict)
    summary_record["path"] = "android-field"
    with pytest.raises(EvidenceFailure, match="regular non-symlink"):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )

    receipt = android_structured_receipt(tmp_path, observed_at)
    summary_record = receipt["field_session_summary"]
    assert isinstance(summary_record, dict)
    target = tmp_path / str(summary_record["path"])
    link = tmp_path / "field_session_summary-link.json"
    link.symlink_to(target)
    summary_record["path"] = link.name
    with pytest.raises(EvidenceFailure, match="non-symlink"):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )


@pytest.mark.parametrize(
    "mutation",
    [
        "legacy_input_format",
        "missing_input_format",
        "legacy_history_flag",
        "summary_unavailable",
        "integrity_unavailable",
        "encrypted_unavailable",
    ],
)
def test_android_summary_requires_explicit_current_device_evidence(
    tmp_path: Path,
    mutation: str,
) -> None:
    summary = android_field_summary()
    integrity = summary["integrity"]
    assert isinstance(integrity, dict)
    if mutation == "legacy_input_format":
        summary["input_format"] = "legacy_plaintext_history_only"
    elif mutation == "missing_input_format":
        summary.pop("input_format")
    elif mutation == "legacy_history_flag":
        integrity["legacy_plaintext_history_only"] = True
    elif mutation == "summary_unavailable":
        summary["summary_available"] = False
    elif mutation == "integrity_unavailable":
        integrity["summary_available"] = False
    elif mutation == "encrypted_unavailable":
        summary["input_format"] = "aead_envelope_v1"
        integrity["encrypted_aead_envelope_detected"] = True

    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at, summary=summary)
    with pytest.raises(EvidenceFailure):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )


@pytest.mark.parametrize("sensitive_key", ["access_token", "vendor_refresh_token", "destination"])
def test_android_summary_rejects_unknown_sensitive_fields(
    tmp_path: Path,
    sensitive_key: str,
) -> None:
    summary = android_field_summary()
    sessions = summary["sessions"]
    assert isinstance(sessions, list) and isinstance(sessions[0], dict)
    sessions[0]["unexpected_metadata"] = {sensitive_key: "must-not-be-accepted"}

    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at, summary=summary)
    with pytest.raises(EvidenceFailure, match="forbidden sensitive field"):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )


def test_android_summary_rejects_duplicate_nonselected_session_ids(tmp_path: Path) -> None:
    summary = android_field_summary()
    sessions = summary["sessions"]
    assert isinstance(sessions, list)
    sessions.extend(
        [
            {"session_id": "duplicate-history-session"},
            {"session_id": "duplicate-history-session"},
        ]
    )

    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at, summary=summary)
    with pytest.raises(EvidenceFailure, match="duplicate session_id"):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )


def test_android_summary_hash_rejects_post_receipt_mutation(tmp_path: Path) -> None:
    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at)
    summary_record = receipt["field_session_summary"]
    assert isinstance(summary_record, dict)
    summary_path = tmp_path / str(summary_record["path"])
    payload = json.loads(summary_path.read_text(encoding="utf-8"))
    payload["integrity"]["evidence_scope"] = "XRCORE_UNSUPPORTED_FIELD"
    write_json(summary_path, payload)
    with pytest.raises(EvidenceFailure, match="sha256 does not match"):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )


@pytest.mark.parametrize(
    "mutation",
    [
        "summary_schema",
        "evidence_scope",
        "unsupported_verified",
        "failure_reasons",
        "expected_source",
        "expected_apk",
        "session_status",
        "session_source",
        "session_apk",
        "unsupported_origin",
        "sample_count",
        "sample_span",
        "sample_gap",
        "aggregate_span",
        "duplicate_elapsed",
        "detection_overflow",
        "missing_fallback",
    ],
)
def test_android_summary_semantic_mutations_fail_closed(tmp_path: Path, mutation: str) -> None:
    summary = android_field_summary()
    integrity = summary["integrity"]
    sessions = summary["sessions"]
    camera_summary = summary["camera_non_metric"]
    assert isinstance(integrity, dict) and isinstance(sessions, list) and isinstance(camera_summary, dict)
    session = sessions[0]
    assert isinstance(session, dict)
    provenance = session["provenance"]
    strict = session["strict_evidence"]
    assert isinstance(provenance, dict) and isinstance(strict, dict)
    camera = strict["camera_non_metric"]
    selected = camera_summary["strict_selected_session_samples"]
    assert isinstance(camera, dict) and isinstance(selected, dict)
    samples = camera["inference_sample_contracts"]
    assert isinstance(samples, list)

    if mutation == "summary_schema":
        summary["schema_version"] = "android.field_summary.v0"
    elif mutation == "evidence_scope":
        integrity["evidence_scope"] = "FORCED_SUPPORTED_FUNCTIONAL"
    elif mutation == "unsupported_verified":
        integrity["arcore_unsupported_verified"] = False
    elif mutation == "failure_reasons":
        integrity["strict_failure_reasons"] = ["fabricated_failure"]
    elif mutation == "expected_source":
        integrity["expected_source_commit"] = "f" * 40
    elif mutation == "expected_apk":
        integrity["expected_apk_sha256"] = "f" * 64
    elif mutation == "session_status":
        session["status"] = "active"
    elif mutation == "session_source":
        provenance["source_commit"] = "f" * 40
    elif mutation == "session_apk":
        provenance["apk_sha256"] = "f" * 64
    elif mutation == "unsupported_origin":
        camera["session_started_contracts"][0]["reason"] = "debug_forced_supported"
    elif mutation == "sample_count":
        selected["sample_count"] = 59
    elif mutation == "sample_span":
        selected["sample_span_ms"] = 899_999
    elif mutation == "sample_gap":
        selected["max_sample_gap_ms"] = 30_001
    elif mutation == "aggregate_span":
        selected["sample_span_ms"] = 916_000
    elif mutation == "duplicate_elapsed":
        samples[1]["elapsed_realtime_ms"] = samples[0]["elapsed_realtime_ms"]
    elif mutation == "detection_overflow":
        samples[0]["detection_count"] = 1 << 31
    elif mutation == "missing_fallback":
        samples[-1]["capability_tier"] = "CAMERA_IMU_NON_METRIC"
        samples[-1]["imu_fresh"] = True

    observed_at = datetime.now(UTC)
    receipt = android_structured_receipt(tmp_path, observed_at, summary=summary)
    with pytest.raises(EvidenceFailure):
        validate_structured_check(
            tmp_path,
            "android_arcore_unsupported_camera_field",
            receipt,
            android_apk_sha256="d" * 64,
        )


def test_release_artifacts_bind_web_build_id_and_android_apk(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_commit = "a" * 40
    build_root = tmp_path / ".next"
    build_root.mkdir()
    build_id = build_root / "BUILD_ID"
    build_id.write_text(f"{source_commit}\n", encoding="utf-8")
    server_bundle = build_root / "server.js"
    server_bundle.write_text("console.log('walksafe')\n", encoding="utf-8")
    deployment_probe = build_root / ".next" / "static" / source_commit / "_buildManifest.js"
    deployment_probe.parent.mkdir(parents=True)
    deployment_probe.write_text("self.__BUILD_MANIFEST={}\n", encoding="utf-8")
    web_archive = tmp_path / "walksafe-web.tar.gz"
    write_web_archive(web_archive, build_root)
    inputs, quality_receipts = web_provenance(tmp_path)
    build_manifest = write_json(
        tmp_path / "web-build-manifest.json",
        {
            "schema_version": "walksafe.web-build-manifest.v4",
            "source_commit": source_commit,
            "build_id": source_commit,
            "inputs": inputs,
            "toolchain": {"node": "v22.23.1", "npm": "10.9.8"},
            "build_environment": WEB_BUILD_ENVIRONMENT,
            "quality_receipts": quality_receipts,
            "deployment_archive": {"name": web_archive.name, **artifact_record(web_archive, tmp_path)},
            "files": [
                {
                    "path": path.relative_to(build_root).as_posix(),
                    "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                }
                for path in (build_id, server_bundle, deployment_probe)
            ],
        },
    )
    apk = tmp_path / "walksafe.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"binary-manifest")
        archive.writestr(
            "classes.dex",
            b"dex-prefix\0"
            + source_commit.encode("ascii")
            + b"\0"
            + release_gate.ANDROID_RELEASE_BUILD_MARKER.encode("ascii")
            + b"\0dex-suffix",
        )
    signer_digest = "b" * 64
    configure_fake_android_tools(tmp_path, monkeypatch)
    monkeypatch.setenv("WALKSAFE_ANDROID_RELEASE_SIGNER_SHA256", signer_digest)
    android_commands: list[tuple[list[str], dict[str, object]]] = []

    def fake_android_tool(command, **kwargs):
        android_commands.append((command, kwargs))
        if "verify" in command:
            output = (
                "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                "Number of signers: 1\n"
                f"Signer #1 certificate SHA-256 digest: {signer_digest}\n"
            )
        elif command[1:3] == ["apk", "summary"]:
            output = "kr.co.hanium.dreamup.walksafe\t1\t0.1.0\n"
        elif command[1:3] == ["manifest", "min-sdk"]:
            output = "26\n"
        elif command[1:3] == ["manifest", "target-sdk"]:
            output = "36\n"
        elif command[1:3] == ["manifest", "debuggable"]:
            output = "false\n"
        elif command[1:3] == ["dex", "list"]:
            output = "classes.dex\n"
        else:
            raise AssertionError(command)
        return subprocess.CompletedProcess(command, 0, stdout=output, stderr="")

    monkeypatch.setattr(
        release_gate.subprocess,
        "run",
        fake_android_tool,
    )
    payload = {
        "release_artifacts": {
            "web_build": {
                "manifest_path": build_manifest.name,
                "manifest_sha256": hashlib.sha256(build_manifest.read_bytes()).hexdigest(),
                "build_root": build_root.name,
                "archive_path": web_archive.name,
                "archive_sha256": hashlib.sha256(web_archive.read_bytes()).hexdigest(),
            },
            "android_apk": {
                "path": apk.name,
                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                "signer_certificate_sha256": signer_digest,
            },
        }
    }

    verified = validate_release_artifacts(
        payload,
        profile="full",
        base_dir=tmp_path,
        source_commit=source_commit,
        repository_root=tmp_path,
    )
    assert verified["web_build"]["build_id"] == source_commit
    assert verified["android_apk"]["sha256"] == hashlib.sha256(apk.read_bytes()).hexdigest()
    assert verified["android_apk"]["source_commit"] == source_commit
    assert verified["android_apk"]["signer_certificate_sha256"] == signer_digest
    assert verified["android_apk"]["application_id"] == "kr.co.hanium.dreamup.walksafe"
    assert verified["android_apk"]["debuggable"] == "false"
    assert verified["android_apk"]["build_marker"] == release_gate.ANDROID_RELEASE_BUILD_MARKER
    signature_command, signature_options = android_commands[0]
    assert signature_command[0] == str(tmp_path / "java-home" / "bin" / "java")
    assert signature_command[1] == "-jar"
    assert signature_command[2].startswith("/proc/self/fd/")
    assert signature_command[-1].startswith("/proc/self/fd/")
    assert signature_options["env"]["PATH"].startswith(str(tmp_path / "java-home" / "bin"))
    assert android_commands[1][0][0] == str(tmp_path / "cmdline-tools" / "bin" / "apkanalyzer")

    web_only = {"release_artifacts": {"web_build": payload["release_artifacts"]["web_build"]}}
    receipt_path = tmp_path / str(quality_receipts[0]["path"])
    original_receipt = receipt_path.read_bytes()
    receipt_path.write_text("fabricated PASS\n", encoding="utf-8")
    with pytest.raises(EvidenceFailure, match="quality receipt"):
        validate_release_artifacts(
            web_only,
            profile="web-release",
            base_dir=tmp_path,
            source_commit=source_commit,
            repository_root=tmp_path,
        )
    receipt_path.write_bytes(original_receipt)

    original_manifest = build_manifest.read_bytes()
    for receipt_name in ("node-toolchain", "node-toolchain-post"):
        manifest_payload = json.loads(original_manifest)
        receipt_record = next(
            record
            for record in manifest_payload["quality_receipts"]
            if record["name"] == receipt_name
        )
        attestation_path = tmp_path / receipt_record["path"]
        original_attestation = attestation_path.read_bytes()
        attestation = json.loads(original_attestation)
        attestation["node"]["sha256"] = "0" * 64
        attestation_path.write_text(json.dumps(attestation), encoding="utf-8")
        receipt_record.update(artifact_record(attestation_path, tmp_path, name=receipt_name))
        build_manifest.write_text(json.dumps(manifest_payload), encoding="utf-8")
        web_only["release_artifacts"]["web_build"]["manifest_sha256"] = hashlib.sha256(
            build_manifest.read_bytes()
        ).hexdigest()
        with pytest.raises(EvidenceFailure, match="Node toolchain attestation"):
            validate_release_artifacts(
                web_only,
                profile="web-release",
                base_dir=tmp_path,
                source_commit=source_commit,
                repository_root=tmp_path,
            )
        attestation_path.write_bytes(original_attestation)
        build_manifest.write_bytes(original_manifest)
        web_only["release_artifacts"]["web_build"]["manifest_sha256"] = hashlib.sha256(
            original_manifest
        ).hexdigest()

    package_json = tmp_path / "apps/web/package.json"
    original_package = package_json.read_bytes()
    package_json.write_text('{"name":"changed-after-build"}\n', encoding="utf-8")
    with pytest.raises(EvidenceFailure, match="differs from the release source"):
        validate_release_artifacts(
            web_only,
            profile="web-release",
            base_dir=tmp_path,
            source_commit=source_commit,
            repository_root=tmp_path,
        )
    package_json.write_bytes(original_package)

    other_root = tmp_path / "different-web-build"
    other_root.mkdir()
    for current in build_root.rglob("*"):
        target = other_root / current.relative_to(build_root)
        if current.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(current.read_bytes())
    (other_root / "server.js").write_text("same manifest, different deployment\n", encoding="utf-8")
    write_web_archive(web_archive, other_root)
    manifest_payload = json.loads(build_manifest.read_text(encoding="utf-8"))
    manifest_payload["deployment_archive"] = {"name": web_archive.name, **artifact_record(web_archive, tmp_path)}
    build_manifest.write_text(json.dumps(manifest_payload), encoding="utf-8")
    web_only["release_artifacts"]["web_build"]["manifest_sha256"] = hashlib.sha256(build_manifest.read_bytes()).hexdigest()
    web_only["release_artifacts"]["web_build"]["archive_sha256"] = hashlib.sha256(web_archive.read_bytes()).hexdigest()
    with pytest.raises(EvidenceFailure, match="archive is unsafe or differs"):
        validate_release_artifacts(
            web_only,
            profile="web-release",
            base_dir=tmp_path,
            source_commit=source_commit,
            repository_root=tmp_path,
        )


def test_full_release_chain_options_are_fail_closed(tmp_path: Path) -> None:
    full = release_gate.parse_args(["--profile", "full"])
    with pytest.raises(EvidenceFailure, match="--full-rc-manifest"):
        release_gate.validate_release_chain_options(full)

    web = release_gate.parse_args(
        [
            "--profile",
            "web-release",
            "--production-receipt",
            str(tmp_path / "production.json"),
        ]
    )
    with pytest.raises(EvidenceFailure, match="only valid with --profile full"):
        release_gate.validate_release_chain_options(web)


def test_imported_full_artifact_binding_entrypoint_is_legacy_blocked(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    source_commit = "a" * 40
    evidence = tmp_path / "release-evidence.json"
    evidence.write_text("{}\n", encoding="utf-8")
    verified_artifacts = {"android_apk": {"sha256": "b" * 64}}
    monkeypatch.setattr(
        release_gate,
        "load_evidence",
        lambda _path: {
            "release_id": "release-precheck",
            "source_commit": source_commit,
            "checks": {},
        },
    )
    monkeypatch.setattr(release_gate, "validate_source_revision", lambda _payload: source_commit)
    monkeypatch.setattr(release_gate, "_load_release_dependencies", lambda: None)
    monkeypatch.setattr(
        release_gate,
        "validate_release_artifacts",
        lambda *_args, **_kwargs: verified_artifacts,
    )

    assert release_gate.main(
        [
            "--profile",
            "full",
            "--evidence",
            str(evidence),
            "--print-release-artifact-binding",
        ]
    ) == 78
    captured = capsys.readouterr()
    assert captured.out == ""
    assert "LEGACY_REFERENCE_ONLY" in captured.err


def test_full_release_chain_replays_pinned_gate_and_requires_exact_receipt_bytes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_commit = "a" * 40
    certificate = "b" * 64
    files: dict[str, Path] = {}
    for name, payload in {
        "walksafe-full-rc-manifest.json": b"manifest\n",
        "walksafe-full-rc-validation-receipt.json": b"validation\n",
        "app-release-unsigned.apk": b"unsigned\n",
        "walksafe-signed.apk": b"signed\n",
        "walksafe-operator-attestation.json": b"attestation\n",
        "walksafe-operator-attestation.json.asc": b"signature\n",
        "approved-reviewers.gpg": b"keyring\n",
        "gpg": b"gpg\n",
        "java": b"java\n",
        "apksigner.jar": b"apksigner\n",
    }.items():
        path = tmp_path / name
        path.write_bytes(payload)
        files[name] = path
    signed = files["walksafe-signed.apk"]
    unsigned = files["app-release-unsigned.apk"]
    manifest = files["walksafe-full-rc-manifest.json"]
    validation = files["walksafe-full-rc-validation-receipt.json"]
    attestation = files["walksafe-operator-attestation.json"]
    signature = files["walksafe-operator-attestation.json.asc"]
    result = {
        "schema_version": "walksafe.android-signing-gate.v2",
        "source_commit": source_commit,
        "unsigned_apk_sha256": hashlib.sha256(unsigned.read_bytes()).hexdigest(),
        "signed_apk_sha256": hashlib.sha256(signed.read_bytes()).hexdigest(),
        "certificate_sha256": certificate,
        "full_rc_manifest": artifact_record(manifest, tmp_path),
        "full_rc_closure_sha256": "c" * 64,
        "validation_receipt": artifact_record(validation, tmp_path),
        "operator_attestation": artifact_record(attestation, tmp_path),
        "operator_attestation_signature": artifact_record(signature, tmp_path),
        "operator_fingerprint": "d" * 40,
        "operator_primary_fingerprint": "d" * 40,
        "operator_signing_fingerprint": "e" * 40,
        "operator_fingerprint_sha256": "f" * 64,
        "gpg": {"path": str(files["gpg"]), "bytes": 4, "sha256": "1" * 64},
        "gpg_keyring": {
            "path": str(files["approved-reviewers.gpg"]),
            "bytes": 8,
            "sha256": "2" * 64,
        },
        "tools": {"java": {}, "apksigner_jar": {}},
        "validation_tools": {"java": {}, "apksigner_jar": {}},
        "android_signing_gate_passed": True,
        "deployment_complete": False,
        "verified": True,
    }
    signing_receipt = tmp_path / "android-signing-gate.json"
    signing_receipt.write_text(
        json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    calls: list[dict[str, object]] = []

    def fake_verify_signed_release(**kwargs: object) -> dict[str, object]:
        calls.append(kwargs)
        return result

    monkeypatch.setattr(release_gate, "verify_signed_release", fake_verify_signed_release)
    payload = {
        "release_artifacts": {
            "android_apk": {
                "path": signed.name,
                "sha256": result["signed_apk_sha256"],
                "signer_certificate_sha256": certificate,
            }
        }
    }
    verified_artifacts = {
        "android_apk": {
            "sha256": result["signed_apk_sha256"],
            "signer_certificate_sha256": certificate,
        }
    }

    chain = release_gate.validate_full_release_signing_chain(
        source_commit=source_commit,
        payload=payload,
        release_artifacts=verified_artifacts,
        base_dir=tmp_path,
        manifest_path=manifest,
        validation_receipt_path=validation,
        unsigned_apk_path=unsigned,
        operator_attestation_path=attestation,
        operator_attestation_signature_path=signature,
        expected_operator_fingerprint="d" * 40,
        gpg_path=files["gpg"],
        expected_gpg_sha256="1" * 64,
        gpg_keyring_path=files["approved-reviewers.gpg"],
        java_path=files["java"],
        expected_java_sha256="2" * 64,
        apksigner_jar_path=files["apksigner.jar"],
        expected_apksigner_jar_sha256="3" * 64,
        signing_gate_receipt_path=signing_receipt,
        repository_root=tmp_path,
    )

    assert calls[0]["signed_apk_path"] == signed
    assert calls[0]["expected_cert_sha256"] == certificate
    assert chain["verification"] == result
    assert chain["receipt"]["sha256"] == hashlib.sha256(signing_receipt.read_bytes()).hexdigest()

    signing_receipt.write_text(
        json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    with pytest.raises(EvidenceFailure, match="canonical bytes"):
        release_gate.validate_full_release_signing_chain(
            source_commit=source_commit,
            payload=payload,
            release_artifacts=verified_artifacts,
            base_dir=tmp_path,
            manifest_path=manifest,
            validation_receipt_path=validation,
            unsigned_apk_path=unsigned,
            operator_attestation_path=attestation,
            operator_attestation_signature_path=signature,
            expected_operator_fingerprint="d" * 40,
            gpg_path=files["gpg"],
            expected_gpg_sha256="1" * 64,
            gpg_keyring_path=files["approved-reviewers.gpg"],
            java_path=files["java"],
            expected_java_sha256="2" * 64,
            apksigner_jar_path=files["apksigner.jar"],
            expected_apksigner_jar_sha256="3" * 64,
            signing_gate_receipt_path=signing_receipt,
            repository_root=tmp_path,
        )


def test_production_blocker_resolution_binds_all_manifest_blockers(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_commit = "a" * 40
    release_artifacts = {"android_apk": {"sha256": "b" * 64}}
    release_artifacts_sha256 = hashlib.sha256(
        json.dumps(release_artifacts, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    requirements = {
        blocker_id: f"verified production requirement for {blocker_id}"
        for blocker_id in release_gate.PRODUCTION_BLOCKER_CHECKS
    }
    manifest = write_json(
        tmp_path / "walksafe-full-rc-manifest.json",
        {
            "external_runtime_inputs": [
                {"id": blocker_id, "included": False, "required": requirements[blocker_id]}
                for blocker_id in sorted(requirements)
            ]
        },
    )
    gpg_record = {"path": "/usr/bin/gpg", "bytes": 1, "sha256": "c" * 64}
    keyring_record = {"path": "/operator/reviewers.gpg", "bytes": 1, "sha256": "d" * 64}
    signing_chain = {
        "verification": {
            "full_rc_manifest": artifact_record(manifest, tmp_path),
            "full_rc_closure_sha256": "e" * 64,
            "operator_primary_fingerprint": "f" * 40,
            "gpg": gpg_record,
            "gpg_keyring": keyring_record,
        },
        "receipt": {
            "path": "android-signing-gate.json",
            "bytes": 100,
            "sha256": "1" * 64,
        },
    }
    evidence_dir = tmp_path / "blocker-evidence"
    evidence_dir.mkdir()
    resolutions = []
    for blocker_id, checks in sorted(release_gate.PRODUCTION_BLOCKER_CHECKS.items()):
        evidence = evidence_dir / f"{blocker_id}.json"
        evidence.write_text(
            json.dumps({"blocker_id": blocker_id, "observed": True}) + "\n",
            encoding="utf-8",
        )
        resolutions.append(
            {
                "blocker_id": blocker_id,
                "required": requirements[blocker_id],
                "status": "resolved",
                "checks": {check: True for check in checks},
                "evidence": [artifact_record(evidence, tmp_path)],
            }
        )
    resolution_payload = {
        "schema_version": "walksafe.production-blocker-resolution.v1",
        "source_commit": source_commit,
        "full_rc_manifest": artifact_record(manifest, tmp_path),
        "full_rc_closure_sha256": "e" * 64,
        "release_artifacts_sha256": release_artifacts_sha256,
        "verified_at": datetime.now(UTC).isoformat(),
        "operator": "release-operator",
        "blocker_ids": sorted(requirements),
        "resolutions": resolutions,
        "all_blockers_resolved": True,
    }
    receipt = tmp_path / "production-blocker-resolution.json"
    signature = tmp_path / "production-blocker-resolution.json.asc"
    signature.write_bytes(b"detached signature\n")

    def write_resolution(payload: dict[str, object]) -> None:
        receipt.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    def validate_resolution() -> dict[str, object]:
        return release_gate.validate_production_blocker_resolution(
            receipt_path=receipt,
            signature_path=signature,
            manifest_path=manifest,
            source_commit=source_commit,
            release_artifacts_sha256=release_artifacts_sha256,
            full_rc_signing_chain=signing_chain,
            expected_operator_fingerprint="f" * 40,
            gpg_path=tmp_path / "gpg",
            expected_gpg_sha256="c" * 64,
            gpg_keyring_path=tmp_path / "reviewers.gpg",
            now=datetime.now(UTC),
            max_age=timedelta(days=1),
        )

    monkeypatch.setattr(
        release_gate,
        "verify_approved_detached_signature",
        lambda **_kwargs: ("f" * 40, "2" * 40, gpg_record, keyring_record),
    )
    write_resolution(resolution_payload)

    verified = validate_resolution()

    assert verified["blocker_ids"] == sorted(requirements)
    assert verified["all_blockers_resolved"] is True
    assert (
        verified["verification_scope"]
        == "approved-reviewer-signed-operational-attestation-integrity.v1"
    )

    pristine = json.loads(json.dumps(resolution_payload))
    mutations = (
        (
            lambda payload: payload["resolutions"][0].__setitem__("required", "changed"),
            "not explicitly resolved",
        ),
        (lambda payload: payload["resolutions"].pop(), "list is incomplete"),
        (
            lambda payload: payload["resolutions"].__setitem__(
                -1, json.loads(json.dumps(payload["resolutions"][0]))
            ),
            "ids are invalid or duplicated",
        ),
        (
            lambda payload: payload["resolutions"][0]["checks"].__setitem__(
                next(iter(payload["resolutions"][0]["checks"])), False
            ),
            "checks are incomplete",
        ),
        (
            lambda payload: payload["resolutions"][0]["checks"].__setitem__("extra", True),
            "checks are incomplete",
        ),
        (
            lambda payload: payload["resolutions"][0]["evidence"][0].__setitem__(
                "sha256", "0" * 64
            ),
            "SHA-256 does not match",
        ),
        (
            lambda payload: payload["resolutions"][0]["evidence"][0].__setitem__(
                "path", "../outside.json"
            ),
            "path is unsafe",
        ),
        (
            lambda payload: payload.__setitem__("verified_at", "2000-01-01T00:00:00+00:00"),
            "future-dated or stale",
        ),
    )
    for mutate, error in mutations:
        candidate = json.loads(json.dumps(pristine))
        mutate(candidate)
        write_resolution(candidate)
        with pytest.raises(EvidenceFailure, match=error):
            validate_resolution()

    write_resolution(pristine)
    monkeypatch.setattr(
        release_gate,
        "verify_approved_detached_signature",
        lambda **_kwargs: ("9" * 40, "2" * 40, gpg_record, keyring_record),
    )
    with pytest.raises(EvidenceFailure, match="different approved reviewer trust chain"):
        validate_resolution()


def test_production_receipt_is_complete_and_published_only_once(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_root = tmp_path / "source"
    source_root.mkdir()
    output = tmp_path / "walksafe-production-release.json"
    source_commit = "a" * 40
    identity = types.SimpleNamespace(
        commit=source_commit,
        tree="b" * 40,
        inventory={"algorithm": "sha256", "file_count": 3, "sha256": "c" * 64},
    )
    monkeypatch.setattr(release_gate, "verify_exact_git_source", lambda *_args, **_kwargs: identity)
    release_artifacts = {"android_apk": {"sha256": "d" * 64}}
    release_artifacts_sha256 = hashlib.sha256(
        json.dumps(release_artifacts, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    gpg_record = {"path": "/usr/bin/gpg", "bytes": 1, "sha256": "1" * 64}
    keyring_record = {"path": "/operator/reviewers.gpg", "bytes": 1, "sha256": "2" * 64}
    signing_chain = {
        "verification": {
            "schema_version": "walksafe.android-signing-gate.v2",
            "source_commit": source_commit,
            "android_signing_gate_passed": True,
            "deployment_complete": False,
            "verified": True,
            "operator_primary_fingerprint": "3" * 40,
            "gpg": gpg_record,
            "gpg_keyring": keyring_record,
        },
        "receipt": {
            "path": "android-signing-gate.json",
            "bytes": 100,
            "sha256": "f" * 64,
        },
    }
    blocker_resolution = {
        "schema_version": "walksafe.production-blocker-resolution-verification.v1",
        "verification_scope": "approved-reviewer-signed-operational-attestation-integrity.v1",
        "receipt": {"path": "resolution.json", "bytes": 100, "sha256": "4" * 64},
        "detached_signature": {"path": "resolution.asc", "bytes": 100, "sha256": "5" * 64},
        "operator_primary_fingerprint": "3" * 40,
        "operator_signing_fingerprint": "6" * 40,
        "gpg": gpg_record,
        "gpg_keyring": keyring_record,
        "blocker_ids": sorted(release_gate.PRODUCTION_BLOCKER_CHECKS),
        "all_blockers_resolved": True,
        "verified": True,
    }

    published = release_gate.publish_production_release_receipt(
        output_path=output,
        release_id="walksafe-release-test",
        source_commit=source_commit,
        release_artifacts=release_artifacts,
        release_artifacts_sha256=release_artifacts_sha256,
        verified_checks={"device": "2026-07-17T00:00:00+00:00"},
        verified_operations={"backup": {"verified": True}},
        full_rc_signing_chain=signing_chain,
        blocker_resolution=blocker_resolution,
        repository_root=source_root,
    )

    assert published["schema_version"] == "walksafe.production-release.v1"
    assert published["deployment_complete"] is True
    assert published["blocker_ids"] == []
    assert published["source"]["commit"] == source_commit
    assert output.stat().st_mode & 0o777 == 0o600
    assert json.loads(output.read_text(encoding="utf-8")) == published
    immutable_input = tmp_path / "immutable-signing-input"
    immutable_input.mkdir()
    with pytest.raises(EvidenceFailure, match="immutable Full-RC/validation/signing"):
        release_gate.publish_production_release_receipt(
            output_path=immutable_input / "production.json",
            release_id="walksafe-release-test",
            source_commit=source_commit,
            release_artifacts=release_artifacts,
            release_artifacts_sha256=release_artifacts_sha256,
            verified_checks={},
            verified_operations={},
            full_rc_signing_chain=signing_chain,
            blocker_resolution=blocker_resolution,
            protected_input_roots=(immutable_input,),
            repository_root=source_root,
        )
    with pytest.raises(EvidenceFailure, match="already exists"):
        release_gate.publish_production_release_receipt(
            output_path=output,
            release_id="walksafe-release-test",
            source_commit=source_commit,
            release_artifacts=release_artifacts,
            release_artifacts_sha256=release_artifacts_sha256,
            verified_checks={},
            verified_operations={},
            full_rc_signing_chain=signing_chain,
            blocker_resolution=blocker_resolution,
            repository_root=source_root,
        )


def test_android_release_rejects_apk_without_release_build_marker(tmp_path: Path) -> None:
    source_commit = "a" * 40
    apk = tmp_path / "debug-like.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"manifest")
        archive.writestr("classes.dex", source_commit.encode("ascii"))

    with pytest.raises(EvidenceFailure, match="release build marker"):
        release_gate.validate_android_apk(
            apk,
            source_commit=source_commit,
            signer_certificate_sha256="b" * 64,
        )


def test_android_release_rejects_effectively_debuggable_apk(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_commit = "a" * 40
    signer_digest = "b" * 64
    apk = tmp_path / "walksafe.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"manifest")
        archive.writestr(
            "classes.dex",
            source_commit.encode("ascii") + release_gate.ANDROID_RELEASE_BUILD_MARKER.encode("ascii"),
        )
    configure_fake_android_tools(tmp_path, monkeypatch)
    monkeypatch.setenv("WALKSAFE_ANDROID_RELEASE_SIGNER_SHA256", signer_digest)

    def fake_tool(command, **_kwargs):
        if "verify" in command:
            output = (
                "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                "Number of signers: 1\n"
                f"Signer #1 certificate SHA-256 digest: {signer_digest}\n"
            )
        elif command[1:3] == ["apk", "summary"]:
            output = "kr.co.hanium.dreamup.walksafe\t1\t0.1.0\n"
        elif command[1:3] == ["manifest", "min-sdk"]:
            output = "26\n"
        elif command[1:3] == ["manifest", "target-sdk"]:
            output = "36\n"
        elif command[1:3] == ["manifest", "debuggable"]:
            output = "true\n"
        else:
            output = "classes.dex\n"
        return subprocess.CompletedProcess(command, 0, stdout=output, stderr="")

    monkeypatch.setattr(release_gate.subprocess, "run", fake_tool)
    with pytest.raises(EvidenceFailure, match="debuggable=false"):
        release_gate.validate_android_apk(
            apk,
            source_commit=source_commit,
            signer_certificate_sha256=signer_digest,
        )


def test_android_release_rejects_apk_replaced_during_signature_validation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_commit = "a" * 40
    signer_digest = "b" * 64
    apk = tmp_path / "walksafe.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"manifest")
        archive.writestr(
            "classes.dex",
            source_commit.encode("ascii") + release_gate.ANDROID_RELEASE_BUILD_MARKER.encode("ascii"),
        )
    original = apk.read_bytes()
    configure_fake_android_tools(tmp_path, monkeypatch)
    monkeypatch.setenv("WALKSAFE_ANDROID_RELEASE_SIGNER_SHA256", signer_digest)

    def replace_apk(command, **_kwargs):
        apk.write_bytes(b"replacement")
        apk.write_bytes(original)
        return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

    monkeypatch.setattr(release_gate.subprocess, "run", replace_apk)

    with pytest.raises(EvidenceFailure, match="changed during APK signature validation"):
        release_gate.validate_android_apk(
            apk,
            source_commit=source_commit,
            signer_certificate_sha256=signer_digest,
        )


def test_android_release_rejects_an_arbitrary_apk_named_blob(tmp_path: Path) -> None:
    apk = tmp_path / "fake.apk"
    apk.write_bytes(b"signed-apk")
    payload = {
        "release_artifacts": {
            "android_apk": {
                "path": apk.name,
                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                "signer_certificate_sha256": "b" * 64,
            }
        }
    }

    with pytest.raises(EvidenceFailure, match="readable APK ZIP archive"):
        validate_release_artifacts(
            payload,
            profile="android-research",
            base_dir=tmp_path,
            source_commit="a" * 40,
        )


def test_running_web_build_must_match_the_source_bound_static_probe(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_commit = "a" * 40
    payload = b"self.__BUILD_MANIFEST=fixture"
    digest = hashlib.sha256(payload).hexdigest()
    monkeypatch.setenv("WALKSAFE_WEB_DEPLOYMENT_URL", "https://walksafe.example")

    class Response:
        def __init__(self, content: bytes, json_payload: dict[str, str] | None = None) -> None:
            self.content = content
            self._json_payload = json_payload
            self.headers = {"cache-control": "no-store"}

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict[str, str]:
            if self._json_payload is None:
                raise ValueError("not json")
            return self._json_payload

    static_response = Response(payload)
    identity_response = Response(
        b"identity",
        {
            "schema_version": "walksafe.web-release-identity.v1",
            "status": "ready",
            "source_commit": source_commit,
        },
    )
    monkeypatch.setattr(
        httpx,
        "get",
        lambda url, **_kwargs: identity_response if url.endswith("/api/release-identity") else static_response,
    )
    verified = validate_running_web_build(
        {
            "deployment_probe_path": "/_next/static/" + source_commit + "/_buildManifest.js",
            "deployment_probe_sha256": digest,
            "source_identity_path": "/api/release-identity",
            "source_commit": source_commit,
        }
    )
    assert verified["probe_sha256"] == digest
    assert verified["source_commit"] == source_commit

    static_response.content = b"another build"
    with pytest.raises(EvidenceFailure, match="does not match"):
        validate_running_web_build(
            {
                "deployment_probe_path": "/_next/static/" + source_commit + "/_buildManifest.js",
                "deployment_probe_sha256": digest,
                "source_identity_path": "/api/release-identity",
                "source_commit": source_commit,
            }
        )

    static_response.content = payload
    identity_response._json_payload = {
        "schema_version": "walksafe.web-release-identity.v1",
        "status": "ready",
        "source_commit": "b" * 40,
    }
    with pytest.raises(EvidenceFailure, match="source identity"):
        validate_running_web_build(
            {
                "deployment_probe_path": "/_next/static/" + source_commit + "/_buildManifest.js",
                "deployment_probe_sha256": digest,
                "source_identity_path": "/api/release-identity",
                "source_commit": source_commit,
            }
        )


def test_web_release_rejects_manifested_next_dev_artifacts(tmp_path: Path) -> None:
    source_commit = "a" * 40
    build_root = tmp_path / ".next"
    stale_dev = build_root / "dev" / "server" / "stale.js"
    stale_dev.parent.mkdir(parents=True)
    (build_root / "BUILD_ID").write_text(source_commit, encoding="utf-8")
    stale_dev.write_text("stale development output", encoding="utf-8")
    files = [build_root / "BUILD_ID", stale_dev]
    web_archive = tmp_path / "walksafe-web.tar.gz"
    write_web_archive(web_archive, build_root)
    inputs, quality_receipts = web_provenance(tmp_path)
    manifest = write_json(
        tmp_path / "web-build-manifest.json",
        {
            "schema_version": "walksafe.web-build-manifest.v4",
            "source_commit": source_commit,
            "build_id": source_commit,
            "inputs": inputs,
            "toolchain": {"node": "v22.23.1", "npm": "10.9.8"},
            "build_environment": WEB_BUILD_ENVIRONMENT,
            "quality_receipts": quality_receipts,
            "deployment_archive": {"name": web_archive.name, **artifact_record(web_archive, tmp_path)},
            "files": [
                {
                    "path": path.relative_to(build_root).as_posix(),
                    "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                }
                for path in files
            ],
        },
    )

    with pytest.raises(EvidenceFailure, match="stale .next/dev"):
        validate_release_artifacts(
            {
                "release_artifacts": {
                    "web_build": {
                        "manifest_path": manifest.name,
                        "manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
                        "build_root": build_root.name,
                        "archive_path": web_archive.name,
                        "archive_sha256": hashlib.sha256(web_archive.read_bytes()).hexdigest(),
                    },
                },
            },
            profile="web-release",
            base_dir=tmp_path,
            source_commit=source_commit,
            repository_root=tmp_path,
        )


def test_android_release_signer_must_match_the_configured_trust_anchor(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_commit = "a" * 40
    apk = tmp_path / "walksafe.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"manifest")
        archive.writestr(
            "classes.dex",
            source_commit.encode("ascii") + release_gate.ANDROID_RELEASE_BUILD_MARKER.encode("ascii"),
        )
    apksigner = tmp_path / "apksigner"
    apksigner.write_text("#!/bin/sh\n", encoding="utf-8")
    apksigner.chmod(0o700)
    monkeypatch.setenv("WALKSAFE_APKSIGNER_BIN", str(apksigner))
    monkeypatch.setenv("WALKSAFE_ANDROID_RELEASE_SIGNER_SHA256", "c" * 64)

    with pytest.raises(EvidenceFailure, match="trusted release certificate"):
        release_gate.validate_android_apk(
            apk,
            source_commit=source_commit,
            signer_certificate_sha256="b" * 64,
        )


def test_android_release_rejects_apkanalyzer_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_commit = "a" * 40
    signer_digest = "b" * 64
    apk = tmp_path / "walksafe.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"manifest")
        archive.writestr(
            "classes.dex",
            source_commit.encode("ascii") + release_gate.ANDROID_RELEASE_BUILD_MARKER.encode("ascii"),
        )
    configure_fake_android_tools(tmp_path, monkeypatch)
    monkeypatch.setenv("WALKSAFE_ANDROID_RELEASE_SIGNER_SHA256", signer_digest)

    def fake_tool(command, **_kwargs):
        if "verify" in command:
            return subprocess.CompletedProcess(
                command,
                0,
                stdout=(
                    "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                    "Number of signers: 1\n"
                    f"Signer #1 certificate SHA-256 digest: {signer_digest}\n"
                ),
                stderr="",
            )
        return subprocess.CompletedProcess(command, 1, stdout="", stderr="invalid APK")

    monkeypatch.setattr(release_gate.subprocess, "run", fake_tool)
    with pytest.raises(EvidenceFailure, match="manifest summary validation failed"):
        release_gate.validate_android_apk(
            apk,
            source_commit=source_commit,
            signer_certificate_sha256=signer_digest,
        )


def test_release_environment_path_rejects_a_configured_symlink(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    real = tmp_path / "real-model.pt"
    link = tmp_path / "linked-model.pt"
    real.write_bytes(b"model")
    link.symlink_to(real)
    monkeypatch.setenv("WALKSAFE_TEST_ARTIFACT_PATH", str(link))

    with pytest.raises(EvidenceFailure, match="symlink path components"):
        required_absolute_environment_path("WALKSAFE_TEST_ARTIFACT_PATH")


def test_source_revision_requires_exact_clean_head_and_ignores_path_git(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    repository = tmp_path / "source"
    repository.mkdir()
    (repository / "README.md").write_text("release source\n", encoding="utf-8")
    subprocess.run(["/usr/bin/git", "init", "-q", str(repository)], check=True)
    subprocess.run(
        ["/usr/bin/git", "-C", str(repository), "config", "user.name", "Release Test"],
        check=True,
    )
    subprocess.run(
        ["/usr/bin/git", "-C", str(repository), "config", "user.email", "release@example.invalid"],
        check=True,
    )
    subprocess.run(["/usr/bin/git", "-C", str(repository), "add", "."], check=True)
    subprocess.run(["/usr/bin/git", "-C", str(repository), "commit", "-qm", "fixture"], check=True)
    head = subprocess.run(
        ["/usr/bin/git", "-C", str(repository), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    decoy = tmp_path / "decoy"
    decoy.mkdir()
    marker = tmp_path / "decoy-used"
    (decoy / "git").write_text(
        f"#!/bin/sh\nprintf x > '{marker}'\nexit 99\n",
        encoding="utf-8",
    )
    (decoy / "git").chmod(0o755)
    monkeypatch.setenv("PATH", str(decoy))

    assert validate_source_revision({"source_commit": head}, repository_root=repository) == head
    assert not marker.exists()

    (repository / "untracked.txt").write_text("dirty\n", encoding="utf-8")
    with pytest.raises(EvidenceFailure, match="worktree is dirty"):
        validate_source_revision({"source_commit": head}, repository_root=repository)


def test_running_backend_must_report_the_release_commit(monkeypatch: pytest.MonkeyPatch) -> None:
    commit = "b" * 40

    class Response:
        def raise_for_status(self) -> None:
            return None

        def json(self):
            return {"status": "ready", "source_commit": commit}

    monkeypatch.setenv("WALKSAFE_BACKEND_HEALTH_URL", "http://127.0.0.1:8000/ready")
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", "field-token-for-test")
    monkeypatch.setattr(httpx, "get", lambda *args, **kwargs: Response())
    assert validate_running_backend_source(commit)["source_commit"] == commit
    with pytest.raises(EvidenceFailure, match="does not match"):
        validate_running_backend_source("c" * 40)

    class NotReadyResponse(Response):
        def json(self):
            return {"status": "not_ready", "source_commit": commit}

    monkeypatch.setattr(httpx, "get", lambda *args, **kwargs: NotReadyResponse())
    with pytest.raises(EvidenceFailure, match="not ready"):
        validate_running_backend_source(commit)


def test_registered_deployment_must_activate_an_eligible_model(tmp_path: Path) -> None:
    model_digest = "a" * 64
    image = tmp_path / "image.bin"
    label = tmp_path / "label.txt"
    manifest = tmp_path / "manifest.csv"
    image.write_bytes(b"release-image")
    label.write_text("0 0.5 0.5 0.2 0.2\n", encoding="utf-8")
    with manifest.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                "split",
                "image_ref",
                "target_image",
                "target_label",
                "image_sha256",
                "label_sha256",
            ],
        )
        writer.writeheader()
        writer.writerow(
            {
                "split": "val",
                "image_ref": "fixture",
                "target_image": image.name,
                "target_label": label.name,
                "image_sha256": hashlib.sha256(image.read_bytes()).hexdigest(),
                "label_sha256": hashlib.sha256(label.read_bytes()).hexdigest(),
            }
        )
    registry = write_json(
        tmp_path / "registry.json",
        {
            "models": [
                {
                    "model_id": "walksafe-release-model",
                    "artifact": {"sha256": model_digest},
                    "dataset": {
                        "content_hash_policy": "sha256_per_image_and_label",
                        "manifest_path": manifest.name,
                        "manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
                    },
                    "deployment_eligible": True,
                    "blockers": [],
                }
            ]
        },
    )
    deployment = write_json(
        tmp_path / "deployment.json",
        {
            "targets": {
                "backend": {
                    "active_model_id": "walksafe-release-model",
                    "runtime_config": "environment:DETECT_V2_RUNTIME_CONFIG_PATH",
                    "model_artifact": "environment:DETECT_V2_UNIFIED_MODEL_PATH",
                }
            }
        },
    )

    assert validate_registered_deployment(
        registry,
        deployment,
        model_sha256=model_digest,
        repository_root=tmp_path,
    )["model_id"] == "walksafe-release-model"
    payload = json.loads(registry.read_text(encoding="utf-8"))
    payload["models"][0]["deployment_eligible"] = False
    write_json(registry, payload)
    with pytest.raises(EvidenceFailure, match="not deployment eligible"):
        validate_registered_deployment(registry, deployment, model_sha256=model_digest, repository_root=tmp_path)

    payload["models"][0]["deployment_eligible"] = True
    write_json(registry, payload)
    image.write_bytes(b"tampered-release-image")
    with pytest.raises(EvidenceFailure, match="content verification failed"):
        validate_registered_deployment(registry, deployment, model_sha256=model_digest, repository_root=tmp_path)


def test_detector_runtime_smoke_rejects_unloadable_model(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model = tmp_path / "not-a-model.pt"
    fixture = tmp_path / "person.jpg"
    model.write_bytes(b"not model weights")
    fixture.write_bytes(b"fixture")

    class BrokenYolo:
        def __init__(self, path: str) -> None:
            raise ValueError(f"cannot load {path}")

    monkeypatch.setitem(sys.modules, "ultralytics", types.SimpleNamespace(YOLO=BrokenYolo))
    with pytest.raises(EvidenceFailure, match="could not be loaded and executed"):
        run_detector_smoke(model, fixture, image_size=768)


def test_detector_runtime_smoke_rejects_wrong_class_order_before_inference(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model = tmp_path / "wrong-order.pt"
    fixture = tmp_path / "person.jpg"
    model.write_bytes(b"model")
    fixture.write_bytes(b"fixture")
    predict_called = False

    class WrongOrderYolo:
        names = {index: name for index, name in enumerate(reversed(UNIFIED_WALKSAFE_CLASSES))}

        def __init__(self, _path: str) -> None:
            pass

        def predict(self, **_kwargs):
            nonlocal predict_called
            predict_called = True
            return []

    monkeypatch.setitem(sys.modules, "ultralytics", types.SimpleNamespace(YOLO=WrongOrderYolo))
    with pytest.raises(EvidenceFailure, match="exact WalkSafe 13-class id/order"):
        run_detector_smoke(model, fixture, image_size=768)
    assert predict_called is False


def test_detector_runtime_smoke_records_validated_class_order(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model = tmp_path / "walksafe.pt"
    fixture = tmp_path / "person.jpg"
    model.write_bytes(b"model")
    fixture.write_bytes(b"fixture")

    class Classes:
        @staticmethod
        def tolist() -> list[int]:
            return [0]

    class ValidYolo:
        names = {index: name for index, name in enumerate(UNIFIED_WALKSAFE_CLASSES)}

        def __init__(self, _path: str) -> None:
            pass

        def predict(self, **_kwargs):
            return [types.SimpleNamespace(boxes=types.SimpleNamespace(cls=Classes()))]

    monkeypatch.setitem(sys.modules, "ultralytics", types.SimpleNamespace(YOLO=ValidYolo))
    result = run_detector_smoke(model, fixture, image_size=768)

    assert result["model_class_names"] == list(UNIFIED_WALKSAFE_CLASSES)
    assert result["detected_names"] == ["person"]


def test_retention_receipt_requires_applied_seven_day_policy(tmp_path: Path) -> None:
    now = datetime.now(UTC)
    receipt = write_json(
        tmp_path / "retention.json",
        {
            "schema_version": "walksafe.field-telemetry-retention.v1",
            "scope": "field_telemetry",
            "checked_at": now.isoformat(),
            "root": str(tmp_path.resolve()),
            "retention_days": 7,
            "applied": True,
            "authorized_admin_id": "walksafe.admin",
            "authorized_session_id": "11111111-1111-4111-8111-111111111111",
            "candidate_dates": ["2026-01-01"],
            "deleted_dates": ["2026-01-01"],
            "candidate_sidecars": [".owners/expired.owner"],
            "deleted_sidecars": [".owners/expired.owner"],
            "inventory_before_sha256": "1" * 64,
            "inventory_after_sha256": "2" * 64,
        },
    )

    result = validate_retention_receipt(receipt, now=now, max_age_hours=26)
    assert result["deleted_count"] == 1
    assert result["authorized_admin_id"] == "walksafe.admin"

    payload = json.loads(receipt.read_text(encoding="utf-8"))
    payload.pop("authorized_session_id")
    write_json(receipt, payload)
    with pytest.raises(EvidenceFailure, match="authorized_session_id"):
        validate_retention_receipt(receipt, now=now, max_age_hours=26)
    payload["authorized_session_id"] = "11111111-1111-4111-8111-111111111111"

    payload["applied"] = False
    write_json(receipt, payload)
    with pytest.raises(EvidenceFailure, match="applied=true"):
        validate_retention_receipt(receipt, now=now, max_age_hours=26)

    payload["applied"] = True
    payload["schema_version"] = "walksafe.test-capture-retention.v1"
    payload["scope"] = "test_capture"
    write_json(receipt, payload)
    assert validate_retention_receipt(
        receipt,
        now=now,
        max_age_hours=26,
        scope="test_capture",
    )["deleted_count"] == 1


def test_report_retention_manifest_requires_completed_policy_apply(tmp_path: Path) -> None:
    now = datetime.now(UTC)
    candidate = {
        "id": "report-1",
        "reason": "fake_demo",
        "retention_days": 30,
        "would_delete": True,
    }
    digest = hashlib.sha256(json.dumps(["report-1"], separators=(",", ":")).encode("utf-8")).hexdigest()
    manifest = write_json(
        tmp_path / "report-retention.json",
        {
            "schema_version": "walksafe.report_retention_apply.v1",
            "run_id": "retention-20260711",
            "actor_id": "retention.operator",
            "authorized_admin_id": "walksafe.admin",
            "authorized_session_id": "11111111-1111-4111-8111-111111111111",
            "started_at": (now - timedelta(minutes=30)).isoformat(),
            "finished_at": now.isoformat(),
            "database_identity_sha256": "1" * 64,
            "upload_root_identity_sha256": "2" * 64,
            "predelete_backup_run_id": "backup-before-retention",
            "predelete_backup_created_at": (now - timedelta(hours=1)).isoformat(),
            "predelete_backup_artifacts_sha256": {
                "reports.dump.gpg": "3" * 64,
                "uploads.tar.gz.gpg": "4" * 64,
            },
            "predelete_restore_restored_at": (now - timedelta(minutes=45)).isoformat(),
            "predelete_restore_receipt_sha256": "5" * 64,
            "maintenance_lock_identity_sha256": "6" * 64,
            "maintenance_lock_device_inode": "1:2",
            "maintenance_lock_acquired_at": (now - timedelta(minutes=31)).isoformat(),
            "status": "completed",
            "destructive_action": True,
            "candidates": [candidate],
            "deleted_count": 1,
            "candidate_ids_sha256": digest,
            "cleanup_errors": [],
        },
    )
    result = validate_report_retention_manifest(manifest, now=now, max_age_hours=26)
    assert result["deleted_count"] == 1
    assert result["maintenance_lock_device_inode"] == "1:2"
    assert result["authorized_session_id"] == "11111111-1111-4111-8111-111111111111"
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    payload.pop("authorized_admin_id")
    write_json(manifest, payload)
    with pytest.raises(EvidenceFailure, match="authorized_admin_id"):
        validate_report_retention_manifest(manifest, now=now, max_age_hours=26)
    payload["authorized_admin_id"] = "walksafe.admin"
    payload["maintenance_lock_device_inode"] = "not-an-inode"
    write_json(manifest, payload)
    with pytest.raises(EvidenceFailure, match="device/inode"):
        validate_report_retention_manifest(manifest, now=now, max_age_hours=26)
    payload["maintenance_lock_device_inode"] = "1:2"
    payload["status"] = "planned"
    write_json(manifest, payload)
    with pytest.raises(EvidenceFailure, match="completed destructive"):
        validate_report_retention_manifest(manifest, now=now, max_age_hours=26)


def test_backup_manifest_verifies_both_artifact_hashes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    now = datetime.now(UTC)
    database = tmp_path / "reports.dump.gpg"
    uploads = tmp_path / "uploads.tar.gz.gpg"
    database.write_bytes(b"database-backup")
    uploads.write_bytes(b"uploads-backup")
    sums = {
        database.name: hashlib.sha256(database.read_bytes()).hexdigest(),
        uploads.name: hashlib.sha256(uploads.read_bytes()).hexdigest(),
    }
    signer_fingerprint = "2" * 40
    (tmp_path / "manifest.json.sig").write_bytes(b"detached-signature")
    monkeypatch.setenv("WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT", signer_fingerprint)

    def verified_signature(command, **kwargs):
        return subprocess.CompletedProcess(
            command,
            0,
            stdout=(
                f"[GNUPG:] GOODSIG {signer_fingerprint[-16:]} Backup Signer\n"
                f"[GNUPG:] VALIDSIG {signer_fingerprint} 2026-07-11 0 0 4 0 1 10 "
                f"00 {signer_fingerprint}\n"
            ),
            stderr="",
        )

    monkeypatch.setattr(release_gate.subprocess, "run", verified_signature)
    manifest = write_json(
        tmp_path / "manifest.json",
        {
            "schema_version": "walksafe.backup.v1",
            "run_id": "backup-20260711",
            "actor_id": "backup.operator",
            "created_at": now.isoformat(),
            "database_identity_sha256": "1" * 64,
            "upload_root_identity_sha256": "2" * 64,
            "snapshot_boundary": {
                "writes_quiesced_by_operator": True,
                "maintenance_lock_identity_sha256": "3" * 64,
                "maintenance_lock_device_inode": "1:2",
                "lock_acquired_at": (now - timedelta(minutes=3)).isoformat(),
                "started_at": (now - timedelta(minutes=2)).isoformat(),
                "finished_at": (now - timedelta(minutes=1)).isoformat(),
            },
            "source_consistency": {
                "ready": True,
                "report_image_count": 2,
                "upload_file_count": 2,
                "missing_count": 0,
                "orphan_count": 0,
                "missing_image_hash_count": 0,
                "image_hash_mismatch_count": 0,
                "snapshot_content_sha256": "7" * 64,
            },
            "database_format": "postgresql-custom+openpgp",
            "uploads_format": "tar-gzip+openpgp",
            "encryption_at_rest": "openpgp",
            "recipient_fingerprint": "1" * 40,
            "signer_fingerprint": signer_fingerprint,
            "artifacts_sha256": sums,
            "contains_secrets": True,
        },
    )

    result = validate_backup_manifest(manifest, now=now, max_age_hours=26)
    assert result["artifact_hashes_verified"] is True
    assert result["maintenance_lock_device_inode"] == "1:2"
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    payload["source_consistency"]["image_hash_mismatch_count"] = 1
    write_json(manifest, payload)
    with pytest.raises(EvidenceFailure, match="content consistency"):
        validate_backup_manifest(manifest, now=now, max_age_hours=26)
    payload["source_consistency"]["image_hash_mismatch_count"] = 0
    write_json(manifest, payload)
    uploads.write_bytes(b"tampered")
    with pytest.raises(EvidenceFailure, match="signed manifest or artifact"):
        validate_backup_manifest(manifest, now=now, max_age_hours=26)


def test_restore_and_agency_receipts_are_bounded(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    now = datetime.now(UTC)
    receipt_signer = "3" * 40
    restore = write_json(
        tmp_path / "restore.json",
        {
            "schema_version": "walksafe.restore-drill.v1",
            "actor_id": "restore.operator",
            "restored_at": now.isoformat(),
            "backup_run_id": "backup-20260711",
            "source_database_identity_sha256": "1" * 64,
            "source_upload_root_identity_sha256": "2" * 64,
            "target_database_identity_sha256": "3" * 64,
            "target_upload_root_identity_sha256": "4" * 64,
            "artifacts_sha256": {
                "reports.dump.gpg": "5" * 64,
                "uploads.tar.gz.gpg": "6" * 64,
            },
            "recipient_fingerprint": "1" * 40,
            "trusted_signer_fingerprint": "2" * 40,
            "receipt_signer_fingerprint": receipt_signer,
            "manifest_signature_verified": True,
            "hashes_verified": True,
            "encrypted_backup_verified": True,
            "database_restore_completed": True,
            "uploads_restore_completed": True,
            "restore_upload_snapshot_sha256": "7" * 64,
            "restore_tree_device_inode": "10:20",
            "report_upload_consistency_verified": True,
            "consistency_counts": {
                "restored_report_count": 2,
                "image_reference_count": 2,
                "matched_image_count": 2,
                "missing_image_count": 0,
                "missing_image_hash_count": 0,
                "image_hash_mismatch_count": 0,
                "unsafe_image_path_count": 0,
                    "restored_upload_file_count": 2,
                    "orphan_upload_file_count": 0,
            },
            "target_was_explicit": True,
        },
    )
    (tmp_path / "restore.json.sig").write_bytes(b"detached-signature")
    verified: list[tuple[Path, Path, str]] = []

    def verify_receipt(
        document: Path,
        signature: Path,
        trusted: str,
    ) -> tuple[dict[str, object], str, str]:
        verified.append((document, signature, trusted))
        return (
            json.loads(document.read_text(encoding="utf-8")),
            trusted,
            hashlib.sha256(document.read_bytes()).hexdigest(),
        )

    monkeypatch.setattr(
        release_gate,
        "verify_signed_json_document_with_digest",
        verify_receipt,
    )
    result = validate_restore_drill_receipt(
        restore,
        now=now,
        max_age_days=30,
        trusted_signer_fingerprint=receipt_signer,
    )
    assert result["database_and_uploads_restored"] is True
    assert result["restore_upload_snapshot_sha256"] == "7" * 64
    assert result["restore_tree_device_inode"] == "10:20"
    assert verified == [(restore, tmp_path / "restore.json.sig", receipt_signer)]

    signed_payload = json.loads(restore.read_text(encoding="utf-8"))
    signed_bytes = restore.read_bytes()
    replacement_bytes = b'{"unsigned":true}'

    def verify_then_replace(
        document: Path,
        _signature: Path,
        trusted: str,
    ) -> tuple[dict[str, object], str, str]:
        document.write_bytes(replacement_bytes)
        return signed_payload, trusted, hashlib.sha256(signed_bytes).hexdigest()

    monkeypatch.setattr(
        release_gate,
        "verify_signed_json_document_with_digest",
        verify_then_replace,
    )
    swapped_result = validate_restore_drill_receipt(
        restore,
        now=now,
        max_age_days=30,
        trusted_signer_fingerprint=receipt_signer,
    )
    assert swapped_result["receipt_sha256"] == hashlib.sha256(signed_bytes).hexdigest()
    assert swapped_result["receipt_sha256"] != hashlib.sha256(replacement_bytes).hexdigest()
    restore.write_bytes(signed_bytes)
    monkeypatch.setattr(
        release_gate,
        "verify_signed_json_document_with_digest",
        verify_receipt,
    )

    restore_payload = json.loads(restore.read_text(encoding="utf-8"))
    restore_payload.pop("restore_upload_snapshot_sha256")
    write_json(restore, restore_payload)
    with pytest.raises(EvidenceFailure, match="restore_upload_snapshot_sha256"):
        validate_restore_drill_receipt(
            restore,
            now=now,
            max_age_days=30,
            trusted_signer_fingerprint=receipt_signer,
        )
    restore_payload["restore_upload_snapshot_sha256"] = "7" * 64
    write_json(restore, restore_payload)
    restore_payload["restore_tree_device_inode"] = "invalid"
    write_json(restore, restore_payload)
    with pytest.raises(EvidenceFailure, match="restore_tree_device_inode"):
        validate_restore_drill_receipt(
            restore,
            now=now,
            max_age_days=30,
            trusted_signer_fingerprint=receipt_signer,
        )
    restore_payload["restore_tree_device_inode"] = "10:20"
    restore_payload["source_database_identity_sha256"] = "A" * 64
    restore_payload["target_database_identity_sha256"] = "a" * 64
    write_json(restore, restore_payload)
    with pytest.raises(EvidenceFailure, match="target database must differ"):
        validate_restore_drill_receipt(
            restore,
            now=now,
            max_age_days=30,
            trusted_signer_fingerprint=receipt_signer,
        )
    restore_payload["source_database_identity_sha256"] = "1" * 64
    restore_payload["target_database_identity_sha256"] = "3" * 64
    restore_payload["source_upload_root_identity_sha256"] = "B" * 64
    restore_payload["target_upload_root_identity_sha256"] = "b" * 64
    write_json(restore, restore_payload)
    with pytest.raises(EvidenceFailure, match="target upload root must differ"):
        validate_restore_drill_receipt(
            restore,
            now=now,
            max_age_days=30,
            trusted_signer_fingerprint=receipt_signer,
        )
    restore_payload["source_upload_root_identity_sha256"] = "2" * 64
    restore_payload["target_upload_root_identity_sha256"] = "4" * 64
    restore_payload["consistency_counts"]["image_hash_mismatch_count"] = 1
    write_json(restore, restore_payload)
    with pytest.raises(EvidenceFailure, match="report/upload consistency"):
        validate_restore_drill_receipt(
            restore,
            now=now,
            max_age_days=30,
            trusted_signer_fingerprint=receipt_signer,
        )
    restore_payload["consistency_counts"]["image_hash_mismatch_count"] = 0
    write_json(restore, restore_payload)

    agency = write_json(
        tmp_path / "agency.json",
        {
            "schema_version": "walksafe.agency-submission-receipt.v1",
            "submitted_at": now.isoformat(),
            "institution": "Seoul Accessibility Office",
            "channel": "portal",
            "external_receipt_id": "receipt-20260711-001",
            "submitted_by_actor_id": "admin.operator",
            "submission_status": "received",
            "access_scope": "admin_exact_location",
            "exact_location_included": True,
            "export_sha256": "a" * 64,
            "export_manifest_sha256": "b" * 64,
            "export_rows_sha256": "c" * 64,
            "export_audit_id": "33333333-3333-4333-8333-333333333333",
            "export_format": "json",
            "report_count": 3,
        },
    )
    evidence = {"details": {"external_receipt_id": "receipt-20260711-001"}}
    assert validate_agency_submission_receipt(agency, now=now, max_age_days=30, evidence_check=evidence)["report_count"] == 3

    evidence["details"]["external_receipt_id"] = "different"
    with pytest.raises(EvidenceFailure, match="does not match"):
        validate_agency_submission_receipt(agency, now=now, max_age_days=30, evidence_check=evidence)


def test_live_account_rbac_requires_distinct_role_credentials(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_TOKEN", "field-internal-token-1234567890")
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", "admin-internal-token-1234567890")
    monkeypatch.setenv("WALKSAFE_GATEWAY_SESSION_SECRET", "separate-session-secret-1234567890abcdef")
    monkeypatch.setenv(
        "WALKSAFE_FIELD_ACCOUNTS_JSON",
        json.dumps([{"actor_id": "field.one", "token": "field-login-token-123456789012"}]),
    )
    monkeypatch.setenv(
        "WALKSAFE_ADMIN_ACCOUNTS_JSON",
        json.dumps([{"actor_id": "admin.one", "token": "admin-login-token-123456789012"}]),
    )
    evidence = {
        "details": {
            "field_account_count": 1,
            "admin_account_count": 1,
            "internal_tokens_distinct": True,
            "session_secret_configured": True,
        }
    }

    assert validate_live_rbac_environment(evidence) == {"field_account_count": 1, "admin_account_count": 1}
    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", "field-internal-token-1234567890")
    with pytest.raises(EvidenceFailure, match="must be distinct"):
        validate_live_rbac_environment(evidence)

    monkeypatch.setenv("WALKSAFE_ADMIN_TOKEN", "admin-internal-token-1234567890")
    monkeypatch.setenv(
        "WALKSAFE_FIELD_ACCOUNTS_JSON",
        json.dumps([{"actor_id": "../invalid", "token": "field-login-token-123456789012"}]),
    )
    with pytest.raises(EvidenceFailure, match="actor_id is invalid"):
        validate_live_rbac_environment(evidence)

    monkeypatch.setenv(
        "WALKSAFE_FIELD_ACCOUNTS_JSON",
        json.dumps([{"actor_id": "anonymous", "token": "field-login-token-123456789012"}]),
    )
    with pytest.raises(EvidenceFailure, match="named account"):
        validate_live_rbac_environment(evidence)


def test_web_release_environment_requires_production_and_private_rate_limit_dir(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    rate_limit_dir = tmp_path / "gateway-rate-limits"
    rate_limit_dir.mkdir(mode=0o700)
    maintenance_dir = tmp_path / "maintenance"
    maintenance_dir.mkdir(mode=0o700)
    maintenance_lock = maintenance_dir / "walksafe.lock"
    maintenance_lock.touch(mode=0o600)
    for directory_name in ("uploads", "field", "test"):
        (tmp_path / directory_name).mkdir()
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "staging")
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "a" * 40)
    monkeypatch.setenv("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKING_ROUTE_PROVIDER", "tmap_pedestrian")
    monkeypatch.setenv("WALKSAFE_GATEWAY_TRUSTED_IP_HEADER", "cf-connecting-ip")
    monkeypatch.setenv("DATABASE_URL", "postgresql://walksafe@db.internal/walksafe")
    monkeypatch.setenv("UPLOAD_DIR", str((tmp_path / "uploads").resolve()))
    monkeypatch.setenv("WALKSAFE_FIELD_LOG_DIR", str((tmp_path / "field").resolve()))
    monkeypatch.setenv("WALKSAFE_TEST_LOG_DIR", str((tmp_path / "test").resolve()))
    monkeypatch.setenv("WALKSAFE_GATEWAY_RATE_LIMIT_DIR", str(rate_limit_dir.resolve()))
    monkeypatch.setenv("WALKSAFE_MAINTENANCE_LOCK_PATH", str(maintenance_lock.resolve()))

    with pytest.raises(EvidenceFailure, match="must be production"):
        validate_live_release_environment("tmap_pedestrian")

    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    with pytest.raises(EvidenceFailure, match="root-owned non-writable authority ancestry"):
        validate_live_release_environment("tmap_pedestrian")
    monkeypatch.setattr(release_gate, "_trusted_maintenance_lock_parent", lambda *_args: True)
    environment = validate_live_release_environment("tmap_pedestrian")
    assert environment["gateway_rate_limit_root"] == rate_limit_dir
    assert environment["maintenance_lock_path"] == maintenance_lock
    assert environment["maintenance_lock_device_inode"] == (
        f"{maintenance_dir.stat().st_dev}:{maintenance_dir.stat().st_ino}"
    )
    rate_limit_dir.chmod(0o755)
    with pytest.raises(EvidenceFailure, match="deny group/other"):
        validate_live_release_environment("tmap_pedestrian")


def test_release_lock_authority_rejects_user_owned_higher_ancestor(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service_directory = tmp_path / "service"
    trusted_immediate_ancestor = service_directory / "trusted"
    lock_parent = trusted_immediate_ancestor / "maintenance"
    lock_parent.mkdir(parents=True, mode=0o700)
    service_directory.chmod(0o700)
    trusted_immediate_ancestor.chmod(0o755)
    higher_ancestor_identity = (service_directory.stat().st_dev, service_directory.stat().st_ino)
    authority_paths = [Path("/")]
    for component in trusted_immediate_ancestor.relative_to("/").parts:
        authority_paths.append(authority_paths[-1] / component)
    simulated_safe_paths = set(authority_paths) - {service_directory}
    simulated_safe_identities = {
        (path.stat().st_dev, path.stat().st_ino) for path in simulated_safe_paths
    }
    visited_identities: list[tuple[int, int]] = []
    real_fstat = os.fstat
    real_access = os.access

    def only_higher_ancestor_remains_user_owned(descriptor: int) -> os.stat_result:
        metadata = real_fstat(descriptor)
        identity = (metadata.st_dev, metadata.st_ino)
        visited_identities.append(identity)
        if identity not in simulated_safe_identities:
            return metadata
        fields = list(metadata)
        fields[0] = (fields[0] & ~0o7777) | 0o755
        fields[4] = 0
        return os.stat_result(fields)

    def simulated_safe_ancestors_appear_non_writable(
        path: str,
        mode: int,
        *,
        dir_fd: int | None = None,
        effective_ids: bool = False,
        follow_symlinks: bool = True,
    ) -> bool:
        if dir_fd is not None:
            metadata = real_fstat(dir_fd)
            if (metadata.st_dev, metadata.st_ino) in simulated_safe_identities:
                return False
        if dir_fd is None and Path(path) in simulated_safe_paths:
            return False
        return real_access(
            path,
            mode,
            dir_fd=dir_fd,
            effective_ids=effective_ids,
            follow_symlinks=follow_symlinks,
        )

    descriptor = os.open(lock_parent, os.O_RDONLY | os.O_DIRECTORY)
    monkeypatch.setattr(release_gate.os, "fstat", only_higher_ancestor_remains_user_owned)
    monkeypatch.setattr(release_gate.os, "access", simulated_safe_ancestors_appear_non_writable)
    try:
        assert release_gate._trusted_maintenance_lock_parent(lock_parent, descriptor) is False
        assert higher_ancestor_identity in visited_identities
    finally:
        os.close(descriptor)


def test_live_detector_evidence_is_bound_to_real_artifact_hashes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime_config = tmp_path / "runtime.json"
    unified_model = tmp_path / "walksafe.pt"
    unified_model.write_bytes(b"model-weights")
    fixture = tmp_path / "person.jpg"
    fixture.write_bytes(b"person-fixture")
    model_digest = hashlib.sha256(unified_model.read_bytes()).hexdigest()
    runtime_payload = json.loads(json.dumps(DEFAULT_RUNTIME_CONFIG))
    runtime_payload["primary_model"] = "unified_walksafe"
    runtime_payload["fallback_model"] = None
    runtime_payload["models"]["unified_walksafe"]["artifact_sha256"] = model_digest
    runtime_config.write_text(json.dumps(runtime_payload), encoding="utf-8")
    monkeypatch.setenv("DETECT_V2_MODE", "real")
    monkeypatch.setenv("NEXT_PUBLIC_DETECTOR_MODE", "server-v2")
    monkeypatch.setenv("TMAP_POI_PROVIDER", "live")
    monkeypatch.setenv("DETECT_V2_RUNTIME_CONFIG_PATH", str(runtime_config.resolve()))
    monkeypatch.setenv("DETECT_V2_UNIFIED_MODEL_PATH", str(unified_model.resolve()))
    monkeypatch.setenv("WALKSAFE_RELEASE_DETECTOR_FIXTURE_IMAGE", str(fixture.resolve()))
    monkeypatch.setenv("DETECT_V2_IMAGE_SIZE", "768")
    monkeypatch.setenv("WALKSAFE_SOURCE_COMMIT", "a" * 40)
    monkeypatch.setattr(
        release_gate,
        "run_detector_smoke",
        lambda model_path, fixture_path, image_size: {
            "fixture_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
            "detected_names": ["person"],
        },
    )
    details = {
        "detect_v2_mode": "real",
        "frontend_detector_mode": "server-v2",
        "walking_route_provider": "tmap_pedestrian",
        "tmap_poi_provider": "live",
        "runtime_config_sha256": hashlib.sha256(runtime_config.read_bytes()).hexdigest(),
        "unified_model_sha256": hashlib.sha256(unified_model.read_bytes()).hexdigest(),
        "smoke_fixture_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
        "smoke_expected_class": "person",
        "backend_source_commit": "a" * 40,
    }

    result = validate_live_detector_environment(
        {"details": details},
        deployment_provider="tmap_pedestrian",
    )
    assert result["unified_model_sha256"] == details["unified_model_sha256"]
    details["unified_model_sha256"] = "0" * 64
    with pytest.raises(EvidenceFailure, match="unified_model_sha256"):
        validate_live_detector_environment(
            {"details": details},
            deployment_provider="tmap_pedestrian",
        )

    details["unified_model_sha256"] = model_digest
    runtime_payload["models"]["unified_walksafe"]["artifact_sha256"] = "0" * 64
    runtime_config.write_text(json.dumps(runtime_payload), encoding="utf-8")
    details["runtime_config_sha256"] = hashlib.sha256(runtime_config.read_bytes()).hexdigest()
    with pytest.raises(EvidenceFailure, match="artifact_sha256"):
        validate_live_detector_environment(
            {"details": details},
            deployment_provider="tmap_pedestrian",
        )

    runtime_payload["models"]["unified_walksafe"]["artifact_sha256"] = model_digest
    runtime_payload["fallback_model"] = "legacy_two_model"
    runtime_config.write_text(json.dumps(runtime_payload), encoding="utf-8")
    details["runtime_config_sha256"] = hashlib.sha256(runtime_config.read_bytes()).hexdigest()
    with pytest.raises(EvidenceFailure, match="fallback_model"):
        validate_live_detector_environment(
            {"details": details},
            deployment_provider="tmap_pedestrian",
        )

    runtime_payload["fallback_model"] = None
    runtime_config.write_text(json.dumps(runtime_payload), encoding="utf-8")
    details["runtime_config_sha256"] = hashlib.sha256(runtime_config.read_bytes()).hexdigest()
    monkeypatch.setenv("DETECT_V2_IMAGE_SIZE", "640")
    with pytest.raises(EvidenceFailure, match="must be 768"):
        validate_live_detector_environment(
            {"details": details},
            deployment_provider="tmap_pedestrian",
        )


def test_backup_storage_evidence_requires_all_protections() -> None:
    evidence = {
        "details": {
            "encrypted_at_rest": True,
            "restricted_access": True,
            "separate_failure_domain": True,
        }
    }
    assert validate_backup_storage_evidence(evidence)["encrypted_at_rest"] is True
    evidence["details"]["encrypted_at_rest"] = False
    with pytest.raises(EvidenceFailure, match="encrypted_at_rest=true"):
        validate_backup_storage_evidence(evidence)


def test_web_release_requires_structured_accessibility_tts_haptic_evidence() -> None:
    assert "web_accessibility_tts_haptic" in required_checks("web-release", "tmap_pedestrian", [])
    evidence = {
        "details": {
            "device": "Pixel 9 Android 16",
            "browser": "Chrome 138",
            "screen_reader": "TalkBack 15",
            "tts_verified": True,
            "haptic_verified": True,
            "risk_priority_verified": True,
            "unavailable_state_verified": True,
        },
    }

    assert validate_web_accessibility_evidence(evidence)["risk_priority_verified"] is True
    evidence["details"]["unavailable_state_verified"] = False
    with pytest.raises(EvidenceFailure, match="unavailable_state_verified=true"):
        validate_web_accessibility_evidence(evidence)


def submission_release_bundle(tmp_path: Path) -> dict[str, object]:
    commit = "a" * 40
    source_root = tmp_path / "validation-clone"
    artifact_root = tmp_path / "artifacts"
    asset_dir = artifact_root / "assets"
    document_dir = artifact_root / "design-documents"
    final_dir = artifact_root / "final"
    form_manifest_path = artifact_root / "SUBMISSION_BUILD_MANIFEST.json"
    receipt_path = artifact_root / "privacy.json"
    for directory in (source_root, asset_dir, document_dir, final_dir):
        directory.mkdir(parents=True)

    facts_relative = "docs/submission/form_materials/09_제출_사실_기준.json"
    source_paths = (
        set(release_gate.ASSET_REPOSITORY_INPUT_PATHS)
        | set(release_gate.DESIGN_BUILD_INPUT_PATHS)
        | set(release_gate.FORM_BUILD_INPUT_PATHS)
        | set(release_gate.EXPECTED_DESIGN_IMPLEMENTATION_BINDINGS)
        | set(release_gate.EXPECTED_DESIGN_DOCUMENT_SOURCES.values())
        | set(release_gate.FINAL_SECTION_PATHS["official_templates"])
        | set(release_gate.FINAL_SECTION_PATHS["critical_sources"])
        | set(release_gate.FINAL_SECTION_PATHS["build_sources"])
        | {facts_relative}
    )
    for relative in sorted(source_paths):
        path = source_root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(f"source:{relative}\n".encode())

    facts = {
        "schema_version": "walksafe.submission_facts.v2",
        "as_of_date": "2026-07-13",
        "project": {
            "overall_status": "PARTIAL",
            "primary_app": "Web/PWA",
            "android_role": "ARCore/depth/TFLite research support",
        },
        "release_gates": [{"id": "FIELD_EVIDENCE", "status": "OPEN"}],
    }
    write_json(source_root / facts_relative, facts)

    def installation_record(relative: str) -> dict[str, object]:
        path = source_root / relative
        return {
            "path": relative,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "bytes": path.stat().st_size,
        }

    lock = {
        "schema_version": "walksafe.submission-toolchain.v4",
        "library_bundle_format": "walksafe.semantic-record-bundle.v1",
        "python_installation": {
            "installer_lock": installation_record(
                release_gate.SUBMISSION_INSTALLER_REQUIREMENTS_PATH
            ),
            "exact8_lock": installation_record(
                release_gate.SUBMISSION_EXACT8_REQUIREMENTS_PATH
            ),
            "pip_version": "26.1.1",
            "install_flags": ["--require-hashes", "--no-deps", "--no-compile"],
        },
        "python": {
            "implementation": "CPython",
            "version": "3.14.4",
            "platform": {"system": "Linux", "machine": "x86_64"},
            "executable": {"resolved_path": "/usr/bin/python3", "sha256": "1" * 64, "bytes": 1},
        },
        "libraries": [
            {"distribution": name, "version": version}
            for name, version in (
                ("Pillow", "12.2.0"),
                ("python-docx", "1.2.0"),
                ("python-pptx", "1.0.2"),
            )
        ],
        "tools": [],
        "fonts": [
            {"path": path, "sha256": digest * 64, "bytes": size}
            for path, digest, size in (
                ("/usr/share/fonts/truetype/nanum/NanumSquareB.ttf", "2", 10),
                ("/usr/share/fonts/truetype/nanum/NanumSquareR.ttf", "3", 11),
            )
        ],
    }
    lock_path = write_json(source_root / release_gate.SUBMISSION_TOOLCHAIN_LOCK_PATH, lock)

    def record(path: Path, logical: str, *, path_key: str = "path") -> dict[str, object]:
        return {
            path_key: logical,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "bytes": path.stat().st_size,
        }

    def source_record(relative: str, *, path_key: str = "path") -> dict[str, object]:
        return record(source_root / relative, relative, path_key=path_key)

    def build_tools(libraries: tuple[str, ...]) -> dict[str, object]:
        versions = {item["distribution"]: item["version"] for item in lock["libraries"]}
        return {
            "python": {"implementation": "CPython", "version": "3.14.4"},
            "platform": {"system": "Linux", "machine": "x86_64"},
            "libraries": [
                {"distribution": name, "version": versions[name]}
                for name in sorted(libraries, key=str.casefold)
            ],
        }

    attestation = {
        "lock": {
            "path": release_gate.SUBMISSION_TOOLCHAIN_LOCK_PATH,
            "sha256": hashlib.sha256(lock_path.read_bytes()).hexdigest(),
            "bytes": lock_path.stat().st_size,
            "schema_version": lock["schema_version"],
        },
        "verified": lock,
    }
    revision = {
        "source_commit": commit,
        "source_dirty": False,
        "source_dirty_excluded_generated_paths": sorted(release_gate.ALL_SUBMISSION_GENERATED_PATHS),
    }

    for name in sorted(release_gate.ASSET_DIRECTORY_FILES - {"BUILD_MANIFEST.json"}):
        source_asset = source_root / "docs/submission/form_materials/assets" / name
        data = source_asset.read_bytes() if source_asset.is_file() else f"asset:{name}".encode()
        (asset_dir / name).write_bytes(data)
    asset_repository_inputs = [
        source_record(relative) for relative in sorted(release_gate.ASSET_REPOSITORY_INPUT_PATHS)
    ]
    asset_system_inputs = [dict(item) for item in lock["fonts"]]
    asset_artifacts = [
        record(asset_dir / Path(relative).name, relative)
        for relative in sorted(release_gate.ASSET_ARTIFACT_PATHS)
    ]
    asset_manifest = {
        "schema_version": "walksafe.submission-assets-build.v1",
        **revision,
        "build_tools": build_tools(("Pillow",)),
        "submission_toolchain": attestation,
        "allowed_asset_files": sorted(release_gate.ASSET_DIRECTORY_FILES),
        "input_bundle_sha256": release_gate.record_bundle_sha256(
            [*asset_repository_inputs, *asset_system_inputs]
        ),
        "repository_inputs": asset_repository_inputs,
        "system_inputs": asset_system_inputs,
        "artifacts": asset_artifacts,
    }
    asset_manifest_path = write_json(asset_dir / "BUILD_MANIFEST.json", asset_manifest)

    for name in sorted(release_gate.DESIGN_DOCUMENT_NAMES):
        (document_dir / name).write_bytes(f"document:{name}".encode())
    (document_dir / "README.md").write_text("design README\n", encoding="utf-8")
    design_assets = [
        record(asset_dir / Path(relative).name, relative, path_key="file")
        for relative in sorted(release_gate.DESIGN_ASSET_PATHS)
    ]
    design_build_inputs = []
    for relative in sorted(release_gate.DESIGN_BUILD_INPUT_PATHS):
        target = asset_manifest_path if relative.endswith("assets/BUILD_MANIFEST.json") else source_root / relative
        design_build_inputs.append(record(target, relative, path_key="file"))
    design_documents = []
    for name in sorted(release_gate.DESIGN_DOCUMENT_NAMES):
        source_relative = release_gate.EXPECTED_DESIGN_DOCUMENT_SOURCES[name]
        item = record(document_dir / name, name, path_key="file")
        item.update(
            {
                "paragraphs": 1,
                "tables": 0,
                "inline_shapes": 0,
                "source": source_relative,
                "source_sha256": hashlib.sha256((source_root / source_relative).read_bytes()).hexdigest(),
                "orientation": "portrait",
            }
        )
        design_documents.append(item)
    design_manifest = {
        "schema_version": "walksafe.design-documents.v2",
        "build_date": facts["as_of_date"],
        "facts": {
            "file": facts_relative,
            "sha256": hashlib.sha256((source_root / facts_relative).read_bytes()).hexdigest(),
            "schema_version": facts["schema_version"],
        },
        "product_status": "PARTIAL",
        "primary_app": "Web/PWA",
        "android_role": "ARCore/depth/TFLite research support",
        "agency_submission": "admin-reviewed export with allow-field check followed by manual external submission",
        "assets": design_assets,
        **revision,
        "build_tools": build_tools(("Pillow", "python-docx")),
        "submission_toolchain": attestation,
        "build_inputs": design_build_inputs,
        "implementation_bindings": [
            source_record(relative, path_key="file")
            for relative in sorted(release_gate.EXPECTED_DESIGN_IMPLEMENTATION_BINDINGS)
        ],
        "supporting_files": [
            record(
                document_dir / "README.md",
                "docs/submission/design_documents/README.md",
                path_key="file",
            )
        ],
        "documents": design_documents,
    }
    design_manifest_path = write_json(document_dir / "BUILD_MANIFEST.json", design_manifest)

    report = final_dir / "개발보고서 양식.docx"
    slides = final_dir / "제작설계서_일반.pptx"
    report.write_bytes(b"final report")
    slides.write_bytes(b"final slides")
    (final_dir / "README.md").write_text("final README\n", encoding="utf-8")
    form_inputs = []
    for relative in sorted(release_gate.FORM_BUILD_INPUT_PATHS):
        target = (
            asset_dir / Path(relative).name
            if relative.startswith("docs/submission/form_materials/assets/")
            else source_root / relative
        )
        form_inputs.append(record(target, relative))
    form_artifacts = [
        record(final_dir / Path(relative).name, relative)
        for relative in sorted(release_gate.FORM_ARTIFACT_PATHS)
    ]
    form_manifest = {
        "schema_version": "walksafe.submission-forms-build.v1",
        **revision,
        "build_tools": build_tools(("Pillow", "python-docx", "python-pptx")),
        "submission_toolchain": attestation,
        "allowed_template_files": sorted(release_gate.FORM_TEMPLATE_FILES),
        "input_bundle_sha256": release_gate.record_bundle_sha256(form_inputs),
        "inputs": form_inputs,
        "artifacts": form_artifacts,
    }
    write_json(form_manifest_path, form_manifest)

    automatic_assets = [
        {
            "name": name,
            "sha256": hashlib.sha256((asset_dir / name).read_bytes()).hexdigest(),
            "width": 1200,
            "height": 800,
        }
        for name in sorted(release_gate.EXPECTED_SUBMISSION_ASSETS)
    ]
    automatic_documents = [
        {"name": name, "sha256": hashlib.sha256((document_dir / name).read_bytes()).hexdigest()}
        for name in sorted(release_gate.EXPECTED_DESIGN_DOCUMENTS)
    ]
    final_documents = [
        {"name": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        for path in (report, slides)
    ]
    receipt = {
        "schema_version": "walksafe.submission-visual-privacy.v2",
        "source_commit": commit,
        "reviewed_at": datetime.now(UTC).isoformat(),
        "reviewer": "privacy.reviewer",
        "reviewer_kind": "human",
        "automatic": {
            "assets": automatic_assets,
            "metadata_violations": [],
            "face_candidates": [],
            "automatic_face_detection_limit": (
                "Haar 후보 검사이며 얼굴·차량번호·OCR 부재를 단독 보장하지 않음"
            ),
            "documents": automatic_documents,
            "final_documents": final_documents,
            "office_render": {
                "report": {"name": report.name, "sha256": final_documents[0]["sha256"], "pdf_pages": 7},
                "slides": {
                    "name": slides.name,
                    "sha256": final_documents[1]["sha256"],
                    "pdf_pages": 34,
                    "slides": 34,
                    "aspect_ratio": "4:3",
                },
            },
        },
        "manual_assertions": {
            "no_visible_faces": True,
            "no_visible_license_plates": True,
            "no_personal_addresses_or_accounts": True,
            "office_pdf_render_reviewed": True,
        },
        "release_ready": True,
    }
    write_json(receipt_path, receipt)
    (final_dir / "ASSISTANT_VISUAL_PRIVACY_REVIEW.json").write_bytes(receipt_path.read_bytes())

    canonical = source_record(facts_relative)
    canonical["schema_version"] = facts["schema_version"]
    final_sections = {
        section: [source_record(relative) for relative in sorted(release_gate.FINAL_SECTION_PATHS[section])]
        for section in ("official_templates", "critical_sources", "build_sources")
    }
    report_record = record(report, "docs/submission/final/개발보고서 양식.docx")
    report_record["pages"] = 7
    slides_record = record(slides, "docs/submission/final/제작설계서_일반.pptx")
    slides_record.update({"slides": 34, "aspect_ratio": "4:3"})
    source_bundle_records = [canonical, *final_sections["critical_sources"], *final_sections["build_sources"]]
    final_manifest = {
        "schema_version": "walksafe.submission-final.v1",
        "as_of_date": facts["as_of_date"],
        "product_status": facts["project"]["overall_status"],
        "candidate_status": "AUTOMATED_AND_HUMAN_VISUAL_QA_PASS",
        "product_scope": {
            "primary_app": facts["project"]["primary_app"],
            "android_role": facts["project"]["android_role"],
            "institution_submission": (
                "named admin review, agency file preparation, manual external-channel submission"
            ),
        },
        "repository": {
            "commit": commit,
            "dirty": False,
            "source_bundle_sha256": release_gate.record_bundle_sha256(source_bundle_records),
            "source_bundle_algorithm": (
                "SHA-256 of sorted sha256sum lines for canonical facts, "
                f"{len(release_gate.FINAL_SECTION_PATHS['critical_sources'])} critical sources, and "
                f"{len(release_gate.FINAL_SECTION_PATHS['build_sources'])} build/validation sources"
            ),
            "source_bundle_entry_count": len(source_bundle_records),
        },
        "canonical_facts": canonical,
        **final_sections,
        "design_pack": {
            "document_count": 8,
            "manifest_path": "docs/submission/design_documents/BUILD_MANIFEST.json",
            "manifest_sha256": hashlib.sha256(design_manifest_path.read_bytes()).hexdigest(),
        },
        "form_build": {
            "schema_version": "walksafe.submission-forms-build.v1",
            "manifest_path": "templates/SUBMISSION_BUILD_MANIFEST.json",
            "manifest_sha256": hashlib.sha256(form_manifest_path.read_bytes()).hexdigest(),
        },
        "artifacts": [report_record, slides_record],
        "supporting_files": [
            record(final_dir / Path(relative).name, relative)
            for relative in sorted(release_gate.FINAL_SECTION_PATHS["supporting_files"])
        ],
        "validation": {
            "test_layers": "PASS",
            "backend_voice_android": "PASS",
            "android_device": "PASS",
            "web": "PASS",
            "submission_materials": "PASS",
            "office_render": "PASS_HUMAN",
        },
        "known_release_blockers": facts["release_gates"],
    }
    write_json(final_dir / "BUILD_MANIFEST.json", final_manifest)
    return {
        "source_root": source_root,
        "asset_dir": asset_dir,
        "document_dir": document_dir,
        "final_dir": final_dir,
        "form_manifest_path": form_manifest_path,
        "receipt_path": receipt_path,
        "source_commit": commit,
    }


def test_submission_privacy_requires_human_manual_and_metadata_clean_receipt(tmp_path: Path) -> None:
    now = datetime.now(UTC)
    bundle = submission_release_bundle(tmp_path)
    kwargs = {
        "now": now,
        "max_age_days": 30,
        "asset_dir": bundle["asset_dir"],
        "document_dir": bundle["document_dir"],
        "final_document_dir": bundle["final_dir"],
        "form_manifest_path": bundle["form_manifest_path"],
        "source_commit": bundle["source_commit"],
        "repository_root": bundle["source_root"],
    }
    receipt = bundle["receipt_path"]
    assert validate_submission_privacy_receipt(receipt, **kwargs)["asset_count"] == 18
    payload = json.loads(receipt.read_text(encoding="utf-8"))
    payload["reviewer_kind"] = "assistant"
    write_json(receipt, payload)
    with pytest.raises(EvidenceFailure, match="human reviewer"):
        validate_submission_privacy_receipt(receipt, **kwargs)


def test_submission_privacy_rejects_self_asserted_hash_after_external_asset_drift(
    tmp_path: Path,
) -> None:
    bundle = submission_release_bundle(tmp_path)
    asset = bundle["asset_dir"] / "adoption_roadmap.png"
    asset.write_bytes(b"arbitrary replacement")
    receipt = json.loads(bundle["receipt_path"].read_text(encoding="utf-8"))
    next(
        item for item in receipt["automatic"]["assets"] if item["name"] == asset.name
    )["sha256"] = hashlib.sha256(asset.read_bytes()).hexdigest()
    write_json(bundle["receipt_path"], receipt)
    (bundle["final_dir"] / "ASSISTANT_VISUAL_PRIVACY_REVIEW.json").write_bytes(
        bundle["receipt_path"].read_bytes()
    )
    with pytest.raises(EvidenceFailure, match="asset artifact"):
        validate_submission_privacy_receipt(
            bundle["receipt_path"],
            now=datetime.now(UTC),
            max_age_days=30,
            asset_dir=bundle["asset_dir"],
            document_dir=bundle["document_dir"],
            final_document_dir=bundle["final_dir"],
            form_manifest_path=bundle["form_manifest_path"],
            source_commit=bundle["source_commit"],
            repository_root=bundle["source_root"],
        )


def test_submission_clean_room_reproduction_runs_exact_pipeline_and_compares_all_files(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_root = tmp_path / "validation-clone"
    artifact_root = tmp_path / "artifacts"
    asset_dir = artifact_root / "assets"
    document_dir = artifact_root / "design-documents"
    final_dir = artifact_root / "final"
    for directory in (source_root, asset_dir, document_dir, final_dir):
        directory.mkdir(parents=True)
    for name in release_gate.ASSET_DIRECTORY_FILES:
        (asset_dir / name).write_bytes(f"asset:{name}".encode())
    for name in release_gate.DESIGN_OUTPUT_NAMES:
        (document_dir / name).write_bytes(f"design:{name}".encode())
    for name in release_gate.FINAL_OUTPUT_NAMES:
        (final_dir / name).write_bytes(f"final:{name}".encode())
    form_manifest = artifact_root / "SUBMISSION_BUILD_MANIFEST.json"
    form_manifest.write_bytes(b"form manifest")
    receipt = artifact_root / "privacy.json"
    receipt.write_bytes(b"human receipt")

    commands: list[list[str]] = []

    def fake_run(
        command: list[str],
        *,
        environment: dict[str, str],
        cwd: Path,
        context: str,
    ) -> None:
        del environment, cwd, context
        commands.append(command)
        if "clone" not in command:
            return
        clone = Path(command[-1])
        (clone / "scripts").mkdir(parents=True)
        (clone / "scripts/run_walksafe_submission_python_20260714.py").write_text(
            "# fixture\n", encoding="utf-8"
        )
        targets = (
            (asset_dir, clone / "docs/submission/form_materials/assets"),
            (document_dir, clone / "docs/submission/design_documents"),
            (final_dir, clone / "docs/submission/final"),
        )
        for external, reproduced in targets:
            reproduced.mkdir(parents=True)
            for path in external.iterdir():
                (reproduced / path.name).write_bytes(path.read_bytes())
        (clone / "templates").mkdir()
        (clone / "templates/SUBMISSION_BUILD_MANIFEST.json").write_bytes(
            form_manifest.read_bytes()
        )

    monkeypatch.setattr(release_gate, "_run_submission_reproduction_command", fake_run)
    result = release_gate.validate_submission_clean_room_reproduction(
        submission_python=Path(sys.executable),
        asset_dir=asset_dir,
        document_dir=document_dir,
        final_document_dir=final_dir,
        form_manifest_path=form_manifest,
        privacy_receipt_path=receipt,
        source_commit="a" * 40,
        repository_root=source_root,
    )

    assert result["file_count"] == 37
    assert len(commands) == 11
    runner_commands = commands[2:]
    assert all(command[1:4] == ["-I", "-S", "-B"] for command in runner_commands)
    assert runner_commands[0][-1] == "--verify-only"
    assert [command[-1] for command in runner_commands[1:7]] == [
        "scripts/build_submission_assets_20260710.py",
        "scripts/validate_submission_materials_20260710.py",
        "scripts/build_design_documents_20260710.py",
        "--validate-only",
        "scripts/build_submission_forms_20260710.py",
        "--working-only",
    ]
    assert "scripts/promote_submission_final_20260713.py" in runner_commands[7]
    assert runner_commands[8][-3:] == [
        "scripts/validate_submission_forms_20260710.py",
        "--final-dir",
        "docs/submission/final",
    ]


def test_submission_clean_room_reproduction_rejects_resigned_arbitrary_bytes(
    tmp_path: Path,
) -> None:
    external = tmp_path / "external.docx"
    reproduced = tmp_path / "reproduced.docx"
    external.write_bytes(b"valid-looking but independently replaced Office bytes")
    reproduced.write_bytes(b"source-rebuilt Office bytes")

    with pytest.raises(EvidenceFailure, match="differs from the clean-room reproduction"):
        release_gate._compare_reproduced_file(
            external,
            reproduced,
            context="final submission/development report",
        )


@pytest.mark.parametrize(
    "target",
    ["asset_manifest", "design_manifest", "form_manifest", "final_manifest", "privacy_receipt"],
)
def test_submission_privacy_rejects_manifest_and_receipt_source_commit_drift(
    target: str,
    tmp_path: Path,
) -> None:
    bundle = submission_release_bundle(tmp_path)
    path = {
        "asset_manifest": bundle["asset_dir"] / "BUILD_MANIFEST.json",
        "design_manifest": bundle["document_dir"] / "BUILD_MANIFEST.json",
        "form_manifest": bundle["form_manifest_path"],
        "final_manifest": bundle["final_dir"] / "BUILD_MANIFEST.json",
        "privacy_receipt": bundle["receipt_path"],
    }[target]
    payload = json.loads(path.read_text(encoding="utf-8"))
    if target == "final_manifest":
        payload["repository"]["commit"] = "b" * 40
    else:
        payload["source_commit"] = "b" * 40
    write_json(path, payload)
    with pytest.raises(EvidenceFailure, match=r"clean validation source(?: commit)?"):
        validate_submission_privacy_receipt(
            bundle["receipt_path"],
            now=datetime.now(UTC),
            max_age_days=30,
            asset_dir=bundle["asset_dir"],
            document_dir=bundle["document_dir"],
            final_document_dir=bundle["final_dir"],
            form_manifest_path=bundle["form_manifest_path"],
            source_commit=bundle["source_commit"],
            repository_root=bundle["source_root"],
        )


@pytest.mark.parametrize("mutation", ["source_input", "toolchain"])
def test_submission_privacy_rejects_source_input_and_toolchain_drift(
    mutation: str,
    tmp_path: Path,
) -> None:
    bundle = submission_release_bundle(tmp_path)
    if mutation == "source_input":
        (bundle["source_root"] / "scripts/build_submission_assets_20260710.py").write_bytes(
            b"changed after build"
        )
        expected = "asset input"
    else:
        asset_manifest_path = bundle["asset_dir"] / "BUILD_MANIFEST.json"
        manifest = json.loads(asset_manifest_path.read_text(encoding="utf-8"))
        manifest["build_tools"]["python"]["version"] = "0.0.0"
        write_json(asset_manifest_path, manifest)
        expected = "build tool provenance"
    with pytest.raises(EvidenceFailure, match=expected):
        validate_submission_privacy_receipt(
            bundle["receipt_path"],
            now=datetime.now(UTC),
            max_age_days=30,
            asset_dir=bundle["asset_dir"],
            document_dir=bundle["document_dir"],
            final_document_dir=bundle["final_dir"],
            form_manifest_path=bundle["form_manifest_path"],
            source_commit=bundle["source_commit"],
            repository_root=bundle["source_root"],
        )


def test_design_documents_bind_clean_release_source_and_implementation(tmp_path: Path) -> None:
    commit = "a" * 40
    document_dir = tmp_path / "docs" / "submission" / "design_documents"
    source_dir = tmp_path / "docs" / "submission" / "deliverables"
    document_dir.mkdir(parents=True)
    source_dir.mkdir(parents=True)

    bindings = []
    for relative in sorted(release_gate.EXPECTED_DESIGN_IMPLEMENTATION_BINDINGS):
        path = tmp_path / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"implementation:{relative}\n", encoding="utf-8")
        bindings.append(
            {
                "file": relative,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "bytes": path.stat().st_size,
            }
        )

    documents = []
    for index, name in enumerate(sorted(release_gate.EXPECTED_DESIGN_DOCUMENTS), start=1):
        document = document_dir / name
        document.write_bytes(f"document:{name}".encode())
        source = source_dir / f"source-{index}.md"
        source.write_text(f"source:{name}\n", encoding="utf-8")
        documents.append(
            {
                "file": name,
                "sha256": hashlib.sha256(document.read_bytes()).hexdigest(),
                "source": source.relative_to(tmp_path).as_posix(),
                "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            }
        )

    manifest = {
        "schema_version": "walksafe.design-documents.v2",
        "source_commit": commit,
        "source_dirty": False,
        "implementation_bindings": bindings,
        "documents": documents,
    }
    manifest_path = write_json(document_dir / "BUILD_MANIFEST.json", manifest)
    result = validate_design_document_manifest(document_dir, source_commit=commit, repository_root=tmp_path)
    assert result == {
        "source_commit": commit,
        "document_count": 8,
        "implementation_binding_count": len(release_gate.EXPECTED_DESIGN_IMPLEMENTATION_BINDINGS),
    }

    extra = tmp_path / "future" / "additional-binding.py"
    extra.parent.mkdir(parents=True)
    extra.write_text("future binding\n", encoding="utf-8")
    bindings.append(
        {
            "file": extra.relative_to(tmp_path).as_posix(),
            "sha256": hashlib.sha256(extra.read_bytes()).hexdigest(),
            "bytes": extra.stat().st_size,
        }
    )
    write_json(manifest_path, manifest)
    assert validate_design_document_manifest(
        document_dir,
        source_commit=commit,
        repository_root=tmp_path,
    )["implementation_binding_count"] == len(bindings)
    bindings.pop()

    missing = bindings.pop()
    write_json(manifest_path, manifest)
    with pytest.raises(EvidenceFailure, match="incomplete"):
        validate_design_document_manifest(document_dir, source_commit=commit, repository_root=tmp_path)
    bindings.append(missing)

    manifest["source_dirty"] = True
    write_json(manifest_path, manifest)
    with pytest.raises(EvidenceFailure, match="clean release source"):
        validate_design_document_manifest(document_dir, source_commit=commit, repository_root=tmp_path)
