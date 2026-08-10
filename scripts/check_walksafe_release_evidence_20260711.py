#!/usr/bin/env python3
"""Fail closed when external WalkSafe release evidence is missing or stale.

Code/unit/headless checks cannot prove mobile install/update UX, a real microphone
report, outdoor navigation, institutional acceptance, or Android device behavior.
This gate keeps those claims blocked until an operator records bounded evidence.
"""
from __future__ import annotations

import sys

if __name__ == "__main__":
    _startup_flags = (
        sys.flags.isolated,
        sys.flags.no_site,
        sys.flags.ignore_environment,
        int(getattr(sys.flags, "safe_path", False)),
        sys.flags.dont_write_bytecode,
    )
    if any(value != 1 for value in _startup_flags) or "site" in sys.modules:
        raise SystemExit(
            "WalkSafe release evidence CLI requires Python -I -S -B before any release code runs"
        )
    _arguments = sys.argv[1:]
    _requested_profiles = []
    for _index, _argument in enumerate(_arguments):
        if _argument == "--profile" and _index + 1 < len(_arguments):
            _requested_profiles.append(_arguments[_index + 1])
        if _argument.startswith("--profile="):
            _requested_profiles.append(_argument.partition("=")[2])
    if not _requested_profiles:
        _requested_profiles.append("web-release")
    if (
        "--help" not in _arguments
        and "-h" not in _arguments
        and any(profile in {"web-release", "full"} for profile in _requested_profiles)
    ):
        print(
            "BLOCKED: Web/full release evidence profiles are LEGACY_REFERENCE_ONLY under FP-009.",
            file=sys.stderr,
        )
        raise SystemExit(78)

import argparse
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import importlib
import json
import os
import pwd
import re
import stat
import subprocess
import tempfile
import types
import zipfile
from urllib.parse import urlparse
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, Iterator

_SCRIPT_DIRECTORY = Path(__file__).resolve().parent
ROOT = _SCRIPT_DIRECTORY.parent
_INTEGRITY_SHA256 = "b084292b5a6e4befd82aea22ceac899527ab1ba1ffe51b64fa022c831b72a18e"
_SIGNED_ANDROID_GATE_SHA256 = "e2be9e7a8be0cf96f7339606d696fd67788d1fe9655d3c17972d626b5e2a3893"
_DEX_BINDING_SHA256 = "cfcc330fcf8a5faf4ee8a11adc6168bd2df98d40b09e4a5471586013a7fcab7e"
RELEASE_IGNORED_OUTPUT_ROOTS = (
    "apps/android/.gradle",
    "apps/android/app/build",
    "apps/android/build",
    "apps/web/.next",
    "apps/web/node_modules",
    "apps/web/tsconfig.tsbuildinfo",
    "apps/web/walksafe-test-logs",
    "artifacts",
)


def _load_pinned_source(
    module_name: str,
    path: Path,
    expected_sha256: str,
) -> types.ModuleType:
    candidate = path.absolute()
    if candidate.resolve() != candidate or candidate.is_symlink():
        raise RuntimeError(f"local source module is not a real file: {candidate.name}")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(candidate, flags)
        with os.fdopen(descriptor, "rb") as source:
            before = os.fstat(source.fileno())
            payload = source.read()
            after = os.fstat(source.fileno())
        current = os.stat(candidate, follow_symlinks=False)
    except OSError as exc:
        raise RuntimeError(f"local source module cannot be read: {candidate.name}") from exc
    identities = {
        (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
        for item in (before, after, current)
    }
    if not stat.S_ISREG(current.st_mode) or len(identities) != 1 or not payload:
        raise RuntimeError(f"local source module changed while reading: {candidate.name}")
    if hashlib.sha256(payload).hexdigest() != expected_sha256:
        raise RuntimeError(f"local source module hash differs from its pin: {candidate.name}")
    module = types.ModuleType(module_name)
    module.__file__ = str(candidate)
    module.__loader__ = None
    module.__package__ = ""
    module.__spec__ = None
    sys.modules[module_name] = module
    try:
        exec(compile(payload, str(candidate), "exec", dont_inherit=True), module.__dict__)
    except BaseException:
        sys.modules.pop(module_name, None)
        raise
    return module


_integrity = _load_pinned_source(
    "_walksafe_release_integrity_for_release_evidence",
    _SCRIPT_DIRECTORY / "walksafe_release_integrity.py",
    _INTEGRITY_SHA256,
)
FileSnapshot = _integrity.FileSnapshot
ReleaseIntegrityError = _integrity.ReleaseIntegrityError
exclusive_atomic_publish = _integrity.exclusive_atomic_publish
strict_json_snapshot = _integrity.strict_json_snapshot
verify_exact_git_source = _integrity.verify_exact_git_source

_signed_android_gate = _load_pinned_source(
    "_walksafe_signed_android_gate_for_release_evidence",
    _SCRIPT_DIRECTORY / "verify_walksafe_signed_android_release_20260713.py",
    _SIGNED_ANDROID_GATE_SHA256,
)
SigningGateError = _signed_android_gate.SigningGateError
verify_signed_release = _signed_android_gate.verify_signed_release
AttestationVerificationError = _signed_android_gate._operator.AttestationVerificationError
verify_approved_detached_signature = _signed_android_gate._operator._verify_detached_signature

_dex_binding = _load_pinned_source(
    "_walksafe_android_dex_binding_for_release_evidence",
    _SCRIPT_DIRECTORY / "walksafe_android_dex_binding.py",
    _DEX_BINDING_SHA256,
)
DexBindingError = _dex_binding.DexBindingError
validate_walksafe_debug_binding = _dex_binding.validate_walksafe_debug_binding

_RELEASE_DEPENDENCIES_LOADED = False


def _load_release_dependencies() -> None:
    global _RELEASE_DEPENDENCIES_LOADED
    if _RELEASE_DEPENDENCIES_LOADED:
        return
    root_text = str(ROOT)
    inserted_root = root_text not in sys.path
    if inserted_root:
        sys.path.insert(0, root_text)
    try:
        environment_identity = importlib.import_module("scripts.walksafe_environment_identity")
        backup_integrity = importlib.import_module("scripts.walksafe_backup_integrity")
        dataset_integrity = importlib.import_module("scripts.walksafe_dataset_integrity")
        agency_submission = importlib.import_module(
            "scripts.record_walksafe_agency_submission_20260711"
        )
        web_manifest = importlib.import_module(
            "scripts.create_walksafe_web_build_manifest_20260711"
        )
        external_receipt = importlib.import_module("scripts.walksafe_external_check_receipt")
        submission_policy = importlib.import_module("scripts.submission_manifest_policy")
        model_runtime = importlib.import_module("model.two_model_runtime")
    finally:
        if inserted_root:
            sys.path.remove(root_text)

    exports = {
        "database_identity_sha256": environment_identity.database_identity_sha256,
        "path_identity_sha256": environment_identity.path_identity_sha256,
        "verify_signed_json_document_with_digest": (
            backup_integrity.verify_signed_json_document_with_digest
        ),
        "verify_signed_backup_manifest": backup_integrity.verify_signed_backup_manifest,
        "DatasetIntegrityError": dataset_integrity.DatasetIntegrityError,
        "verify_content_hashed_manifest": dataset_integrity.verify_content_hashed_manifest,
        "validate_agency_manifest_contract": agency_submission.validate_agency_manifest_contract,
        "validate_export_matches_manifest": agency_submission.validate_export_matches_manifest,
        "verify_deployment_archive": web_manifest.verify_deployment_archive,
        "STRUCTURED_EXTERNAL_CHECKS": external_receipt.CHECK_CONTRACTS,
        "ExternalCheckReceiptFailure": external_receipt.ExternalCheckReceiptFailure,
        "validate_external_check_receipt": external_receipt.validate_external_check_receipt,
        "UNIFIED_WALKSAFE_CLASSES": model_runtime.UNIFIED_WALKSAFE_CLASSES,
        "load_threshold_config": model_runtime.load_threshold_config,
    }
    for name in (
        "ALL_SUBMISSION_GENERATED_PATHS",
        "ASSET_ARTIFACT_PATHS",
        "ASSET_DIRECTORY_FILES",
        "ASSET_REPOSITORY_INPUT_PATHS",
        "ASSET_SYSTEM_INPUT_PATHS",
        "DESIGN_ASSET_PATHS",
        "DESIGN_BUILD_INPUT_PATHS",
        "DESIGN_DOCUMENT_NAMES",
        "DESIGN_OUTPUT_NAMES",
        "FINAL_OUTPUT_NAMES",
        "FINAL_SECTION_PATHS",
        "FORM_ARTIFACT_PATHS",
        "FORM_BUILD_INPUT_PATHS",
        "FORM_TEMPLATE_FILES",
        "SUBMISSION_EXACT8_REQUIREMENTS_PATH",
        "SUBMISSION_INSTALLER_REQUIREMENTS_PATH",
        "SUBMISSION_TOOLCHAIN_LOCK_PATH",
        "record_bundle_sha256",
    ):
        exports[name] = getattr(submission_policy, name)
    globals().update(exports)
    _RELEASE_DEPENDENCIES_LOADED = True


SCHEMA_VERSION = "walksafe.release_evidence.v1"
WEB_EXTERNAL_CHECKS = (
    "pwa_mobile_install_update",
    "voice_report_microphone_e2e",
    "web_phone_outdoor_navigation",
    "web_non_metric_advisory_field",
    "web_accessibility_tts_haptic",
    "institution_submission_acceptance",
    "account_rbac_configured",
    "backup_storage_protection",
    "detector_runtime_live",
)
ANDROID_RESEARCH_CHECKS = (
    "android_device_bbox_depth_alignment",
    "android_device_tts_haptic",
    "android_arcore_unsupported_camera_field",
)
FULL_PHYSICAL_ACCEPTANCE_CHECKS = (
    "android_signed_release_physical_smoke",
)
PLACEHOLDER_TOKENS = ("todo", "tbd", "placeholder", "example", "replace", "미정", "예시")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
ANDROID_RELEASE_BUILD_MARKER = "walksafe-release-v1"
ANDROID_DEBUG_BUILD_MARKER = "walksafe-debug-v1"
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
OPENPGP_FINGERPRINT = re.compile(r"^[0-9a-f]{40}$")
ACTOR_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")
EXPECTED_SUBMISSION_ASSETS = {
    "admin_desktop.png",
    "report_csv_flow.png",
    "reports_erd.png",
    "risk_processing_flow.png",
    "system_architecture.png",
    "model_quality_gate.png",
    "value_flow.png",
    "web_main_desktop.png",
    "web_main_mobile.png",
    "problem_solution_map.png",
    "use_case_swimlane.png",
    "ui_state_map.png",
    "ui_screen_storyboard.png",
    "navigation_state_flow.png",
    "risk_timeline.png",
    "evidence_results.png",
    "scope_change_map.png",
    "adoption_roadmap.png",
}
EXPECTED_DESIGN_DOCUMENTS = {
    "01_요구사항_정의서.docx",
    "02_유스케이스_정의서.docx",
    "03_요구사항_기능_추적표.docx",
    "04_서비스_구성도_및_흐름도.docx",
    "05_화면설계서_UIUX_정의서.docx",
    "06_엔티티관계도_테이블정의서.docx",
    "07_기능처리도_알고리즘명세서.docx",
    "08_프로그램목록_핵심소스코드_개발환경.docx",
}
EXPECTED_FINAL_SUBMISSION_DOCUMENTS = {"개발보고서 양식.docx", "제작설계서_일반.pptx"}
EXPECTED_DESIGN_IMPLEMENTATION_BINDINGS = {
    "apps/web/app/_walksafe/hooks/useDetectionV2.ts",
    "apps/web/app/_walksafe/hooks/useNavigationGuidance.ts",
    "apps/web/app/_walksafe/hooks/useVoiceCommands.ts",
    "apps/web/app/_walksafe/voice-intent-executor.ts",
    "apps/web/app/_walksafe/tactile-route-policy.ts",
    "apps/web/app/_walksafe/absolute-heading.ts",
    "apps/web/app/_walksafe/feedback.ts",
    "apps/web/app/_walksafe/camera-policy.ts",
    "apps/web/app/_walksafe/detection-availability.ts",
    "apps/web/app/_walksafe/motion-projection.ts",
    "apps/web/app/_walksafe/navigation-destination.ts",
    "apps/web/app/api/_gateway-auth.ts",
    "apps/web/app/api/_backend.ts",
    "apps/web/lib/auto-report-v2.ts",
    "apps/web/lib/detect-api-v2.ts",
    "apps/web/lib/navigation-api.ts",
    "backend/app/api/detect.py",
    "backend/app/api/health.py",
    "backend/app/api/navigation.py",
    "backend/app/api/reports.py",
    "backend/app/config.py",
    "backend/app/field_test_security.py",
    "backend/app/main.py",
    "backend/app/openapi_contract.py",
    "backend/app/uploads.py",
    "backend/app/services/actor_rate_limit.py",
    "backend/app/services/inference_process.py",
    "backend/app/services/report_policy.py",
    "backend/app/services/report_read_audit.py",
    "backend/app/services/report_storage.py",
    "backend/app/services/tmap_pedestrian.py",
    "backend/app/services/yolo_inference_adapter.py",
    "backend/alembic/versions/202607130003_report_read_audits.py",
    "backend/alembic/versions/202607130004_shared_actor_rate_limits.py",
    "backend/alembic/versions/202607160001_report_duplicate_check_audit.py",
    "contracts/walksafe.openapi.json",
    "backend/tests/conftest.py",
    "apps/android/app/build.gradle.kts",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidVoiceCommand.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClient.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/TactileRoutePolicy.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSession.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/TfliteAndroidFrameDetector.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/NavigationSpeechDelivery.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/UtteranceCallbackRegistry.kt",
    "apps/android/app/src/main/assets/model-config/two_model_runtime.json",
    "voice/intents.py",
    "voice/server.py",
    "voice/tts.py",
    "apps/web/app/_walksafe/server-v2-privacy.ts",
    "scripts/run_walksafe_web_single_instance_20260713.py",
    "scripts/run_walksafe_test_layers_20260711.sh",
    "scripts/check_walksafe_test_database_20260713.py",
    "scripts/check_android_apk_model_asset_20260713.py",
    "scripts/check_web_runtime_trace_scope_20260713.py",
    "scripts/check_walksafe_backup_source_20260713.py",
    "scripts/walksafe_environment_identity.py",
    "scripts/backup_walksafe_data_20260711.sh",
    "scripts/restore_walksafe_backup_drill_20260711.sh",
    "scripts/check_walksafe_release_evidence_20260711.py",
    "scripts/walksafe_external_check_receipt.py",
    ".github/workflows/quality.yml",
}
EXPECTED_DESIGN_DOCUMENT_SOURCES = {
    "01_요구사항_정의서.docx": "docs/submission/deliverables/요구사항_정의서.md",
    "02_유스케이스_정의서.docx": "docs/submission/deliverables/유스케이스_정의서.md",
    "03_요구사항_기능_추적표.docx": "docs/submission/drafts/요구사항_추적표.md",
    "04_서비스_구성도_및_흐름도.docx": "docs/submission/deliverables/서비스_구성도_및_흐름도.md",
    "05_화면설계서_UIUX_정의서.docx": "docs/submission/deliverables/화면설계서_UIUX_정의서.md",
    "06_엔티티관계도_테이블정의서.docx": "docs/submission/deliverables/엔티티관계도_테이블정의서.md",
    "07_기능처리도_알고리즘명세서.docx": "docs/submission/deliverables/기능처리도_알고리즘명세서.md",
    "08_프로그램목록_핵심소스코드_개발환경.docx": "docs/submission/deliverables/프로그램목록_핵심소스코드_개발환경.md",
}


class EvidenceFailure(RuntimeError):
    pass


def parse_timestamp(value: Any, context: str) -> datetime:
    if not isinstance(value, str) or not value.strip():
        raise EvidenceFailure(f"{context}.observed_at is required")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise EvidenceFailure(f"{context}.observed_at is not ISO-8601: {value!r}") from exc
    if parsed.tzinfo is None:
        raise EvidenceFailure(f"{context}.observed_at must include a timezone")
    return parsed.astimezone(UTC)


def meaningful_text(value: Any) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    lowered = value.casefold()
    return not any(token in lowered for token in PLACEHOLDER_TOKENS)


def required_absolute_environment_path(name: str) -> Path:
    raw = os.environ.get(name, "").strip()
    if not raw:
        raise EvidenceFailure(f"{name} must be explicitly configured for release evidence")
    configured = Path(raw).expanduser()
    if not configured.is_absolute():
        raise EvidenceFailure(f"{name} must be an absolute path for release evidence")
    absolute = Path(os.path.abspath(configured))
    try:
        resolved = absolute.resolve(strict=True)
    except OSError as exc:
        raise EvidenceFailure(f"{name} must be an existing path for release evidence") from exc
    if resolved != absolute:
        raise EvidenceFailure(f"{name} must not contain symlink path components")
    return absolute


def required_private_environment_directory(name: str) -> Path:
    configured = required_absolute_environment_path(name)
    if not configured.is_dir():
        raise EvidenceFailure(f"{name} must be an existing real directory")
    metadata = configured.stat()
    if metadata.st_uid != os.geteuid() or metadata.st_mode & 0o077:
        raise EvidenceFailure(f"{name} must be owned by the release operator and deny group/other access")
    return configured.resolve()


def _trusted_maintenance_lock_parent(parent: Path, parent_descriptor: int) -> bool:
    if os.geteuid() == 0 or not parent.is_absolute():
        return False
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | os.O_DIRECTORY
        | os.O_NOFOLLOW
    )
    authority_paths = [Path("/")]
    authority_descriptors: list[int] = []
    try:
        if parent.resolve(strict=True) != parent:
            return False
        authority_descriptors.append(os.open("/", flags))
        for component in parent.parent.relative_to("/").parts:
            authority_descriptors.append(
                os.open(component, flags, dir_fd=authority_descriptors[-1])
            )
            authority_paths.append(authority_paths[-1] / component)
        for index, (authority_path, descriptor) in enumerate(
            zip(authority_paths, authority_descriptors, strict=True)
        ):
            opened_authority = os.fstat(descriptor)
            path_authority = authority_path.stat(follow_symlinks=False)
            if (
                not stat.S_ISDIR(opened_authority.st_mode)
                or opened_authority.st_uid != 0
                or stat.S_IMODE(opened_authority.st_mode) & 0o022
                or os.access(
                    ".",
                    os.W_OK,
                    dir_fd=descriptor,
                    effective_ids=True,
                    follow_symlinks=False,
                )
                or (path_authority.st_dev, path_authority.st_ino)
                != (opened_authority.st_dev, opened_authority.st_ino)
            ):
                return False
            if index:
                anchored_authority = os.stat(
                    authority_path.name,
                    dir_fd=authority_descriptors[index - 1],
                    follow_symlinks=False,
                )
                if (anchored_authority.st_dev, anchored_authority.st_ino) != (
                    opened_authority.st_dev,
                    opened_authority.st_ino,
                ):
                    return False
        anchored_parent = os.stat(
            parent.name,
            dir_fd=authority_descriptors[-1],
            follow_symlinks=False,
        )
        opened_parent = os.fstat(parent_descriptor)
        path_parent = parent.stat(follow_symlinks=False)
        return (
            (path_parent.st_dev, path_parent.st_ino)
            == (opened_parent.st_dev, opened_parent.st_ino)
            == (anchored_parent.st_dev, anchored_parent.st_ino)
        )
    except (OSError, RuntimeError):
        return False
    finally:
        for descriptor in reversed(authority_descriptors):
            os.close(descriptor)


def required_private_environment_lock_file(name: str) -> Path:
    configured = required_absolute_environment_path(name)
    parent = configured.parent
    if parent.is_symlink() or not parent.is_dir():
        raise EvidenceFailure(f"{name} parent must be a real directory")
    parent_metadata = parent.stat()
    if parent_metadata.st_uid != os.geteuid() or parent_metadata.st_mode & 0o777 != 0o700:
        raise EvidenceFailure(f"{name} parent must be owned by the release operator with mode 0700")
    try:
        parent_descriptor = os.open(
            parent,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
    except OSError as exc:
        raise EvidenceFailure(f"{name} parent cannot be opened safely") from exc
    try:
        if not _trusted_maintenance_lock_parent(parent, parent_descriptor):
            raise EvidenceFailure(
                f"{name} parent must be protected by root-owned non-writable authority ancestry up to /"
            )
    finally:
        os.close(parent_descriptor)
    if not configured.is_file():
        raise EvidenceFailure(f"{name} must be an existing regular non-symlink file")
    metadata = configured.stat()
    if metadata.st_uid != os.geteuid() or metadata.st_mode & 0o777 != 0o600 or metadata.st_nlink != 1:
        raise EvidenceFailure(f"{name} must be a single-link 0600 file owned by the release operator")
    return configured.resolve()


def validate_live_release_environment(deployment_provider: str) -> dict[str, Any]:
    environment = os.environ.get("WALKSAFE_ENVIRONMENT", "").strip().lower()
    if environment != "production":
        raise EvidenceFailure("WALKSAFE_ENVIRONMENT must be production for web/full release evidence")
    source_commit = os.environ.get("WALKSAFE_SOURCE_COMMIT", "").strip().lower()
    if re.fullmatch(r"[0-9a-f]{40}", source_commit) is None:
        raise EvidenceFailure("WALKSAFE_SOURCE_COMMIT must bind the running backend to a full Git commit")
    if os.environ.get("WALKSAFE_FIELD_TEST_SECURITY_ENABLED", "").strip().lower() not in {"1", "true", "yes", "on"}:
        raise EvidenceFailure("WALKSAFE_FIELD_TEST_SECURITY_ENABLED must explicitly be true")
    configured_provider = os.environ.get("WALKING_ROUTE_PROVIDER", "").strip().lower()
    if configured_provider != deployment_provider:
        raise EvidenceFailure("--deployment-provider must match the live WALKING_ROUTE_PROVIDER")
    trusted_ip_header = os.environ.get("WALKSAFE_GATEWAY_TRUSTED_IP_HEADER", "").strip().lower()
    if trusted_ip_header not in {"cf-connecting-ip", "x-real-ip"}:
        raise EvidenceFailure("WALKSAFE_GATEWAY_TRUSTED_IP_HEADER must name the header set by the trusted edge proxy")
    database_url = os.environ.get("DATABASE_URL", "").strip()
    if not database_url:
        raise EvidenceFailure("DATABASE_URL must be explicitly configured for release evidence")
    upload_root = required_absolute_environment_path("UPLOAD_DIR")
    field_log_root = required_absolute_environment_path("WALKSAFE_FIELD_LOG_DIR")
    test_log_root = required_absolute_environment_path("WALKSAFE_TEST_LOG_DIR")
    for name, root in (
        ("UPLOAD_DIR", upload_root),
        ("WALKSAFE_FIELD_LOG_DIR", field_log_root),
        ("WALKSAFE_TEST_LOG_DIR", test_log_root),
    ):
        if not root.is_dir():
            raise EvidenceFailure(f"{name} must be an existing real directory")
    gateway_rate_limit_root = required_private_environment_directory("WALKSAFE_GATEWAY_RATE_LIMIT_DIR")
    maintenance_lock_path = required_private_environment_lock_file("WALKSAFE_MAINTENANCE_LOCK_PATH")
    maintenance_lock_authority = maintenance_lock_path.parent.stat(follow_symlinks=False)
    if field_log_root == test_log_root:
        raise EvidenceFailure("WALKSAFE_FIELD_LOG_DIR and WALKSAFE_TEST_LOG_DIR must be distinct roots")
    return {
        "environment": environment,
        "provider": configured_provider,
        "source_commit": source_commit,
        "trusted_ip_header": trusted_ip_header,
        "database_identity_sha256": database_identity_sha256(database_url),
        "upload_root_identity_sha256": path_identity_sha256(upload_root),
        "upload_root": upload_root,
        "field_log_root": field_log_root,
        "test_log_root": test_log_root,
        "gateway_rate_limit_root": gateway_rate_limit_root,
        "maintenance_lock_path": maintenance_lock_path,
        "maintenance_lock_device_inode": (
            f"{maintenance_lock_authority.st_dev}:{maintenance_lock_authority.st_ino}"
        ),
    }


def validate_live_detector_environment(check_value: Any, *, deployment_provider: str) -> dict[str, Any]:
    if os.environ.get("DETECT_V2_MODE", "").strip().lower() != "real":
        raise EvidenceFailure("DETECT_V2_MODE must be real for web/full release evidence")
    if os.environ.get("NEXT_PUBLIC_DETECTOR_MODE", "").strip().lower() != "server-v2":
        raise EvidenceFailure("NEXT_PUBLIC_DETECTOR_MODE must be server-v2 for web/full release evidence")
    if os.environ.get("TMAP_POI_PROVIDER", "").strip().lower() != "live":
        raise EvidenceFailure("TMAP_POI_PROVIDER must be live for web/full release evidence")
    runtime_config = required_absolute_environment_path("DETECT_V2_RUNTIME_CONFIG_PATH")
    unified_model = required_absolute_environment_path("DETECT_V2_UNIFIED_MODEL_PATH")
    smoke_fixture = required_absolute_environment_path("WALKSAFE_RELEASE_DETECTOR_FIXTURE_IMAGE")
    for name, artifact in (
        ("DETECT_V2_RUNTIME_CONFIG_PATH", runtime_config),
        ("DETECT_V2_UNIFIED_MODEL_PATH", unified_model),
        ("WALKSAFE_RELEASE_DETECTOR_FIXTURE_IMAGE", smoke_fixture),
    ):
        if artifact.is_symlink() or not artifact.is_file():
            raise EvidenceFailure(f"{name} must be a regular non-symlink file")
    try:
        runtime_payload = json.loads(runtime_config.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EvidenceFailure("DETECT_V2_RUNTIME_CONFIG_PATH must contain valid JSON") from exc
    if not isinstance(runtime_payload, dict):
        raise EvidenceFailure("DETECT_V2_RUNTIME_CONFIG_PATH root must be an object")

    runtime_digest = sha256_file(runtime_config)
    model_digest = sha256_file(unified_model)
    fixture_digest = sha256_file(smoke_fixture)
    try:
        normalized_runtime = load_threshold_config(runtime_config)
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as exc:
        raise EvidenceFailure("DETECT_V2_RUNTIME_CONFIG_PATH does not satisfy the runtime schema") from exc
    if normalized_runtime.get("primary_model") != "unified_walksafe":
        raise EvidenceFailure("release runtime primary_model must be unified_walksafe")
    if normalized_runtime.get("fallback_model") is not None:
        raise EvidenceFailure("release runtime fallback_model must be null")
    models = runtime_payload.get("models")
    unified_config = models.get("unified_walksafe") if isinstance(models, dict) else None
    declared_model_digest = unified_config.get("artifact_sha256") if isinstance(unified_config, dict) else None
    if not isinstance(declared_model_digest, str) or declared_model_digest.lower() != model_digest:
        raise EvidenceFailure(
            "runtime models.unified_walksafe.artifact_sha256 does not match DETECT_V2_UNIFIED_MODEL_PATH"
        )
    if not isinstance(check_value, dict) or not isinstance(check_value.get("details"), dict):
        raise EvidenceFailure("checks.detector_runtime_live.details is required")
    details = check_value["details"]
    expected = {
        "detect_v2_mode": "real",
        "frontend_detector_mode": "server-v2",
        "walking_route_provider": deployment_provider,
        "tmap_poi_provider": "live",
        "runtime_config_sha256": runtime_digest,
        "unified_model_sha256": model_digest,
        "smoke_fixture_sha256": fixture_digest,
        "smoke_expected_class": "person",
        "backend_source_commit": os.environ.get("WALKSAFE_SOURCE_COMMIT", "").strip().lower(),
    }
    for field, actual in expected.items():
        if details.get(field) != actual:
            raise EvidenceFailure(f"detector runtime evidence {field} does not match the live environment")
    try:
        image_size = int(os.environ.get("DETECT_V2_IMAGE_SIZE", ""))
    except ValueError as exc:
        raise EvidenceFailure("DETECT_V2_IMAGE_SIZE must be an integer for the runtime smoke") from exc
    if image_size != 768:
        raise EvidenceFailure("DETECT_V2_IMAGE_SIZE must be 768 for the deployed unified checkpoint")
    smoke = run_detector_smoke(unified_model, smoke_fixture, image_size=image_size)
    return {
        **expected,
        "runtime_config_path_identity_sha256": path_identity_sha256(runtime_config),
        "unified_model_path_identity_sha256": path_identity_sha256(unified_model),
        "runtime_smoke": smoke,
    }


def load_evidence(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise EvidenceFailure(f"evidence file not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise EvidenceFailure(f"evidence file is not valid JSON: {exc}") from exc
    if not isinstance(payload, dict):
        raise EvidenceFailure("evidence root must be an object")
    if payload.get("schema_version") != SCHEMA_VERSION:
        raise EvidenceFailure(f"schema_version must be {SCHEMA_VERSION!r}")
    if not meaningful_text(payload.get("release_id")):
        raise EvidenceFailure("release_id must be a non-placeholder value")
    if not meaningful_text(payload.get("operator")):
        raise EvidenceFailure("operator must identify the human verifier")
    if not isinstance(payload.get("checks"), dict):
        raise EvidenceFailure("checks must be an object")
    return payload


def load_json_object(path: Path, context: str) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise EvidenceFailure(f"{context} file not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise EvidenceFailure(f"{context} is not valid JSON: {exc}") from exc
    if not isinstance(payload, dict):
        raise EvidenceFailure(f"{context} root must be an object")
    return payload


def validate_retention_receipt(
    path: Path,
    *,
    now: datetime,
    max_age_hours: int,
    scope: str = "field_telemetry",
    expected_root: Path | None = None,
) -> dict[str, Any]:
    expected_schemas = {
        "field_telemetry": "walksafe.field-telemetry-retention.v1",
        "test_capture": "walksafe.test-capture-retention.v1",
    }
    if scope not in expected_schemas:
        raise EvidenceFailure("retention receipt scope is invalid")
    context = f"{scope.replace('_', ' ')} retention receipt"
    payload = load_json_object(path, context)
    if payload.get("schema_version") != expected_schemas[scope] or payload.get("scope") != scope:
        raise EvidenceFailure(f"{context} schema_version or scope is invalid")
    checked_at = parse_timestamp(payload.get("checked_at"), context)
    if checked_at > now + timedelta(minutes=5):
        raise EvidenceFailure(f"{context} checked_at is in the future")
    if now - checked_at > timedelta(hours=max_age_hours):
        raise EvidenceFailure(f"{context} is older than {max_age_hours} hours")
    if payload.get("retention_days") != 7:
        raise EvidenceFailure(f"{context} must enforce exactly 7 days")
    if payload.get("applied") is not True:
        raise EvidenceFailure(f"{context} must have applied=true; dry-run is not release evidence")
    authorized_admin_id = payload.get("authorized_admin_id")
    authorized_session_id = payload.get("authorized_session_id")
    if not isinstance(authorized_admin_id, str) or ACTOR_ID.fullmatch(authorized_admin_id) is None:
        raise EvidenceFailure(f"{context} authorized_admin_id is invalid")
    if not isinstance(authorized_session_id, str) or UUID.fullmatch(authorized_session_id.lower()) is None:
        raise EvidenceFailure(f"{context} authorized_session_id is invalid")
    root = payload.get("root")
    if not meaningful_text(root) or not Path(root).is_absolute():
        raise EvidenceFailure(f"{context} root must be an absolute non-placeholder path")
    if expected_root is not None and Path(root).resolve() != expected_root.resolve():
        raise EvidenceFailure(f"{context} root does not match the live configured log root")
    candidate_dates = payload.get("candidate_dates")
    deleted_dates = payload.get("deleted_dates")
    if not isinstance(candidate_dates, list) or not all(isinstance(value, str) for value in candidate_dates):
        raise EvidenceFailure(f"{context} candidate_dates must be a string list")
    if not isinstance(deleted_dates, list) or not all(isinstance(value, str) for value in deleted_dates):
        raise EvidenceFailure(f"{context} deleted_dates must be a string list")
    if sorted(candidate_dates) != sorted(deleted_dates):
        raise EvidenceFailure(f"{context} apply did not delete every expired candidate")
    candidate_sidecars = payload.get("candidate_sidecars")
    deleted_sidecars = payload.get("deleted_sidecars")
    if scope == "field_telemetry":
        if not isinstance(candidate_sidecars, list) or not all(isinstance(value, str) for value in candidate_sidecars):
            raise EvidenceFailure(f"{context} candidate_sidecars must be a string list")
        if not isinstance(deleted_sidecars, list) or not all(isinstance(value, str) for value in deleted_sidecars):
            raise EvidenceFailure(f"{context} deleted_sidecars must be a string list")
        if sorted(candidate_sidecars) != sorted(deleted_sidecars):
            raise EvidenceFailure(f"{context} apply did not delete every expired owner/revocation sidecar")
    else:
        candidate_sidecars = []
        deleted_sidecars = []
    before_digest = payload.get("inventory_before_sha256")
    after_digest = payload.get("inventory_after_sha256")
    if (
        not isinstance(before_digest, str)
        or SHA256.fullmatch(before_digest.lower()) is None
        or not isinstance(after_digest, str)
        or SHA256.fullmatch(after_digest.lower()) is None
    ):
        raise EvidenceFailure(f"{context} must bind before/after inventories with SHA-256")
    if (deleted_dates or deleted_sidecars) and before_digest.lower() == after_digest.lower():
        raise EvidenceFailure(f"{context} inventory did not change after deleting candidates")
    return {
        "checked_at": checked_at.isoformat(),
        "retention_days": 7,
        "authorized_admin_id": authorized_admin_id,
        "authorized_session_id": authorized_session_id.lower(),
        "deleted_count": len(deleted_dates),
        "deleted_sidecar_count": len(deleted_sidecars),
        "inventory_before_sha256": before_digest.lower(),
        "inventory_after_sha256": after_digest.lower(),
    }


def validate_report_retention_manifest(
    path: Path,
    *,
    now: datetime,
    max_age_hours: int,
    expected_database_identity: str | None = None,
    expected_upload_identity: str | None = None,
) -> dict[str, Any]:
    payload = load_json_object(path, "report retention manifest")
    if payload.get("schema_version") != "walksafe.report_retention_apply.v1":
        raise EvidenceFailure("report retention manifest schema_version is invalid")
    finished_at = parse_timestamp(payload.get("finished_at"), "report retention manifest")
    started_at = parse_timestamp(payload.get("started_at"), "report retention manifest")
    backup_created_at = parse_timestamp(
        payload.get("predelete_backup_created_at"),
        "report retention predelete backup",
    )
    restore_restored_at = parse_timestamp(
        payload.get("predelete_restore_restored_at"),
        "report retention predelete restore",
    )
    maintenance_lock_acquired_at = parse_timestamp(
        payload.get("maintenance_lock_acquired_at"),
        "report retention maintenance lock",
    )
    if finished_at > now + timedelta(minutes=5) or now - finished_at > timedelta(hours=max_age_hours):
        raise EvidenceFailure(f"report retention manifest must be no older than {max_age_hours} hours")
    if not backup_created_at <= restore_restored_at <= maintenance_lock_acquired_at <= started_at <= finished_at:
        raise EvidenceFailure("report retention backup/restore/start/finish timestamps are out of order")
    if payload.get("status") != "completed" or payload.get("destructive_action") is not True:
        raise EvidenceFailure("report retention manifest must be a completed destructive apply run")
    if not meaningful_text(payload.get("run_id")) or not meaningful_text(payload.get("actor_id")):
        raise EvidenceFailure("report retention manifest run_id and actor_id are required")
    authorized_admin_id = payload.get("authorized_admin_id")
    authorized_session_id = payload.get("authorized_session_id")
    if not isinstance(authorized_admin_id, str) or ACTOR_ID.fullmatch(authorized_admin_id) is None:
        raise EvidenceFailure("report retention manifest authorized_admin_id is invalid")
    if not isinstance(authorized_session_id, str) or UUID.fullmatch(authorized_session_id.lower()) is None:
        raise EvidenceFailure("report retention manifest authorized_session_id is invalid")
    if not meaningful_text(payload.get("predelete_backup_run_id")):
        raise EvidenceFailure("report retention manifest predelete_backup_run_id is required")
    restore_receipt_sha256 = payload.get("predelete_restore_receipt_sha256")
    lock_identity = payload.get("maintenance_lock_identity_sha256")
    lock_device_inode = payload.get("maintenance_lock_device_inode")
    backup_artifacts = payload.get("predelete_backup_artifacts_sha256")
    if not isinstance(restore_receipt_sha256, str) or not SHA256.fullmatch(restore_receipt_sha256.lower()):
        raise EvidenceFailure("report retention restore receipt hash is invalid")
    if not isinstance(lock_identity, str) or not SHA256.fullmatch(lock_identity.lower()):
        raise EvidenceFailure("report retention maintenance lock identity is invalid")
    if not isinstance(lock_device_inode, str) or re.fullmatch(r"[0-9]+:[0-9]+", lock_device_inode) is None:
        raise EvidenceFailure("report retention maintenance lock device/inode is invalid")
    if not isinstance(backup_artifacts, dict) or set(backup_artifacts) != {"reports.dump.gpg", "uploads.tar.gz.gpg"}:
        raise EvidenceFailure("report retention backup artifact hashes are invalid")
    normalized_backup_artifacts: dict[str, str] = {}
    for name, digest_value in backup_artifacts.items():
        if not isinstance(digest_value, str) or not SHA256.fullmatch(digest_value.lower()):
            raise EvidenceFailure("report retention backup artifact hashes are invalid")
        normalized_backup_artifacts[name] = digest_value.lower()
    database_identity = payload.get("database_identity_sha256")
    upload_identity = payload.get("upload_root_identity_sha256")
    if not isinstance(database_identity, str) or not SHA256.fullmatch(database_identity.lower()):
        raise EvidenceFailure("report retention database identity is invalid")
    if not isinstance(upload_identity, str) or not SHA256.fullmatch(upload_identity.lower()):
        raise EvidenceFailure("report retention upload identity is invalid")
    if expected_database_identity is not None and database_identity.lower() != expected_database_identity:
        raise EvidenceFailure("report retention database identity does not match the live environment")
    if expected_upload_identity is not None and upload_identity.lower() != expected_upload_identity:
        raise EvidenceFailure("report retention upload identity does not match the live environment")
    candidates = payload.get("candidates")
    if not isinstance(candidates, list) or not all(isinstance(item, dict) for item in candidates):
        raise EvidenceFailure("report retention manifest candidates must be an object list")
    if payload.get("deleted_count") != len(candidates):
        raise EvidenceFailure("report retention manifest deleted_count does not match its candidates")
    for candidate in candidates:
        reason = candidate.get("reason")
        expected_days = 30 if reason == "fake_demo" else 180 if reason in {"active", "resolved"} else None
        if expected_days is None or candidate.get("retention_days") != expected_days or candidate.get("would_delete") is not True:
            raise EvidenceFailure("report retention manifest contains an invalid policy candidate")
    candidate_ids = sorted(str(candidate.get("id", "")) for candidate in candidates)
    digest = hashlib.sha256(json.dumps(candidate_ids, separators=(",", ":")).encode("utf-8")).hexdigest()
    if payload.get("candidate_ids_sha256") != digest:
        raise EvidenceFailure("report retention manifest candidate digest is invalid")
    if payload.get("cleanup_errors") != [] or payload.get("restore_errors") not in (None, []):
        raise EvidenceFailure("report retention manifest contains cleanup or restore errors")
    return {
        "started_at": started_at.isoformat(),
        "finished_at": finished_at.isoformat(),
        "deleted_count": len(candidates),
        "authorized_admin_id": authorized_admin_id,
        "authorized_session_id": authorized_session_id.lower(),
        "database_identity_sha256": database_identity.lower(),
        "upload_root_identity_sha256": upload_identity.lower(),
        "predelete_backup_run_id": payload["predelete_backup_run_id"],
        "predelete_backup_created_at": backup_created_at.isoformat(),
        "predelete_backup_artifacts_sha256": normalized_backup_artifacts,
        "predelete_restore_restored_at": restore_restored_at.isoformat(),
        "predelete_restore_receipt_sha256": restore_receipt_sha256.lower(),
        "maintenance_lock_identity_sha256": lock_identity.lower(),
        "maintenance_lock_device_inode": lock_device_inode,
    }


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _bound_regular_file(
    raw_path: Any,
    raw_digest: Any,
    *,
    base_dir: Path,
    context: str,
) -> Path:
    if not isinstance(raw_path, str) or not meaningful_text(raw_path):
        raise EvidenceFailure(f"{context} path is required")
    if not isinstance(raw_digest, str) or SHA256.fullmatch(raw_digest.lower()) is None:
        raise EvidenceFailure(f"{context} SHA-256 is required")
    configured = Path(raw_path).expanduser()
    if not configured.is_absolute():
        configured = base_dir / configured
    absolute = Path(os.path.abspath(configured))
    try:
        resolved = absolute.resolve(strict=True)
    except OSError as exc:
        raise EvidenceFailure(f"{context} must be a regular non-symlink file") from exc
    if resolved != absolute or not absolute.is_file():
        raise EvidenceFailure(f"{context} must be a regular non-symlink file")
    if sha256_file(resolved) != raw_digest.lower():
        raise EvidenceFailure(f"{context} SHA-256 does not match the file")
    return resolved


def validate_source_revision(payload: dict[str, Any], *, repository_root: Path = ROOT) -> str:
    source_commit = payload.get("source_commit")
    if not isinstance(source_commit, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", source_commit):
        raise EvidenceFailure("source_commit must be a full Git commit SHA")
    try:
        identity = verify_exact_git_source(
            repository_root,
            context="release evidence source",
            expected_commit=source_commit.lower(),
            allowed_ignored_roots=RELEASE_IGNORED_OUTPUT_ROOTS,
        )
    except ReleaseIntegrityError as exc:
        raise EvidenceFailure(f"release source exact validation failed: {exc}") from exc
    return identity.commit


def validate_running_backend_source(source_commit: str) -> dict[str, str]:
    import httpx

    health_url = os.environ.get("WALKSAFE_BACKEND_HEALTH_URL", "").strip()
    parsed = urlparse(health_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise EvidenceFailure("WALKSAFE_BACKEND_HEALTH_URL must be an absolute HTTP(S) URL")
    if parsed.scheme == "http" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise EvidenceFailure("plaintext backend health checks are allowed only on loopback")
    token = os.environ.get("WALKSAFE_FIELD_TEST_TOKEN", "").strip()
    if not token:
        raise EvidenceFailure("WALKSAFE_FIELD_TEST_TOKEN is required to verify the running backend")
    try:
        response = httpx.get(
            health_url,
            headers={
                "x-walksafe-field-test-token": token,
                "x-walksafe-actor-id": "release-gate",
            },
            timeout=5.0,
        )
        response.raise_for_status()
        payload = response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise EvidenceFailure("running backend health/source check failed") from exc
    if not isinstance(payload, dict) or payload.get("source_commit") != source_commit:
        raise EvidenceFailure("running backend source_commit does not match the release source")
    if payload.get("status") != "ready":
        raise EvidenceFailure("running backend is not ready")
    return {"health_url": health_url, "source_commit": source_commit}


def validate_release_artifacts(
    payload: dict[str, Any],
    *,
    profile: str,
    base_dir: Path,
    source_commit: str,
    repository_root: Path = ROOT,
) -> dict[str, Any]:
    artifacts = payload.get("release_artifacts")
    if not isinstance(artifacts, dict):
        raise EvidenceFailure("release_artifacts must bind the tested build outputs")
    verified: dict[str, Any] = {}
    if profile in {"web-release", "full"}:
        web = artifacts.get("web_build")
        if not isinstance(web, dict):
            raise EvidenceFailure("release_artifacts.web_build is required")
        manifest_path = _bound_regular_file(
            web.get("manifest_path"),
            web.get("manifest_sha256"),
            base_dir=base_dir,
            context="web build manifest",
        )
        manifest = load_json_object(manifest_path, "web build manifest")
        if manifest.get("schema_version") != "walksafe.web-build-manifest.v4":
            raise EvidenceFailure("web build manifest schema_version is invalid")
        if manifest.get("source_commit") != source_commit:
            raise EvidenceFailure("web build manifest source_commit does not match the release source")
        archive_path = _bound_regular_file(
            web.get("archive_path"),
            web.get("archive_sha256"),
            base_dir=base_dir,
            context="web standalone deployment archive",
        )
        manifest_base = manifest_path.parent

        def manifested_file(record: Any, context: str) -> Path:
            if not isinstance(record, dict):
                raise EvidenceFailure(f"{context} record is invalid")
            raw_path = record.get("path")
            if (
                not isinstance(raw_path, str)
                or Path(raw_path).is_absolute()
                or ".." in Path(raw_path).parts
            ):
                raise EvidenceFailure(f"{context} path must stay inside the Web artifact bundle")
            resolved = _bound_regular_file(
                raw_path,
                record.get("sha256"),
                base_dir=manifest_base,
                context=context,
            )
            size = record.get("bytes")
            if not isinstance(size, int) or isinstance(size, bool) or size <= 0 or size != resolved.stat().st_size:
                raise EvidenceFailure(f"{context} byte size does not match the file")
            return resolved

        declared_archive = manifest.get("deployment_archive")
        if not isinstance(declared_archive, dict):
            raise EvidenceFailure("web standalone deployment archive record is invalid")
        manifested_archive = manifested_file(declared_archive, "web standalone deployment archive")
        if declared_archive.get("name") != archive_path.name or manifested_archive != archive_path:
            raise EvidenceFailure("web standalone deployment archive does not match its build manifest")
        inputs = manifest.get("inputs")
        toolchain = manifest.get("toolchain")
        build_environment = manifest.get("build_environment")
        quality_receipts = manifest.get("quality_receipts")
        if not isinstance(inputs, dict) or set(inputs) != {
            "package_json",
            "package_lock",
            "node_toolchain_lock",
        }:
            raise EvidenceFailure("web build manifest dependency inputs are invalid")
        repository_root = repository_root.resolve()
        input_sources = {
            "package_json": repository_root / "apps/web/package.json",
            "package_lock": repository_root / "apps/web/package-lock.json",
            "node_toolchain_lock": (
                repository_root / "configs/walksafe_node_toolchain_lock_20260715.json"
            ),
        }
        for name, source in input_sources.items():
            preserved = manifested_file(inputs[name], f"web dependency input {name}")
            if source.is_symlink() or not source.is_file():
                raise EvidenceFailure(f"current Web dependency input is missing: {name}")
            if source.stat().st_size != preserved.stat().st_size or sha256_file(source) != sha256_file(preserved):
                raise EvidenceFailure(f"preserved Web dependency input differs from the release source: {name}")
        if not isinstance(toolchain, dict) or any(
            not meaningful_text(toolchain.get(key)) for key in ("node", "npm")
        ):
            raise EvidenceFailure("web build manifest toolchain is invalid")
        expected_environment = {
            "NEXT_PUBLIC_DETECTOR_MODE": "server-v2",
            "NEXT_PUBLIC_WALKSAFE_PWA_ENABLED": "true",
            "NEXT_PUBLIC_WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING": "false",
            "NEXT_PUBLIC_WALKSAFE_TEST_CAPTURE_PANEL": "false",
        }
        if build_environment != expected_environment:
            raise EvidenceFailure("web build manifest public build environment is invalid")
        expected_receipts = {
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
        }
        if not isinstance(quality_receipts, list) or len(quality_receipts) != len(expected_receipts) or {
            receipt.get("name") for receipt in quality_receipts if isinstance(receipt, dict)
        } != expected_receipts:
            raise EvidenceFailure("web build manifest quality receipt set is incomplete")
        for receipt in quality_receipts:
            manifested_file(receipt, f"web quality receipt {receipt.get('name') if isinstance(receipt, dict) else 'unknown'}")
        node_lock_path = manifested_file(
            inputs["node_toolchain_lock"], "web Node toolchain lock"
        )
        node_receipt = next(
            receipt
            for receipt in quality_receipts
            if isinstance(receipt, dict) and receipt.get("name") == "node-toolchain"
        )
        node_attestation_path = manifested_file(
            node_receipt, "web Node toolchain attestation"
        )
        node_lock = load_json_object(node_lock_path, "web Node toolchain lock")
        if (
            set(node_lock)
            != {"schema_version", "official_archive", "platform", "root", "node", "npm"}
            or node_lock.get("schema_version") != "walksafe.node-toolchain.v2"
        ):
            raise EvidenceFailure("web Node toolchain lock schema is invalid")
        locked_node = node_lock.get("node")
        locked_npm = node_lock.get("npm")
        if (
            not isinstance(locked_node, dict)
            or not isinstance(locked_npm, dict)
            or locked_node.get("version") != toolchain.get("node")
            or locked_npm.get("version") != toolchain.get("npm")
        ):
            raise EvidenceFailure("web Node toolchain versions differ from the source lock")
        expected_attestation = {
            "schema_version": "walksafe.node-toolchain-attestation.v2",
            "lock_sha256": sha256_file(node_lock_path),
            "official_archive": node_lock["official_archive"],
            "platform": node_lock["platform"],
            "root": node_lock["root"],
            "node": locked_node,
            "npm": locked_npm,
        }
        if load_json_object(
            node_attestation_path, "web Node toolchain attestation"
        ) != expected_attestation:
            raise EvidenceFailure("web Node toolchain attestation differs from the source lock")
        post_node_receipt = next(
            receipt
            for receipt in quality_receipts
            if isinstance(receipt, dict) and receipt.get("name") == "node-toolchain-post"
        )
        post_node_attestation_path = manifested_file(
            post_node_receipt, "web post-build Node toolchain attestation"
        )
        if load_json_object(
            post_node_attestation_path, "web post-build Node toolchain attestation"
        ) != expected_attestation:
            raise EvidenceFailure(
                "web post-build Node toolchain attestation differs from the source lock"
            )
        raw_build_root = web.get("build_root")
        if not isinstance(raw_build_root, str) or not meaningful_text(raw_build_root):
            raise EvidenceFailure("web build_root is required")
        build_root = Path(raw_build_root).expanduser()
        if not build_root.is_absolute():
            build_root = base_dir / build_root
        if build_root.is_symlink() or not build_root.is_dir():
            raise EvidenceFailure("web build_root must be a real directory")
        build_root = build_root.resolve()
        entries = manifest.get("files")
        if not isinstance(entries, list) or not entries:
            raise EvidenceFailure("web build manifest files must be a non-empty list")
        expected_files: dict[str, str] = {}
        for entry in entries:
            if not isinstance(entry, dict):
                raise EvidenceFailure("web build manifest contains an invalid file entry")
            relative = entry.get("path")
            digest = entry.get("sha256")
            if not isinstance(relative, str) or Path(relative).is_absolute() or ".." in Path(relative).parts:
                raise EvidenceFailure("web build manifest contains an unsafe file path")
            if Path(relative).parts and Path(relative).parts[0] == "dev":
                raise EvidenceFailure("web build manifest must not include stale .next/dev artifacts")
            if not isinstance(digest, str) or SHA256.fullmatch(digest.lower()) is None or relative in expected_files:
                raise EvidenceFailure("web build manifest contains an invalid file hash")
            expected_files[relative] = digest.lower()
        current_files: dict[str, Path] = {}
        for item in build_root.rglob("*"):
            if item.is_symlink():
                raise EvidenceFailure("web build contains a symlink")
            if item.is_file():
                current_files[item.relative_to(build_root).as_posix()] = item
            elif not item.is_dir():
                raise EvidenceFailure("web build contains an unsupported entry type")
        if set(current_files) != set(expected_files):
            raise EvidenceFailure("web build file set does not match the manifest")
        for relative, path in current_files.items():
            if sha256_file(path) != expected_files[relative]:
                raise EvidenceFailure(f"web build file hash changed: {relative}")
        try:
            verify_deployment_archive(archive_path, build_root)
        except ValueError as exc:
            raise EvidenceFailure(f"web standalone deployment archive is unsafe or differs from build_root: {exc}") from exc
        build_id_path = current_files.get("BUILD_ID")
        if build_id_path is None:
            raise EvidenceFailure("web build manifest must include BUILD_ID")
        build_id = build_id_path.read_text(encoding="utf-8").strip()
        if not meaningful_text(build_id) or manifest.get("build_id") != build_id:
            raise EvidenceFailure("web build_id does not match the manifested BUILD_ID")
        if build_id != source_commit:
            raise EvidenceFailure("web BUILD_ID is not bound to the release source commit")
        probe_relative = f".next/static/{source_commit}/_buildManifest.js"
        probe_path = current_files.get(probe_relative)
        if probe_path is None:
            raise EvidenceFailure("web build is missing its source-bound deployment probe")
        verified["web_build"] = {
            "build_id": build_id,
            "manifest_sha256": sha256_file(manifest_path),
            "file_count": len(current_files),
            "deployment_probe_path": f"/_next/static/{source_commit}/_buildManifest.js",
            "deployment_probe_sha256": sha256_file(probe_path),
            "source_identity_path": "/api/release-identity",
            "source_commit": source_commit,
        }
    if profile in {"android-research", "full"}:
        android = artifacts.get("android_apk")
        if not isinstance(android, dict):
            raise EvidenceFailure("release_artifacts.android_apk is required")
        apk = _bound_regular_file(
            android.get("path"),
            android.get("sha256"),
            base_dir=base_dir,
            context="Android APK artifact",
        )
        if apk.suffix.lower() != ".apk":
            raise EvidenceFailure("Android APK artifact must use the .apk extension")
        certificate_sha256 = android.get("signer_certificate_sha256")
        if not isinstance(certificate_sha256, str) or SHA256.fullmatch(certificate_sha256.lower()) is None:
            raise EvidenceFailure("Android APK signer_certificate_sha256 is required")
        apk_details = validate_android_apk(
            apk,
            source_commit=source_commit,
            signer_certificate_sha256=certificate_sha256.lower(),
            expected_sha256=str(android["sha256"]).lower(),
        )
        verified["android_apk"] = {
            "sha256": str(android["sha256"]).lower(),
            **apk_details,
        }
    return verified


FULL_RELEASE_CHAIN_OPTIONS = (
    ("full_rc_manifest", "--full-rc-manifest"),
    ("full_rc_validation_receipt", "--full-rc-validation-receipt"),
    ("full_rc_unsigned_apk", "--full-rc-unsigned-apk"),
    ("operator_attestation", "--operator-attestation"),
    ("operator_attestation_signature", "--operator-attestation-signature"),
    ("expected_operator_fingerprint", "--expected-operator-fingerprint"),
    ("gpg", "--gpg"),
    ("expected_gpg_sha256", "--expected-gpg-sha256"),
    ("gpg_keyring", "--gpg-keyring"),
    ("java", "--java"),
    ("expected_java_sha256", "--expected-java-sha256"),
    ("apksigner_jar", "--apksigner-jar"),
    ("expected_apksigner_jar_sha256", "--expected-apksigner-jar-sha256"),
    ("android_signing_gate_receipt", "--android-signing-gate-receipt"),
    ("blocker_resolution_receipt", "--blocker-resolution-receipt"),
    ("blocker_resolution_signature", "--blocker-resolution-signature"),
    ("production_receipt", "--production-receipt"),
)
ANDROID_SIGNING_GATE_FIELDS = {
    "schema_version",
    "source_commit",
    "unsigned_apk_sha256",
    "signed_apk_sha256",
    "certificate_sha256",
    "full_rc_manifest",
    "full_rc_closure_sha256",
    "validation_receipt",
    "operator_attestation",
    "operator_attestation_signature",
    "operator_fingerprint",
    "operator_primary_fingerprint",
    "operator_signing_fingerprint",
    "operator_fingerprint_sha256",
    "gpg",
    "gpg_keyring",
    "tools",
    "validation_tools",
    "android_signing_gate_passed",
    "deployment_complete",
    "verified",
}
PRODUCTION_BLOCKER_CHECKS = {
    "operator-secrets": (
        "database_credentials_configured",
        "tmap_credentials_configured",
        "gateway_credentials_configured",
        "backend_credentials_configured",
        "voice_credentials_configured",
        "session_credentials_configured",
    ),
    "trusted-edge-tls-identity": (
        "public_dns_matches_release_origin",
        "tls_certificate_chain_valid",
        "tls_private_key_permissions_verified",
        "firewall_only_443_public",
        "web_port_3000_not_public",
        "trusted_proxy_config_active",
    ),
    "backend-model-weight": (
        "detector_weight_hash_approved",
        "detector_runtime_smoke_passed",
        "registry_deployment_binding_verified",
    ),
    "voice-model-weights": (
        "stt_model_revision_and_manifest_verified",
        "tts_model_revision_and_manifest_verified",
        "model_caches_read_only",
        "optional_reference_audio_policy_verified",
    ),
    "voice-ffprobe": (
        "ffprobe_executable_hash_approved",
        "non_wav_duration_validation_passed",
    ),
    "shared-postgis-and-upload-storage": (
        "postgis_atomic_limiter_store_active",
        "postgis_shared_across_replicas",
        "upload_storage_durable",
        "upload_storage_shared_across_replicas",
    ),
    "android-operator-signing": (
        "unsigned_manifest_binding_verified",
        "approved_certificate_verified",
        "signed_payload_equivalence_verified",
    ),
    "external-acceptance": (
        "physical_device_acceptance_verified",
        "outdoor_navigation_acceptance_verified",
        "institution_acceptance_verified",
    ),
    "runtime-images-and-native-libraries": (
        "node_runtime_image_digest_approved",
        "cpython_runtime_image_digest_approved",
        "native_library_inventory_matches_quality_receipts",
        "runtime_images_immutable",
    ),
    "operator-release-attestation": (
        "full_rc_validation_receipt_verified",
        "reviewer_primary_fingerprint_approved",
        "detached_signature_verified",
    ),
}


def validate_release_chain_options(args: argparse.Namespace) -> None:
    supplied = [option for attribute, option in FULL_RELEASE_CHAIN_OPTIONS if getattr(args, attribute) is not None]
    if args.profile != "full":
        if supplied:
            raise EvidenceFailure(
                f"{', '.join(supplied)} are only valid with --profile full"
            )
        return
    missing = [option for attribute, option in FULL_RELEASE_CHAIN_OPTIONS if getattr(args, attribute) is None]
    if missing:
        raise EvidenceFailure(
            "--profile full requires the complete Full-RC signing chain: "
            + ", ".join(missing)
        )


def validate_full_release_signing_chain(
    *,
    source_commit: str,
    payload: dict[str, Any],
    release_artifacts: dict[str, Any],
    base_dir: Path,
    manifest_path: Path,
    validation_receipt_path: Path,
    unsigned_apk_path: Path,
    operator_attestation_path: Path,
    operator_attestation_signature_path: Path,
    expected_operator_fingerprint: str,
    gpg_path: Path,
    expected_gpg_sha256: str,
    gpg_keyring_path: Path,
    java_path: Path,
    expected_java_sha256: str,
    apksigner_jar_path: Path,
    expected_apksigner_jar_sha256: str,
    signing_gate_receipt_path: Path,
    repository_root: Path = ROOT,
) -> dict[str, Any]:
    declared_artifacts = payload.get("release_artifacts")
    declared_android = declared_artifacts.get("android_apk") if isinstance(declared_artifacts, dict) else None
    verified_android = release_artifacts.get("android_apk")
    if not isinstance(declared_android, dict) or not isinstance(verified_android, dict):
        raise EvidenceFailure("full release signing chain requires the verified Android APK artifact")
    signed_apk = _bound_regular_file(
        declared_android.get("path"),
        declared_android.get("sha256"),
        base_dir=base_dir,
        context="full release signed Android APK",
    )
    certificate_sha256 = declared_android.get("signer_certificate_sha256")
    if (
        not isinstance(certificate_sha256, str)
        or SHA256.fullmatch(certificate_sha256.lower()) is None
        or verified_android.get("sha256") != str(declared_android.get("sha256")).lower()
        or verified_android.get("signer_certificate_sha256") != certificate_sha256.lower()
    ):
        raise EvidenceFailure("full release signed APK/certificate differs from verified release artifacts")
    try:
        verification = verify_signed_release(
            source_root=repository_root,
            manifest_path=manifest_path,
            unsigned_apk_path=unsigned_apk_path,
            signed_apk_path=signed_apk,
            expected_cert_sha256=certificate_sha256.lower(),
            validation_receipt_path=validation_receipt_path,
            operator_attestation_path=operator_attestation_path,
            operator_attestation_signature_path=operator_attestation_signature_path,
            expected_operator_fingerprint=expected_operator_fingerprint,
            gpg_path=gpg_path,
            expected_gpg_sha256=expected_gpg_sha256,
            gpg_keyring_path=gpg_keyring_path,
            java_path=java_path,
            expected_java_sha256=expected_java_sha256,
            apksigner_jar_path=apksigner_jar_path,
            expected_apksigner_jar_sha256=expected_apksigner_jar_sha256,
        )
    except SigningGateError as exc:
        raise EvidenceFailure(f"Full-RC signed Android verification failed: {exc}") from exc
    if (
        not isinstance(verification, dict)
        or set(verification) != ANDROID_SIGNING_GATE_FIELDS
        or verification.get("schema_version") != "walksafe.android-signing-gate.v2"
        or verification.get("android_signing_gate_passed") is not True
        or verification.get("deployment_complete") is not False
        or verification.get("verified") is not True
    ):
        raise EvidenceFailure("recomputed Android signing gate result has an invalid fixed schema")
    if verification.get("source_commit") != source_commit:
        raise EvidenceFailure("Android signing gate source_commit differs from release evidence")
    if verification.get("signed_apk_sha256") != verified_android.get("sha256"):
        raise EvidenceFailure("Android signing gate signed APK differs from release evidence")
    if verification.get("certificate_sha256") != verified_android.get("signer_certificate_sha256"):
        raise EvidenceFailure("Android signing gate certificate differs from release evidence")
    closure_sha256 = verification.get("full_rc_closure_sha256")
    if not isinstance(closure_sha256, str) or SHA256.fullmatch(closure_sha256) is None:
        raise EvidenceFailure("Android signing gate Full-RC closure digest is invalid")

    try:
        with FileSnapshot.capture(
            manifest_path,
            context="Full-RC manifest after signing replay",
            display_path=manifest_path.name,
        ) as manifest, FileSnapshot.capture(
            validation_receipt_path,
            context="Full-RC validation receipt after signing replay",
            display_path=validation_receipt_path.name,
        ) as validation_receipt, FileSnapshot.capture(
            unsigned_apk_path,
            context="Full-RC unsigned APK after signing replay",
            display_path=unsigned_apk_path.name,
        ) as unsigned_apk, FileSnapshot.capture(
            operator_attestation_path,
            context="operator attestation after signing replay",
            display_path=operator_attestation_path.name,
        ) as operator_attestation, FileSnapshot.capture(
            operator_attestation_signature_path,
            context="operator attestation signature after signing replay",
            display_path=operator_attestation_signature_path.name,
        ) as operator_signature:
            expected_records = {
                "full_rc_manifest": manifest.record(path=manifest_path.name),
                "validation_receipt": validation_receipt.record(path=validation_receipt_path.name),
                "operator_attestation": operator_attestation.record(),
                "operator_attestation_signature": operator_signature.record(),
            }
            for field, record in expected_records.items():
                if verification.get(field) != record:
                    raise EvidenceFailure(
                        f"Android signing gate {field} differs from its supplied input"
                    )
            if verification.get("unsigned_apk_sha256") != unsigned_apk.sha256:
                raise EvidenceFailure("Android signing gate unsigned APK differs from the Full-RC input")

        canonical_receipt = (
            json.dumps(verification, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        ).encode("utf-8")
        with FileSnapshot.capture(
            signing_gate_receipt_path,
            context="Android signing gate receipt",
            display_path=signing_gate_receipt_path.name,
            max_bytes=1024 * 1024,
        ) as signing_receipt:
            receipt_payload = strict_json_snapshot(
                signing_receipt,
                context="Android signing gate receipt",
            )
            if receipt_payload != verification:
                raise EvidenceFailure(
                    "Android signing gate receipt fields differ from the recomputed result"
                )
            if signing_receipt.read_bytes() != canonical_receipt:
                raise EvidenceFailure(
                    "Android signing gate receipt canonical bytes differ from the recomputed result"
                )
            if not signing_receipt.matches_path(signing_gate_receipt_path):
                raise EvidenceFailure("Android signing gate receipt changed during verification")
            receipt_record = signing_receipt.record(path=signing_gate_receipt_path.name)
    except ReleaseIntegrityError as exc:
        raise EvidenceFailure(f"Android signing gate receipt/input validation failed: {exc}") from exc
    return {"verification": verification, "receipt": receipt_record}


def validate_production_blocker_resolution(
    *,
    receipt_path: Path,
    signature_path: Path,
    manifest_path: Path,
    source_commit: str,
    release_artifacts_sha256: str,
    full_rc_signing_chain: dict[str, Any],
    expected_operator_fingerprint: str,
    gpg_path: Path,
    expected_gpg_sha256: str,
    gpg_keyring_path: Path,
    now: datetime,
    max_age: timedelta,
) -> dict[str, Any]:
    verification = full_rc_signing_chain.get("verification")
    if not isinstance(verification, dict):
        raise EvidenceFailure("blocker resolution requires the verified Full-RC signing chain")
    receipt_original = receipt_path.expanduser().absolute()
    signature_original = signature_path.expanduser().absolute()
    if (
        receipt_original.resolve() != receipt_original
        or signature_original.resolve() != signature_original
    ):
        raise EvidenceFailure("blocker resolution receipt/signature paths must not contain symlinks")
    try:
        with FileSnapshot.capture(
            manifest_path,
            context="Full-RC manifest for blocker resolution",
            display_path=manifest_path.name,
            max_bytes=16 * 1024 * 1024,
        ) as manifest_snapshot:
            manifest = strict_json_snapshot(
                manifest_snapshot,
                context="Full-RC manifest for blocker resolution",
            )
            if (
                not isinstance(manifest, dict)
                or manifest_snapshot.record(path=manifest_path.name)
                != verification.get("full_rc_manifest")
            ):
                raise EvidenceFailure("blocker resolution Full-RC manifest binding is invalid")
            external_inputs = manifest.get("external_runtime_inputs")
            if not isinstance(external_inputs, list):
                raise EvidenceFailure("Full-RC external runtime blocker contract is missing")
            requirements: dict[str, str] = {}
            for entry in external_inputs:
                if (
                    not isinstance(entry, dict)
                    or set(entry) != {"id", "included", "required"}
                    or entry.get("included") is not False
                    or not isinstance(entry.get("id"), str)
                    or not meaningful_text(entry.get("required"))
                    or entry["id"] in requirements
                ):
                    raise EvidenceFailure("Full-RC external runtime blocker contract is malformed")
                requirements[entry["id"]] = entry["required"]
            if set(requirements) != set(PRODUCTION_BLOCKER_CHECKS):
                raise EvidenceFailure("Full-RC external runtime blocker set is not the exact production set")

        with FileSnapshot.capture(
            receipt_original,
            context="production blocker resolution receipt",
            display_path=receipt_original.name,
            max_bytes=2 * 1024 * 1024,
        ) as receipt, FileSnapshot.capture(
            signature_original,
            context="production blocker resolution detached signature",
            display_path=signature_original.name,
            max_bytes=1024 * 1024,
        ) as signature:
            resolution = strict_json_snapshot(
                receipt,
                context="production blocker resolution receipt",
            )
            if not isinstance(resolution, dict) or set(resolution) != {
                "schema_version",
                "source_commit",
                "full_rc_manifest",
                "full_rc_closure_sha256",
                "release_artifacts_sha256",
                "verified_at",
                "operator",
                "blocker_ids",
                "resolutions",
                "all_blockers_resolved",
            }:
                raise EvidenceFailure("production blocker resolution receipt fields are invalid")
            canonical = (
                json.dumps(resolution, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
            ).encode("utf-8")
            if receipt.read_bytes() != canonical:
                raise EvidenceFailure("production blocker resolution receipt is not canonical JSON")
            try:
                (
                    primary_fingerprint,
                    signing_fingerprint,
                    gpg_record,
                    keyring_record,
                ) = verify_approved_detached_signature(
                    attestation=receipt,
                    signature=signature,
                    expected_fingerprint=expected_operator_fingerprint,
                    gpg_path=gpg_path,
                    expected_gpg_sha256=expected_gpg_sha256,
                    gpg_keyring_path=gpg_keyring_path,
                )
            except AttestationVerificationError as exc:
                raise EvidenceFailure(
                    f"production blocker resolution signature verification failed: {exc}"
                ) from exc
            if (
                primary_fingerprint != verification.get("operator_primary_fingerprint")
                or gpg_record != verification.get("gpg")
                or keyring_record != verification.get("gpg_keyring")
            ):
                raise EvidenceFailure(
                    "production blocker resolution used a different approved reviewer trust chain"
                )
            blocker_ids = sorted(requirements)
            if (
                resolution.get("schema_version")
                != "walksafe.production-blocker-resolution.v1"
                or resolution.get("source_commit") != source_commit
                or resolution.get("full_rc_manifest") != verification.get("full_rc_manifest")
                or resolution.get("full_rc_closure_sha256")
                != verification.get("full_rc_closure_sha256")
                or resolution.get("release_artifacts_sha256") != release_artifacts_sha256
                or resolution.get("blocker_ids") != blocker_ids
                or resolution.get("all_blockers_resolved") is not True
                or not meaningful_text(resolution.get("operator"))
            ):
                raise EvidenceFailure(
                    "production blocker resolution does not bind the exact release contract"
                )
            verified_at = parse_timestamp(
                resolution.get("verified_at"),
                "production blocker resolution.verified_at",
            )
            if verified_at > now + timedelta(minutes=5) or now - verified_at > max_age:
                raise EvidenceFailure("production blocker resolution receipt is future-dated or stale")
            resolutions = resolution.get("resolutions")
            if not isinstance(resolutions, list) or len(resolutions) != len(blocker_ids):
                raise EvidenceFailure("production blocker resolution list is incomplete")
            by_id: dict[str, dict[str, Any]] = {}
            evidence_paths: set[Path] = set()
            evidence_root = receipt_original.parent.resolve()
            for entry in resolutions:
                if not isinstance(entry, dict) or set(entry) != {
                    "blocker_id",
                    "required",
                    "status",
                    "checks",
                    "evidence",
                }:
                    raise EvidenceFailure("production blocker resolution entry fields are invalid")
                blocker_id = entry.get("blocker_id")
                if not isinstance(blocker_id, str) or blocker_id in by_id:
                    raise EvidenceFailure("production blocker resolution ids are invalid or duplicated")
                by_id[blocker_id] = entry
                if entry.get("required") != requirements.get(blocker_id) or entry.get("status") != "resolved":
                    raise EvidenceFailure(f"production blocker is not explicitly resolved: {blocker_id}")
                expected_checks = PRODUCTION_BLOCKER_CHECKS.get(blocker_id)
                checks = entry.get("checks")
                if (
                    expected_checks is None
                    or not isinstance(checks, dict)
                    or set(checks) != set(expected_checks)
                    or any(checks[check] is not True for check in expected_checks)
                ):
                    raise EvidenceFailure(f"production blocker checks are incomplete: {blocker_id}")
                evidence = entry.get("evidence")
                if not isinstance(evidence, list) or not 1 <= len(evidence) <= 20:
                    raise EvidenceFailure(f"production blocker evidence is missing: {blocker_id}")
                for index, record in enumerate(evidence):
                    if not isinstance(record, dict) or set(record) != {"path", "bytes", "sha256"}:
                        raise EvidenceFailure(
                            f"production blocker evidence record is invalid: {blocker_id}"
                        )
                    raw_path = record.get("path")
                    relative = Path(raw_path) if isinstance(raw_path, str) else None
                    if (
                        relative is None
                        or relative.is_absolute()
                        or ".." in relative.parts
                        or not meaningful_text(raw_path)
                    ):
                        raise EvidenceFailure(
                            f"production blocker evidence path is unsafe: {blocker_id}"
                        )
                    evidence_path = _bound_regular_file(
                        raw_path,
                        record.get("sha256"),
                        base_dir=evidence_root,
                        context=f"production blocker evidence {blocker_id}[{index}]",
                    )
                    try:
                        evidence_path.relative_to(evidence_root)
                    except ValueError as exc:
                        raise EvidenceFailure(
                            f"production blocker evidence escapes its receipt root: {blocker_id}"
                        ) from exc
                    if evidence_path in {receipt_original, signature_original}:
                        raise EvidenceFailure(
                            "production blocker evidence must be separate from its receipt/signature"
                        )
                    if evidence_path in evidence_paths:
                        raise EvidenceFailure("production blocker evidence files must be unique")
                    evidence_paths.add(evidence_path)
                    with FileSnapshot.capture(
                        evidence_path,
                        context=f"production blocker evidence {blocker_id}[{index}]",
                        display_path=raw_path,
                    ) as evidence_snapshot:
                        if evidence_snapshot.record(path=raw_path) != record:
                            raise EvidenceFailure(
                                f"production blocker evidence bytes differ: {blocker_id}"
                            )
                        if not evidence_snapshot.matches_path(evidence_path):
                            raise EvidenceFailure(
                                f"production blocker evidence changed: {blocker_id}"
                            )
            if set(by_id) != set(blocker_ids):
                raise EvidenceFailure("production blocker resolution ids differ from the Full-RC manifest")
            if not receipt.matches_path(receipt_original) or not signature.matches_path(signature_original):
                raise EvidenceFailure("production blocker resolution receipt/signature changed")
            receipt_record = receipt.record(path=receipt_original.name)
            signature_record = signature.record(path=signature_original.name)
    except ReleaseIntegrityError as exc:
        raise EvidenceFailure(f"production blocker resolution validation failed: {exc}") from exc
    return {
        "schema_version": "walksafe.production-blocker-resolution-verification.v1",
        "verification_scope": "approved-reviewer-signed-operational-attestation-integrity.v1",
        "receipt": receipt_record,
        "detached_signature": signature_record,
        "operator_primary_fingerprint": primary_fingerprint,
        "operator_signing_fingerprint": signing_fingerprint,
        "gpg": gpg_record,
        "gpg_keyring": keyring_record,
        "blocker_ids": blocker_ids,
        "all_blockers_resolved": True,
        "verified": True,
    }


def publish_production_release_receipt(
    *,
    output_path: Path,
    release_id: str,
    source_commit: str,
    release_artifacts: dict[str, Any],
    release_artifacts_sha256: str,
    verified_checks: dict[str, Any],
    verified_operations: dict[str, Any],
    full_rc_signing_chain: dict[str, Any],
    blocker_resolution: dict[str, Any],
    protected_input_roots: tuple[Path, ...] = (),
    repository_root: Path = ROOT,
) -> dict[str, Any]:
    output = output_path.expanduser().absolute()
    source_root = repository_root.expanduser().absolute().resolve()
    if output == source_root or source_root in output.parents:
        raise EvidenceFailure("production receipt must be published outside the validated source")
    for protected in protected_input_roots:
        protected_root = protected.expanduser().absolute().resolve()
        if output == protected_root or protected_root in output.parents:
            raise EvidenceFailure(
                "production receipt must be outside immutable Full-RC/validation/signing input roots"
            )
    if not meaningful_text(release_id):
        raise EvidenceFailure("production release_id is invalid")
    expected_artifact_binding = hashlib.sha256(
        json.dumps(
            release_artifacts,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    if release_artifacts_sha256 != expected_artifact_binding:
        raise EvidenceFailure("production release artifact binding is inconsistent")
    verification = full_rc_signing_chain.get("verification")
    signing_receipt = full_rc_signing_chain.get("receipt")
    if (
        set(full_rc_signing_chain) != {"verification", "receipt"}
        or not isinstance(verification, dict)
        or verification.get("schema_version") != "walksafe.android-signing-gate.v2"
        or verification.get("source_commit") != source_commit
        or verification.get("android_signing_gate_passed") is not True
        or verification.get("deployment_complete") is not False
        or verification.get("verified") is not True
        or not isinstance(signing_receipt, dict)
        or set(signing_receipt) != {"path", "bytes", "sha256"}
        or not meaningful_text(signing_receipt.get("path"))
        or not isinstance(signing_receipt.get("bytes"), int)
        or isinstance(signing_receipt.get("bytes"), bool)
        or signing_receipt["bytes"] <= 0
        or not isinstance(signing_receipt.get("sha256"), str)
        or SHA256.fullmatch(signing_receipt["sha256"]) is None
    ):
        raise EvidenceFailure("production release signing chain is incomplete")
    if (
        not isinstance(blocker_resolution, dict)
        or set(blocker_resolution) != {
            "schema_version",
            "verification_scope",
            "receipt",
            "detached_signature",
            "operator_primary_fingerprint",
            "operator_signing_fingerprint",
            "gpg",
            "gpg_keyring",
            "blocker_ids",
            "all_blockers_resolved",
            "verified",
        }
        or blocker_resolution.get("schema_version")
        != "walksafe.production-blocker-resolution-verification.v1"
        or blocker_resolution.get("verification_scope")
        != "approved-reviewer-signed-operational-attestation-integrity.v1"
        or blocker_resolution.get("blocker_ids") != sorted(PRODUCTION_BLOCKER_CHECKS)
        or blocker_resolution.get("all_blockers_resolved") is not True
        or blocker_resolution.get("verified") is not True
        or blocker_resolution.get("operator_primary_fingerprint")
        != verification.get("operator_primary_fingerprint")
        or blocker_resolution.get("gpg") != verification.get("gpg")
        or blocker_resolution.get("gpg_keyring") != verification.get("gpg_keyring")
    ):
        raise EvidenceFailure("production release blocker resolution is incomplete")
    try:
        source = verify_exact_git_source(
            source_root,
            context="production release source",
            expected_commit=source_commit,
            allowed_ignored_roots=RELEASE_IGNORED_OUTPUT_ROOTS,
        )
    except ReleaseIntegrityError as exc:
        raise EvidenceFailure(f"production release source exact validation failed: {exc}") from exc
    production = {
        "schema_version": "walksafe.production-release.v1",
        "release_id": release_id,
        "deployment_complete": True,
        "blocker_ids": [],
        "source": {
            "commit": source.commit,
            "tree": source.tree,
            "inventory": source.inventory,
            "worktree_clean": True,
        },
        "release_artifacts": release_artifacts,
        "release_artifacts_sha256": release_artifacts_sha256,
        "full_rc_signing_chain": full_rc_signing_chain,
        "blocker_resolution": blocker_resolution,
        "verified_checks": verified_checks,
        "verified_operations": verified_operations,
    }
    rendered = json.dumps(production, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    try:
        exclusive_atomic_publish(output, rendered.encode("utf-8"), mode=0o600)
    except ReleaseIntegrityError as exc:
        raise EvidenceFailure(f"production receipt publication failed: {exc}") from exc
    return production


def validate_running_web_build(web_artifact: dict[str, Any]) -> dict[str, str]:
    import httpx

    raw_url = os.environ.get("WALKSAFE_WEB_DEPLOYMENT_URL", "").strip()
    parsed = urlparse(raw_url)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
    ):
        raise EvidenceFailure("WALKSAFE_WEB_DEPLOYMENT_URL must be one exact root HTTPS origin")
    probe_path = web_artifact.get("deployment_probe_path")
    expected_sha256 = web_artifact.get("deployment_probe_sha256")
    identity_path = web_artifact.get("source_identity_path")
    expected_commit = web_artifact.get("source_commit")
    if (
        not isinstance(probe_path, str)
        or not isinstance(expected_sha256, str)
        or identity_path != "/api/release-identity"
        or not isinstance(expected_commit, str)
        or re.fullmatch(r"[0-9a-f]{40}", expected_commit) is None
    ):
        raise EvidenceFailure("web release artifact is missing its deployment probe binding")
    probe_url = raw_url.rstrip("/") + probe_path
    identity_url = raw_url.rstrip("/") + identity_path
    try:
        response = httpx.get(probe_url, timeout=5.0, follow_redirects=False)
        response.raise_for_status()
        identity_response = httpx.get(identity_url, timeout=5.0, follow_redirects=False)
        identity_response.raise_for_status()
    except httpx.HTTPError as exc:
        raise EvidenceFailure("running Web build probe failed") from exc
    if hashlib.sha256(response.content).hexdigest() != expected_sha256:
        raise EvidenceFailure("running Web deployment does not match the release build artifact")
    try:
        identity = identity_response.json()
    except ValueError as exc:
        raise EvidenceFailure("running Web source identity response is not valid JSON") from exc
    if identity != {
        "schema_version": "walksafe.web-release-identity.v1",
        "status": "ready",
        "source_commit": expected_commit,
    }:
        raise EvidenceFailure("running Web source identity does not match the release source")
    cache_control = identity_response.headers.get("cache-control", "")
    if "no-store" not in {value.strip().lower() for value in cache_control.split(",")}:
        raise EvidenceFailure("running Web source identity must be served with Cache-Control: no-store")
    return {
        "url": raw_url.rstrip("/"),
        "probe_sha256": expected_sha256,
        "source_commit": expected_commit,
    }


def _required_android_sdk_tool(
    *,
    path_environment: str,
    sha256_environment: str,
    label: str,
) -> FileSnapshot:
    raw = os.environ.get(path_environment, "").strip()
    if not raw:
        raise EvidenceFailure(f"{path_environment} must identify the trusted Android SDK {label}")
    binary = Path(raw).expanduser()
    if (
        not binary.is_absolute()
        or binary.is_symlink()
        or not binary.is_file()
        or binary.resolve() != binary
        or not os.access(binary, os.X_OK)
    ):
        raise EvidenceFailure(f"{path_environment} must be an absolute executable canonical regular file")
    expected_sha256 = os.environ.get(sha256_environment, "").strip().lower()
    if SHA256.fullmatch(expected_sha256) is None:
        raise EvidenceFailure(f"{sha256_environment} must configure the approved Android SDK {label} SHA-256")
    try:
        snapshot = FileSnapshot.capture(binary, context=f"Android SDK {label}")
    except ReleaseIntegrityError as exc:
        raise EvidenceFailure(f"Android SDK {label} cannot be snapshotted") from exc
    if snapshot.sha256 != expected_sha256:
        snapshot.close()
        raise EvidenceFailure(f"Android SDK {label} SHA-256 does not match its approval pin")
    return snapshot


def _required_java_runtime() -> FileSnapshot:
    java = _required_android_sdk_tool(
        path_environment="WALKSAFE_JAVA_BIN",
        sha256_environment="WALKSAFE_JAVA_SHA256",
        label="Java runtime",
    )
    if java.source_path.name != "java" or java.source_path.parent.name != "bin":
        java.close()
        raise EvidenceFailure("WALKSAFE_JAVA_BIN must identify a canonical JAVA_HOME/bin/java")
    try:
        _validate_system_managed_java_home(java.source_path.parent.parent)
    except EvidenceFailure:
        java.close()
        raise
    return java


def _validate_system_managed_java_home(java_home: Path) -> None:
    if java_home.resolve() != java_home or java_home.is_symlink() or not java_home.is_dir():
        raise EvidenceFailure("JAVA_HOME must be a canonical system-managed directory")
    paths = [java_home, *java_home.rglob("*")]
    for path in paths:
        metadata = path.lstat()
        if metadata.st_uid != 0:
            raise EvidenceFailure("JAVA_HOME must be owned by the system administrator")
        if path.is_symlink():
            try:
                target = path.resolve(strict=True)
                target_metadata = target.stat()
            except OSError:
                target = path.resolve(strict=False)
                existing_parent = target.parent
                while not existing_parent.exists() and existing_parent != existing_parent.parent:
                    existing_parent = existing_parent.parent
                parent_metadata = existing_parent.stat(follow_symlinks=False)
                if parent_metadata.st_uid != 0 or parent_metadata.st_mode & 0o022:
                    raise EvidenceFailure("JAVA_HOME contains an unsafe unresolved symlink")
                continue
            if target_metadata.st_uid != 0 or target_metadata.st_mode & 0o022:
                raise EvidenceFailure("JAVA_HOME symlink target is not system-managed")
        elif metadata.st_mode & 0o022:
            raise EvidenceFailure("JAVA_HOME contains a group/world-writable path")
    for parent in (java_home, *java_home.parents):
        metadata = parent.stat(follow_symlinks=False)
        if metadata.st_uid != 0 or metadata.st_mode & 0o022:
            raise EvidenceFailure("JAVA_HOME ancestry is not system-managed")


def _path_identity(path: Path) -> tuple[int, int, int, int, int, int, int, int]:
    metadata = path.stat(follow_symlinks=False)
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _snapshot_matches_source(snapshot: FileSnapshot) -> bool:
    try:
        metadata = snapshot.source_path.stat(follow_symlinks=False)
    except OSError:
        return False
    return stat.S_ISREG(metadata.st_mode) and stat.S_IMODE(metadata.st_mode) == snapshot.mode and (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    ) == snapshot.source_identity


@contextmanager
def _private_named_snapshot(snapshot: FileSnapshot, *, filename: str) -> Iterator[FileSnapshot]:
    with tempfile.TemporaryDirectory(prefix="walksafe-private-snapshot-") as temporary_name:
        root = Path(temporary_name).absolute()
        metadata = root.stat(follow_symlinks=False)
        if (
            root.resolve() != root
            or root.is_symlink()
            or not stat.S_ISDIR(metadata.st_mode)
            or metadata.st_uid != os.getuid()
            or stat.S_IMODE(metadata.st_mode) != 0o700
        ):
            raise EvidenceFailure("private analyzer snapshot directory is unsafe")
        output = root / filename
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(output, flags, 0o400)
        try:
            with os.fdopen(descriptor, "wb") as target, snapshot.open_reader() as source:
                descriptor = -1
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    target.write(chunk)
                target.flush()
                os.fsync(target.fileno())
        finally:
            if descriptor >= 0:
                os.close(descriptor)
        try:
            named = FileSnapshot.capture(output, context="private analyzer APK snapshot")
        except ReleaseIntegrityError as exc:
            raise EvidenceFailure("private analyzer APK snapshot cannot be captured") from exc
        try:
            if (named.size, named.sha256) != (snapshot.size, snapshot.sha256):
                raise EvidenceFailure("private analyzer APK snapshot differs from the verified APK")
            root_identity = _path_identity(root)
            yield named
            if _path_identity(root) != root_identity or not _snapshot_matches_source(named):
                raise EvidenceFailure("private analyzer APK snapshot changed during validation")
        finally:
            named.close()


def _directory_identities(root: Path) -> dict[str, tuple[int, int, int, int, int, int, int, int]]:
    if root.resolve() != root or root.is_symlink() or not root.is_dir():
        raise EvidenceFailure("Android SDK support directory must be a canonical real directory")
    identities = {".": _path_identity(root)}
    for path in sorted(root.rglob("*"), key=lambda item: item.relative_to(root).as_posix()):
        if path.is_symlink():
            raise EvidenceFailure("Android SDK support directory contains a symlink")
        metadata = path.stat(follow_symlinks=False)
        if not (stat.S_ISDIR(metadata.st_mode) or stat.S_ISREG(metadata.st_mode)):
            raise EvidenceFailure("Android SDK support directory contains a special file")
        identities[path.relative_to(root).as_posix()] = _path_identity(path)
    return identities


@dataclass(frozen=True)
class _DirectoryClosure:
    root: Path
    sha256: str
    identities: dict[str, tuple[int, int, int, int, int, int, int, int]]

    def matches_path(self) -> bool:
        try:
            return _directory_identities(self.root) == self.identities
        except (OSError, EvidenceFailure):
            return False


def _capture_android_support_closure(root: Path) -> _DirectoryClosure:
    root = root.expanduser().absolute()
    before = _directory_identities(root)
    records: list[dict[str, Any]] = []
    for relative, identity in before.items():
        path = root if relative == "." else root / relative
        mode = stat.S_IMODE(identity[2])
        if stat.S_ISDIR(identity[2]):
            records.append({"path": relative, "kind": "directory", "mode": mode})
            continue
        try:
            with FileSnapshot.capture(path, context="Android SDK support file") as snapshot:
                if snapshot.source_identity != (
                    identity[0],
                    identity[1],
                    identity[5],
                    identity[6],
                    identity[7],
                ):
                    raise EvidenceFailure("Android SDK support file changed while being hashed")
                records.append(
                    {
                        "path": relative,
                        "kind": "file",
                        "mode": mode,
                        "bytes": snapshot.size,
                        "sha256": snapshot.sha256,
                    }
                )
        except ReleaseIntegrityError as exc:
            raise EvidenceFailure("Android SDK support file cannot be snapshotted") from exc
    after = _directory_identities(root)
    if before != after:
        raise EvidenceFailure("Android SDK support closure changed while being hashed")
    digest = hashlib.sha256(
        json.dumps(records, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    return _DirectoryClosure(root=root, sha256=digest, identities=before)


def _required_android_support_closure(tool: FileSnapshot, *, label: str) -> _DirectoryClosure:
    if label == "apksigner":
        root = tool.source_path.parent / "lib"
        required_entry = "apksigner.jar"
        sha256_environment = "WALKSAFE_APKSIGNER_SUPPORT_SHA256"
    elif label == "apkanalyzer":
        root = tool.source_path.parent.parent / "lib"
        required_entry = "apkanalyzer-classpath.jar"
        sha256_environment = "WALKSAFE_APKANALYZER_SUPPORT_SHA256"
    else:
        raise EvidenceFailure("unknown Android SDK support closure")
    closure = _capture_android_support_closure(root)
    required_identity = closure.identities.get(required_entry)
    if required_identity is None or not stat.S_ISREG(required_identity[2]):
        raise EvidenceFailure(f"Android SDK {label} support closure is incomplete")
    expected = os.environ.get(sha256_environment, "").strip().lower()
    if SHA256.fullmatch(expected) is None:
        raise EvidenceFailure(f"{sha256_environment} must configure the approved Android SDK support closure")
    if closure.sha256 != expected:
        raise EvidenceFailure(f"Android SDK {label} support closure does not match its approval pin")
    return closure


def android_tool_pin_records(
    *,
    java_path: Path,
    apksigner_path: Path,
    apkanalyzer_path: Path,
) -> dict[str, str]:
    paths = {
        "WALKSAFE_JAVA_BIN": java_path.expanduser().absolute(),
        "WALKSAFE_APKSIGNER_BIN": apksigner_path.expanduser().absolute(),
        "WALKSAFE_APKANALYZER_BIN": apkanalyzer_path.expanduser().absolute(),
    }
    snapshots: dict[str, FileSnapshot] = {}
    try:
        for environment_name, path in paths.items():
            if (
                path.resolve() != path
                or path.is_symlink()
                or not path.is_file()
                or not os.access(path, os.X_OK)
            ):
                raise EvidenceFailure(f"{environment_name} input must be a canonical executable regular file")
            snapshots[environment_name] = FileSnapshot.capture(
                path,
                context=f"{environment_name} input",
            )
        java = snapshots["WALKSAFE_JAVA_BIN"]
        if java.source_path.name != "java" or java.source_path.parent.name != "bin":
            raise EvidenceFailure("WALKSAFE_JAVA_BIN input must identify JAVA_HOME/bin/java")
        _validate_system_managed_java_home(java.source_path.parent.parent)
        apksigner_support = _capture_android_support_closure(
            snapshots["WALKSAFE_APKSIGNER_BIN"].source_path.parent / "lib"
        )
        apkanalyzer_support = _capture_android_support_closure(
            snapshots["WALKSAFE_APKANALYZER_BIN"].source_path.parent.parent / "lib"
        )
        if "apksigner.jar" not in apksigner_support.identities:
            raise EvidenceFailure("apksigner support closure is missing apksigner.jar")
        if "apkanalyzer-classpath.jar" not in apkanalyzer_support.identities:
            raise EvidenceFailure("apkanalyzer support closure is missing apkanalyzer-classpath.jar")
        return {
            "WALKSAFE_JAVA_BIN": str(java.source_path),
            "WALKSAFE_JAVA_SHA256": java.sha256,
            "WALKSAFE_APKSIGNER_BIN": str(snapshots["WALKSAFE_APKSIGNER_BIN"].source_path),
            "WALKSAFE_APKSIGNER_SHA256": snapshots["WALKSAFE_APKSIGNER_BIN"].sha256,
            "WALKSAFE_APKSIGNER_SUPPORT_SHA256": apksigner_support.sha256,
            "WALKSAFE_APKANALYZER_BIN": str(snapshots["WALKSAFE_APKANALYZER_BIN"].source_path),
            "WALKSAFE_APKANALYZER_SHA256": snapshots["WALKSAFE_APKANALYZER_BIN"].sha256,
            "WALKSAFE_APKANALYZER_SUPPORT_SHA256": apkanalyzer_support.sha256,
        }
    except ReleaseIntegrityError as exc:
        raise EvidenceFailure("Android tool pin input cannot be snapshotted") from exc
    finally:
        for snapshot in snapshots.values():
            snapshot.close()


def _run_android_sdk_tool(
    tool: FileSnapshot,
    java: FileSnapshot,
    support: _DirectoryClosure,
    artifact: FileSnapshot,
    arguments: list[str],
    context: str,
    extra_snapshots: tuple[FileSnapshot, ...] = (),
) -> str:
    if (
        not tool.matches_path()
        or not java.matches_path()
        or not support.matches_path()
        or not _snapshot_matches_source(artifact)
        or any(not _snapshot_matches_source(snapshot) for snapshot in extra_snapshots)
    ):
        raise EvidenceFailure(f"Android SDK tool changed before APK {context} validation")
    command = [str(tool.source_path), *arguments]
    result = subprocess.run(
        command,
        capture_output=True,
        text=True,
        check=False,
        env={
            "HOME": pwd.getpwuid(os.getuid()).pw_dir,
            "LANG": "C",
            "LC_ALL": "C",
            "JAVA_HOME": str(java.source_path.parent.parent),
            "PATH": f"{java.source_path.parent}:/usr/bin:/bin",
        },
        pass_fds=(artifact.fd, *(snapshot.fd for snapshot in extra_snapshots)),
    )
    if (
        not tool.matches_path()
        or not java.matches_path()
        or not support.matches_path()
        or not _snapshot_matches_source(artifact)
        or any(not _snapshot_matches_source(snapshot) for snapshot in extra_snapshots)
    ):
        raise EvidenceFailure(f"Android SDK tool changed during APK {context} validation")
    if result.returncode != 0:
        raise EvidenceFailure(f"Android APK {context} validation failed")
    return result.stdout.strip()


def _zip_entry_is_symlink(entry: zipfile.ZipInfo) -> bool:
    return stat.S_ISLNK((entry.external_attr >> 16) & 0xFFFF)


def _zip_entry_contains(entry: zipfile.ZipInfo, archive: zipfile.ZipFile, needle: bytes) -> bool:
    tail = b""
    with archive.open(entry, "r") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            combined = tail + chunk
            if needle in combined:
                return True
            tail = combined[-max(0, len(needle) - 1) :]
    return False


def _validate_android_apk_signature(snapshot: FileSnapshot) -> str:
    with _required_java_runtime() as java, _required_android_sdk_tool(
        path_environment="WALKSAFE_APKSIGNER_BIN",
        sha256_environment="WALKSAFE_APKSIGNER_SHA256",
        label="apksigner",
    ) as apksigner:
        apksigner_support = _required_android_support_closure(apksigner, label="apksigner")
        try:
            apksigner_jar = FileSnapshot.capture(
                apksigner_support.root / "apksigner.jar",
                context="Android SDK apksigner JAR",
            )
        except ReleaseIntegrityError as exc:
            raise EvidenceFailure("Android SDK apksigner JAR cannot be snapshotted") from exc
        with apksigner_jar:
            output = _run_android_sdk_tool(
                java,
                java,
                apksigner_support,
                snapshot,
                [
                    "-jar",
                    apksigner_jar.proc_path,
                    "verify",
                    "--verbose",
                    "--print-certs",
                    snapshot.proc_path,
                ],
                "signature",
                extra_snapshots=(apksigner_jar,),
            )
    if re.search(r"Verified using v(?:2|3) scheme[^:]*:\s*true", output, re.IGNORECASE) is None:
        raise EvidenceFailure("Android APK must verify with APK Signature Scheme v2 or v3")
    signer_count = re.search(r"Number of signers:\s*(\d+)", output, re.IGNORECASE)
    if signer_count is None or signer_count.group(1) != "1":
        raise EvidenceFailure("Android APK must have exactly one signer")
    digests = {
        match.replace(":", "").lower()
        for match in re.findall(
            r"Signer #\d+ certificate SHA-256 digest:\s*([0-9A-Fa-f:]{64,95})",
            output,
        )
    }
    if len(digests) != 1 or SHA256.fullmatch(next(iter(digests))) is None:
        raise EvidenceFailure("Android APK signer certificate SHA-256 is invalid")
    return next(iter(digests))


def _validate_android_apk_manifest(
    apk: Path,
    snapshot: FileSnapshot,
    *,
    expected_debuggable: str,
) -> dict[str, str]:
    with _required_java_runtime() as java, _required_android_sdk_tool(
        path_environment="WALKSAFE_APKANALYZER_BIN",
        sha256_environment="WALKSAFE_APKANALYZER_SHA256",
        label="apkanalyzer",
    ) as apkanalyzer:
        apkanalyzer_support = _required_android_support_closure(apkanalyzer, label="apkanalyzer")
        with _private_named_snapshot(snapshot, filename=apk.name) as analyzer_apk:
            analyzer_extra = (analyzer_apk,)
            summary = _run_android_sdk_tool(
                apkanalyzer,
                java,
                apkanalyzer_support,
                snapshot,
                ["apk", "summary", str(analyzer_apk.source_path)],
                "manifest summary",
                extra_snapshots=analyzer_extra,
            )
            summary_fields = summary.split("\t")
            if len(summary_fields) != 3 or summary_fields[0] != "kr.co.hanium.dreamup.walksafe":
                raise EvidenceFailure("Android APK application id is not the WalkSafe package")
            version_code, version_name = summary_fields[1:]
            if not version_code.isdigit() or int(version_code) <= 0 or not meaningful_text(version_name):
                raise EvidenceFailure("Android APK version code/name is invalid")
            min_sdk_text = _run_android_sdk_tool(
                apkanalyzer,
                java,
                apkanalyzer_support,
                snapshot,
                ["manifest", "min-sdk", str(analyzer_apk.source_path)],
                "min SDK",
                extra_snapshots=analyzer_extra,
            )
            target_sdk_text = _run_android_sdk_tool(
                apkanalyzer,
                java,
                apkanalyzer_support,
                snapshot,
                ["manifest", "target-sdk", str(analyzer_apk.source_path)],
                "target SDK",
                extra_snapshots=analyzer_extra,
            )
            if not min_sdk_text.isdigit() or int(min_sdk_text) != 26:
                raise EvidenceFailure("Android APK min SDK does not match the reviewed WalkSafe contract")
            if not target_sdk_text.isdigit() or int(target_sdk_text) < 35:
                raise EvidenceFailure("Android APK target SDK is below the reviewed WalkSafe contract")
            debuggable_text = _run_android_sdk_tool(
                apkanalyzer,
                java,
                apkanalyzer_support,
                snapshot,
                ["manifest", "debuggable", str(analyzer_apk.source_path)],
                "debuggable flag",
                extra_snapshots=analyzer_extra,
            ).lower()
            if debuggable_text != expected_debuggable:
                raise EvidenceFailure(
                    f"Android APK must have effective android:debuggable={expected_debuggable}"
                )
            dex_listing = _run_android_sdk_tool(
                apkanalyzer,
                java,
                apkanalyzer_support,
                snapshot,
                ["dex", "list", str(analyzer_apk.source_path)],
                "DEX structure",
                extra_snapshots=analyzer_extra,
            )
            if "classes.dex" not in dex_listing.splitlines():
                raise EvidenceFailure("Android APK analyzer did not find a valid primary classes.dex")
    return {
        "application_id": summary_fields[0],
        "version_code": version_code,
        "version_name": version_name,
        "min_sdk": min_sdk_text,
        "target_sdk": target_sdk_text,
        "debuggable": debuggable_text,
    }


def _validate_android_field_apk_sdk(apk: Path, snapshot: FileSnapshot) -> dict[str, str]:
    signer_certificate_sha256 = _validate_android_apk_signature(snapshot)
    return {
        "signer_certificate_sha256": signer_certificate_sha256,
        **_validate_android_apk_manifest(apk, snapshot, expected_debuggable="true"),
    }


def validate_android_apk(
    apk: Path,
    *,
    source_commit: str,
    signer_certificate_sha256: str,
    expected_sha256: str | None = None,
) -> dict[str, str]:
    try:
        snapshot = FileSnapshot.capture(apk, context="Android APK", display_path=apk.name)
    except ReleaseIntegrityError as exc:
        raise EvidenceFailure("Android APK cannot be snapshotted") from exc
    try:
        if expected_sha256 is not None and snapshot.sha256 != expected_sha256:
            raise EvidenceFailure("Android APK changed after its evidence hash was checked")
        result = _validate_android_apk_snapshot(
            apk,
            snapshot=snapshot,
            source_commit=source_commit,
            signer_certificate_sha256=signer_certificate_sha256,
        )
        if not _snapshot_matches_source(snapshot):
            raise EvidenceFailure("Android APK changed during validation")
        return result
    finally:
        snapshot.close()


def validate_android_field_apk(
    apk: Path,
    *,
    source_commit: str,
    expected_sha256: str,
) -> dict[str, Any]:
    """Bind a debug research APK without treating it as the signed release artifact."""

    try:
        snapshot = FileSnapshot.capture(apk, context="Android field APK", display_path=apk.name)
    except ReleaseIntegrityError as exc:
        raise EvidenceFailure("Android field APK cannot be snapshotted") from exc
    try:
        if snapshot.sha256 != expected_sha256:
            raise EvidenceFailure("Android field APK differs from its receipt SHA-256")
        try:
            with snapshot.open_reader() as apk_reader, zipfile.ZipFile(apk_reader) as archive:
                entries = archive.infolist()
                names = [entry.filename for entry in entries]
                if len(names) != len(set(names)):
                    raise EvidenceFailure("Android field APK contains duplicate ZIP entry names")
                for entry in entries:
                    entry_path = Path(entry.filename)
                    if entry_path.is_absolute() or ".." in entry_path.parts or _zip_entry_is_symlink(entry):
                        raise EvidenceFailure("Android field APK contains an unsafe ZIP entry")
                if "AndroidManifest.xml" not in names:
                    raise EvidenceFailure("Android field APK is missing AndroidManifest.xml")
                dex_entries = {
                    entry.filename: entry
                    for entry in entries
                    if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", entry.filename)
                }
                root_dex_like_names = {
                    name
                    for name in names
                    if "/" not in name and name.startswith("classes") and name.endswith(".dex")
                }
                if root_dex_like_names != set(dex_entries):
                    raise EvidenceFailure("Android field APK contains a noncanonical root DEX name")
                expected_dex_names = {
                    "classes.dex",
                    *[
                        f"classes{index}.dex"
                        for index in range(2, len(dex_entries) + 1)
                    ],
                }
                if set(dex_entries) != expected_dex_names:
                    raise EvidenceFailure("Android field APK DEX names must be canonical and contiguous")
                if sum(entry.file_size for entry in dex_entries.values()) > 256 * 1024 * 1024:
                    raise EvidenceFailure("Android field APK DEX payload is too large")
                try:
                    defining_dex = validate_walksafe_debug_binding(
                        {
                            name: archive.read(entry)
                            for name, entry in dex_entries.items()
                        },
                        source_commit,
                        ANDROID_DEBUG_BUILD_MARKER,
                    )
                except DexBindingError as exc:
                    raise EvidenceFailure(
                        f"Android field APK BuildConfig debug binding failed: {exc}"
                    ) from exc
        except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
            if isinstance(exc, EvidenceFailure):
                raise
            raise EvidenceFailure("Android field APK is not a readable APK ZIP archive") from exc
        sdk_validation = _validate_android_field_apk_sdk(apk, snapshot)
        if not _snapshot_matches_source(snapshot):
            raise EvidenceFailure("Android field APK changed during validation")
        return {
            "path": str(apk),
            "sha256": snapshot.sha256,
            "bytes": snapshot.size,
            "source_commit": source_commit,
            "build_type": "debug",
            "build_marker": ANDROID_DEBUG_BUILD_MARKER,
            "build_config_dex": defining_dex,
            "evidence_scope": "SAME_COMMIT_DEBUG_FIELD_APK",
            **sdk_validation,
        }
    finally:
        snapshot.close()


def _validate_android_apk_snapshot(
    apk: Path,
    *,
    snapshot: FileSnapshot,
    source_commit: str,
    signer_certificate_sha256: str,
) -> dict[str, str]:
    try:
        with snapshot.open_reader() as apk_reader, zipfile.ZipFile(apk_reader) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            if len(names) != len(set(names)):
                raise EvidenceFailure("Android APK contains duplicate ZIP entry names")
            for entry in entries:
                entry_path = Path(entry.filename)
                if entry_path.is_absolute() or ".." in entry_path.parts or _zip_entry_is_symlink(entry):
                    raise EvidenceFailure("Android APK contains an unsafe ZIP entry")
            if "AndroidManifest.xml" not in names:
                raise EvidenceFailure("Android APK is missing AndroidManifest.xml")
            dex_entries = [entry for entry in entries if re.fullmatch(r"classes(?:\d+)?\.dex", entry.filename)]
            if not dex_entries:
                raise EvidenceFailure("Android APK is missing classes.dex")
            marker = source_commit.encode("ascii")
            if not any(_zip_entry_contains(entry, archive, marker) for entry in dex_entries):
                raise EvidenceFailure("Android APK does not embed the release source_commit in its DEX code")
            release_marker = ANDROID_RELEASE_BUILD_MARKER.encode("ascii")
            if not any(_zip_entry_contains(entry, archive, release_marker) for entry in dex_entries):
                raise EvidenceFailure("Android APK does not embed the WalkSafe release build marker")
            debug_marker = ANDROID_DEBUG_BUILD_MARKER.encode("ascii")
            if any(_zip_entry_contains(entry, archive, debug_marker) for entry in dex_entries):
                raise EvidenceFailure("Android APK embeds the WalkSafe debug build marker")
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        if isinstance(exc, EvidenceFailure):
            raise
        raise EvidenceFailure("Android APK is not a readable APK ZIP archive") from exc

    configured_signer = os.environ.get("WALKSAFE_ANDROID_RELEASE_SIGNER_SHA256", "").strip().lower()
    if SHA256.fullmatch(configured_signer) is None:
        raise EvidenceFailure("WALKSAFE_ANDROID_RELEASE_SIGNER_SHA256 must configure the trusted release certificate")
    if signer_certificate_sha256 != configured_signer:
        raise EvidenceFailure("Android APK evidence signer does not match the configured trusted release certificate")
    if _validate_android_apk_signature(snapshot) != signer_certificate_sha256:
        raise EvidenceFailure("Android APK signer certificate SHA-256 does not match release evidence")
    manifest_validation = _validate_android_apk_manifest(
        apk,
        snapshot,
        expected_debuggable="false",
    )
    return {
        "source_commit": source_commit,
        "signer_certificate_sha256": signer_certificate_sha256,
        "build_marker": ANDROID_RELEASE_BUILD_MARKER,
        **manifest_validation,
    }


def _validated_unified_model_class_names(raw_names: Any) -> tuple[str, ...]:
    if isinstance(raw_names, dict):
        normalized: dict[int, str] = {}
        try:
            for raw_id, raw_name in raw_names.items():
                if isinstance(raw_id, bool):
                    raise ValueError
                class_id = int(raw_id)
                if class_id in normalized:
                    raise ValueError
                normalized[class_id] = str(raw_name)
        except (TypeError, ValueError) as exc:
            raise EvidenceFailure("detector model.names must use unique integer class ids") from exc
        expected_ids = set(range(len(UNIFIED_WALKSAFE_CLASSES)))
        if set(normalized) != expected_ids:
            raise EvidenceFailure("detector model.names must contain exactly the WalkSafe 13 class ids")
        class_names = tuple(normalized[index] for index in range(len(UNIFIED_WALKSAFE_CLASSES)))
    elif isinstance(raw_names, (list, tuple)):
        class_names = tuple(str(name) for name in raw_names)
    else:
        raise EvidenceFailure("detector model.names must expose the WalkSafe 13-class order")
    if class_names != UNIFIED_WALKSAFE_CLASSES:
        raise EvidenceFailure("detector model.names does not match the exact WalkSafe 13-class id/order")
    return class_names


def run_detector_smoke(model_path: Path, fixture_path: Path, *, image_size: int) -> dict[str, Any]:
    try:
        from ultralytics import YOLO  # type: ignore[import-not-found]

        model = YOLO(str(model_path))
        class_names = _validated_unified_model_class_names(model.names)
        results = model.predict(
            source=str(fixture_path),
            imgsz=image_size,
            device="cpu",
            verbose=False,
        )
        class_name_by_id = dict(enumerate(class_names))
        detected_names: list[str] = []
        for result in results:
            boxes = getattr(result, "boxes", None)
            classes = getattr(boxes, "cls", None)
            if classes is None:
                continue
            detected_names.extend(str(class_name_by_id.get(int(value), int(value))) for value in classes.tolist())
    except EvidenceFailure:
        raise
    except Exception as exc:
        raise EvidenceFailure("configured detector artifact could not be loaded and executed") from exc
    if "person" not in detected_names:
        raise EvidenceFailure("person detector fixture did not produce a person detection")
    return {
        "fixture_sha256": sha256_file(fixture_path),
        "model_class_names": list(class_names),
        "detected_names": detected_names,
    }


def validate_registered_deployment(
    registry_path: Path,
    deployment_path: Path,
    *,
    model_sha256: str,
    repository_root: Path = ROOT,
) -> dict[str, Any]:
    registry = load_json_object(registry_path, "model registry")
    deployment = load_json_object(deployment_path, "deployment ledger")
    models = registry.get("models")
    if not isinstance(models, list):
        raise EvidenceFailure("model registry models must be a list")
    matches = [
        item
        for item in models
        if isinstance(item, dict)
        and isinstance(item.get("artifact"), dict)
        and str(item["artifact"].get("sha256", "")).lower() == model_sha256
    ]
    if len(matches) != 1:
        raise EvidenceFailure("configured detector artifact must match exactly one registry model")
    model = matches[0]
    if model.get("deployment_eligible") is not True or model.get("blockers") not in (None, []):
        raise EvidenceFailure("configured detector registry model is not deployment eligible")
    dataset = model.get("dataset")
    if not isinstance(dataset, dict) or dataset.get("content_hash_policy") != "sha256_per_image_and_label":
        raise EvidenceFailure("configured detector dataset lacks per-image and per-label content hashes")
    manifest_value = dataset.get("manifest_path")
    manifest_digest = str(dataset.get("manifest_sha256", "")).lower()
    if not isinstance(manifest_value, str) or not manifest_value or SHA256.fullmatch(manifest_digest) is None:
        raise EvidenceFailure("configured detector dataset manifest path/hash is invalid")
    relative_manifest = Path(manifest_value)
    if relative_manifest.is_absolute() or ".." in relative_manifest.parts:
        raise EvidenceFailure("configured detector dataset manifest must be repository-relative")
    repository_root = repository_root.resolve()
    manifest_path = repository_root / relative_manifest
    if (
        not manifest_path.resolve().is_relative_to(repository_root)
        or manifest_path.is_symlink()
        or not manifest_path.is_file()
        or sha256_file(manifest_path) != manifest_digest
    ):
        raise EvidenceFailure("configured detector dataset manifest file/hash does not match")
    try:
        dataset_integrity = verify_content_hashed_manifest(
            manifest_path,
            repository_root=repository_root,
        )
    except (DatasetIntegrityError, OSError, ValueError) as exc:
        raise EvidenceFailure(f"configured detector dataset content verification failed: {exc}") from exc
    model_id = model.get("model_id")
    backend_target = deployment.get("targets", {}).get("backend") if isinstance(deployment.get("targets"), dict) else None
    if not isinstance(backend_target, dict) or backend_target.get("active_model_id") != model_id:
        raise EvidenceFailure("backend deployment ledger does not activate the configured registry model")
    if backend_target.get("runtime_config") != "environment:DETECT_V2_RUNTIME_CONFIG_PATH":
        raise EvidenceFailure("backend deployment ledger runtime config binding is invalid")
    if backend_target.get("model_artifact") != "environment:DETECT_V2_UNIFIED_MODEL_PATH":
        raise EvidenceFailure("backend deployment ledger model artifact binding is invalid")
    return {
        "model_id": model_id,
        "registry_sha256": sha256_file(registry_path),
        "deployment_ledger_sha256": sha256_file(deployment_path),
        "dataset_content_set_sha256": dataset_integrity["content_set_sha256"],
        "dataset_rows_verified": dataset_integrity["rows"],
    }


def validate_backup_manifest(
    path: Path,
    *,
    now: datetime,
    max_age_hours: int,
    expected_database_identity: str | None = None,
    expected_upload_identity: str | None = None,
) -> dict[str, Any]:
    trusted_signer = os.environ.get("WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT", "").strip().lower()
    if OPENPGP_FINGERPRINT.fullmatch(trusted_signer) is None:
        raise EvidenceFailure("WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT must be a 40-hex fingerprint")
    try:
        payload = verify_signed_backup_manifest(
            path,
            trusted_signer_fingerprint=trusted_signer,
        )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        raise EvidenceFailure("backup signed manifest or artifact verification failed") from exc
    if payload.get("schema_version") != "walksafe.backup.v1":
        raise EvidenceFailure("backup manifest schema_version is invalid")
    created_at = parse_timestamp(payload.get("created_at"), "backup manifest")
    if created_at > now + timedelta(minutes=5) or now - created_at > timedelta(hours=max_age_hours):
        raise EvidenceFailure(f"backup manifest must be no older than {max_age_hours} hours")
    if not meaningful_text(payload.get("run_id")) or not meaningful_text(payload.get("actor_id")):
        raise EvidenceFailure("backup manifest run_id and actor_id are required")
    if payload.get("database_format") != "postgresql-custom+openpgp" or payload.get("uploads_format") != "tar-gzip+openpgp":
        raise EvidenceFailure("backup manifest formats are invalid")
    if payload.get("encryption_at_rest") != "openpgp":
        raise EvidenceFailure("backup manifest must declare encryption_at_rest=openpgp")
    recipient_fingerprint = payload.get("recipient_fingerprint")
    if not isinstance(recipient_fingerprint, str) or not OPENPGP_FINGERPRINT.fullmatch(recipient_fingerprint.lower()):
        raise EvidenceFailure("backup manifest recipient_fingerprint must be a 40-character OpenPGP fingerprint")
    if payload.get("contains_secrets") is not True:
        raise EvidenceFailure("backup manifest must mark the backup as containing secrets")
    signer_fingerprint = payload.get("signer_fingerprint")
    if not isinstance(signer_fingerprint, str) or signer_fingerprint.lower() != trusted_signer:
        raise EvidenceFailure("backup manifest signer_fingerprint is not the trusted signer")
    database_identity = payload.get("database_identity_sha256")
    upload_identity = payload.get("upload_root_identity_sha256")
    if not isinstance(database_identity, str) or not SHA256.fullmatch(database_identity.lower()):
        raise EvidenceFailure("backup database identity is invalid")
    if not isinstance(upload_identity, str) or not SHA256.fullmatch(upload_identity.lower()):
        raise EvidenceFailure("backup upload identity is invalid")
    if expected_database_identity is not None and database_identity.lower() != expected_database_identity:
        raise EvidenceFailure("backup database identity does not match the live environment")
    if expected_upload_identity is not None and upload_identity.lower() != expected_upload_identity:
        raise EvidenceFailure("backup upload identity does not match the live environment")
    snapshot = payload.get("snapshot_boundary")
    if not isinstance(snapshot, dict) or snapshot.get("writes_quiesced_by_operator") is not True:
        raise EvidenceFailure("backup manifest must record a write-quiesced snapshot boundary")
    lock_identity = snapshot.get("maintenance_lock_identity_sha256")
    if not isinstance(lock_identity, str) or not SHA256.fullmatch(lock_identity.lower()):
        raise EvidenceFailure("backup maintenance lock identity is invalid")
    lock_device_inode = snapshot.get("maintenance_lock_device_inode")
    if not isinstance(lock_device_inode, str) or re.fullmatch(r"[0-9]+:[0-9]+", lock_device_inode) is None:
        raise EvidenceFailure("backup maintenance lock device/inode is invalid")
    lock_acquired_at = parse_timestamp(snapshot.get("lock_acquired_at"), "backup snapshot lock")
    snapshot_started_at = parse_timestamp(snapshot.get("started_at"), "backup snapshot start")
    snapshot_finished_at = parse_timestamp(snapshot.get("finished_at"), "backup snapshot finish")
    if not lock_acquired_at <= snapshot_started_at <= snapshot_finished_at <= created_at:
        raise EvidenceFailure("backup snapshot boundary timestamps are out of order")

    source_consistency = payload.get("source_consistency")
    source_count_names = (
        "report_image_count",
        "upload_file_count",
        "missing_count",
        "orphan_count",
        "missing_image_hash_count",
        "image_hash_mismatch_count",
    )
    if (
        not isinstance(source_consistency, dict)
        or source_consistency.get("ready") is not True
        or not isinstance(source_consistency.get("snapshot_content_sha256"), str)
        or SHA256.fullmatch(source_consistency["snapshot_content_sha256"].lower()) is None
        or any(
            not isinstance(source_consistency.get(name), int)
            or isinstance(source_consistency.get(name), bool)
            or source_consistency[name] < 0
            for name in source_count_names
        )
        or source_consistency["report_image_count"] != source_consistency["upload_file_count"]
        or any(
            source_consistency[name] != 0
            for name in (
                "missing_count",
                "orphan_count",
                "missing_image_hash_count",
                "image_hash_mismatch_count",
            )
        )
    ):
        raise EvidenceFailure("backup did not prove report/upload content consistency")

    encrypted_artifacts = {"reports.dump.gpg", "uploads.tar.gz.gpg"}
    manifest_artifacts = payload.get("artifacts_sha256")
    expected = (
        {str(name): str(digest).lower() for name, digest in manifest_artifacts.items()}
        if isinstance(manifest_artifacts, dict)
        else {}
    )
    if set(expected) != encrypted_artifacts or any(SHA256.fullmatch(digest) is None for digest in expected.values()):
        raise EvidenceFailure("backup signed manifest artifact hashes are invalid")
    return {
        "run_id": payload["run_id"],
        "created_at": created_at.isoformat(),
        "artifact_hashes_verified": True,
        "encryption_at_rest": "openpgp",
        "recipient_fingerprint": recipient_fingerprint.lower(),
        "signer_fingerprint": trusted_signer,
        "database_identity_sha256": database_identity.lower(),
        "upload_root_identity_sha256": upload_identity.lower(),
        "maintenance_lock_identity_sha256": lock_identity.lower(),
        "maintenance_lock_device_inode": lock_device_inode,
        "source_consistency_verified": True,
        "snapshot_started_at": snapshot_started_at.isoformat(),
        "snapshot_finished_at": snapshot_finished_at.isoformat(),
        "artifacts_sha256": expected,
    }


def validate_restore_drill_receipt(
    path: Path,
    *,
    now: datetime,
    max_age_days: int,
    trusted_signer_fingerprint: str | None = None,
) -> dict[str, Any]:
    trusted_receipt_signer = (
        trusted_signer_fingerprint
        or os.environ.get("WALKSAFE_RESTORE_TRUSTED_SIGNER_FINGERPRINT", "")
    ).strip().lower()
    if OPENPGP_FINGERPRINT.fullmatch(trusted_receipt_signer) is None:
        raise EvidenceFailure("WALKSAFE_RESTORE_TRUSTED_SIGNER_FINGERPRINT must be a 40-hex fingerprint")
    signature_path = path.with_name(f"{path.name}.sig")
    try:
        payload, verified_receipt_signer, receipt_sha256 = verify_signed_json_document_with_digest(
            path,
            signature_path,
            trusted_receipt_signer,
        )
    except (OSError, ValueError) as exc:
        raise EvidenceFailure("restore drill receipt detached signature is invalid") from exc
    if payload.get("schema_version") != "walksafe.restore-drill.v1":
        raise EvidenceFailure("restore drill receipt schema_version is invalid")
    restored_at = parse_timestamp(payload.get("restored_at"), "restore drill receipt")
    if restored_at > now + timedelta(minutes=5) or now - restored_at > timedelta(days=max_age_days):
        raise EvidenceFailure(f"restore drill receipt must be no older than {max_age_days} days")
    if not meaningful_text(payload.get("actor_id")):
        raise EvidenceFailure("restore drill receipt actor_id is required")
    if not meaningful_text(payload.get("backup_run_id")):
        raise EvidenceFailure("restore drill receipt backup_run_id is required")
    recipient_fingerprint = payload.get("recipient_fingerprint")
    if not isinstance(recipient_fingerprint, str) or not OPENPGP_FINGERPRINT.fullmatch(recipient_fingerprint.lower()):
        raise EvidenceFailure("restore drill receipt recipient_fingerprint must be a 40-character OpenPGP fingerprint")
    signer_fingerprint = payload.get("trusted_signer_fingerprint")
    if not isinstance(signer_fingerprint, str) or not OPENPGP_FINGERPRINT.fullmatch(signer_fingerprint.lower()):
        raise EvidenceFailure("restore drill receipt trusted_signer_fingerprint is invalid")
    declared_receipt_signer = payload.get("receipt_signer_fingerprint")
    if (
        not isinstance(declared_receipt_signer, str)
        or declared_receipt_signer.lower() != verified_receipt_signer
    ):
        raise EvidenceFailure("restore drill receipt signer field does not match its verified signature")
    for field in (
        "hashes_verified",
        "encrypted_backup_verified",
        "database_restore_completed",
        "uploads_restore_completed",
        "target_was_explicit",
        "report_upload_consistency_verified",
        "manifest_signature_verified",
    ):
        if payload.get(field) is not True:
            raise EvidenceFailure(f"restore drill receipt requires {field}=true")
    source_database_identity = payload.get("source_database_identity_sha256")
    source_upload_identity = payload.get("source_upload_root_identity_sha256")
    target_database_identity = payload.get("target_database_identity_sha256")
    target_upload_identity = payload.get("target_upload_root_identity_sha256")
    for field_name, value in {
        "source_database_identity_sha256": source_database_identity,
        "source_upload_root_identity_sha256": source_upload_identity,
        "target_database_identity_sha256": target_database_identity,
        "target_upload_root_identity_sha256": target_upload_identity,
    }.items():
        if not isinstance(value, str) or not SHA256.fullmatch(value.lower()):
            raise EvidenceFailure(f"restore drill receipt {field_name} is invalid")
    normalized_source_database_identity = str(source_database_identity).lower()
    normalized_source_upload_identity = str(source_upload_identity).lower()
    normalized_target_database_identity = str(target_database_identity).lower()
    normalized_target_upload_identity = str(target_upload_identity).lower()
    if normalized_source_database_identity == normalized_target_database_identity:
        raise EvidenceFailure("restore drill target database must differ from its source")
    if normalized_source_upload_identity == normalized_target_upload_identity:
        raise EvidenceFailure("restore drill target upload root must differ from its source")
    artifacts = payload.get("artifacts_sha256")
    if not isinstance(artifacts, dict) or set(artifacts) != {"reports.dump.gpg", "uploads.tar.gz.gpg"}:
        raise EvidenceFailure("restore drill receipt artifact hashes are invalid")
    normalized_artifacts: dict[str, str] = {}
    for name, value in artifacts.items():
        if not isinstance(value, str) or not SHA256.fullmatch(value.lower()):
            raise EvidenceFailure("restore drill receipt artifact hashes are invalid")
        normalized_artifacts[name] = value.lower()
    upload_snapshot = payload.get("restore_upload_snapshot_sha256")
    if not isinstance(upload_snapshot, str) or not SHA256.fullmatch(upload_snapshot.lower()):
        raise EvidenceFailure("restore drill receipt restore_upload_snapshot_sha256 is invalid")
    upload_tree_device_inode = payload.get("restore_tree_device_inode")
    if (
        not isinstance(upload_tree_device_inode, str)
        or re.fullmatch(r"[0-9]+:[0-9]+", upload_tree_device_inode) is None
    ):
        raise EvidenceFailure("restore drill receipt restore_tree_device_inode is invalid")
    counts = payload.get("consistency_counts")
    required_counts = (
        "restored_report_count",
        "image_reference_count",
        "matched_image_count",
        "missing_image_count",
        "missing_image_hash_count",
        "image_hash_mismatch_count",
        "unsafe_image_path_count",
        "restored_upload_file_count",
        "orphan_upload_file_count",
    )
    if not isinstance(counts, dict) or any(
        not isinstance(counts.get(name), int) or isinstance(counts.get(name), bool) or counts[name] < 0
        for name in required_counts
    ):
        raise EvidenceFailure("restore drill receipt consistency counts are invalid")
    if not (
        counts["restored_report_count"]
        == counts["image_reference_count"]
        == counts["matched_image_count"]
        == counts["restored_upload_file_count"]
        and counts["missing_image_count"] == 0
        and counts["missing_image_hash_count"] == 0
        and counts["image_hash_mismatch_count"] == 0
        and counts["unsafe_image_path_count"] == 0
        and counts["orphan_upload_file_count"] == 0
    ):
        raise EvidenceFailure("restore drill did not prove restored report/upload consistency")
    return {
        "restored_at": restored_at.isoformat(),
        "database_and_uploads_restored": True,
        "recipient_fingerprint": recipient_fingerprint.lower(),
        "signer_fingerprint": signer_fingerprint.lower(),
        "receipt_signer_fingerprint": verified_receipt_signer,
        "backup_run_id": payload["backup_run_id"],
        "source_database_identity_sha256": normalized_source_database_identity,
        "source_upload_root_identity_sha256": normalized_source_upload_identity,
        "target_database_identity_sha256": normalized_target_database_identity,
        "target_upload_root_identity_sha256": normalized_target_upload_identity,
        "restore_upload_snapshot_sha256": upload_snapshot.lower(),
        "restore_tree_device_inode": upload_tree_device_inode,
        "artifacts_sha256": normalized_artifacts,
        "receipt_sha256": receipt_sha256,
        "consistency_counts": {name: counts[name] for name in required_counts},
    }


def validate_agency_submission_receipt(
    path: Path,
    *,
    now: datetime,
    max_age_days: int,
    evidence_check: Any,
    export_path: Path | None = None,
    export_manifest_path: Path | None = None,
) -> dict[str, Any]:
    payload = load_json_object(path, "agency submission receipt")
    if payload.get("schema_version") != "walksafe.agency-submission-receipt.v1":
        raise EvidenceFailure("agency submission receipt schema_version is invalid")
    submitted_at = parse_timestamp(payload.get("submitted_at"), "agency submission receipt")
    if submitted_at > now + timedelta(minutes=5) or now - submitted_at > timedelta(days=max_age_days):
        raise EvidenceFailure(f"agency submission receipt must be no older than {max_age_days} days")
    for field in ("institution", "external_receipt_id", "submitted_by_actor_id"):
        if not meaningful_text(payload.get(field)):
            raise EvidenceFailure(f"agency submission receipt {field} is required and cannot be a placeholder")
    normalized_actor_id = str(payload["submitted_by_actor_id"]).casefold()
    if normalized_actor_id in {"unknown", "system", "anonymous"} or normalized_actor_id.endswith("-shared"):
        raise EvidenceFailure("agency submission receipt requires a named submitting actor")
    if payload.get("channel") not in {"portal", "email", "api", "in_person"}:
        raise EvidenceFailure("agency submission receipt channel is invalid")
    if payload.get("submission_status") not in {"received", "accepted"}:
        raise EvidenceFailure("agency submission receipt must have an external received/accepted status")
    if payload.get("access_scope") != "admin_exact_location" or payload.get("exact_location_included") is not True:
        raise EvidenceFailure("agency receipt must identify the admin-only exact-location submission scope")
    for field in ("export_sha256", "export_manifest_sha256"):
        value = payload.get(field)
        if not isinstance(value, str) or not SHA256.fullmatch(value.lower()):
            raise EvidenceFailure(f"agency submission receipt {field} must be a SHA-256 digest")
    rows_sha256 = payload.get("export_rows_sha256")
    if not isinstance(rows_sha256, str) or not SHA256.fullmatch(rows_sha256.lower()):
        raise EvidenceFailure("agency submission receipt export_rows_sha256 must be a SHA-256 digest")
    if payload.get("export_format") not in {"csv", "json", "geojson"}:
        raise EvidenceFailure("agency submission receipt export format/audit identity is invalid")
    export_audit_id = payload.get("export_audit_id")
    if not isinstance(export_audit_id, str) or UUID.fullmatch(export_audit_id.lower()) is None:
        raise EvidenceFailure("agency submission receipt export format/audit identity is invalid")
    report_count = payload.get("report_count")
    if not isinstance(report_count, int) or isinstance(report_count, bool) or report_count < 1:
        raise EvidenceFailure("agency submission receipt report_count must be at least 1")
    if not isinstance(evidence_check, dict) or not isinstance(evidence_check.get("details"), dict):
        raise EvidenceFailure("institution submission evidence details are required")
    if evidence_check["details"].get("external_receipt_id") != payload["external_receipt_id"]:
        raise EvidenceFailure("institution submission evidence external_receipt_id does not match the receipt")
    if export_path is not None and export_manifest_path is not None:
        if sha256_file(export_path) != str(payload["export_sha256"]).lower():
            raise EvidenceFailure("agency export file changed after external submission")
        if sha256_file(export_manifest_path) != str(payload["export_manifest_sha256"]).lower():
            raise EvidenceFailure("agency export manifest changed after external submission")
        manifest = load_json_object(export_manifest_path, "agency export manifest")
        try:
            export_format, manifest_rows_sha256 = validate_export_matches_manifest(export_path, manifest)
            manifest_rows = validate_agency_manifest_contract(
                manifest,
                actor_id=str(payload["submitted_by_actor_id"]),
            )
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            raise EvidenceFailure(f"agency export and manifest are not bound: {exc}") from exc
        if export_format != payload["export_format"] or manifest_rows_sha256 != rows_sha256.lower():
            raise EvidenceFailure("agency export format or row digest does not match the receipt")
        if manifest.get("audit_id") != payload["export_audit_id"]:
            raise EvidenceFailure("agency export audit id does not match the receipt")
        if len(manifest_rows) != report_count:
            raise EvidenceFailure("agency export report count does not match the receipt")
    return {
        "submitted_at": submitted_at.isoformat(),
        "institution": payload["institution"],
        "external_receipt_id": payload["external_receipt_id"],
        "report_count": report_count,
    }


def _load_submission_manifest(path: Path, context: str) -> dict[str, Any]:
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise EvidenceFailure(f"{context} contains duplicate JSON key: {key}")
            result[key] = value
        return result

    if path.is_symlink() or not path.is_file():
        raise EvidenceFailure(f"{context} must be a regular non-symlink file")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicates)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise EvidenceFailure(f"{context} is not valid JSON: {exc}") from exc
    if not isinstance(payload, dict):
        raise EvidenceFailure(f"{context} root must be an object")
    return payload


def _require_exact_keys(value: Any, expected: set[str], context: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise EvidenceFailure(f"{context} fields differ from the exact generation schema")
    return value


def _regular_repository_file(repository_root: Path, relative: str, context: str) -> Path:
    logical = Path(relative)
    if logical.is_absolute() or logical.as_posix() != relative or ".." in logical.parts:
        raise EvidenceFailure(f"{context} path is not normalized repository-relative")
    root = repository_root.resolve()
    unresolved = Path(os.path.abspath(root / logical))
    try:
        resolved = unresolved.resolve(strict=True)
    except OSError as exc:
        raise EvidenceFailure(f"{context} is missing from the validation source") from exc
    if resolved != unresolved or root not in resolved.parents or not resolved.is_file():
        raise EvidenceFailure(f"{context} must be a real validation-source file")
    return resolved


def _regular_external_file(path: Path, context: str) -> Path:
    unresolved = Path(os.path.abspath(path))
    try:
        resolved = unresolved.resolve(strict=True)
    except OSError as exc:
        raise EvidenceFailure(f"{context} is missing") from exc
    if resolved != unresolved or not resolved.is_file():
        raise EvidenceFailure(f"{context} must be a regular non-symlink file")
    return resolved


def _require_external_directory(directory: Path, expected: set[str] | frozenset[str], context: str) -> Path:
    unresolved = Path(os.path.abspath(directory))
    try:
        resolved = unresolved.resolve(strict=True)
    except OSError as exc:
        raise EvidenceFailure(f"{context} is missing") from exc
    if resolved != unresolved or not resolved.is_dir():
        raise EvidenceFailure(f"{context} must be a real non-symlink directory")
    entries = list(resolved.iterdir())
    if any(item.is_symlink() or not item.is_file() for item in entries):
        raise EvidenceFailure(f"{context} must contain regular non-symlink files only")
    names = {item.name for item in entries}
    if names != set(expected) or len(entries) != len(expected):
        raise EvidenceFailure(
            f"{context} file set differs: missing={sorted(set(expected) - names)}, "
            f"unexpected={sorted(names - set(expected))}"
        )
    return resolved


def _require_external_to_source(path: Path, repository_root: Path, context: str) -> None:
    artifact = Path(os.path.abspath(path)).resolve()
    source = repository_root.resolve()
    if artifact == source or source in artifact.parents:
        raise EvidenceFailure(f"{context} must be outside the clean validation source tree")


def _manifest_records(
    value: Any,
    *,
    path_key: str,
    expected_paths: set[str] | frozenset[str],
    expected_keys: set[str] | None,
    context: str,
) -> dict[str, dict[str, Any]]:
    if not isinstance(value, list) or any(not isinstance(item, dict) for item in value):
        raise EvidenceFailure(f"{context} must be an object list")
    if expected_keys is not None and any(set(item) != expected_keys for item in value):
        raise EvidenceFailure(f"{context} record fields differ from the exact generation schema")
    paths = [item.get(path_key) for item in value]
    if (
        any(not isinstance(path, str) for path in paths)
        or len(paths) != len(set(paths))
        or set(paths) != set(expected_paths)
    ):
        raise EvidenceFailure(f"{context} path set differs from the exact generation schema")
    return {str(item[path_key]): item for item in value}


def _verify_manifest_record(
    record: dict[str, Any],
    target: Path,
    *,
    context: str,
) -> None:
    digest = record.get("sha256")
    size = record.get("bytes")
    if not isinstance(digest, str) or SHA256.fullmatch(digest) is None:
        raise EvidenceFailure(f"{context} SHA-256 is invalid")
    if isinstance(size, bool) or not isinstance(size, int) or size < 0:
        raise EvidenceFailure(f"{context} byte size is invalid")
    if sha256_file(target) != digest or target.stat().st_size != size:
        raise EvidenceFailure(f"{context} hash/size differs from current bound bytes")


def _expected_build_tools(lock: dict[str, Any], libraries: tuple[str, ...]) -> dict[str, Any]:
    python = _require_exact_keys(
        lock.get("python"),
        {"implementation", "version", "platform", "executable"},
        "submission toolchain Python lock",
    )
    platform_record = _require_exact_keys(
        python.get("platform"), {"system", "machine"}, "submission toolchain platform lock"
    )
    locked_libraries = lock.get("libraries")
    if not isinstance(locked_libraries, list) or any(not isinstance(item, dict) for item in locked_libraries):
        raise EvidenceFailure("submission toolchain libraries lock is invalid")
    by_name = {item.get("distribution"): item for item in locked_libraries}
    if len(by_name) != len(locked_libraries) or any(name not in by_name for name in libraries):
        raise EvidenceFailure("submission toolchain libraries required by the builder are missing")
    return {
        "python": {
            "implementation": python["implementation"],
            "version": python["version"],
        },
        "platform": dict(platform_record),
        "libraries": [
            {"distribution": name, "version": by_name[name].get("version")}
            for name in sorted(libraries, key=str.casefold)
        ],
    }


def _validate_manifest_source_and_toolchain(
    manifest: dict[str, Any],
    *,
    source_commit: str,
    repository_root: Path,
    libraries: tuple[str, ...],
    context: str,
) -> dict[str, Any]:
    if manifest.get("source_commit") != source_commit or manifest.get("source_dirty") is not False:
        raise EvidenceFailure(f"{context} does not bind the current clean validation source commit")
    if manifest.get("source_dirty_excluded_generated_paths") != sorted(ALL_SUBMISSION_GENERATED_PATHS):
        raise EvidenceFailure(f"{context} generated-source exclusions differ from the builder contract")
    lock_path = _regular_repository_file(
        repository_root, SUBMISSION_TOOLCHAIN_LOCK_PATH, "submission toolchain lock"
    )
    lock = _load_submission_manifest(lock_path, "submission toolchain lock")
    if (
        lock.get("schema_version") != "walksafe.submission-toolchain.v4"
        or lock.get("library_bundle_format") != "walksafe.semantic-record-bundle.v1"
        or set(lock)
        != {
            "schema_version",
            "library_bundle_format",
            "python_installation",
            "python",
            "libraries",
            "tools",
            "fonts",
        }
    ):
        raise EvidenceFailure("submission toolchain lock differs from the exact v4 contract")
    installation = _require_exact_keys(
        lock.get("python_installation"),
        {"installer_lock", "exact8_lock", "pip_version", "install_flags"},
        "submission Python installation lock",
    )
    if installation.get("pip_version") != "26.1.1" or installation.get("install_flags") != [
        "--require-hashes",
        "--no-deps",
        "--no-compile",
    ]:
        raise EvidenceFailure("submission Python installation recipe differs from the v4 contract")
    for key, expected_relative in (
        ("installer_lock", SUBMISSION_INSTALLER_REQUIREMENTS_PATH),
        ("exact8_lock", SUBMISSION_EXACT8_REQUIREMENTS_PATH),
    ):
        requirements_record = _require_exact_keys(
            installation.get(key),
            {"path", "sha256", "bytes"},
            f"submission Python {key}",
        )
        if requirements_record.get("path") != expected_relative:
            raise EvidenceFailure(f"submission Python {key} path is invalid")
        requirements_path = _regular_repository_file(
            repository_root,
            expected_relative,
            f"submission Python {key}",
        )
        _verify_manifest_record(
            requirements_record,
            requirements_path,
            context=f"submission Python {key}",
        )
    attestation = _require_exact_keys(
        manifest.get("submission_toolchain"), {"lock", "verified"}, f"{context} toolchain attestation"
    )
    lock_record = _require_exact_keys(
        attestation.get("lock"),
        {"path", "sha256", "bytes", "schema_version"},
        f"{context} toolchain lock record",
    )
    if lock_record.get("path") != SUBMISSION_TOOLCHAIN_LOCK_PATH:
        raise EvidenceFailure(f"{context} toolchain lock path is invalid")
    _verify_manifest_record(lock_record, lock_path, context=f"{context} toolchain lock")
    if lock_record.get("schema_version") != lock.get("schema_version") or attestation.get("verified") != lock:
        raise EvidenceFailure(f"{context} toolchain attestation differs from the clean source lock")
    if manifest.get("build_tools") != _expected_build_tools(lock, libraries):
        raise EvidenceFailure(f"{context} build tool provenance differs from the pinned toolchain")
    return lock


def _validate_asset_manifest(
    asset_dir: Path,
    *,
    source_commit: str,
    repository_root: Path,
) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    asset_dir = _require_external_directory(asset_dir, ASSET_DIRECTORY_FILES, "submission asset directory")
    manifest_path = asset_dir / "BUILD_MANIFEST.json"
    manifest = _load_submission_manifest(manifest_path, "submission asset build manifest")
    expected_top = {
        "schema_version",
        "source_commit",
        "source_dirty",
        "source_dirty_excluded_generated_paths",
        "build_tools",
        "submission_toolchain",
        "allowed_asset_files",
        "input_bundle_sha256",
        "repository_inputs",
        "system_inputs",
        "artifacts",
    }
    _require_exact_keys(manifest, expected_top, "submission asset build manifest")
    if manifest.get("schema_version") != "walksafe.submission-assets-build.v1":
        raise EvidenceFailure("submission asset build manifest schema_version is invalid")
    lock = _validate_manifest_source_and_toolchain(
        manifest,
        source_commit=source_commit,
        repository_root=repository_root,
        libraries=("Pillow",),
        context="submission asset build manifest",
    )
    if manifest.get("allowed_asset_files") != sorted(ASSET_DIRECTORY_FILES):
        raise EvidenceFailure("submission asset allowed file set differs from the builder contract")
    repository_records = _manifest_records(
        manifest.get("repository_inputs"),
        path_key="path",
        expected_paths=ASSET_REPOSITORY_INPUT_PATHS,
        expected_keys={"path", "sha256", "bytes"},
        context="submission asset repository inputs",
    )
    for relative, record in repository_records.items():
        source = _regular_repository_file(repository_root, relative, f"submission asset input {relative}")
        _verify_manifest_record(record, source, context=f"submission asset input {relative}")
        if relative.startswith("docs/submission/form_materials/assets/"):
            external = _regular_external_file(asset_dir / Path(relative).name, f"external source asset {relative}")
            _verify_manifest_record(record, external, context=f"external source asset {relative}")
    system_records = _manifest_records(
        manifest.get("system_inputs"),
        path_key="path",
        expected_paths=ASSET_SYSTEM_INPUT_PATHS,
        expected_keys={"path", "sha256", "bytes"},
        context="submission asset system inputs",
    )
    locked_fonts = lock.get("fonts")
    if not isinstance(locked_fonts, list) or any(not isinstance(item, dict) for item in locked_fonts):
        raise EvidenceFailure("submission toolchain font lock is invalid")
    fonts_by_path = {item.get("path"): item for item in locked_fonts}
    if set(fonts_by_path) != set(ASSET_SYSTEM_INPUT_PATHS):
        raise EvidenceFailure("submission toolchain font set differs from the asset builder contract")
    for relative, record in system_records.items():
        if record != fonts_by_path[relative]:
            raise EvidenceFailure(f"submission asset system input differs from the source lock: {relative}")
    artifact_records = _manifest_records(
        manifest.get("artifacts"),
        path_key="path",
        expected_paths=ASSET_ARTIFACT_PATHS,
        expected_keys={"path", "sha256", "bytes"},
        context="submission asset artifacts",
    )
    for relative, record in artifact_records.items():
        target = _regular_external_file(asset_dir / Path(relative).name, f"submission asset artifact {relative}")
        _verify_manifest_record(record, target, context=f"submission asset artifact {relative}")
    input_records = [*repository_records.values(), *system_records.values()]
    try:
        bundle_hash = record_bundle_sha256(input_records)
    except ValueError as exc:
        raise EvidenceFailure(f"submission asset input bundle is invalid: {exc}") from exc
    if manifest.get("input_bundle_sha256") != bundle_hash:
        raise EvidenceFailure("submission asset input bundle SHA-256 is invalid")
    return manifest, {**repository_records, **artifact_records}


def _validate_design_manifest(
    document_dir: Path,
    *,
    asset_dir: Path,
    asset_records: dict[str, dict[str, Any]],
    source_commit: str,
    repository_root: Path,
) -> dict[str, Any]:
    document_dir = _require_external_directory(
        document_dir, DESIGN_OUTPUT_NAMES, "design document directory"
    )
    manifest_path = document_dir / "BUILD_MANIFEST.json"
    manifest = _load_submission_manifest(manifest_path, "design document build manifest")
    expected_top = {
        "schema_version",
        "build_date",
        "facts",
        "product_status",
        "primary_app",
        "android_role",
        "agency_submission",
        "assets",
        "source_commit",
        "source_dirty",
        "source_dirty_excluded_generated_paths",
        "build_tools",
        "submission_toolchain",
        "build_inputs",
        "implementation_bindings",
        "supporting_files",
        "documents",
    }
    _require_exact_keys(manifest, expected_top, "design document build manifest")
    if manifest.get("schema_version") != "walksafe.design-documents.v2":
        raise EvidenceFailure("design document build manifest schema_version is invalid")
    _validate_manifest_source_and_toolchain(
        manifest,
        source_commit=source_commit,
        repository_root=repository_root,
        libraries=("Pillow", "python-docx"),
        context="design document build manifest",
    )
    facts_path = _regular_repository_file(
        repository_root,
        "docs/submission/form_materials/09_제출_사실_기준.json",
        "canonical submission facts",
    )
    facts = _load_submission_manifest(facts_path, "canonical submission facts")
    if facts.get("schema_version") != "walksafe.submission_facts.v2":
        raise EvidenceFailure("canonical submission facts schema_version is invalid")
    facts_record = _require_exact_keys(
        manifest.get("facts"), {"file", "sha256", "schema_version"}, "design canonical facts"
    )
    if facts_record.get("file") != "docs/submission/form_materials/09_제출_사실_기준.json":
        raise EvidenceFailure("design canonical facts path is invalid")
    if (
        facts_record.get("sha256") != sha256_file(facts_path)
        or facts_record.get("schema_version") != facts.get("schema_version")
    ):
        raise EvidenceFailure("design canonical facts differ from the clean validation source")
    expected_metadata = {
        "build_date": facts.get("as_of_date"),
        "product_status": "PARTIAL",
        "primary_app": "Web/PWA",
        "android_role": "ARCore/depth/TFLite research support",
        "agency_submission": "admin-reviewed export with allow-field check followed by manual external submission",
    }
    if any(manifest.get(key) != value for key, value in expected_metadata.items()):
        raise EvidenceFailure("design manifest canonical metadata differs from the generation contract")
    design_assets = _manifest_records(
        manifest.get("assets"),
        path_key="file",
        expected_paths=DESIGN_ASSET_PATHS,
        expected_keys={"file", "sha256", "bytes"},
        context="design manifest assets",
    )
    for relative, record in design_assets.items():
        target = _regular_external_file(asset_dir / Path(relative).name, f"design asset {relative}")
        _verify_manifest_record(record, target, context=f"design asset {relative}")
        upstream = asset_records.get(relative)
        if upstream is None or any(record.get(key) != upstream.get(key) for key in ("sha256", "bytes")):
            raise EvidenceFailure(f"design asset does not match the asset build manifest: {relative}")
    build_inputs = _manifest_records(
        manifest.get("build_inputs"),
        path_key="file",
        expected_paths=DESIGN_BUILD_INPUT_PATHS,
        expected_keys={"file", "sha256", "bytes"},
        context="design manifest build inputs",
    )
    for relative, record in build_inputs.items():
        target = (
            _regular_external_file(asset_dir / "BUILD_MANIFEST.json", "design asset build-manifest input")
            if relative == "docs/submission/form_materials/assets/BUILD_MANIFEST.json"
            else _regular_repository_file(repository_root, relative, f"design build input {relative}")
        )
        _verify_manifest_record(record, target, context=f"design build input {relative}")
    bindings = _manifest_records(
        manifest.get("implementation_bindings"),
        path_key="file",
        expected_paths=EXPECTED_DESIGN_IMPLEMENTATION_BINDINGS,
        expected_keys={"file", "sha256", "bytes"},
        context="design implementation bindings",
    )
    for relative, record in bindings.items():
        target = _regular_repository_file(repository_root, relative, f"design implementation binding {relative}")
        _verify_manifest_record(record, target, context=f"design implementation binding {relative}")
    supporting = _manifest_records(
        manifest.get("supporting_files"),
        path_key="file",
        expected_paths={"docs/submission/design_documents/README.md"},
        expected_keys={"file", "sha256", "bytes"},
        context="design supporting files",
    )
    _verify_manifest_record(
        supporting["docs/submission/design_documents/README.md"],
        _regular_external_file(document_dir / "README.md", "design README"),
        context="design README",
    )
    documents = _manifest_records(
        manifest.get("documents"),
        path_key="file",
        expected_paths=DESIGN_DOCUMENT_NAMES,
        expected_keys={
            "file",
            "sha256",
            "bytes",
            "paragraphs",
            "tables",
            "inline_shapes",
            "source",
            "source_sha256",
            "orientation",
        },
        context="design document artifacts",
    )
    for name, record in documents.items():
        target = _regular_external_file(document_dir / name, f"design document {name}")
        _verify_manifest_record(record, target, context=f"design document {name}")
        source_relative = EXPECTED_DESIGN_DOCUMENT_SOURCES[name]
        source = _regular_repository_file(repository_root, source_relative, f"design document source {name}")
        if record.get("source") != source_relative or record.get("source_sha256") != sha256_file(source):
            raise EvidenceFailure(f"design document source differs from the validation clone: {name}")
        for field in ("paragraphs", "tables", "inline_shapes"):
            value = record.get(field)
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise EvidenceFailure(f"design document {field} is invalid: {name}")
        if record.get("orientation") not in {"portrait", "landscape"}:
            raise EvidenceFailure(f"design document orientation is invalid: {name}")
    return manifest


def _validate_form_manifest(
    form_manifest_path: Path,
    *,
    asset_dir: Path,
    final_document_dir: Path,
    source_commit: str,
    repository_root: Path,
) -> dict[str, Any]:
    form_manifest_path = _regular_external_file(form_manifest_path, "submission form build manifest")
    manifest = _load_submission_manifest(form_manifest_path, "submission form build manifest")
    expected_top = {
        "schema_version",
        "source_commit",
        "source_dirty",
        "source_dirty_excluded_generated_paths",
        "build_tools",
        "submission_toolchain",
        "allowed_template_files",
        "input_bundle_sha256",
        "inputs",
        "artifacts",
    }
    _require_exact_keys(manifest, expected_top, "submission form build manifest")
    if manifest.get("schema_version") != "walksafe.submission-forms-build.v1":
        raise EvidenceFailure("submission form build manifest schema_version is invalid")
    _validate_manifest_source_and_toolchain(
        manifest,
        source_commit=source_commit,
        repository_root=repository_root,
        libraries=("Pillow", "python-docx", "python-pptx"),
        context="submission form build manifest",
    )
    if manifest.get("allowed_template_files") != sorted(FORM_TEMPLATE_FILES):
        raise EvidenceFailure("submission form allowed file set differs from the builder contract")
    inputs = _manifest_records(
        manifest.get("inputs"),
        path_key="path",
        expected_paths=FORM_BUILD_INPUT_PATHS,
        expected_keys={"path", "sha256", "bytes"},
        context="submission form build inputs",
    )
    for relative, record in inputs.items():
        target = (
            _regular_external_file(asset_dir / Path(relative).name, f"submission form asset input {relative}")
            if relative.startswith("docs/submission/form_materials/assets/")
            else _regular_repository_file(repository_root, relative, f"submission form input {relative}")
        )
        _verify_manifest_record(record, target, context=f"submission form input {relative}")
    try:
        bundle_hash = record_bundle_sha256(inputs.values())
    except ValueError as exc:
        raise EvidenceFailure(f"submission form input bundle is invalid: {exc}") from exc
    if manifest.get("input_bundle_sha256") != bundle_hash:
        raise EvidenceFailure("submission form input bundle SHA-256 is invalid")
    artifacts = _manifest_records(
        manifest.get("artifacts"),
        path_key="path",
        expected_paths=FORM_ARTIFACT_PATHS,
        expected_keys={"path", "sha256", "bytes"},
        context="submission form artifacts",
    )
    for relative, record in artifacts.items():
        target = _regular_external_file(
            final_document_dir / Path(relative).name, f"submission form artifact {relative}"
        )
        _verify_manifest_record(record, target, context=f"submission form artifact {relative}")
    return manifest


def _validate_final_manifest(
    final_document_dir: Path,
    *,
    document_dir: Path,
    form_manifest_path: Path,
    privacy_receipt_path: Path,
    source_commit: str,
    repository_root: Path,
) -> dict[str, Any]:
    final_document_dir = _require_external_directory(
        final_document_dir, FINAL_OUTPUT_NAMES, "final submission directory"
    )
    manifest = _load_submission_manifest(
        final_document_dir / "BUILD_MANIFEST.json", "final submission build manifest"
    )
    expected_top = {
        "schema_version",
        "as_of_date",
        "product_status",
        "candidate_status",
        "product_scope",
        "repository",
        "canonical_facts",
        "official_templates",
        "critical_sources",
        "build_sources",
        "design_pack",
        "form_build",
        "artifacts",
        "supporting_files",
        "validation",
        "known_release_blockers",
    }
    _require_exact_keys(manifest, expected_top, "final submission build manifest")
    if manifest.get("schema_version") != "walksafe.submission-final.v1":
        raise EvidenceFailure("final submission build manifest schema_version is invalid")
    facts_path = _regular_repository_file(
        repository_root,
        "docs/submission/form_materials/09_제출_사실_기준.json",
        "canonical submission facts",
    )
    facts = _load_submission_manifest(facts_path, "canonical submission facts")
    if facts.get("schema_version") != "walksafe.submission_facts.v2":
        raise EvidenceFailure("canonical submission facts schema_version is invalid")
    project = facts.get("project")
    expected_scope = {
        "primary_app": project.get("primary_app") if isinstance(project, dict) else None,
        "android_role": project.get("android_role") if isinstance(project, dict) else None,
        "institution_submission": "named admin review, agency file preparation, manual external-channel submission",
    }
    if (
        not isinstance(project, dict)
        or manifest.get("as_of_date") != facts.get("as_of_date")
        or manifest.get("product_status") != project.get("overall_status")
        or manifest.get("candidate_status") != "AUTOMATED_AND_HUMAN_VISUAL_QA_PASS"
        or manifest.get("product_scope") != expected_scope
        or manifest.get("known_release_blockers") != facts.get("release_gates")
    ):
        raise EvidenceFailure("final manifest canonical facts/status/scope differ from generation inputs")
    canonical = _require_exact_keys(
        manifest.get("canonical_facts"),
        {"path", "sha256", "bytes", "schema_version"},
        "final canonical facts",
    )
    canonical_path = "docs/submission/form_materials/09_제출_사실_기준.json"
    if canonical.get("path") != canonical_path or canonical.get("schema_version") != facts.get("schema_version"):
        raise EvidenceFailure("final canonical facts record is invalid")
    _verify_manifest_record(canonical, facts_path, context="final canonical facts")
    verified_source_records: list[dict[str, Any]] = [canonical]
    for section in ("official_templates", "critical_sources", "build_sources"):
        records = _manifest_records(
            manifest.get(section),
            path_key="path",
            expected_paths=FINAL_SECTION_PATHS[section],
            expected_keys={"path", "sha256", "bytes"},
            context=f"final manifest {section}",
        )
        for relative, record in records.items():
            target = _regular_repository_file(repository_root, relative, f"final {section} source {relative}")
            _verify_manifest_record(record, target, context=f"final {section} source {relative}")
        if section in {"critical_sources", "build_sources"}:
            verified_source_records.extend(records.values())
    design_pack = _require_exact_keys(
        manifest.get("design_pack"),
        {"document_count", "manifest_path", "manifest_sha256"},
        "final design pack",
    )
    design_manifest_path = _regular_external_file(
        document_dir / "BUILD_MANIFEST.json", "external design build manifest"
    )
    if (
        design_pack.get("document_count") != len(DESIGN_DOCUMENT_NAMES)
        or design_pack.get("manifest_path") != "docs/submission/design_documents/BUILD_MANIFEST.json"
        or design_pack.get("manifest_sha256") != sha256_file(design_manifest_path)
    ):
        raise EvidenceFailure("final design pack does not bind the external design manifest")
    form_build = _require_exact_keys(
        manifest.get("form_build"),
        {"schema_version", "manifest_path", "manifest_sha256"},
        "final form build",
    )
    form_manifest_path = _regular_external_file(form_manifest_path, "external form build manifest")
    if (
        form_build.get("schema_version") != "walksafe.submission-forms-build.v1"
        or form_build.get("manifest_path") != "templates/SUBMISSION_BUILD_MANIFEST.json"
        or form_build.get("manifest_sha256") != sha256_file(form_manifest_path)
    ):
        raise EvidenceFailure("final form build does not bind the external form manifest")
    artifacts = _manifest_records(
        manifest.get("artifacts"),
        path_key="path",
        expected_paths=FINAL_SECTION_PATHS["artifacts"],
        expected_keys=None,
        context="final submission artifacts",
    )
    report_path = "docs/submission/final/개발보고서 양식.docx"
    slides_path = "docs/submission/final/제작설계서_일반.pptx"
    if set(artifacts[report_path]) != {"path", "sha256", "bytes", "pages"}:
        raise EvidenceFailure("final report artifact fields differ from the exact generation schema")
    if set(artifacts[slides_path]) != {"path", "sha256", "bytes", "slides", "aspect_ratio"}:
        raise EvidenceFailure("final slide artifact fields differ from the exact generation schema")
    for relative, record in artifacts.items():
        target = _regular_external_file(final_document_dir / Path(relative).name, f"final artifact {relative}")
        _verify_manifest_record(record, target, context=f"final artifact {relative}")
    report_pages = artifacts[report_path].get("pages")
    if isinstance(report_pages, bool) or not isinstance(report_pages, int) or report_pages < 1:
        raise EvidenceFailure("final report page count is invalid")
    if artifacts[slides_path].get("slides") != 34 or artifacts[slides_path].get("aspect_ratio") != "4:3":
        raise EvidenceFailure("final slide semantics differ from the 34-slide 4:3 generation contract")
    supporting = _manifest_records(
        manifest.get("supporting_files"),
        path_key="path",
        expected_paths=FINAL_SECTION_PATHS["supporting_files"],
        expected_keys={"path", "sha256", "bytes"},
        context="final supporting files",
    )
    for relative, record in supporting.items():
        target = _regular_external_file(final_document_dir / Path(relative).name, f"final supporting file {relative}")
        _verify_manifest_record(record, target, context=f"final supporting file {relative}")
    receipt_copy = final_document_dir / "ASSISTANT_VISUAL_PRIVACY_REVIEW.json"
    privacy_receipt_path = _regular_external_file(privacy_receipt_path, "submission privacy receipt")
    if receipt_copy.read_bytes() != privacy_receipt_path.read_bytes():
        raise EvidenceFailure("final submission does not contain the exact reviewed privacy receipt bytes")
    validation = _require_exact_keys(
        manifest.get("validation"),
        {
            "test_layers",
            "backend_voice_android",
            "android_device",
            "web",
            "submission_materials",
            "office_render",
        },
        "final validation summary",
    )
    if any(not meaningful_text(value) for value in validation.values()):
        raise EvidenceFailure("final validation summary contains an empty or placeholder value")
    repository = _require_exact_keys(
        manifest.get("repository"),
        {
            "commit",
            "dirty",
            "source_bundle_sha256",
            "source_bundle_algorithm",
            "source_bundle_entry_count",
        },
        "final repository provenance",
    )
    expected_algorithm = (
        "SHA-256 of sorted sha256sum lines for canonical facts, "
        f"{len(FINAL_SECTION_PATHS['critical_sources'])} critical sources, and "
        f"{len(FINAL_SECTION_PATHS['build_sources'])} build/validation sources"
    )
    try:
        source_bundle = record_bundle_sha256(verified_source_records)
    except ValueError as exc:
        raise EvidenceFailure(f"final source bundle records are invalid: {exc}") from exc
    if (
        repository.get("commit") != source_commit
        or repository.get("dirty") is not False
        or repository.get("source_bundle_sha256") != source_bundle
        or repository.get("source_bundle_algorithm") != expected_algorithm
        or repository.get("source_bundle_entry_count") != len(verified_source_records)
    ):
        raise EvidenceFailure("final repository provenance does not bind the clean validation source")
    return manifest


def validate_submission_artifact_manifests(
    *,
    asset_dir: Path,
    document_dir: Path,
    final_document_dir: Path,
    form_manifest_path: Path,
    privacy_receipt_path: Path,
    source_commit: str,
    repository_root: Path = ROOT,
) -> dict[str, Any]:
    for path, context in (
        (asset_dir, "submission asset directory"),
        (document_dir, "design document directory"),
        (final_document_dir, "final submission directory"),
        (form_manifest_path, "submission form build manifest"),
        (privacy_receipt_path, "submission privacy receipt"),
    ):
        _require_external_to_source(path, repository_root, context)
    asset_manifest, asset_records = _validate_asset_manifest(
        asset_dir,
        source_commit=source_commit,
        repository_root=repository_root,
    )
    design_manifest = _validate_design_manifest(
        document_dir,
        asset_dir=asset_dir,
        asset_records=asset_records,
        source_commit=source_commit,
        repository_root=repository_root,
    )
    form_manifest = _validate_form_manifest(
        form_manifest_path,
        asset_dir=asset_dir,
        final_document_dir=final_document_dir,
        source_commit=source_commit,
        repository_root=repository_root,
    )
    final_manifest = _validate_final_manifest(
        final_document_dir,
        document_dir=document_dir,
        form_manifest_path=form_manifest_path,
        privacy_receipt_path=privacy_receipt_path,
        source_commit=source_commit,
        repository_root=repository_root,
    )
    if not all(
        manifest.get("source_commit") == source_commit
        for manifest in (asset_manifest, design_manifest, form_manifest)
    ) or final_manifest.get("repository", {}).get("commit") != source_commit:
        raise EvidenceFailure("submission manifest chain does not share one source commit")
    return {
        "source_commit": source_commit,
        "asset_count": len(EXPECTED_SUBMISSION_ASSETS),
        "document_count": len(EXPECTED_DESIGN_DOCUMENTS),
        "final_document_count": len(EXPECTED_FINAL_SUBMISSION_DOCUMENTS),
        "report_pages": next(
            record["pages"]
            for record in final_manifest["artifacts"]
            if record["path"] == "docs/submission/final/개발보고서 양식.docx"
        ),
        "slides": next(
            record["slides"]
            for record in final_manifest["artifacts"]
            if record["path"] == "docs/submission/final/제작설계서_일반.pptx"
        ),
    }


def _validate_submission_privacy_receipt_chain(
    path: Path,
    *,
    now: datetime,
    max_age_days: int,
    asset_dir: Path,
    document_dir: Path,
    final_document_dir: Path,
    form_manifest_path: Path,
    source_commit: str,
    repository_root: Path = ROOT,
) -> dict[str, Any]:
    payload = _load_submission_manifest(path, "submission visual privacy receipt")
    _require_exact_keys(
        payload,
        {
            "schema_version",
            "source_commit",
            "reviewed_at",
            "reviewer",
            "reviewer_kind",
            "automatic",
            "manual_assertions",
            "release_ready",
        },
        "submission visual privacy receipt",
    )
    if payload.get("schema_version") != "walksafe.submission-visual-privacy.v2":
        raise EvidenceFailure("submission privacy receipt schema_version is invalid")
    if payload.get("source_commit") != source_commit:
        raise EvidenceFailure("submission privacy receipt does not bind the clean validation source commit")
    reviewed_at = parse_timestamp(payload.get("reviewed_at"), "submission visual privacy receipt")
    if reviewed_at > now + timedelta(minutes=5) or now - reviewed_at > timedelta(days=max_age_days):
        raise EvidenceFailure(f"submission privacy receipt must be no older than {max_age_days} days")
    if payload.get("reviewer_kind") != "human" or not meaningful_text(payload.get("reviewer")):
        raise EvidenceFailure("submission privacy receipt requires an identified human reviewer")
    if payload.get("release_ready") is not True:
        raise EvidenceFailure("submission privacy receipt requires release_ready=true")

    manual = _require_exact_keys(
        payload.get("manual_assertions"),
        {
            "no_visible_faces",
            "no_visible_license_plates",
            "no_personal_addresses_or_accounts",
            "office_pdf_render_reviewed",
        },
        "submission privacy manual assertions",
    )
    for field in (
        "no_visible_faces",
        "no_visible_license_plates",
        "no_personal_addresses_or_accounts",
        "office_pdf_render_reviewed",
    ):
        if manual.get(field) is not True:
            raise EvidenceFailure(f"submission privacy receipt requires manual_assertions.{field}=true")

    automatic = _require_exact_keys(
        payload.get("automatic"),
        {
            "assets",
            "metadata_violations",
            "face_candidates",
            "automatic_face_detection_limit",
            "documents",
            "final_documents",
            "office_render",
        },
        "submission privacy automatic evidence",
    )
    if automatic.get("metadata_violations") != []:
        raise EvidenceFailure("submission privacy receipt automatic.metadata_violations must be an empty array")
    if (
        not isinstance(automatic.get("face_candidates"), list)
        or automatic.get("automatic_face_detection_limit")
        != "Haar 후보 검사이며 얼굴·차량번호·OCR 부재를 단독 보장하지 않음"
    ):
        raise EvidenceFailure("submission privacy receipt automatic detector disclosure is invalid")
    assets = automatic.get("assets")
    if not isinstance(assets, list) or not assets:
        raise EvidenceFailure("submission privacy receipt must include audited asset hashes")
    asset_names: set[str] = set()
    for asset in assets:
        if (
            not isinstance(asset, dict)
            or set(asset) != {"name", "sha256", "width", "height"}
            or not meaningful_text(asset.get("name"))
        ):
            raise EvidenceFailure("submission privacy receipt contains an invalid asset entry")
        digest = asset.get("sha256")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest.lower()):
            raise EvidenceFailure("submission privacy receipt contains an invalid asset SHA-256")
        if any(
            isinstance(asset.get(field), bool)
            or not isinstance(asset.get(field), int)
            or asset[field] < 1
            for field in ("width", "height")
        ):
            raise EvidenceFailure("submission privacy receipt contains invalid asset dimensions")
        asset_names.add(asset["name"])
    if asset_names != EXPECTED_SUBMISSION_ASSETS or len(assets) != len(EXPECTED_SUBMISSION_ASSETS):
        raise EvidenceFailure("submission privacy receipt does not cover the exact fixed submission asset set")
    documents = automatic.get("documents")
    if not isinstance(documents, list) or len(documents) != len(EXPECTED_DESIGN_DOCUMENTS):
        raise EvidenceFailure("submission privacy receipt must include all eight design document hashes")
    document_names: set[str] = set()
    for document in documents:
        if (
            not isinstance(document, dict)
            or set(document) != {"name", "sha256"}
            or not meaningful_text(document.get("name"))
        ):
            raise EvidenceFailure("submission privacy receipt contains an invalid document entry")
        digest = document.get("sha256")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest.lower()):
            raise EvidenceFailure("submission privacy receipt contains an invalid document SHA-256")
        document_names.add(document["name"])
    if document_names != EXPECTED_DESIGN_DOCUMENTS:
        raise EvidenceFailure("submission privacy receipt does not cover the exact design document set")
    final_documents = automatic.get("final_documents")
    if not isinstance(final_documents, list) or len(final_documents) != len(EXPECTED_FINAL_SUBMISSION_DOCUMENTS):
        raise EvidenceFailure("submission privacy receipt must include both final Office submission files")
    final_document_names: set[str] = set()
    for document in final_documents:
        if (
            not isinstance(document, dict)
            or set(document) != {"name", "sha256"}
            or not meaningful_text(document.get("name"))
        ):
            raise EvidenceFailure("submission privacy receipt contains an invalid final document entry")
        digest = document.get("sha256")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest.lower()):
            raise EvidenceFailure("submission privacy receipt contains an invalid final document SHA-256")
        final_document_names.add(document["name"])
    if final_document_names != EXPECTED_FINAL_SUBMISSION_DOCUMENTS:
        raise EvidenceFailure("submission privacy receipt does not cover the exact final Office document set")

    def verify_current_files(directory: Path, entries: list[dict[str, Any]], suffix: str, context: str) -> None:
        expected = {str(entry["name"]): str(entry["sha256"]).lower() for entry in entries}
        current = {item.name: item for item in directory.glob(f"*{suffix}") if item.is_file()}
        if set(current) != set(expected):
            raise EvidenceFailure(f"{context} files changed after the privacy review")
        for name, item in current.items():
            if item.is_symlink() or sha256_file(item) != expected[name]:
                raise EvidenceFailure(f"{context} hash changed after the privacy review: {name}")

    verify_current_files(asset_dir, assets, ".png", "submission asset")
    verify_current_files(document_dir, documents, ".docx", "design document")
    expected = {str(entry["name"]): str(entry["sha256"]).lower() for entry in final_documents}
    current = {
        item.name: item
        for item in final_document_dir.iterdir()
        if item.is_file() and item.suffix.lower() in {".docx", ".pptx"}
    }
    if set(current) != set(expected):
        raise EvidenceFailure("final Office submission files changed after the privacy review")
    for name, item in current.items():
        if item.is_symlink() or sha256_file(item) != expected[name]:
            raise EvidenceFailure(f"final Office submission hash changed after the privacy review: {name}")
    office_render = _require_exact_keys(
        automatic.get("office_render"), {"report", "slides"}, "submission privacy Office render"
    )
    report_render = _require_exact_keys(
        office_render.get("report"),
        {"name", "sha256", "pdf_pages"},
        "submission privacy report render",
    )
    slides_render = _require_exact_keys(
        office_render.get("slides"),
        {"name", "sha256", "pdf_pages", "slides", "aspect_ratio"},
        "submission privacy slide render",
    )
    report_pages = report_render.get("pdf_pages")
    if (
        report_render.get("name") != "개발보고서 양식.docx"
        or report_render.get("sha256") != expected["개발보고서 양식.docx"]
        or isinstance(report_pages, bool)
        or not isinstance(report_pages, int)
        or report_pages < 1
        or slides_render.get("name") != "제작설계서_일반.pptx"
        or slides_render.get("sha256") != expected["제작설계서_일반.pptx"]
        or slides_render.get("pdf_pages") != 34
        or slides_render.get("slides") != 34
        or slides_render.get("aspect_ratio") != "4:3"
    ):
        raise EvidenceFailure("submission privacy Office render evidence is invalid")
    manifest_chain = validate_submission_artifact_manifests(
        asset_dir=asset_dir,
        document_dir=document_dir,
        final_document_dir=final_document_dir,
        form_manifest_path=form_manifest_path,
        privacy_receipt_path=path,
        source_commit=source_commit,
        repository_root=repository_root,
    )
    if manifest_chain["report_pages"] != report_pages or manifest_chain["slides"] != 34:
        raise EvidenceFailure("privacy Office render evidence differs from the final build manifest")
    return {
        "reviewed_at": reviewed_at.isoformat(),
        "reviewer": payload["reviewer"],
        "asset_count": len(assets),
        "document_count": len(documents),
        "final_document_count": len(final_documents),
        "source_commit": manifest_chain["source_commit"],
    }


def _submission_python_identity(
    path: Path,
) -> tuple[Path, tuple[int, int, int, int, int], tuple[int, int, int, int, int]]:
    if not path.is_absolute() or Path(os.path.abspath(path)) != path:
        raise EvidenceFailure("--submission-python must be a normalized absolute venv launcher path")
    try:
        launcher = path.lstat()
        resolved = path.resolve(strict=True)
        executable = resolved.stat()
    except OSError as exc:
        raise EvidenceFailure("--submission-python is missing or cannot be resolved") from exc
    if (
        not (stat.S_ISREG(launcher.st_mode) or stat.S_ISLNK(launcher.st_mode))
        or not stat.S_ISREG(executable.st_mode)
        or not os.access(path, os.X_OK)
    ):
        raise EvidenceFailure("--submission-python must resolve to an executable regular file")
    launcher_identity = (
        launcher.st_dev,
        launcher.st_ino,
        launcher.st_size,
        launcher.st_mtime_ns,
        launcher.st_ctime_ns,
    )
    executable_identity = (
        executable.st_dev,
        executable.st_ino,
        executable.st_size,
        executable.st_mtime_ns,
        executable.st_ctime_ns,
    )
    return resolved, launcher_identity, executable_identity


def _run_submission_reproduction_command(
    command: list[str],
    *,
    environment: dict[str, str],
    cwd: Path,
    context: str,
) -> None:
    try:
        subprocess.run(
            command,
            cwd=cwd,
            env=environment,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise EvidenceFailure(f"submission clean-room reproduction failed during {context}") from exc


def _compare_reproduced_file(
    external: Path,
    reproduced: Path,
    *,
    context: str,
) -> tuple[int, str]:
    try:
        with FileSnapshot.capture(external, context=f"external {context}") as external_snapshot:
            with FileSnapshot.capture(reproduced, context=f"reproduced {context}") as reproduced_snapshot:
                if (
                    external_snapshot.size != reproduced_snapshot.size
                    or external_snapshot.read_bytes() != reproduced_snapshot.read_bytes()
                ):
                    raise EvidenceFailure(
                        f"external {context} differs from the clean-room reproduction"
                    )
                return external_snapshot.size, external_snapshot.sha256
    except ReleaseIntegrityError as exc:
        raise EvidenceFailure(f"{context} could not be snapshotted safely: {exc}") from exc


def _compare_reproduced_directory(
    external: Path,
    reproduced: Path,
    *,
    expected_names: set[str] | frozenset[str],
    context: str,
) -> list[tuple[str, int, str]]:
    external_root = _require_external_directory(external, expected_names, f"external {context}")
    reproduced_root = _require_external_directory(
        reproduced, expected_names, f"reproduced {context}"
    )
    records: list[tuple[str, int, str]] = []
    for name in sorted(expected_names):
        size, digest = _compare_reproduced_file(
            external_root / name,
            reproduced_root / name,
            context=f"{context}/{name}",
        )
        records.append((name, size, digest))
    return records


def validate_submission_clean_room_reproduction(
    *,
    submission_python: Path,
    asset_dir: Path,
    document_dir: Path,
    final_document_dir: Path,
    form_manifest_path: Path,
    privacy_receipt_path: Path,
    source_commit: str,
    repository_root: Path = ROOT,
) -> dict[str, Any]:
    python_path = submission_python
    resolved_python, launcher_before, executable_before = _submission_python_identity(python_path)
    repository = Path(os.path.abspath(repository_root))
    try:
        resolved_repository = repository.resolve(strict=True)
    except OSError as exc:
        raise EvidenceFailure("clean validation source cannot be resolved for reproduction") from exc
    if resolved_repository != repository or not repository.is_dir():
        raise EvidenceFailure("clean validation source must be a real non-symlink directory")

    receipt = _regular_external_file(privacy_receipt_path, "submission privacy receipt")
    external_form_manifest = _regular_external_file(
        form_manifest_path, "external submission form manifest"
    )
    _require_external_to_source(receipt, repository, "submission privacy receipt")
    _require_external_to_source(
        external_form_manifest, repository, "external submission form manifest"
    )
    try:
        with FileSnapshot.capture(receipt, context="submission privacy receipt") as receipt_snapshot:
            receipt_bytes = receipt_snapshot.read_bytes()
            receipt_identity = (
                receipt_snapshot.size,
                receipt_snapshot.sha256,
                receipt_snapshot.source_identity,
            )
    except ReleaseIntegrityError as exc:
        raise EvidenceFailure(f"submission privacy receipt cannot be snapshotted: {exc}") from exc

    with tempfile.TemporaryDirectory(prefix="walksafe-submission-reproduction-") as temporary_name:
        temporary = Path(temporary_name)
        private_home = temporary / "home"
        (private_home / "xdg").mkdir(parents=True, mode=0o700)
        runtime = temporary / "runtime"
        runtime.mkdir(mode=0o700)
        locked_receipt = temporary / "privacy-receipt.json"
        locked_receipt.write_bytes(receipt_bytes)
        locked_receipt.chmod(0o400)
        clone = temporary / "source"
        environment = {
            "LC_ALL": "C",
            "LANG": "C",
            "PATH": "/usr/bin:/bin",
            "HOME": str(private_home),
            "XDG_CONFIG_HOME": str(private_home / "xdg"),
            "TMPDIR": str(runtime),
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_SYSTEM": "/dev/null",
            "GIT_CONFIG_GLOBAL": "/dev/null",
            "GIT_NO_REPLACE_OBJECTS": "1",
            "GIT_OPTIONAL_LOCKS": "0",
            "GIT_TERMINAL_PROMPT": "0",
        }
        git_prefix = [
            "/usr/bin/git",
            "-c",
            "core.fsmonitor=false",
            "-c",
            "core.untrackedCache=false",
        ]
        _run_submission_reproduction_command(
            [*git_prefix, "clone", "--no-local", "--no-checkout", "--", str(repository), str(clone)],
            environment=environment,
            cwd=temporary,
            context="private no-local clone",
        )
        _run_submission_reproduction_command(
            [*git_prefix, "-C", str(clone), "checkout", "--detach", source_commit],
            environment=environment,
            cwd=temporary,
            context="detached source checkout",
        )

        runner = clone / "scripts/run_walksafe_submission_python_20260714.py"
        prefix = [
            str(python_path),
            "-I",
            "-S",
            "-B",
            str(runner),
            "--repo-root",
            str(clone),
            "--expected-commit",
            source_commit,
        ]
        _run_submission_reproduction_command(
            [*prefix, "--verify-only"],
            environment=environment,
            cwd=clone,
            context="exact submission toolchain verification",
        )
        steps = (
            ("asset build", ("scripts/build_submission_assets_20260710.py",)),
            ("asset validation", ("scripts/validate_submission_materials_20260710.py",)),
            ("design build", ("scripts/build_design_documents_20260710.py",)),
            (
                "design validation",
                ("scripts/build_design_documents_20260710.py", "--validate-only"),
            ),
            ("form build", ("scripts/build_submission_forms_20260710.py",)),
            (
                "working form validation",
                ("scripts/validate_submission_forms_20260710.py", "--working-only"),
            ),
            (
                "final promotion",
                (
                    "scripts/promote_submission_final_20260713.py",
                    "--privacy-receipt",
                    str(locked_receipt),
                ),
            ),
            (
                "promoted form validation",
                (
                    "scripts/validate_submission_forms_20260710.py",
                    "--final-dir",
                    "docs/submission/final",
                ),
            ),
        )
        for context, arguments in steps:
            _run_submission_reproduction_command(
                [*prefix, "--", *arguments],
                environment=environment,
                cwd=clone,
                context=context,
            )

        records: list[tuple[str, int, str]] = []
        records.extend(
            (f"assets/{name}", size, digest)
            for name, size, digest in _compare_reproduced_directory(
                asset_dir,
                clone / "docs/submission/form_materials/assets",
                expected_names=ASSET_DIRECTORY_FILES,
                context="submission assets",
            )
        )
        records.extend(
            (f"design-documents/{name}", size, digest)
            for name, size, digest in _compare_reproduced_directory(
                document_dir,
                clone / "docs/submission/design_documents",
                expected_names=DESIGN_OUTPUT_NAMES,
                context="design documents",
            )
        )
        size, digest = _compare_reproduced_file(
            external_form_manifest,
            clone / "templates/SUBMISSION_BUILD_MANIFEST.json",
            context="submission form manifest",
        )
        records.append(("SUBMISSION_BUILD_MANIFEST.json", size, digest))
        records.extend(
            (f"final/{name}", size, digest)
            for name, size, digest in _compare_reproduced_directory(
                final_document_dir,
                clone / "docs/submission/final",
                expected_names=FINAL_OUTPUT_NAMES,
                context="final submission",
            )
        )

    try:
        with FileSnapshot.capture(receipt, context="submission privacy receipt") as current_receipt:
            current_receipt_identity = (
                current_receipt.size,
                current_receipt.sha256,
                current_receipt.source_identity,
            )
    except ReleaseIntegrityError as exc:
        raise EvidenceFailure(f"submission privacy receipt cannot be rechecked: {exc}") from exc
    if current_receipt_identity != receipt_identity:
        raise EvidenceFailure("submission privacy receipt changed during clean-room reproduction")
    resolved_after, launcher_after, executable_after = _submission_python_identity(python_path)
    if (
        resolved_after != resolved_python
        or launcher_after != launcher_before
        or executable_after != executable_before
    ):
        raise EvidenceFailure("submission Python launcher changed during clean-room reproduction")
    canonical_records = "".join(
        f"{name}\0{size}\0{digest}\n" for name, size, digest in sorted(records)
    ).encode("utf-8")
    return {
        "source_commit": source_commit,
        "file_count": len(records),
        "artifact_bundle_sha256": hashlib.sha256(canonical_records).hexdigest(),
        "python_resolved_path": str(resolved_python),
    }


def validate_submission_privacy_receipt(
    path: Path,
    *,
    now: datetime,
    max_age_days: int,
    asset_dir: Path,
    document_dir: Path,
    final_document_dir: Path,
    form_manifest_path: Path,
    submission_python: Path,
    source_commit: str,
    repository_root: Path = ROOT,
) -> dict[str, Any]:
    result = _validate_submission_privacy_receipt_chain(
        path,
        now=now,
        max_age_days=max_age_days,
        asset_dir=asset_dir,
        document_dir=document_dir,
        final_document_dir=final_document_dir,
        form_manifest_path=form_manifest_path,
        source_commit=source_commit,
        repository_root=repository_root,
    )
    result["clean_room_reproduction"] = validate_submission_clean_room_reproduction(
        submission_python=submission_python,
        asset_dir=asset_dir,
        document_dir=document_dir,
        final_document_dir=final_document_dir,
        form_manifest_path=form_manifest_path,
        privacy_receipt_path=path,
        source_commit=source_commit,
        repository_root=repository_root,
    )
    return result


def validate_design_document_manifest(
    document_dir: Path,
    *,
    source_commit: str,
    repository_root: Path = ROOT,
) -> dict[str, Any]:
    manifest_path = document_dir / "BUILD_MANIFEST.json"
    if manifest_path.is_symlink() or not manifest_path.is_file():
        raise EvidenceFailure("design document build manifest is missing or is a symlink")
    payload = load_json_object(manifest_path, "design document build manifest")
    if payload.get("schema_version") != "walksafe.design-documents.v2":
        raise EvidenceFailure("design document build manifest schema_version is invalid")
    if payload.get("source_commit") != source_commit or payload.get("source_dirty") is not False:
        raise EvidenceFailure("design documents must be rebuilt from the clean release source commit")

    repository_root = repository_root.resolve()

    def bound_file(relative: Any, *, context: str) -> Path:
        if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
            raise EvidenceFailure(f"{context} path must be repository-relative")
        unresolved = Path(os.path.abspath(repository_root / relative))
        try:
            resolved = unresolved.resolve(strict=True)
        except OSError as exc:
            raise EvidenceFailure(f"{context} path is missing") from exc
        if resolved != unresolved or repository_root not in resolved.parents or not resolved.is_file():
            raise EvidenceFailure(f"{context} path must be a real repository file")
        return resolved

    def verify_record(record: Any, *, path_key: str, context: str) -> Path:
        if not isinstance(record, dict):
            raise EvidenceFailure(f"{context} entry is invalid")
        path = bound_file(record.get(path_key), context=context)
        digest = record.get("sha256")
        if not isinstance(digest, str) or digest.lower() != sha256_file(path):
            raise EvidenceFailure(f"{context} SHA-256 does not match current source")
        size = record.get("bytes")
        if not isinstance(size, int) or isinstance(size, bool) or size != path.stat().st_size:
            raise EvidenceFailure(f"{context} byte size does not match current source")
        return path

    bindings = payload.get("implementation_bindings")
    if not isinstance(bindings, list):
        raise EvidenceFailure("design document implementation bindings are required")
    binding_names: set[str] = set()
    for record in bindings:
        verify_record(record, path_key="file", context="design implementation binding")
        binding_names.add(str(record["file"]))
    if len(bindings) != len(binding_names) or not EXPECTED_DESIGN_IMPLEMENTATION_BINDINGS.issubset(binding_names):
        raise EvidenceFailure("design document implementation binding set is incomplete or duplicated")

    documents = payload.get("documents")
    if not isinstance(documents, list):
        raise EvidenceFailure("design document records are required")
    document_names: set[str] = set()
    for record in documents:
        if not isinstance(record, dict) or record.get("file") not in EXPECTED_DESIGN_DOCUMENTS:
            raise EvidenceFailure("design document record set is invalid")
        name = str(record["file"])
        document_names.add(name)
        current = document_dir / name
        if current.is_symlink() or not current.is_file() or record.get("sha256") != sha256_file(current):
            raise EvidenceFailure(f"design document hash does not match the build manifest: {name}")
        source = bound_file(record.get("source"), context=f"design document source for {name}")
        if record.get("source_sha256") != sha256_file(source):
            raise EvidenceFailure(f"design document source changed after build: {name}")
    if len(documents) != len(document_names) or document_names != EXPECTED_DESIGN_DOCUMENTS:
        raise EvidenceFailure("design document build manifest does not cover the exact document set")
    return {
        "source_commit": source_commit,
        "document_count": len(documents),
        "implementation_binding_count": len(bindings),
    }


def configured_accounts(environment_name: str) -> list[dict[str, str]]:
    raw = os.environ.get(environment_name, "").strip()
    if not raw:
        raise EvidenceFailure(f"{environment_name} must contain account-type credentials")
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise EvidenceFailure(f"{environment_name} is not valid JSON") from exc
    if not isinstance(payload, list) or not 1 <= len(payload) <= 100:
        raise EvidenceFailure(f"{environment_name} must be a non-empty account array with at most 100 entries")
    accounts: list[dict[str, str]] = []
    for index, value in enumerate(payload):
        if not isinstance(value, dict):
            raise EvidenceFailure(f"{environment_name}[{index}] must be an object")
        actor_id = value.get("actor_id")
        token = value.get("token")
        if not isinstance(actor_id, str) or ACTOR_ID.fullmatch(actor_id.strip()) is None:
            raise EvidenceFailure(f"{environment_name}[{index}].actor_id is invalid")
        normalized_actor_id = actor_id.strip().casefold()
        if normalized_actor_id in {"unknown", "system", "anonymous"} or normalized_actor_id.endswith("-shared"):
            raise EvidenceFailure(f"{environment_name}[{index}].actor_id must identify a named account")
        if not isinstance(token, str) or len(token.strip()) < 24:
            raise EvidenceFailure(f"{environment_name}[{index}].token must be at least 24 characters")
        accounts.append({"actor_id": actor_id.strip(), "token": token.strip()})
    if len({account["actor_id"] for account in accounts}) != len(accounts):
        raise EvidenceFailure(f"{environment_name} actor_id values must be unique")
    if len({account["token"] for account in accounts}) != len(accounts):
        raise EvidenceFailure(f"{environment_name} account tokens must be unique")
    return accounts


def validate_live_rbac_environment(check_value: Any) -> dict[str, Any]:
    field_internal = os.environ.get("WALKSAFE_FIELD_TEST_TOKEN", "").strip()
    admin_internal = os.environ.get("WALKSAFE_ADMIN_TOKEN", "").strip()
    session_secret = os.environ.get("WALKSAFE_GATEWAY_SESSION_SECRET", "").strip()
    if len(field_internal) < 24 or len(admin_internal) < 24:
        raise EvidenceFailure("field/admin internal service tokens must each be at least 24 characters")
    if field_internal == admin_internal:
        raise EvidenceFailure("field/admin internal service tokens must be distinct")
    if len(session_secret) < 32:
        raise EvidenceFailure("WALKSAFE_GATEWAY_SESSION_SECRET must be a separate value of at least 32 characters")
    if session_secret in {field_internal, admin_internal}:
        raise EvidenceFailure("gateway session secret must differ from both internal service tokens")

    field_accounts = configured_accounts("WALKSAFE_FIELD_ACCOUNTS_JSON")
    admin_accounts = configured_accounts("WALKSAFE_ADMIN_ACCOUNTS_JSON")
    all_account_tokens = [account["token"] for account in field_accounts + admin_accounts]
    if len(set(all_account_tokens)) != len(all_account_tokens):
        raise EvidenceFailure("field/admin account tokens must be unique across both roles")
    if any(token in {field_internal, admin_internal, session_secret} for token in all_account_tokens):
        raise EvidenceFailure("login account tokens must differ from internal service tokens and the session secret")

    if not isinstance(check_value, dict) or not isinstance(check_value.get("details"), dict):
        raise EvidenceFailure("checks.account_rbac_configured.details must record the verified account counts")
    details = check_value["details"]
    if details.get("field_account_count") != len(field_accounts):
        raise EvidenceFailure("account RBAC evidence field_account_count does not match the live environment")
    if details.get("admin_account_count") != len(admin_accounts):
        raise EvidenceFailure("account RBAC evidence admin_account_count does not match the live environment")
    if details.get("internal_tokens_distinct") is not True or details.get("session_secret_configured") is not True:
        raise EvidenceFailure("account RBAC evidence must attest distinct internal tokens and a separate session secret")
    return {"field_account_count": len(field_accounts), "admin_account_count": len(admin_accounts)}


def validate_backup_storage_evidence(check_value: Any) -> dict[str, bool]:
    if not isinstance(check_value, dict) or not isinstance(check_value.get("details"), dict):
        raise EvidenceFailure("checks.backup_storage_protection.details is required")
    details = check_value["details"]
    for field in ("encrypted_at_rest", "restricted_access", "separate_failure_domain"):
        if details.get(field) is not True:
            raise EvidenceFailure(f"backup storage evidence requires {field}=true")
    return {field: True for field in ("encrypted_at_rest", "restricted_access", "separate_failure_domain")}


def validate_web_accessibility_evidence(check_value: Any) -> dict[str, Any]:
    if not isinstance(check_value, dict) or not isinstance(check_value.get("details"), dict):
        raise EvidenceFailure("checks.web_accessibility_tts_haptic.details is required")
    details = check_value["details"]
    for field in ("device", "browser", "screen_reader"):
        if not meaningful_text(details.get(field)):
            raise EvidenceFailure(f"web accessibility evidence requires a non-placeholder {field}")
    for field in ("tts_verified", "haptic_verified", "risk_priority_verified", "unavailable_state_verified"):
        if details.get(field) is not True:
            raise EvidenceFailure(f"web accessibility evidence requires {field}=true")
    return {
        "device": details["device"],
        "browser": details["browser"],
        "screen_reader": details["screen_reader"],
        "tts_verified": True,
        "haptic_verified": True,
        "risk_priority_verified": True,
        "unavailable_state_verified": True,
    }


def required_checks(profile: str, deployment_provider: str, extra: list[str]) -> list[str]:
    if deployment_provider != "tmap_pedestrian":
        raise EvidenceFailure("only the tmap_pedestrian walking route provider is supported")
    required: list[str] = []
    if profile in {"web-release", "full"}:
        required.extend(WEB_EXTERNAL_CHECKS)
        required.append("tmap_live_route_smoke")
    if profile in {"android-research", "full"}:
        required.extend(ANDROID_RESEARCH_CHECKS)
    if profile == "full":
        required.extend(FULL_PHYSICAL_ACCEPTANCE_CHECKS)
    required.extend(extra)
    return list(dict.fromkeys(required))


def validate_check(
    check_id: str,
    value: Any,
    *,
    now: datetime,
    max_age: timedelta,
    evidence_root: Path,
    source_commit: str | None = None,
    release_artifacts_sha256: str | None = None,
    android_apk_sha256: str | None = None,
) -> datetime:
    context = f"checks.{check_id}"
    if not isinstance(value, dict):
        raise EvidenceFailure(f"{context} is missing or is not an object")
    if value.get("passed") is not True:
        raise EvidenceFailure(f"{context}.passed must be true after direct observation")
    observed_at = parse_timestamp(value.get("observed_at"), context)
    if observed_at > now + timedelta(minutes=5):
        raise EvidenceFailure(f"{context}.observed_at is in the future")
    if now - observed_at > max_age:
        raise EvidenceFailure(f"{context} is stale ({(now - observed_at).days} days old; max {max_age.days})")
    if not meaningful_text(value.get("evidence_ref")):
        raise EvidenceFailure(f"{context}.evidence_ref must point to a non-placeholder log, receipt, or recording")
    evidence_path = _bound_regular_file(
        value.get("evidence_ref"),
        value.get("evidence_sha256"),
        base_dir=evidence_root,
        context=f"{context}.evidence_ref",
    )
    if not meaningful_text(value.get("environment")):
        raise EvidenceFailure(f"{context}.environment must identify the device/provider/institution environment")
    if source_commit is not None and value.get("source_commit") != source_commit:
        raise EvidenceFailure(f"{context}.source_commit does not match the tested release source")
    if release_artifacts_sha256 is not None and value.get("release_artifacts_sha256") != release_artifacts_sha256:
        raise EvidenceFailure(f"{context}.release_artifacts_sha256 does not match the tested release artifacts")
    if check_id in STRUCTURED_EXTERNAL_CHECKS:
        if source_commit is None or release_artifacts_sha256 is None:
            raise EvidenceFailure(f"{context} structured receipt requires release source and artifact bindings")
        try:
            receipt = validate_external_check_receipt(
                check_id,
                evidence_path,
                source_commit=source_commit,
                release_artifacts_sha256=release_artifacts_sha256,
                observed_at=observed_at,
                android_apk_sha256=android_apk_sha256,
            )
        except ExternalCheckReceiptFailure as exc:
            raise EvidenceFailure(str(exc)) from exc
        if check_id == "android_arcore_unsupported_camera_field":
            field_apk = receipt.get("field_apk")
            if not isinstance(field_apk, dict):
                raise EvidenceFailure(f"{context} field APK binding is missing")
            validate_android_field_apk(
                Path(str(field_apk.get("path"))),
                source_commit=source_commit,
                expected_sha256=str(field_apk.get("sha256")),
            )
        if check_id == "web_accessibility_tts_haptic":
            details = validate_web_accessibility_evidence(value)
            expected = {
                "device": receipt["environment"]["device"],
                "browser": receipt["environment"]["browser"],
                "screen_reader": receipt["environment"]["screen_reader"],
                **receipt["result"],
            }
            if details != expected:
                raise EvidenceFailure(
                    f"{context}.details must exactly match the structured accessibility receipt"
                )
    return observed_at


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--web-build-manifest", type=Path, help="Web manifest for artifact-only CI precheck.")
    parser.add_argument("--web-build-root", type=Path, help="Standalone Web directory for artifact-only CI precheck.")
    parser.add_argument("--web-deployment-archive", type=Path, help="Web tar.gz for artifact-only CI precheck.")
    parser.add_argument("--print-android-tool-pins", action="store_true")
    parser.add_argument("--android-java", type=Path)
    parser.add_argument("--android-apksigner", type=Path)
    parser.add_argument("--android-apkanalyzer", type=Path)
    parser.add_argument("--profile", choices=("web-release", "android-research", "full"), default="web-release")
    parser.add_argument("--full-rc-manifest", type=Path)
    parser.add_argument("--full-rc-validation-receipt", type=Path)
    parser.add_argument("--full-rc-unsigned-apk", type=Path)
    parser.add_argument("--operator-attestation", type=Path)
    parser.add_argument("--operator-attestation-signature", type=Path)
    parser.add_argument("--expected-operator-fingerprint")
    parser.add_argument("--gpg", type=Path)
    parser.add_argument("--expected-gpg-sha256")
    parser.add_argument("--gpg-keyring", type=Path)
    parser.add_argument("--java", type=Path)
    parser.add_argument("--expected-java-sha256")
    parser.add_argument("--apksigner-jar", type=Path)
    parser.add_argument("--expected-apksigner-jar-sha256")
    parser.add_argument("--android-signing-gate-receipt", type=Path)
    parser.add_argument("--blocker-resolution-receipt", type=Path)
    parser.add_argument("--blocker-resolution-signature", type=Path)
    parser.add_argument("--production-receipt", type=Path)
    parser.add_argument(
        "--deployment-provider",
        choices=("tmap_pedestrian",),
        default="tmap_pedestrian",
        help="WalkSafe's TMAP pedestrian route provider; its live smoke is required.",
    )
    parser.add_argument("--required-check", action="append", default=[], help="Additional evidence check id; repeatable.")
    parser.add_argument("--max-age-days", type=int, default=30)
    parser.add_argument("--retention-receipt", type=Path, help="JSON receipt written by manage_field_telemetry_retention_20260711.py --apply.")
    parser.add_argument(
        "--test-capture-retention-receipt",
        type=Path,
        help="JSON receipt written with manage_field_telemetry_retention_20260711.py --scope test_capture --apply.",
    )
    parser.add_argument("--retention-receipt-max-age-hours", type=int, default=26)
    parser.add_argument("--report-retention-manifest", type=Path, help="Completed report retention apply audit manifest.")
    parser.add_argument("--report-retention-max-age-hours", type=int, default=26)
    parser.add_argument("--backup-manifest", type=Path, help="manifest.json in a completed walksafe.backup.v1 directory.")
    parser.add_argument("--backup-max-age-hours", type=int, default=26)
    parser.add_argument("--restore-drill-receipt", type=Path, help="Latest walksafe.restore-drill.v1 receipt.")
    parser.add_argument("--restore-drill-max-age-days", type=int, default=30)
    parser.add_argument("--agency-submission-receipt", type=Path, help="Latest external institution receipt without raw coordinates.")
    parser.add_argument("--agency-export", type=Path, help="Exact export file referenced by the institution receipt.")
    parser.add_argument("--agency-export-manifest", type=Path, help="Manifest generated from the same agency export rows.")
    parser.add_argument("--submission-privacy-receipt", type=Path, help="Human walksafe.submission-visual-privacy.v2 receipt.")
    parser.add_argument("--submission-asset-dir", type=Path, default=Path("docs/submission/form_materials/assets"))
    parser.add_argument("--design-document-dir", type=Path, default=Path("docs/submission/design_documents"))
    parser.add_argument("--final-submission-dir", type=Path, default=Path("docs/submission/final"))
    parser.add_argument(
        "--submission-form-manifest",
        type=Path,
        help="External canonical templates/SUBMISSION_BUILD_MANIFEST.json copied from the build clone.",
    )
    parser.add_argument(
        "--submission-python",
        type=Path,
        help="Absolute exact-8 submission venv launcher used for an independent clean-room rebuild.",
    )
    parser.add_argument("--model-registry", type=Path, default=Path("model/registry/walksafe-model-registry.json"))
    parser.add_argument("--deployment-ledger", type=Path, default=Path("model/deployments/local-deployment.json"))
    parser.add_argument(
        "--print-release-artifact-binding",
        action="store_true",
        help="Validate the release artifacts, print their canonical SHA-256 binding, and stop before external checks.",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.profile in {"web-release", "full"}:
        print(
            "BLOCKED: Web/full release evidence profiles are LEGACY_REFERENCE_ONLY under FP-009.",
            file=sys.stderr,
        )
        return 78
    if args.print_android_tool_pins:
        if args.evidence is not None or any(
            (args.web_build_manifest, args.web_build_root, args.web_deployment_archive)
        ):
            raise EvidenceFailure("Android tool pin inspection cannot be combined with release evidence inputs")
        if not all((args.android_java, args.android_apksigner, args.android_apkanalyzer)):
            raise EvidenceFailure(
                "--print-android-tool-pins requires --android-java, --android-apksigner and --android-apkanalyzer"
            )
        print(
            json.dumps(
                android_tool_pin_records(
                    java_path=args.android_java,
                    apksigner_path=args.android_apksigner,
                    apkanalyzer_path=args.android_apkanalyzer,
                ),
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            )
        )
        return 0
    if args.profile != "full":
        validate_release_chain_options(args)
    if args.max_age_days < 1 or args.max_age_days > 365:
        raise EvidenceFailure("--max-age-days must be between 1 and 365")

    precheck_paths = (args.web_build_manifest, args.web_build_root, args.web_deployment_archive)
    if any(precheck_paths):
        if not all(precheck_paths):
            raise EvidenceFailure("artifact-only precheck requires Web manifest, build root and deployment archive")
        if args.evidence is not None or not args.print_release_artifact_binding or args.profile != "web-release":
            raise EvidenceFailure("artifact-only Web inputs require --profile web-release and --print-release-artifact-binding without --evidence")
        manifest_argument = args.web_build_manifest.expanduser()
        build_root_argument = args.web_build_root.expanduser()
        archive_argument = args.web_deployment_archive.expanduser()
        if manifest_argument.is_symlink() or not manifest_argument.is_file():
            raise EvidenceFailure("artifact-only Web build manifest is missing or is a symlink")
        if archive_argument.is_symlink() or not archive_argument.is_file():
            raise EvidenceFailure("artifact-only Web deployment archive is missing or is a symlink")
        if build_root_argument.is_symlink() or not build_root_argument.is_dir():
            raise EvidenceFailure("artifact-only Web build root is missing or is a symlink")
        manifest_path = manifest_argument.resolve()
        archive_path = archive_argument.resolve()
        manifest = load_json_object(manifest_path, "web build manifest")
        payload = {
            "source_commit": manifest.get("source_commit"),
            "release_artifacts": {
                "web_build": {
                    "manifest_path": str(manifest_path),
                    "manifest_sha256": sha256_file(manifest_path),
                    "build_root": str(build_root_argument.resolve()),
                    "archive_path": str(archive_path),
                    "archive_sha256": sha256_file(archive_path),
                }
            },
        }
        evidence_base = manifest_path.parent
    else:
        if args.evidence is None:
            raise EvidenceFailure("--evidence is required unless artifact-only Web precheck inputs are provided")
        payload = load_evidence(args.evidence)
        evidence_base = args.evidence.resolve().parent
    source_commit = validate_source_revision(payload)
    _load_release_dependencies()
    release_artifacts = validate_release_artifacts(
        payload,
        profile=args.profile,
        base_dir=evidence_base,
        source_commit=source_commit,
    )
    release_artifacts_sha256 = hashlib.sha256(
        json.dumps(release_artifacts, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    if args.print_release_artifact_binding:
        print(release_artifacts_sha256)
        return 0
    validate_release_chain_options(args)
    checks = payload["checks"]
    now = datetime.now(UTC)
    max_age = timedelta(days=args.max_age_days)
    required = required_checks(args.profile, args.deployment_provider, args.required_check)
    android_apk_sha256 = (
        release_artifacts.get("android_apk", {}).get("sha256")
        if isinstance(release_artifacts.get("android_apk"), dict)
        else None
    )
    observed: dict[str, str] = {}
    failures: list[str] = []

    for check_id in required:
        try:
            timestamp = validate_check(
                check_id,
                checks.get(check_id),
                now=now,
                max_age=max_age,
                evidence_root=args.evidence.resolve().parent,
                source_commit=source_commit,
                release_artifacts_sha256=release_artifacts_sha256,
                android_apk_sha256=android_apk_sha256,
            )
            observed[check_id] = timestamp.isoformat()
        except EvidenceFailure as exc:
            failures.append(str(exc))

    if failures:
        raise EvidenceFailure("release evidence gate failed:\n- " + "\n- ".join(failures))

    operational: dict[str, Any] = {}
    if args.profile in {"web-release", "full"}:
        operational["running_web_build"] = validate_running_web_build(release_artifacts["web_build"])
        operational["design_documents"] = validate_design_document_manifest(
            args.design_document_dir,
            source_commit=source_commit,
        )
        operational["running_backend_source"] = validate_running_backend_source(source_commit)
        live_environment = validate_live_release_environment(args.deployment_provider)
        if live_environment["source_commit"] != source_commit:
            raise EvidenceFailure("running backend source commit does not match the release source_commit")
        operational["live_environment"] = {
            "environment": live_environment["environment"],
            "provider": live_environment["provider"],
            "source_commit": live_environment["source_commit"],
            "trusted_ip_header": live_environment["trusted_ip_header"],
            "database_identity_sha256": live_environment["database_identity_sha256"],
            "upload_root_identity_sha256": live_environment["upload_root_identity_sha256"],
            "gateway_rate_limit_root_identity_sha256": path_identity_sha256(
                live_environment["gateway_rate_limit_root"]
            ),
            "maintenance_lock_identity_sha256": path_identity_sha256(
                live_environment["maintenance_lock_path"]
            ),
            "maintenance_lock_device_inode": live_environment["maintenance_lock_device_inode"],
        }
        operational["detector_runtime"] = validate_live_detector_environment(
            checks.get("detector_runtime_live"),
            deployment_provider=args.deployment_provider,
        )
        operational["registered_deployment"] = validate_registered_deployment(
            args.model_registry.resolve(),
            args.deployment_ledger.resolve(),
            model_sha256=operational["detector_runtime"]["unified_model_sha256"],
        )
        if not args.retention_receipt:
            raise EvidenceFailure("--retention-receipt is required for web/full production release evidence")
        if args.retention_receipt_max_age_hours < 1 or args.retention_receipt_max_age_hours > 168:
            raise EvidenceFailure("--retention-receipt-max-age-hours must be between 1 and 168")
        operational["field_telemetry_retention"] = validate_retention_receipt(
            args.retention_receipt,
            now=now,
            max_age_hours=args.retention_receipt_max_age_hours,
            expected_root=live_environment["field_log_root"],
        )
        if not args.test_capture_retention_receipt:
            raise EvidenceFailure("--test-capture-retention-receipt is required for web/full production release evidence")
        operational["test_capture_retention"] = validate_retention_receipt(
            args.test_capture_retention_receipt,
            now=now,
            max_age_hours=args.retention_receipt_max_age_hours,
            scope="test_capture",
            expected_root=live_environment["test_log_root"],
        )
        if not args.report_retention_manifest:
            raise EvidenceFailure("--report-retention-manifest is required for web/full production release evidence")
        if args.report_retention_max_age_hours < 1 or args.report_retention_max_age_hours > 168:
            raise EvidenceFailure("--report-retention-max-age-hours must be between 1 and 168")
        operational["report_retention"] = validate_report_retention_manifest(
            args.report_retention_manifest,
            now=now,
            max_age_hours=args.report_retention_max_age_hours,
            expected_database_identity=live_environment["database_identity_sha256"],
            expected_upload_identity=live_environment["upload_root_identity_sha256"],
        )
        operational["account_rbac"] = validate_live_rbac_environment(checks.get("account_rbac_configured"))
        if not args.backup_manifest:
            raise EvidenceFailure("--backup-manifest is required for web/full production release evidence")
        if args.backup_max_age_hours < 1 or args.backup_max_age_hours > 168:
            raise EvidenceFailure("--backup-max-age-hours must be between 1 and 168")
        operational["backup"] = validate_backup_manifest(
            args.backup_manifest,
            now=now,
            max_age_hours=args.backup_max_age_hours,
            expected_database_identity=live_environment["database_identity_sha256"],
            expected_upload_identity=live_environment["upload_root_identity_sha256"],
        )
        if operational["report_retention"]["predelete_backup_run_id"] != operational["backup"]["run_id"]:
            raise EvidenceFailure("report retention was not preceded by the current verified backup run")
        if operational["report_retention"]["predelete_backup_created_at"] != operational["backup"]["created_at"]:
            raise EvidenceFailure("report retention backup timestamp does not match the current backup manifest")
        if (
            operational["report_retention"]["predelete_backup_artifacts_sha256"]
            != operational["backup"]["artifacts_sha256"]
        ):
            raise EvidenceFailure("report retention backup artifact hashes do not match the current backup")
        if (
            operational["report_retention"]["maintenance_lock_identity_sha256"]
            != operational["backup"]["maintenance_lock_identity_sha256"]
        ):
            raise EvidenceFailure("report retention and backup did not use the same maintenance lock")
        if (
            operational["backup"]["maintenance_lock_identity_sha256"]
            != operational["live_environment"]["maintenance_lock_identity_sha256"]
        ):
            raise EvidenceFailure("backup/retention maintenance lock does not match the live environment")
        if not (
            operational["report_retention"]["maintenance_lock_device_inode"]
            == operational["backup"]["maintenance_lock_device_inode"]
            == operational["live_environment"]["maintenance_lock_device_inode"]
        ):
            raise EvidenceFailure(
                "backup/retention maintenance lock authority does not match the live environment"
            )
        operational["backup_storage"] = validate_backup_storage_evidence(checks.get("backup_storage_protection"))
        if not args.restore_drill_receipt:
            raise EvidenceFailure("--restore-drill-receipt is required for web/full production release evidence")
        if args.restore_drill_max_age_days < 1 or args.restore_drill_max_age_days > 365:
            raise EvidenceFailure("--restore-drill-max-age-days must be between 1 and 365")
        operational["restore_drill"] = validate_restore_drill_receipt(
            args.restore_drill_receipt,
            now=now,
            max_age_days=args.restore_drill_max_age_days,
        )
        if operational["backup"]["recipient_fingerprint"] != operational["restore_drill"]["recipient_fingerprint"]:
            raise EvidenceFailure("restore drill recipient fingerprint does not match the current backup manifest")
        if operational["backup"]["signer_fingerprint"] != operational["restore_drill"]["signer_fingerprint"]:
            raise EvidenceFailure("restore drill signer fingerprint does not match the trusted backup signer")
        if operational["backup"]["run_id"] != operational["restore_drill"]["backup_run_id"]:
            raise EvidenceFailure("restore drill does not reference the current backup run")
        if operational["backup"]["artifacts_sha256"] != operational["restore_drill"]["artifacts_sha256"]:
            raise EvidenceFailure("restore drill artifact hashes do not match the current backup")
        if (
            operational["report_retention"]["predelete_restore_receipt_sha256"]
            != operational["restore_drill"]["receipt_sha256"]
            or operational["report_retention"]["predelete_restore_restored_at"]
            != operational["restore_drill"]["restored_at"]
        ):
            raise EvidenceFailure("report retention was not guarded by the current verified restore drill")
        if (
            operational["backup"]["database_identity_sha256"]
            != operational["restore_drill"]["source_database_identity_sha256"]
            or operational["backup"]["upload_root_identity_sha256"]
            != operational["restore_drill"]["source_upload_root_identity_sha256"]
        ):
            raise EvidenceFailure("restore drill source environment does not match the current backup")
        if not args.agency_submission_receipt:
            raise EvidenceFailure("--agency-submission-receipt is required for web/full production release evidence")
        if not args.agency_export or not args.agency_export_manifest:
            raise EvidenceFailure("--agency-export and --agency-export-manifest are required for web/full release evidence")
        operational["agency_submission"] = validate_agency_submission_receipt(
            args.agency_submission_receipt,
            now=now,
            max_age_days=args.max_age_days,
            evidence_check=checks.get("institution_submission_acceptance"),
            export_path=args.agency_export,
            export_manifest_path=args.agency_export_manifest,
        )
        if not args.submission_privacy_receipt:
            raise EvidenceFailure("--submission-privacy-receipt is required for web/full production release evidence")
        if not args.submission_form_manifest:
            raise EvidenceFailure("--submission-form-manifest is required for web/full production release evidence")
        if not args.submission_python:
            raise EvidenceFailure("--submission-python is required for web/full production release evidence")
        operational["submission_visual_privacy"] = validate_submission_privacy_receipt(
            args.submission_privacy_receipt,
            now=now,
            max_age_days=args.max_age_days,
            asset_dir=args.submission_asset_dir,
            document_dir=args.design_document_dir,
            final_document_dir=args.final_submission_dir,
            form_manifest_path=args.submission_form_manifest,
            submission_python=args.submission_python,
            source_commit=source_commit,
        )

    full_rc_signing_chain: dict[str, Any] | None = None
    blocker_resolution: dict[str, Any] | None = None
    production_release: dict[str, Any] | None = None
    if args.profile == "full":
        full_rc_signing_chain = validate_full_release_signing_chain(
            source_commit=source_commit,
            payload=payload,
            release_artifacts=release_artifacts,
            base_dir=evidence_base,
            manifest_path=args.full_rc_manifest,
            validation_receipt_path=args.full_rc_validation_receipt,
            unsigned_apk_path=args.full_rc_unsigned_apk,
            operator_attestation_path=args.operator_attestation,
            operator_attestation_signature_path=args.operator_attestation_signature,
            expected_operator_fingerprint=args.expected_operator_fingerprint,
            gpg_path=args.gpg,
            expected_gpg_sha256=args.expected_gpg_sha256,
            gpg_keyring_path=args.gpg_keyring,
            java_path=args.java,
            expected_java_sha256=args.expected_java_sha256,
            apksigner_jar_path=args.apksigner_jar,
            expected_apksigner_jar_sha256=args.expected_apksigner_jar_sha256,
            signing_gate_receipt_path=args.android_signing_gate_receipt,
        )
        blocker_resolution = validate_production_blocker_resolution(
            receipt_path=args.blocker_resolution_receipt,
            signature_path=args.blocker_resolution_signature,
            manifest_path=args.full_rc_manifest,
            source_commit=source_commit,
            release_artifacts_sha256=release_artifacts_sha256,
            full_rc_signing_chain=full_rc_signing_chain,
            expected_operator_fingerprint=args.expected_operator_fingerprint,
            gpg_path=args.gpg,
            expected_gpg_sha256=args.expected_gpg_sha256,
            gpg_keyring_path=args.gpg_keyring,
            now=now,
            max_age=max_age,
        )
        declared_android = payload["release_artifacts"]["android_apk"]
        signed_apk = _bound_regular_file(
            declared_android["path"],
            declared_android["sha256"],
            base_dir=evidence_base,
            context="production signed Android APK input",
        )
        production_release = publish_production_release_receipt(
            output_path=args.production_receipt,
            release_id=payload["release_id"],
            source_commit=source_commit,
            release_artifacts=release_artifacts,
            release_artifacts_sha256=release_artifacts_sha256,
            verified_checks=observed,
            verified_operations=operational,
            full_rc_signing_chain=full_rc_signing_chain,
            blocker_resolution=blocker_resolution,
            protected_input_roots=(
                args.full_rc_manifest.parent,
                args.full_rc_validation_receipt.parent,
                args.full_rc_unsigned_apk.parent,
                signed_apk.parent,
                args.operator_attestation.parent,
                args.operator_attestation_signature.parent,
                args.gpg.parent,
                args.gpg_keyring.parent,
                args.java.parent,
                args.apksigner_jar.parent,
                args.android_signing_gate_receipt.parent,
                args.blocker_resolution_receipt.parent,
                args.blocker_resolution_signature.parent,
            ),
        )

    print(f"PASS: release evidence profile={args.profile} provider={args.deployment_provider}")
    result: dict[str, Any] = {
        "release_id": payload["release_id"],
        "source_commit": source_commit,
        "release_artifacts": release_artifacts,
        "release_artifacts_sha256": release_artifacts_sha256,
        "verified_checks": observed,
        "verified_operations": operational,
    }
    if (
        full_rc_signing_chain is not None
        and blocker_resolution is not None
        and production_release is not None
    ):
        result["full_rc_signing_chain"] = full_rc_signing_chain
        result["blocker_resolution"] = blocker_resolution
        result["production_release"] = production_release
    print(
        json.dumps(
            result,
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ != "__main__":
    _load_release_dependencies()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except EvidenceFailure as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
