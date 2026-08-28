#!/usr/bin/env python3
"""Independently validate a WalkSafe full-product RC and its SPDX SBOM."""

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
            "WalkSafe full RC validator CLI requires Python -I -S -B before any release code runs"
        )
    print(
        "BLOCKED: the Web-inclusive full RC validator is LEGACY_REFERENCE_ONLY under FP-009.",
        file=sys.stderr,
    )
    raise SystemExit(78)

import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import tarfile
import types
from typing import Any
import xml.etree.ElementTree as ET
import zipfile
from urllib.parse import parse_qs, quote, urlsplit


_SCRIPT_DIRECTORY = Path(__file__).resolve().parent
_INTEGRITY_SHA256 = "b084292b5a6e4befd82aea22ceac899527ab1ba1ffe51b64fa022c831b72a18e"
_DEX_BINDING_SHA256 = "cfcc330fcf8a5faf4ee8a11adc6168bd2df98d40b09e4a5471586013a7fcab7e"
_LEGACY_WEB_ENV_STUB_SHA256 = "443dfcb27779b3cb5b6b2608a9ddada373050d9566774dcc6bc7ea42833d766f"
_LEGACY_WEB_NGINX_STUB_SHA256 = "1ae093927b4c7836e52a8bbf36c23df62dfba4bbf58b3c5fc03e928a93b28931"
_LEGACY_WEB_SYSTEMD_STUB_SHA256 = "8e5303c8cea39149a90c84046c061d03c314439c50e4bd49ede78653c79312d3"


def _load_local_source(
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


_integrity = _load_local_source(
    "_walksafe_release_integrity_for_full_rc_validator",
    _SCRIPT_DIRECTORY / "walksafe_release_integrity.py",
    _INTEGRITY_SHA256,
)
DirectorySnapshot = _integrity.DirectorySnapshot
FileSnapshot = _integrity.FileSnapshot
ReleaseIntegrityError = _integrity.ReleaseIntegrityError
exact_directory_record = _integrity.exact_directory_record
exclusive_atomic_publish = _integrity.exclusive_atomic_publish
ensure_real_subdirectory = _integrity.ensure_real_subdirectory
publish_snapshot = _integrity.publish_snapshot
require_isolated_python = _integrity.require_isolated_python
root_owned_system_trust = _integrity.root_owned_system_trust
strict_json_bytes = _integrity.strict_json_bytes
strict_json_snapshot = _integrity.strict_json_snapshot
verify_exact_git_source = _integrity.verify_exact_git_source

_dex_binding = _load_local_source(
    "_walksafe_android_dex_binding_for_full_rc_validator",
    _SCRIPT_DIRECTORY / "walksafe_android_dex_binding.py",
    _DEX_BINDING_SHA256,
)
DexBindingError = _dex_binding.DexBindingError
validate_walksafe_release_binding = _dex_binding.validate_walksafe_release_binding


FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
PYTHON_PIN = re.compile(r"^([A-Za-z0-9_.-]+)(?:\[[A-Za-z0-9_,.-]+\])?==([^\s;]+)$")
GRADLE_PLUGIN = re.compile(r"\bid\(\"([^\"]+)\"\)\s+version\s+\"([^\"]+)\"")
FORBIDDEN_RUNTIME_SUFFIXES = {
    ".bin",
    ".ckpt",
    ".engine",
    ".gguf",
    ".onnx",
    ".pt",
    ".pth",
    ".safetensors",
    ".tflite",
}
WEB_BUILD_ENVIRONMENT = {
    "NEXT_PUBLIC_DETECTOR_MODE": "server-v2",
    "NEXT_PUBLIC_WALKSAFE_PWA_ENABLED": "true",
    "NEXT_PUBLIC_WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING": "false",
    "NEXT_PUBLIC_WALKSAFE_TEST_CAPTURE_PANEL": "false",
}
WEB_QUALITY_RECEIPTS = {
    "browser-lifecycle",
    "node-toolchain",
    "node-toolchain-post",
    "npm-audit",
    "npm-build",
    "npm-ci",
    "npm-lint",
    "npm-test",
    "npm-typecheck",
    "runtime-trace",
}
SEMANTIC_VERSION = re.compile(r"^v?\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")
QUALITY_POLICY_SHA256 = "c4a9dccedcee730f6542ed2a5b627780bb9419c13a10ce7fd011ecdc8e73a6f5"
QUALITY_PRODUCTS = ("android", "backend", "voice", "web")
QUALITY_RECEIPT_SCHEMA = "walksafe.product-quality-receipt.v3"
QUALITY_STEP_LOG_SCHEMA = "walksafe.product-quality-step-log.v1"
NODE_TOOLCHAIN_CHECKER_SHA256 = "dd88cd342addda35bc486440091e39e3b16d064c89e0f79d62fc3c03d21cdcfe"

# Kept deliberately local to this validator: generator policy constants remain
# independent. Only low-level snapshot and strict parsing primitives are shared.
FULL_RC_MANIFEST_KEYS = frozenset(
    {
        "schema_version",
        "source",
        "release_state",
        "components",
        "quality",
        "sbom",
        "source_inputs",
        "files",
        "closure",
        "authentication",
        "external_runtime_inputs",
        "packaging_policy",
    }
)
ARTIFACT_RECORD_KEYS = frozenset({"path", "sha256", "bytes"})
WEB_BUILD_MANIFEST_KEYS = frozenset(
    {
        "schema_version",
        "source_commit",
        "build_id",
        "inputs",
        "toolchain",
        "build_environment",
        "quality_receipts",
        "deployment_archive",
        "files",
    }
)
WEB_NAMED_ARTIFACT_RECORD_KEYS = frozenset(
    {"name", "path", "sha256", "bytes"}
)
WEB_FILE_HASH_RECORD_KEYS = frozenset({"path", "sha256"})
EXPECTED_RELEASE_STATE = {
    "kind": "release_candidate",
    "deployment_complete": False,
    "reason": (
        "Android signing, server/Voice model inputs, secrets, trusted edge TLS identity, "
        "shared storage, ffprobe, runtime images, operator attestation, and external "
        "acceptance remain operator-provided blockers"
    ),
}
EXPECTED_EXTERNAL_RUNTIME_REQUIREMENTS = {
    "operator-secrets": "database, TMAP, gateway, backend, Voice, and session credentials",
    "trusted-edge-tls-identity": (
        "operator-provisioned public DNS, TLS certificate/private key, and firewall "
        "that exposes only TLS port 443 and denies direct Web port 3000 for the "
        "packaged trusted proxy configuration"
    ),
    "backend-model-weight": (
        "hash-approved img768 detector weight at DETECT_V2_UNIFIED_MODEL_PATH"
    ),
    "voice-model-weights": (
        "operator-provisioned STT/TTS model caches and optional reference audio"
    ),
    "voice-ffprobe": "operator-installed ffprobe for non-WAV audio duration validation",
    "shared-postgis-and-upload-storage": (
        "PostGIS atomic limiter store and shared durable UPLOAD_DIR for multi-replica backend"
    ),
    "android-operator-signing": (
        "operator keystore, approved certificate digest, signing, and apksigner gate"
    ),
    "external-acceptance": (
        "physical-device, outdoor, and institutional acceptance receipts"
    ),
    "runtime-images-and-native-libraries": (
        "operator-attested immutable Node/CPython runtime image digests and native "
        "library inventory matching the structured quality receipts"
    ),
    "operator-release-attestation": (
        "independent validation receipt and detached signature from an out-of-band "
        "approved release-reviewer key"
    ),
}
EXPECTED_PACKAGING_POLICY = {
    "secrets_included": False,
    "loose_server_or_voice_model_weights_included": False,
    "android_embedded_model_note": (
        "The APK retains its separately audited embedded on-device model assets; "
        "no loose model file is added by this builder."
    ),
}
COMPONENT_RECORD_KEYS = {
    "web": frozenset(
        {
            "artifact",
            "build_manifest",
            "evidence_files",
            "runtime_support",
            "runtime_unit",
            "configuration_example",
            "trusted_proxy_configuration",
            "trusted_proxy_runtime_check",
        }
    ),
    "android": frozenset(
        {"artifact", "signing_status", "deployable", "deployment_blocker"}
    ),
    "backend": frozenset(
        {
            "artifact",
            "runtime_unit",
            "migration_unit",
            "configuration_example",
            "migration_configuration_example",
            "issuer_binding_unit",
            "issuer_binding_cli",
        }
    ),
    "voice": frozenset({"artifact", "runtime_unit", "configuration_example"}),
}
EXPECTED_COMPONENT_METADATA = {
    "web": {
        "runtime_unit": "deploy/systemd/walksafe-web.service",
        "configuration_example": "deploy/config/walksafe-web.env.example",
        "trusted_proxy_configuration": "deploy/nginx/walksafe-web.conf.example",
        "trusted_proxy_runtime_check": "scripts/check_walksafe_trusted_proxy_20260716.py",
    },
    "android": {
        "signing_status": "unsigned",
        "deployable": False,
        "deployment_blocker": "operator signing and certificate verification are required",
    },
    "backend": {
        "runtime_unit": "deploy/systemd/walksafe-backend.service",
        "migration_unit": "deploy/systemd/walksafe-backend-migrate.service",
        "configuration_example": "deploy/config/walksafe-backend.env.example",
        "migration_configuration_example": (
            "deploy/config/walksafe-backend-migration.env.example"
        ),
        "issuer_binding_unit": "deploy/systemd/walksafe-admin-issuer-bind.service",
        "issuer_binding_cli": "scripts/bind_walksafe_admin_credential_issuer_key.py",
    },
    "voice": {
        "runtime_unit": "deploy/systemd/walksafe-voice.service",
        "configuration_example": "deploy/config/walksafe-voice.env.example",
    },
}
REQUIRED_PROVENANCE_PATHS = {
    "apps/android/app/build.gradle.kts",
    "apps/android/build.gradle.kts",
    "apps/android/gradle/wrapper/gradle-wrapper.jar",
    "apps/android/gradle/wrapper/gradle-wrapper.properties",
    "apps/android/app/gradle.lockfile",
    "apps/android/gradle/verification-metadata.xml",
    "apps/android/settings.gradle.kts",
    "apps/android/app/src/main/assets/model-config/two_model_runtime.json",
    "apps/android/app/src/main/assets/models/coco_yolo26n_float32.tflite",
    "apps/android/app/src/main/assets/models/custom_tactile_yolo26s_float32.tflite",
    "apps/android/app/src/main/assets/models/walksafe_unified_yolo26n_768_float32.tflite",
    "apps/web/.env.example",
    "apps/web/package-lock.json",
    "apps/web/package.json",
    "apps/web/quality-requirements.lock",
    "apps/web/quality-requirements.txt",
    "backend/.env.example",
    "backend/requirements.txt",
    "backend/requirements.lock",
    "configs/walksafe_product_quality_policy_20260713.json",
    "configs/walksafe_node_toolchain_lock_20260715.json",
    "deploy/config/walksafe-backend.env.example",
    "deploy/config/walksafe-backend-migration.env.example",
    "deploy/config/walksafe-report-retention.env.example",
    "deploy/config/walksafe-voice.env.example",
    "deploy/config/walksafe-web.env.example",
    "deploy/nginx/walksafe-web.conf.example",
    "deploy/systemd/walksafe-backend-migrate.service",
    "deploy/systemd/walksafe-backend.service",
    "deploy/systemd/walksafe-admin-issuer-bind.service",
    "deploy/sysusers.d/walksafe-backend.conf",
    "deploy/systemd/walksafe-report-retention.service",
    "deploy/systemd/walksafe-report-retention.timer",
    "deploy/systemd/walksafe-voice.service",
    "deploy/systemd/walksafe-web.service",
    "docs/release/walksafe_full_rc_20260713.md",
    "scripts/bind_walksafe_admin_credential_issuer_key.py",
    "scripts/build_walksafe_full_rc_20260713.py",
    "scripts/build_walksafe_web_release_20260711.sh",
    "scripts/check_android_apk_model_asset_20260713.py",
    "scripts/check_pwa_browser_lifecycle_20260711.py",
    "scripts/check_pwa_release_update_20260717.py",
    "scripts/check_walksafe_trusted_proxy_20260716.py",
    "scripts/check_walksafe_node_toolchain_20260715.py",
    "scripts/check_report_retention_dry_run.py",
    "scripts/check_web_runtime_trace_scope_20260713.py",
    "scripts/create_walksafe_web_build_manifest_20260711.py",
    "scripts/run_walksafe_web_single_instance_20260713.py",
    "scripts/run_walksafe_product_quality_20260713.py",
    "scripts/run_walksafe_isolated_python_20260713.py",
    "scripts/run_walksafe_report_retention_20260717.sh",
    "scripts/walksafe_backup_integrity.py",
    "scripts/walksafe_environment_identity.py",
    "scripts/walksafe_external_check_receipt.py",
    "scripts/walksafe_android_dex_binding.py",
    "scripts/walksafe_release_integrity.py",
    "scripts/validate_walksafe_full_rc_20260713.py",
    "scripts/verify_walksafe_operator_attestation_20260713.py",
    "scripts/verify_walksafe_signed_android_release_20260713.py",
    "voice/requirements.txt",
    "voice/requirements.lock",
    "voice/quality-requirements.txt",
    "voice/quality-requirements.lock",
}


class ValidationError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _snapshot_file(
    path: Path,
    relative: str,
    context: str,
    *,
    capture: bool = False,
) -> tuple[dict[str, Any], bytes | None]:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
        with os.fdopen(descriptor, "rb") as source:
            before = os.fstat(source.fileno())
            if not stat.S_ISREG(before.st_mode) or before.st_size <= 0:
                raise ValidationError(f"{context} is not a nonempty regular file")
            digest = hashlib.sha256()
            captured = bytearray() if capture else None
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
                if captured is not None:
                    captured.extend(chunk)
            after = os.fstat(source.fileno())
            current = os.stat(path, follow_symlinks=False)
    except OSError as exc:
        raise ValidationError(f"{context} cannot be snapshotted") from exc
    identities = {
        (
            value.st_dev,
            value.st_ino,
            value.st_size,
            value.st_mtime_ns,
            value.st_ctime_ns,
        )
        for value in (before, after, current)
    }
    if not stat.S_ISREG(current.st_mode) or len(identities) != 1:
        raise ValidationError(f"{context} changed while being snapshotted")
    return (
        {"path": relative, "bytes": before.st_size, "sha256": digest.hexdigest()},
        bytes(captured) if captured is not None else None,
    )


def _snapshot_rc(rc: Path) -> dict[str, Any]:
    try:
        return exact_directory_record(
            rc,
            context="full RC",
            manifest_name="walksafe-full-rc-manifest.json",
        )
    except ReleaseIntegrityError as exc:
        raise ValidationError(str(exc)) from exc


def _safe_path(raw: str, context: str, *, allow_root: bool = False) -> PurePosixPath | None:
    if not raw or "\\" in raw:
        raise ValidationError(f"{context} contains an unsafe path: {raw!r}")
    parsed = PurePosixPath(raw)
    if parsed.is_absolute() or ".." in parsed.parts:
        raise ValidationError(f"{context} contains an unsafe path: {raw!r}")
    parts = tuple(part for part in parsed.parts if part not in {"", "."})
    if not parts:
        if allow_root:
            return None
        raise ValidationError(f"{context} path is empty")
    return PurePosixPath(*parts)


def _load_json(path: Path, context: str) -> dict[str, Any]:
    try:
        with FileSnapshot.capture(path, context=context, display_path=path.name) as snapshot:
            payload = strict_json_snapshot(snapshot, context=context)
    except ReleaseIntegrityError as exc:
        raise ValidationError(str(exc)) from exc
    if not isinstance(payload, dict):
        raise ValidationError(f"{context} must contain a JSON object")
    return payload


def _require_record_fields(
    record: Any,
    expected_fields: frozenset[str],
    context: str,
) -> dict[str, Any]:
    if not isinstance(record, dict) or set(record) != expected_fields:
        raise ValidationError(f"{context} fields differ from the exact record contract")
    return record


def _json_exact_equal(actual: Any, expected: Any) -> bool:
    if type(actual) is not type(expected):
        return False
    if isinstance(expected, dict):
        return set(actual) == set(expected) and all(
            _json_exact_equal(actual[key], value) for key, value in expected.items()
        )
    if isinstance(expected, list):
        return len(actual) == len(expected) and all(
            _json_exact_equal(left, right)
            for left, right in zip(actual, expected, strict=True)
        )
    return actual == expected


def _record_path(
    record: Any,
    root: Path,
    context: str,
    *,
    expected_fields: frozenset[str] = ARTIFACT_RECORD_KEYS,
) -> Path:
    record = _require_record_fields(record, expected_fields, context)
    if type(record.get("path")) is not str or type(record.get("sha256")) is not str:
        raise ValidationError(f"{context} artifact record types are invalid")
    if type(record.get("bytes")) is not int:
        raise ValidationError(f"{context} byte count type is invalid")
    relative = _safe_path(record["path"], context)
    assert relative is not None
    candidate = root.joinpath(*relative.parts)
    if candidate.is_symlink() or not candidate.is_file() or candidate.stat().st_size <= 0:
        raise ValidationError(f"{context} is missing, empty, or a symlink")
    resolved_root = root.resolve()
    resolved = candidate.resolve()
    if resolved_root not in resolved.parents:
        raise ValidationError(f"{context} escaped the RC root")
    if record["bytes"] != resolved.stat().st_size:
        raise ValidationError(f"{context} byte count does not match")
    if record.get("sha256") != sha256_file(resolved):
        raise ValidationError(f"{context} SHA-256 does not match")
    return resolved


def _validate_web_node_toolchain(
    *,
    lock_path: Path,
    attestation_path: Path,
    toolchain: dict[str, Any],
) -> None:
    lock = _load_json(lock_path, "Web Node toolchain lock")
    if (
        set(lock) != {"schema_version", "official_archive", "platform", "root", "node", "npm"}
        or lock.get("schema_version") != "walksafe.node-toolchain.v2"
    ):
        raise ValidationError("Web Node toolchain lock schema is invalid")
    node = lock.get("node")
    npm = lock.get("npm")
    if (
        not isinstance(node, dict)
        or not isinstance(npm, dict)
        or node.get("version") != toolchain["node"]
        or npm.get("version") != toolchain["npm"]
    ):
        raise ValidationError("Web Node toolchain versions differ from the source lock")
    expected = {
        "schema_version": "walksafe.node-toolchain-attestation.v2",
        "lock_sha256": sha256_file(lock_path),
        "official_archive": lock["official_archive"],
        "platform": lock["platform"],
        "root": lock["root"],
        "node": node,
        "npm": npm,
    }
    if not _json_exact_equal(
        _load_json(attestation_path, "Web Node toolchain attestation"), expected
    ):
        raise ValidationError("Web Node toolchain attestation differs from the source lock")


def _source_file_record(root: Path, relative: str) -> dict[str, Any]:
    path = root / relative
    if path.is_symlink() or not path.is_file() or root not in path.resolve().parents:
        raise ValidationError(f"quality source input is unavailable: {relative}")
    return {"path": relative, "bytes": path.stat().st_size, "sha256": sha256_file(path)}


def _tracked_source_inventory(root: Path) -> dict[str, Any]:
    try:
        return verify_exact_git_source(root, context="quality source").inventory
    except ReleaseIntegrityError as exc:
        raise ValidationError(str(exc)) from exc


def _load_quality_policy(source: Path) -> dict[str, Any]:
    path = source / "configs/walksafe_product_quality_policy_20260713.json"
    try:
        with FileSnapshot.capture(path, context="quality policy") as snapshot:
            if snapshot.sha256 != QUALITY_POLICY_SHA256:
                raise ValidationError("quality policy differs from the validator-pinned policy")
            policy = strict_json_snapshot(snapshot, context="quality policy")
    except ReleaseIntegrityError as exc:
        raise ValidationError(str(exc)) from exc
    if not isinstance(policy, dict):
        raise ValidationError("quality policy must contain a JSON object")
    if (
        policy.get("schema_version") != "walksafe.product-quality-policy.v2"
        or policy.get("receipt_schema_version") != QUALITY_RECEIPT_SCHEMA
        or not isinstance(policy.get("products"), dict)
        or set(policy["products"]) != set(QUALITY_PRODUCTS)
        or not isinstance(policy.get("ambient_environment_allowlist"), list)
    ):
        raise ValidationError("quality policy schema or product set is invalid")
    return policy


def _validate_quality_environment(
    *,
    product: str,
    step: dict[str, Any],
    recorded: Any,
    source_commit: str,
    android_gateway_origin: str | None,
    ambient_allowlist: set[str],
) -> None:
    if not isinstance(recorded, dict) or set(recorded) != {
        "allowlisted_ambient_presence",
        "required_ambient_presence",
        "derived_secret_presence",
        "fixed_public",
    }:
        raise ValidationError("quality step environment record is malformed")
    allowlisted = recorded["allowlisted_ambient_presence"]
    if (
        not isinstance(allowlisted, dict)
        or not set(allowlisted).issubset(ambient_allowlist)
        or not _json_exact_equal(allowlisted.get("PATH"), {"present": True})
        or any(
            not _json_exact_equal(value, {"present": True})
            for value in allowlisted.values()
        )
    ):
        raise ValidationError("quality step ambient environment record is invalid")
    if not _json_exact_equal(
        recorded["required_ambient_presence"],
        {name: {"present": True} for name in step.get("ambient_environment", [])},
    ):
        raise ValidationError("quality step required environment record is invalid")
    if not _json_exact_equal(
        recorded["derived_secret_presence"],
        {
            destination: {"source": source, "present": True}
            for destination, source in step.get("derived_environment", {}).items()
        },
    ):
        raise ValidationError("quality step derived environment record is invalid")
    expected_fixed = {
        name: {
            "{repo_root}": "{repo_root}",
            "{source_commit}": source_commit,
            "{android_gateway_origin}": android_gateway_origin,
        }.get(value, value)
        for name, value in step.get("fixed_environment", {}).items()
    }
    expected_fixed.update(
        {
            "HOME": "{private_quality_home}",
            "GRADLE_USER_HOME": "{private_quality_home}/.gradle",
            "NPM_CONFIG_USERCONFIG": "{private_quality_home}/.npmrc",
            "PYTHONNOUSERSITE": "1",
        }
    )
    if product == "web":
        expected_fixed["PATH"] = "{locked_node_bin}:/usr/bin:/bin"
    if not _json_exact_equal(recorded["fixed_public"], expected_fixed):
        raise ValidationError("quality step fixed environment record is invalid")


def _validate_tested_environment(
    *,
    product: str,
    definition: dict[str, Any],
    recorded: Any,
    source: Path,
    step_records: list[dict[str, Any]],
) -> None:
    lock_relatives = [
        relative
        for relative in (
            definition.get("tested_environment_lock"),
            definition.get("tested_tool_lock"),
        )
        if relative is not None
    ]
    if not lock_relatives:
        if recorded is not None or definition.get("tested_site_packages") is not None:
            raise ValidationError(f"{product} quality receipt has an unexpected tested environment")
        return
    if not isinstance(recorded, dict) or set(recorded) != {
        "python", "locks", "locked_distributions", "installed_distributions", "site_packages"
    }:
        raise ValidationError(f"{product} tested environment record is malformed")
    trusted_tools = definition.get("trusted_tool_distributions")
    if not isinstance(trusted_tools, dict) or any(
        not isinstance(name, str)
        or re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name) is None
        or not isinstance(version, str)
        or not version
        or any(character in version for character in "\0\r\n")
        for name, version in trusted_tools.items()
    ):
        raise ValidationError(f"{product} trusted tool policy is malformed")
    expected_locks: list[dict[str, Any]] = []
    locked_by_name: dict[str, dict[str, Any]] = {}
    for lock_relative in lock_relatives:
        lock = source / lock_relative
        if lock.is_symlink() or not lock.is_file():
            raise ValidationError(f"{product} tested environment lock is missing")
        expected_locks.append(
            {
                "path": f"repository:{lock_relative}",
                "bytes": lock.stat().st_size,
                "sha256": sha256_file(lock),
            }
        )
        component = lock_relative.split("/", 1)[0]
        requirements_relative = lock_relative.removesuffix(".lock") + ".txt"
        for item in _python_lock_entries_from_paths(
            source,
            component=component,
            requirements_relative=requirements_relative,
            lock_relative=lock_relative,
        ):
            previous = locked_by_name.get(item["name"])
            if previous is not None and previous["version"] != item["version"]:
                raise ValidationError(f"{product} tested environment locks disagree")
            locked_by_name[item["name"]] = item
    if not _json_exact_equal(recorded.get("locks"), expected_locks):
        raise ValidationError(f"{product} tested environment lock differs from source")
    canonical = "".join(
        f"{name}=={locked_by_name[name]['version']}\n" for name in sorted(locked_by_name)
    ).encode("utf-8")
    if not _json_exact_equal(
        recorded.get("locked_distributions"),
        {
            "count": len(locked_by_name),
            "sha256": hashlib.sha256(canonical).hexdigest(),
        },
    ):
        raise ValidationError(f"{product} tested distribution identity differs from its lock")
    collisions = sorted(set(locked_by_name) & set(trusted_tools))
    if collisions:
        raise ValidationError(f"{product} trusted tool policy collides with its fixed locks")
    installed_by_name = {
        **{name: item["version"] for name, item in locked_by_name.items()},
        **trusted_tools,
    }
    installed_canonical = "".join(
        f"{name}=={installed_by_name[name]}\n" for name in sorted(installed_by_name)
    ).encode("utf-8")
    if not _json_exact_equal(
        recorded.get("installed_distributions"),
        {
            "count": len(installed_by_name),
            "sha256": hashlib.sha256(installed_canonical).hexdigest(),
        },
    ):
        raise ValidationError(
            f"{product} installed distribution identity differs from its exact policy set"
        )
    site_packages = recorded.get("site_packages")
    if not isinstance(site_packages, dict) or set(site_packages) != {
        "format", "roots", "closure"
    }:
        raise ValidationError(f"{product} tested site-packages record is malformed")
    roots = site_packages.get("roots")
    closure = site_packages.get("closure")
    if (
        site_packages.get("format") != "walksafe.record-claimed-site-closure.v2"
        or not isinstance(roots, list)
        or not roots
        or len(roots) != len(set(roots))
        or any(
            not isinstance(root, str)
            or not Path(root).is_absolute()
            or any(character in root for character in "\0\r\n")
            for root in roots
        )
        or not isinstance(closure, dict)
        or set(closure) != {"count", "sha256"}
        or not isinstance(closure.get("count"), int)
        or isinstance(closure.get("count"), bool)
        or closure["count"] <= len(roots)
        or type(closure.get("sha256")) is not str
        or re.fullmatch(r"[0-9a-f]{64}", closure["sha256"]) is None
    ):
        raise ValidationError(f"{product} tested site-packages record is malformed")
    if not _json_exact_equal(
        {
            "format": site_packages["format"],
            "closure": site_packages["closure"],
        },
        definition.get("tested_site_packages"),
    ):
        raise ValidationError(
            f"{product} tested site-packages differs from the source-pinned policy"
        )
    python = recorded.get("python")
    if (
        not isinstance(python, dict)
        or set(python) != {"path", "bytes", "sha256"}
        or type(python.get("path")) is not str
        or not Path(python["path"]).is_absolute()
        or type(python.get("bytes")) is not int
        or python["bytes"] <= 0
        or type(python.get("sha256")) is not str
        or re.fullmatch(r"[0-9a-f]{64}", python["sha256"]) is None
    ):
        raise ValidationError(f"{product} tested Python record is malformed")
    python_path = str(Path(python["path"]))
    for step, record in zip(definition["steps"], step_records, strict=True):
        if step["argv"][0] == "{python}" and (
            record["executable"]["resolved_path"] != python_path
            or record["executable"]["bytes"] != python["bytes"]
            or record["executable"]["sha256"] != python["sha256"]
        ):
            raise ValidationError(f"{product} quality step used a different Python environment")


def _validate_quality_node_toolchain(
    *,
    product: str,
    recorded: Any,
    source: Path,
) -> None:
    if product != "web":
        if recorded is not None:
            raise ValidationError(f"{product} quality receipt has unexpected Node evidence")
        return
    if not isinstance(recorded, dict) or set(recorded) != {
        "checker", "lock", "before", "after"
    }:
        raise ValidationError("Web quality Node toolchain evidence is malformed")
    checker = _source_file_record(
        source, "scripts/check_walksafe_node_toolchain_20260715.py"
    )
    lock_record = _source_file_record(
        source, "configs/walksafe_node_toolchain_lock_20260715.json"
    )
    if checker["sha256"] != NODE_TOOLCHAIN_CHECKER_SHA256:
        raise ValidationError("Web quality Node checker differs from its approved pin")
    if not _json_exact_equal(recorded.get("checker"), checker) or not _json_exact_equal(
        recorded.get("lock"), lock_record
    ):
        raise ValidationError("Web quality Node provenance differs from source")
    lock_path = source / "configs/walksafe_node_toolchain_lock_20260715.json"
    lock = _load_json(lock_path, "Web quality Node toolchain lock")
    if (
        set(lock) != {"schema_version", "official_archive", "platform", "root", "node", "npm"}
        or lock.get("schema_version") != "walksafe.node-toolchain.v2"
    ):
        raise ValidationError("Web quality Node toolchain lock is invalid")
    expected = {
        "schema_version": "walksafe.node-toolchain-attestation.v2",
        "lock_sha256": lock_record["sha256"],
        "official_archive": lock["official_archive"],
        "platform": lock["platform"],
        "root": lock["root"],
        "node": lock["node"],
        "npm": lock["npm"],
    }
    if not _json_exact_equal(recorded.get("before"), expected) or not _json_exact_equal(
        recorded.get("after"), expected
    ):
        raise ValidationError("Web quality Node attestation differs from the source lock")


def _validate_quality_bundle(
    *,
    quality: Any,
    source: Path,
    rc: Path,
    source_commit: str,
    source_tree: str,
    web_archive: Path,
    android_apk: Path,
) -> tuple[set[str], dict[str, Any]]:
    if not isinstance(quality, dict) or set(quality) != {"policy", "products"}:
        raise ValidationError("full RC quality record is malformed")
    policy = _load_quality_policy(source)
    policy_record = _source_file_record(
        source, "configs/walksafe_product_quality_policy_20260713.json"
    )
    if not _json_exact_equal(quality.get("policy"), policy_record):
        raise ValidationError("full RC quality policy provenance differs from source")
    products = quality.get("products")
    if not isinstance(products, dict) or set(products) != set(QUALITY_PRODUCTS):
        raise ValidationError("full RC quality product set is incomplete")
    inventory = _tracked_source_inventory(source)
    expected_provenance = {
        "runner": _source_file_record(source, "scripts/run_walksafe_product_quality_20260713.py"),
        "bootstrap": _source_file_record(
            source, "scripts/run_walksafe_isolated_python_20260713.py"
        ),
        "policy": policy_record,
    }
    expected_outputs: dict[str, dict[str, Path]] = {
        "web": {"web_release_archive": web_archive},
        "android": {"unsigned_apk": android_apk},
        "backend": {
            "openapi_contract": source / "contracts/walksafe.openapi.json",
            "walking_route_fixture": source / "contracts/fixtures/walking-route-v1.json",
        },
        "voice": {},
    }
    all_paths: set[str] = set()
    receipt_records: dict[str, Any] = {}
    for product in QUALITY_PRODUCTS:
        product_record = products[product]
        if not isinstance(product_record, dict) or set(product_record) != {"receipt", "evidence_files"}:
            raise ValidationError(f"{product} quality manifest record is malformed")
        receipt_record = product_record["receipt"]
        receipt_path = _record_path(receipt_record, rc, f"{product} quality receipt")
        expected_receipt_path = f"quality/{product}/walksafe-{product}-quality-receipt.json"
        if receipt_path.relative_to(rc).as_posix() != expected_receipt_path:
            raise ValidationError(f"{product} quality receipt path is not canonical")
        receipt = _load_json(receipt_path, f"{product} quality receipt")
        if set(receipt) != {
            "schema_version", "product", "exit_code", "result", "source", "provenance",
            "inputs", "tested_environment", "node_toolchain", "public_arguments", "steps", "outputs",
        }:
            raise ValidationError(f"{product} quality receipt fields are invalid")
        if (
            receipt.get("schema_version") != QUALITY_RECEIPT_SCHEMA
            or receipt.get("product") != product
            or not _json_exact_equal(receipt.get("exit_code"), 0)
            or receipt.get("result") != "passed"
            or not _json_exact_equal(
                receipt.get("source"),
                {
                    "commit": source_commit,
                    "git_tree": source_tree,
                    "tree_clean_before": True,
                    "tree_clean_after": True,
                },
            )
            or not _json_exact_equal(receipt.get("provenance"), expected_provenance)
            or not _json_exact_equal(
                receipt.get("inputs"),
                {
                    "tracked_source_before": inventory,
                    "tracked_source_after": inventory,
                },
            )
        ):
            raise ValidationError(f"{product} quality receipt does not bind the clean source HEAD")
        public_arguments = receipt.get("public_arguments")
        android_origin: str | None = None
        if product == "web":
            if not _json_exact_equal(
                public_arguments, {"web_release_archive": web_archive.name}
            ):
                raise ValidationError("Web quality receipt does not bind the RC archive")
        elif product == "android":
            if not isinstance(public_arguments, dict) or set(public_arguments) != {"android_gateway_origin"}:
                raise ValidationError("Android quality receipt gateway origin is missing")
            android_origin = public_arguments["android_gateway_origin"]
            if not isinstance(android_origin, str):
                raise ValidationError("Android quality receipt gateway origin is invalid")
            parsed = urlsplit(android_origin)
            try:
                port = parsed.port
            except ValueError as exc:
                raise ValidationError("Android quality receipt gateway origin is invalid") from exc
            canonical = f"https://{(parsed.hostname or '').lower()}{'' if port is None else f':{port}'}"
            if (
                parsed.scheme != "https"
                or not parsed.hostname
                or parsed.username is not None
                or parsed.password is not None
                or parsed.path not in {"", "/"}
                or parsed.query
                or parsed.fragment
                or android_origin != canonical
            ):
                raise ValidationError("Android quality receipt gateway origin is not canonical HTTPS")
        elif not _json_exact_equal(public_arguments, {}):
            raise ValidationError(f"{product} quality receipt has unexpected public arguments")

        definition = policy["products"][product]
        expected_steps = definition.get("steps")
        steps = receipt.get("steps")
        if not isinstance(expected_steps, list) or not isinstance(steps, list) or len(steps) != len(expected_steps):
            raise ValidationError(f"{product} quality receipt step set is incomplete")
        evidence_paths = {receipt_path}
        ambient_allowlist = set(policy["ambient_environment_allowlist"])
        for index, (recorded, expected) in enumerate(zip(steps, expected_steps, strict=True), start=1):
            if not isinstance(recorded, dict) or set(recorded) != {
                "id", "argv", "cwd", "environment", "executable", "exit_code", "result", "log"
            }:
                raise ValidationError(f"{product} quality step record is malformed")
            step_id = expected.get("id")
            expected_argv = expected.get("argv")
            argv = recorded.get("argv")
            executable = recorded.get("executable")
            if (
                recorded.get("id") != step_id
                or recorded.get("cwd") != expected.get("cwd")
                or not _json_exact_equal(recorded.get("exit_code"), 0)
                or recorded.get("result") != "passed"
                or not isinstance(expected_argv, list)
                or not isinstance(argv, list)
                or len(argv) != len(expected_argv)
                or any(not isinstance(value, str) or not value for value in argv)
                or argv[1:] != expected_argv[1:]
                or not isinstance(executable, dict)
                or set(executable) != {"path", "resolved_path", "bytes", "sha256"}
                or argv[0] != executable.get("path")
                or not isinstance(executable.get("path"), str)
                or not isinstance(executable.get("resolved_path"), str)
                or type(executable.get("bytes")) is not int
                or executable["bytes"] <= 0
                or type(executable.get("sha256")) is not str
                or re.fullmatch(r"[0-9a-f]{64}", executable["sha256"]) is None
            ):
                raise ValidationError(f"{product} quality step differs from the fixed policy: {step_id}")
            _validate_quality_environment(
                product=product,
                step=expected,
                recorded=recorded["environment"],
                source_commit=source_commit,
                android_gateway_origin=android_origin,
                ambient_allowlist=ambient_allowlist,
            )
            expected_log_relative = f"{receipt_path.stem}.logs/{index:02d}-{step_id}.json"
            log_record = recorded.get("log")
            if not isinstance(log_record, dict) or set(log_record) != {"path", "bytes", "sha256"}:
                raise ValidationError(f"{product} quality log record is malformed")
            if log_record.get("path") != expected_log_relative:
                raise ValidationError(f"{product} quality log path is not canonical")
            log_path = _record_path(log_record, receipt_path.parent, f"{product} quality log")
            expected_log = {
                "schema_version": QUALITY_STEP_LOG_SCHEMA,
                "product": product,
                "step_id": step_id,
                "argv": argv,
                "cwd": recorded["cwd"],
                "environment": recorded["environment"],
                "executable": executable,
                "exit_code": 0,
                "result": "passed",
            }
            if not _json_exact_equal(
                _load_json(log_path, f"{product} quality log"), expected_log
            ):
                raise ValidationError(f"{product} quality log differs from its receipt")
            evidence_paths.add(log_path)
        evidence_rows = product_record.get("evidence_files")
        if not isinstance(evidence_rows, list):
            raise ValidationError(f"{product} quality evidence records are missing")
        manifest_evidence = {
            _record_path(row, rc, f"{product} quality evidence") for row in evidence_rows
        }
        if len(manifest_evidence) != len(evidence_rows) or manifest_evidence != evidence_paths:
            raise ValidationError(f"{product} quality evidence file set is incomplete or duplicated")

        _validate_tested_environment(
            product=product,
            definition=definition,
            recorded=receipt.get("tested_environment"),
            source=source,
            step_records=steps,
        )
        _validate_quality_node_toolchain(
            product=product,
            recorded=receipt.get("node_toolchain"),
            source=source,
        )

        output_definitions = definition.get("outputs")
        outputs = receipt.get("outputs")
        if not isinstance(output_definitions, list) or not isinstance(outputs, list) or len(outputs) != len(output_definitions):
            raise ValidationError(f"{product} quality output set is incomplete")
        if {item.get("name") for item in output_definitions} != set(expected_outputs[product]):
            raise ValidationError(f"{product} quality output binding is incomplete")
        for recorded, expected in zip(outputs, output_definitions, strict=True):
            name = expected["name"]
            path = expected_outputs[product][name].resolve()
            display_path = (
                f"repository:{expected['path']}"
                if "path" in expected
                else f"operator-artifact:{path.name}"
            )
            expected_record = {
                "name": name,
                "path": display_path,
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
            step_key = "producer_step" if "producer_step" in expected else "verification_step"
            if step_key in expected:
                expected_record.update(
                    verification=expected["verification"],
                )
                expected_record[step_key] = expected[step_key]
            if not _json_exact_equal(recorded, expected_record):
                raise ValidationError(f"{product} quality output differs from the validated artifact")
        all_paths.update(path.relative_to(rc).as_posix() for path in evidence_paths)
        receipt_records[product] = receipt_record
    return all_paths, {
        "policy_sha256": QUALITY_POLICY_SHA256,
        "products": receipt_records,
    }


def inspect_deterministic_archive(path: Path) -> dict[str, dict[str, Any]]:
    """Inspect a runtime tar.gz without extracting any member."""
    if path.is_symlink() or not path.is_file() or path.stat().st_size <= 0:
        raise ValidationError("runtime archive is missing, empty, or a symlink")
    header = path.read_bytes()[:10]
    if len(header) < 10 or header[:2] != b"\x1f\x8b":
        raise ValidationError("runtime archive is not gzip")
    if int.from_bytes(header[4:8], "little") != 0 or header[3] & 0x08:
        raise ValidationError("runtime archive gzip header is not deterministic")

    records: dict[str, dict[str, Any]] = {}
    ordered_names: list[str] = []
    try:
        with tarfile.open(path, mode="r:gz") as archive:
            for member in archive:
                relative = _safe_path(member.name, "runtime archive", allow_root=False)
                assert relative is not None
                name = relative.as_posix()
                if name in records:
                    raise ValidationError(f"runtime archive contains a duplicate path: {name}")
                if not member.isreg():
                    raise ValidationError(f"runtime archive member must be a regular file: {name}")
                if member.mtime != 0:
                    raise ValidationError(f"runtime archive member mtime must be zero: {name}")
                if member.uid != 0 or member.gid != 0 or member.uname or member.gname:
                    raise ValidationError(f"runtime archive ownership is not deterministic: {name}")
                if member.mode != 0o644:
                    raise ValidationError(f"runtime archive mode is not canonical: {name}")
                if relative.suffix.lower() in FORBIDDEN_RUNTIME_SUFFIXES:
                    raise ValidationError(f"runtime archive contains a loose model weight: {name}")
                extracted = archive.extractfile(member)
                if extracted is None:
                    raise ValidationError(f"runtime archive member cannot be read: {name}")
                digest = hashlib.sha256()
                size = 0
                with extracted:
                    for chunk in iter(lambda: extracted.read(1024 * 1024), b""):
                        digest.update(chunk)
                        size += len(chunk)
                if size != member.size:
                    raise ValidationError(f"runtime archive member size changed while reading: {name}")
                records[name] = {"sha256": digest.hexdigest(), "bytes": size}
                ordered_names.append(name)
    except (OSError, tarfile.TarError) as exc:
        raise ValidationError("runtime archive is not readable") from exc
    if ordered_names != sorted(ordered_names):
        raise ValidationError("runtime archive member order is not deterministic")
    if not records:
        raise ValidationError("runtime archive is empty")
    return records


def _source_python_files(source: Path, relative_root: str) -> set[str]:
    directory = source / relative_root
    if directory.is_symlink() or not directory.is_dir():
        raise ValidationError(f"source runtime directory is missing: {relative_root}")
    result: set[str] = set()
    for item in directory.rglob("*"):
        if item.is_symlink():
            raise ValidationError(f"source runtime directory contains a symlink: {item}")
        if item.is_file() and item.suffix == ".py":
            result.add(item.relative_to(source).as_posix())
    if not result:
        raise ValidationError(f"source runtime directory has no Python files: {relative_root}")
    return result


def _validate_runtime_archive(
    archive_path: Path,
    *,
    source: Path,
    root_name: str,
    expected_relative_files: set[str],
) -> None:
    members = inspect_deterministic_archive(archive_path)
    expected_names = {f"{root_name}/{relative}" for relative in expected_relative_files}
    if set(members) != expected_names:
        missing = sorted(expected_names - set(members))[:3]
        unexpected = sorted(set(members) - expected_names)[:3]
        raise ValidationError(
            f"runtime archive file set differs from source; missing={missing}, unexpected={unexpected}"
        )
    for archive_name, record in members.items():
        relative = archive_name.removeprefix(f"{root_name}/")
        source_file = source / relative
        if source_file.is_symlink() or not source_file.is_file():
            raise ValidationError(f"runtime archive source file is unavailable: {relative}")
        if record["bytes"] != source_file.stat().st_size or record["sha256"] != sha256_file(source_file):
            raise ValidationError(f"runtime archive bytes differ from source: {relative}")


def _inspect_web_archive(path: Path, web_manifest: dict[str, Any]) -> None:
    expected_rows = web_manifest.get("files")
    if not isinstance(expected_rows, list) or not expected_rows:
        raise ValidationError("Web build manifest contains no file hashes")
    expected: dict[str, str] = {}
    for row in expected_rows:
        row = _require_record_fields(
            row,
            WEB_FILE_HASH_RECORD_KEYS,
            "Web file hash entry",
        )
        if type(row.get("path")) is not str or type(row.get("sha256")) is not str:
            raise ValidationError("Web file hash entry types are invalid")
        relative = _safe_path(row["path"], "Web build manifest file")
        assert relative is not None
        name = relative.as_posix()
        digest = row["sha256"]
        if name in expected or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
            raise ValidationError("Web build manifest file hashes are invalid or duplicated")
        expected[name] = digest

    gzip_header = path.read_bytes()[:10]
    if (
        len(gzip_header) < 10
        or gzip_header[:2] != b"\x1f\x8b"
        or int.from_bytes(gzip_header[4:8], "little") != 0
        or gzip_header[3] & 0x08
    ):
        raise ValidationError("Web deployment archive gzip header is not deterministic")
    actual: dict[str, str] = {}
    names_in_order: list[str] = []
    seen: set[str] = set()
    member_types: dict[str, str] = {}
    root_seen = False
    build_id: bytes | None = None
    try:
        with tarfile.open(path, mode="r:gz") as archive:
            for member in archive:
                relative = _safe_path(member.name, "Web deployment archive", allow_root=True)
                if relative is None:
                    if root_seen:
                        raise ValidationError("Web archive contains a duplicate root path")
                    root_seen = True
                    if not member.isdir() or member.mode != 0o755:
                        raise ValidationError("Web archive root entry is not a directory")
                    if member.mtime != 0 or member.uid != 0 or member.gid != 0:
                        raise ValidationError("Web archive root metadata is not deterministic")
                    continue
                name = relative.as_posix()
                if name in seen:
                    raise ValidationError(f"Web archive contains a duplicate path: {name}")
                seen.add(name)
                if relative.parts[:1] == ("dev",) or relative.parts[:2] == (".next", "dev"):
                    raise ValidationError(f"Web archive contains development-only output: {name}")
                if member.mtime != 0 or member.uid != 0 or member.gid != 0:
                    raise ValidationError(f"Web archive metadata is not deterministic: {name}")
                if member.issym() or member.islnk() or not (member.isdir() or member.isreg()):
                    raise ValidationError(f"Web archive contains an unsafe member: {name}")
                prefixes = ["/".join(relative.parts[:index]) for index in range(1, len(relative.parts))]
                if any(member_types.get(prefix) == "file" for prefix in prefixes):
                    raise ValidationError(f"Web archive contains a path collision: {name}")
                if member.isreg() and any(existing.startswith(f"{name}/") for existing in member_types):
                    raise ValidationError(f"Web archive contains a path collision: {name}")
                member_types[name] = "dir" if member.isdir() else "file"
                expected_modes = {0o755} if member.isdir() else {0o644, 0o755}
                if member.mode not in expected_modes:
                    raise ValidationError(f"Web archive contains a non-canonical mode: {name}")
                names_in_order.append(name)
                if member.isdir():
                    continue
                extracted = archive.extractfile(member)
                if extracted is None:
                    raise ValidationError(f"Web archive file is unreadable: {name}")
                with extracted:
                    content = extracted.read()
                    actual[name] = hashlib.sha256(content).hexdigest()
                    if name == "BUILD_ID":
                        build_id = content
    except (OSError, tarfile.TarError) as exc:
        raise ValidationError("Web deployment archive is not readable") from exc
    if names_in_order != sorted(
        names_in_order, key=lambda name: PurePosixPath(name).parts
    ):
        raise ValidationError("Web deployment archive order is not deterministic")
    if actual != expected:
        raise ValidationError("Web deployment archive file set or hashes differ from its build manifest")
    source_commit = web_manifest.get("source_commit")
    try:
        decoded_build_id = build_id.decode("utf-8").strip().lower() if build_id is not None else ""
    except UnicodeDecodeError as exc:
        raise ValidationError("Web deployment archive BUILD_ID is not UTF-8") from exc
    if decoded_build_id != source_commit:
        raise ValidationError("Web deployment archive BUILD_ID does not match source HEAD")


def _capture_tool(
    explicit: Path,
    expected_sha256: str,
    *,
    label: str,
    executable: bool,
) -> FileSnapshot:
    candidate = explicit.expanduser()
    if not candidate.is_absolute():
        raise ValidationError(f"explicit {label} path must be absolute")
    candidate = candidate.absolute()
    if candidate.resolve() != candidate:
        raise ValidationError(f"explicit {label} path must not contain symlinks")
    if executable and not os.access(candidate, os.X_OK):
        raise ValidationError(f"explicit {label} is not executable")
    try:
        snapshot = FileSnapshot.capture(
            candidate,
            context=label,
            display_path=str(candidate),
            private_mode=0o500 if executable else 0o400,
        )
    except ReleaseIntegrityError as exc:
        raise ValidationError(str(exc)) from exc
    if re.fullmatch(r"[0-9a-f]{64}", expected_sha256) is None or snapshot.sha256 != expected_sha256:
        snapshot.close()
        raise ValidationError(f"{label} does not match the out-of-band SHA-256")
    return snapshot


def _validate_android_models(
    archive: zipfile.ZipFile,
    names: set[str],
    source: Path,
) -> None:
    config_path = source / "apps/android/app/src/main/assets/model-config/two_model_runtime.json"
    if config_path.is_symlink() or not config_path.is_file():
        raise ValidationError("Android source model config is unavailable")
    config_bytes = config_path.read_bytes()
    try:
        config = strict_json_bytes(config_bytes, context="Android source model config")
    except ReleaseIntegrityError as exc:
        raise ValidationError(str(exc)) from exc
    if not isinstance(config, dict):
        raise ValidationError("Android source model config must contain a JSON object")
    models = config.get("models")
    if not isinstance(models, dict) or not models:
        raise ValidationError("Android source model config has no models")
    expected: dict[str, tuple[Path, str]] = {}
    for model_name, model in models.items():
        if not isinstance(model, dict):
            raise ValidationError(f"Android source model entry is invalid: {model_name}")
        raw_asset = model.get("asset")
        expected_hash = model.get("artifact_sha256")
        relative = _safe_path(str(raw_asset or ""), f"Android model asset {model_name}")
        assert relative is not None
        if relative.parent != PurePosixPath("models"):
            raise ValidationError(f"Android model asset is outside models/: {raw_asset}")
        if not isinstance(expected_hash, str) or re.fullmatch(r"[0-9a-f]{64}", expected_hash) is None:
            raise ValidationError(f"Android model hash is invalid: {model_name}")
        source_asset = source / "apps/android/app/src/main/assets" / relative.as_posix()
        if source_asset.is_symlink() or not source_asset.is_file():
            raise ValidationError(f"Android source model is unavailable: {model_name}")
        if sha256_file(source_asset) != expected_hash:
            raise ValidationError(f"Android source model hash differs from config: {model_name}")
        expected[f"assets/{relative.as_posix()}"] = (source_asset, expected_hash)

    config_entry = "assets/model-config/two_model_runtime.json"
    if config_entry not in names or archive.read(config_entry) != config_bytes:
        raise ValidationError("Android APK model config differs from source HEAD")
    packaged_models = {
        name for name in names if name.startswith("assets/models/") and not name.endswith("/")
    }
    if packaged_models != set(expected):
        raise ValidationError("Android APK model asset set differs from source config")
    for name, (source_asset, expected_hash) in expected.items():
        payload = archive.read(name)
        if hashlib.sha256(payload).hexdigest() != expected_hash or len(payload) != source_asset.stat().st_size:
            raise ValidationError(f"Android APK model asset differs from source HEAD: {name}")


def _validate_unsigned_apk(
    path: Path,
    source_commit: str,
    java: FileSnapshot,
    apksigner_jar: FileSnapshot,
    source: Path,
) -> None:
    if path.name != "app-release-unsigned.apk":
        raise ValidationError("Android RC filename must be app-release-unsigned.apk")
    try:
        with zipfile.ZipFile(path) as archive:
            seen: set[str] = set()
            for info in archive.infolist():
                relative = _safe_path(info.filename, "Android APK")
                assert relative is not None
                name = relative.as_posix()
                if name in seen:
                    raise ValidationError(f"Android APK has a duplicate path: {name}")
                seen.add(name)
                if stat.S_IFMT((info.external_attr >> 16) & 0xFFFF) == stat.S_IFLNK:
                    raise ValidationError(f"Android APK contains a symlink: {name}")
                if info.is_dir():
                    continue
            dex_names = {
                name for name in seen if name.startswith("classes") and name.endswith(".dex")
            }
            try:
                validate_walksafe_release_binding(
                    {name: archive.read(name) for name in dex_names},
                    source_commit,
                )
            except DexBindingError as exc:
                raise ValidationError("Android APK BuildConfig release binding is invalid") from exc
            _validate_android_models(archive, seen, source)
    except (OSError, zipfile.BadZipFile) as exc:
        raise ValidationError("Android artifact is not a readable APK") from exc
    try:
        completed = subprocess.run(
            [
                str(java.source_path),
                "-jar",
                apksigner_jar.proc_path,
                "verify",
                "--verbose",
                "--min-sdk-version",
                "23",
                str(path),
            ],
            capture_output=True,
            text=True,
            env={
                "LC_ALL": "C",
                "LANG": "C",
                "PATH": "/usr/bin:/bin",
                "HOME": "/nonexistent",
            },
            pass_fds=(apksigner_jar.fd,),
        )
    except OSError as exc:
        raise ValidationError("Java/apksigner.jar execution failed") from exc
    if completed.returncode == 0:
        raise ValidationError("Android RC APK unexpectedly verifies as signed")
    if "DOES NOT VERIFY" not in f"{completed.stdout}\n{completed.stderr}":
        raise ValidationError("apksigner failed without proving that the Android RC APK is unsigned")


def _python_lock_entries_from_paths(
    source: Path,
    *,
    component: str,
    requirements_relative: str,
    lock_relative: str,
) -> list[dict[str, Any]]:
    direct: dict[str, str] = {}
    for line_number, raw_line in enumerate(
        (source / requirements_relative).read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        match = PYTHON_PIN.fullmatch(line)
        if match is None:
            raise ValidationError(
                f"unversioned Python dependency input: {requirements_relative}:{line_number}"
            )
        direct[re.sub(r"[-_.]+", "-", match.group(1)).lower()] = match.group(2)

    logical: list[str] = []
    pending = ""
    for raw_line in (source / lock_relative).read_text(encoding="utf-8").splitlines():
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("--") and not pending:
            raise ValidationError(f"Python lock contains a global installer option: {lock_relative}")
        if stripped.endswith("\\"):
            pending += stripped[:-1].strip() + " "
        else:
            logical.append((pending + stripped).strip())
            pending = ""
    if pending:
        raise ValidationError(f"Python lock has an incomplete continuation: {lock_relative}")
    pattern = re.compile(
        r"^([A-Za-z0-9_.-]+)(?:\[[A-Za-z0-9_,.-]+\])?==([^\s;]+)"
        r"((?:\s+--hash=sha256:[0-9a-f]{64})+)$"
    )
    entries: dict[str, dict[str, Any]] = {}
    for line in logical:
        match = pattern.fullmatch(line)
        if match is None:
            raise ValidationError(f"Python lock entry is not exactly pinned with hashes: {lock_relative}")
        name = re.sub(r"[-_.]+", "-", match.group(1)).lower()
        hashes = sorted(set(re.findall(r"--hash=sha256:([0-9a-f]{64})", match.group(3))))
        if name in entries or not hashes:
            raise ValidationError(f"Python lock entry is duplicated or unhashed: {lock_relative}:{name}")
        entries[name] = {
            "comment": f"source={lock_relative}#package/{name}",
            "component": component,
            "name": name,
            "version": match.group(2),
            "hashes": hashes,
            "relationship": "DEPENDS_ON",
        }
    if not entries:
        raise ValidationError(f"Python lock is empty: {lock_relative}")
    for name, version in direct.items():
        if name not in entries or entries[name]["version"] != version:
            raise ValidationError(f"Python lock does not preserve direct requirement: {name}=={version}")
    return [entries[name] for name in sorted(entries)]


def _python_lock_entries(source: Path, component: str) -> list[dict[str, Any]]:
    return _python_lock_entries_from_paths(
        source,
        component=component,
        requirements_relative=f"{component}/requirements.txt",
        lock_relative=f"{component}/requirements.lock",
    )


def _android_verification_hashes(source: Path) -> dict[tuple[str, str, str], list[str]]:
    path = source / "apps/android/gradle/verification-metadata.xml"
    try:
        document = ET.parse(path)
    except (OSError, ET.ParseError) as exc:
        raise ValidationError("Android dependency verification metadata is invalid") from exc
    namespace = document.getroot().tag.partition("}")[0].removeprefix("{")
    result: dict[tuple[str, str, str], list[str]] = {}
    for component in document.findall(f".//{{{namespace}}}component"):
        key = (
            component.attrib.get("group", ""),
            component.attrib.get("name", ""),
            component.attrib.get("version", ""),
        )
        hashes = sorted(
            {
                node.attrib.get("value", "")
                for node in component.findall(f".//{{{namespace}}}sha256")
                if re.fullmatch(r"[0-9a-f]{64}", node.attrib.get("value", ""))
            }
        )
        if not all(key) or not hashes or key in result:
            raise ValidationError("Android dependency verification component is invalid")
        result[key] = hashes
    return result


def _expected_sbom_dependencies(source: Path) -> dict[str, dict[str, Any]]:
    expected: dict[str, dict[str, Any]] = {}
    for component in ("backend", "voice"):
        for entry in _python_lock_entries(source, component):
            entry["purl"] = (
                f"pkg:pypi/{quote(entry['name'])}@{quote(entry['version'])}"
            )
            expected[entry["comment"]] = entry

    web_lock = _load_json(source / "apps/web/package-lock.json", "source Web package lock")
    if web_lock.get("lockfileVersion") != 3 or not isinstance(web_lock.get("packages"), dict):
        raise ValidationError("source Web lock is not lockfileVersion 3")
    for package_path, package in web_lock["packages"].items():
        if not package_path:
            continue
        if not isinstance(package, dict) or not isinstance(package.get("version"), str):
            raise ValidationError(f"Web lock package is not pinned: {package_path}")
        integrity = package.get("integrity")
        if not isinstance(integrity, str) or re.fullmatch(r"sha512-[A-Za-z0-9+/]+={0,2}", integrity) is None:
            raise ValidationError(f"Web lock package has no exact SHA-512 integrity: {package_path}")
        try:
            integrity_bytes = base64.b64decode(integrity.removeprefix("sha512-"), validate=True)
        except (ValueError, binascii.Error) as exc:
            raise ValidationError(f"Web lock package SHA-512 integrity is invalid: {package_path}") from exc
        if len(integrity_bytes) != 64:
            raise ValidationError(f"Web lock package SHA-512 integrity is invalid: {package_path}")
        name = package_path.rsplit("node_modules/", 1)[-1]
        comment = f"source=apps/web/package-lock.json#packages/{package_path}"
        expected[comment] = {
            "comment": comment,
            "component": "web",
            "name": name,
            "version": package["version"],
            "hashes": [integrity_bytes.hex()],
            "checksum_algorithm": "SHA512",
            "purl": f"pkg:npm/{quote(name, safe='/')}@{quote(package['version'])}",
            "relationship": "DEPENDS_ON",
        }

    verification = _android_verification_hashes(source)
    lock_relative = "apps/android/app/gradle.lockfile"
    release_count = 0
    for raw_line in (source / lock_relative).read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("empty="):
            continue
        if "=" not in line:
            raise ValidationError("Android dependency lock entry is malformed")
        coordinate, raw_configurations = line.split("=", 1)
        if "releaseRuntimeClasspath" not in set(raw_configurations.split(",")):
            continue
        parts = coordinate.split(":")
        if len(parts) != 3 or tuple(parts) not in verification:
            raise ValidationError(f"Android release dependency is unverified: {coordinate}")
        group, artifact, version = parts
        comment = f"source={lock_relative}#releaseRuntimeClasspath/{coordinate}"
        expected[comment] = {
            "comment": comment,
            "component": "android",
            "name": f"{group}:{artifact}",
            "version": version,
            "hashes": verification[tuple(parts)],
            "purl": f"pkg:maven/{quote(group)}/{quote(artifact)}@{quote(version)}",
            "relationship": "DEPENDS_ON",
        }
        release_count += 1
    if release_count == 0:
        raise ValidationError("Android release dependency lock is empty")

    root_relative = "apps/android/build.gradle.kts"
    root_text = (source / root_relative).read_text(encoding="utf-8")
    for plugin, version in GRADLE_PLUGIN.findall(root_text):
        key = (plugin, f"{plugin}.gradle.plugin", version)
        if key not in verification:
            raise ValidationError(f"Android Gradle plugin is unverified: {plugin}")
        comment = f"source={root_relative}#plugin/{plugin}"
        expected[comment] = {
            "comment": comment,
            "component": "android",
            "name": plugin,
            "version": version,
            "hashes": verification[key],
            "purl": f"pkg:gradle-plugin/{quote(plugin)}@{quote(version)}",
            "relationship": "DEPENDS_ON",
        }
    wrapper_relative = "apps/android/gradle/wrapper/gradle-wrapper.properties"
    wrapper_text = (source / wrapper_relative).read_text(encoding="utf-8")
    wrapper = re.search(r"gradle-([0-9][0-9A-Za-z.-]*)-bin\.zip", wrapper_text)
    wrapper_hash = re.search(r"(?m)^distributionSha256Sum=([0-9a-f]{64})$", wrapper_text)
    if wrapper is None or wrapper_hash is None:
        raise ValidationError("Gradle wrapper dependency or hash is not pinned")
    wrapper_comment = f"source={wrapper_relative}#distributionUrl"
    expected[wrapper_comment] = {
        "comment": wrapper_comment,
        "component": "android",
        "name": "gradle",
        "version": wrapper.group(1),
        "hashes": [wrapper_hash.group(1)],
        "purl": f"pkg:generic/gradle@{quote(wrapper.group(1))}",
        "relationship": "DEPENDS_ON",
    }

    config_relative = "apps/android/app/src/main/assets/model-config/two_model_runtime.json"
    model_config = _load_json(source / config_relative, "Android source model config")
    models = model_config.get("models")
    if not isinstance(models, dict) or not models:
        raise ValidationError("Android source model config has no models")
    for model_name, model in sorted(models.items()):
        if not isinstance(model, dict) or not isinstance(model.get("artifact_sha256"), str):
            raise ValidationError(f"Android model SBOM source is invalid: {model_name}")
        digest = model["artifact_sha256"]
        if re.fullmatch(r"[0-9a-f]{64}", digest) is None:
            raise ValidationError(f"Android model SBOM hash is invalid: {model_name}")
        comment = f"source={config_relative}#model/{model_name}"
        version = model_config.get("version", "source-bound")
        if not isinstance(version, str) or not version:
            raise ValidationError(f"Android model SBOM version is invalid: {model_name}")
        expected[comment] = {
            "comment": comment,
            "component": "android",
            "name": f"WalkSafe Android model: {model_name}",
            "version": version,
            "hashes": [digest],
            "purl": (
                f"pkg:generic/walksafe-android-model-{quote(model_name)}@{quote(digest)}"
            ),
            "relationship": "CONTAINS",
        }
    return expected


def _expected_spdx_package(
    *,
    spdx_id: str,
    name: str,
    version: str,
    comment: str,
    checksums: list[str],
    checksum_algorithm: str = "SHA256",
    purl: str | None = None,
) -> dict[str, Any]:
    package: dict[str, Any] = {
        "SPDXID": spdx_id,
        "name": name,
        "versionInfo": version,
        "downloadLocation": "NOASSERTION",
        "filesAnalyzed": False,
        "licenseConcluded": "NOASSERTION",
        "licenseDeclared": "NOASSERTION",
        "copyrightText": "NOASSERTION",
        "comment": comment,
        "checksums": [
            {"algorithm": checksum_algorithm, "checksumValue": digest}
            for digest in sorted(set(checksums))
        ],
    }
    if purl is not None:
        package["externalRefs"] = [
            {
                "referenceCategory": "PACKAGE-MANAGER",
                "referenceType": "purl",
                "referenceLocator": purl,
            }
        ]
    return package


def _validate_sbom(
    sbom: dict[str, Any],
    *,
    source: Path,
    source_commit: str,
    components: dict[str, Any],
) -> None:
    expected_document_fields = {
        "spdxVersion",
        "dataLicense",
        "SPDXID",
        "name",
        "documentNamespace",
        "creationInfo",
        "packages",
        "relationships",
    }
    if set(sbom) != expected_document_fields:
        raise ValidationError("SBOM top-level fields differ from the exact contract")
    expected_document_identity = {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": f"WalkSafe full RC {source_commit}",
        "documentNamespace": f"https://walksafe.invalid/spdx/full-rc/{source_commit}",
    }
    if any(
        not _json_exact_equal(sbom.get(field), value)
        for field, value in expected_document_identity.items()
    ):
        raise ValidationError("SBOM document identity differs from the exact contract")
    if not _json_exact_equal(
        sbom.get("creationInfo"),
        {
            "created": "1970-01-01T00:00:00Z",
            "creators": ["Tool: build_walksafe_full_rc_20260713.py"],
        },
    ):
        raise ValidationError("SBOM creation metadata differs from the exact contract")

    component_ids = {
        "web": "SPDXRef-WalkSafe-Web",
        "android": "SPDXRef-WalkSafe-Android",
        "backend": "SPDXRef-WalkSafe-Backend",
        "voice": "SPDXRef-WalkSafe-Voice",
    }
    expected_packages: dict[str, dict[str, Any]] = {}
    expected_relationships: list[tuple[str, str, str]] = []
    for component, spdx_id in component_ids.items():
        artifact = components[component]["artifact"]
        expected_packages[spdx_id] = _expected_spdx_package(
            spdx_id=spdx_id,
            name=f"WalkSafe {component}",
            version=source_commit,
            comment=f"source={artifact['path']}",
            checksums=[artifact["sha256"]],
        )
        expected_relationships.append(("SPDXRef-DOCUMENT", "DESCRIBES", spdx_id))

    support_id = "SPDXRef-WalkSafe-WebRuntimeSupport"
    support = components["web"]["runtime_support"]
    expected_packages[support_id] = _expected_spdx_package(
        spdx_id=support_id,
        name="WalkSafe web single-instance runtime support",
        version=source_commit,
        comment=f"source={support['path']}",
        checksums=[support["sha256"]],
    )
    expected_relationships.append(("SPDXRef-DOCUMENT", "DESCRIBES", support_id))

    for comment, dependency in _expected_sbom_dependencies(source).items():
        component = dependency["component"]
        source_reference = comment.removeprefix("source=")
        dependency_id = (
            f"SPDXRef-{component}-"
            f"{hashlib.sha256(source_reference.encode('utf-8')).hexdigest()[:20]}"
        )
        if dependency_id in expected_packages:
            raise ValidationError("SBOM expected dependency IDs are not unique")
        expected_packages[dependency_id] = _expected_spdx_package(
            spdx_id=dependency_id,
            name=dependency["name"],
            version=dependency["version"],
            comment=comment,
            checksums=dependency["hashes"],
            checksum_algorithm=dependency.get("checksum_algorithm", "SHA256"),
            purl=dependency["purl"],
        )
        expected_relationships.append(
            (
                component_ids[component],
                dependency["relationship"],
                dependency_id,
            )
        )

    packages = sbom.get("packages")
    if not isinstance(packages, list):
        raise ValidationError("SBOM packages are missing")
    actual_packages: dict[str, dict[str, Any]] = {}
    for package in packages:
        if not isinstance(package, dict) or type(package.get("SPDXID")) is not str:
            raise ValidationError("SBOM contains a malformed package")
        spdx_id = package["SPDXID"]
        if spdx_id in actual_packages:
            raise ValidationError("SBOM contains duplicate SPDX package IDs")
        actual_packages[spdx_id] = package
    if len(actual_packages) != len(packages) or set(actual_packages) != set(expected_packages):
        raise ValidationError("SBOM package set differs from the exact contract")
    for spdx_id, expected in expected_packages.items():
        if not _json_exact_equal(actual_packages[spdx_id], expected):
            raise ValidationError(f"SBOM package differs from the exact contract: {spdx_id}")

    relationships = sbom.get("relationships")
    relationship_fields = {
        "spdxElementId", "relationshipType", "relatedSpdxElement"
    }
    if not isinstance(relationships, list):
        raise ValidationError("SBOM relationships are missing")
    actual_relationships: list[tuple[str, str, str]] = []
    for relationship in relationships:
        if (
            not isinstance(relationship, dict)
            or set(relationship) != relationship_fields
            or any(type(relationship[field]) is not str for field in relationship_fields)
        ):
            raise ValidationError("SBOM relationship fields differ from the exact contract")
        actual_relationships.append(
            (
                relationship["spdxElementId"],
                relationship["relationshipType"],
                relationship["relatedSpdxElement"],
            )
        )
    if sorted(actual_relationships) != sorted(expected_relationships):
        raise ValidationError("SBOM relationship multiset differs from the exact contract")


def _validate_trusted_proxy_contract(source: Path) -> None:
    config = source / "deploy/nginx/walksafe-web.conf.example"
    if config.is_symlink() or not config.is_file():
        raise ValidationError("trusted proxy configuration is missing or is a symlink")
    if hashlib.sha256(config.read_bytes()).hexdigest() == _LEGACY_WEB_NGINX_STUB_SHA256:
        return
    raise ValidationError(
        "trusted proxy configuration differs from the exact single-listener TLS legacy closure stub"
    )

    # Historical active-ingress validator retained below for archival code reading.
    lines = [
        content
        for raw_line in config.read_text(encoding="utf-8").splitlines()
        if (content := raw_line.split("#", 1)[0].strip())
    ]
    expected_lines = [
        "server {",
        "listen 443 ssl;",
        "server_name CHANGE_ME_WALKSAFE_HOSTNAME;",
        "ssl_certificate CHANGE_ME_TLS_CERTIFICATE_PATH;",
        "ssl_certificate_key CHANGE_ME_TLS_PRIVATE_KEY_PATH;",
        "ssl_protocols TLSv1.2 TLSv1.3;",
        "server_tokens off;",
        "client_max_body_size 64k;",
        "client_header_timeout 10s;",
        "client_body_timeout 10s;",
        "send_timeout 20s;",
        "proxy_http_version 1.1;",
        "proxy_request_buffering on;",
        "proxy_connect_timeout 2s;",
        "proxy_send_timeout 15s;",
        "proxy_read_timeout 20s;",
        'proxy_set_header Connection "";',
        "proxy_set_header Host $server_name;",
        "proxy_set_header CF-Connecting-IP $remote_addr;",
        "proxy_set_header X-Real-IP $remote_addr;",
        "proxy_set_header X-Forwarded-For $remote_addr;",
        "proxy_set_header X-Forwarded-Proto $scheme;",
        'proxy_set_header Forwarded "";',
        'proxy_set_header True-Client-IP "";',
        'proxy_set_header X-Client-IP "";',
        'proxy_set_header X-Cluster-Client-IP "";',
        'proxy_set_header X-Forwarded-Host "";',
        'proxy_set_header X-Forwarded-Port "";',
        'proxy_set_header X-Forwarded-Server "";',
        'proxy_set_header X-Original-Forwarded-For "";',
        "location = /api/detect {",
        "client_max_body_size 9m;",
        "proxy_request_buffering off;",
        "proxy_pass http://127.0.0.1:3000;",
        "}",
        "location = /api/detect/v2 {",
        "client_max_body_size 9m;",
        "proxy_request_buffering off;",
        "proxy_pass http://127.0.0.1:3000;",
        "}",
        "location = /api/reports {",
        "client_max_body_size 9m;",
        "proxy_request_buffering off;",
        "proxy_pass http://127.0.0.1:3000;",
        "}",
        "location = /api/reports/v2 {",
        "client_max_body_size 9m;",
        "proxy_request_buffering off;",
        "proxy_pass http://127.0.0.1:3000;",
        "}",
        "location = /api/speech/stt {",
        "client_max_body_size 11m;",
        "proxy_request_buffering off;",
        "proxy_pass http://127.0.0.1:3000;",
        "}",
        "location / {",
        "proxy_pass http://127.0.0.1:3000;",
        "}",
        "}",
    ]
    if lines != expected_lines:
        raise ValidationError(
            "trusted proxy configuration differs from the exact single-listener TLS contract"
        )
    for required in (
        "listen 443 ssl;",
        "server_name CHANGE_ME_WALKSAFE_HOSTNAME;",
        "ssl_certificate CHANGE_ME_TLS_CERTIFICATE_PATH;",
        "ssl_certificate_key CHANGE_ME_TLS_PRIVATE_KEY_PATH;",
        "ssl_protocols TLSv1.2 TLSv1.3;",
    ):
        if lines.count(required) != 1:
            raise ValidationError(
                f"trusted proxy configuration must declare exactly one: {required}"
            )
    if lines.count("proxy_pass http://127.0.0.1:3000;") != 6:
        raise ValidationError(
            "trusted proxy configuration must proxy exactly six bounded locations"
        )
    expected_locations = {
        "location = /api/detect {",
        "location = /api/detect/v2 {",
        "location = /api/reports {",
        "location = /api/reports/v2 {",
        "location = /api/speech/stt {",
        "location / {",
    }
    if sum(line.startswith("server ") for line in lines) != 1 or sum(
        line.startswith("location ") for line in lines
    ) != len(expected_locations) or {
        line for line in lines if line.startswith("location ")
    } != expected_locations:
        raise ValidationError(
            "trusted proxy configuration must contain one server and the exact bounded locations"
        )
    if any(
        forbidden in line
        for line in lines
        for forbidden in (
            "real_ip_header",
            "set_real_ip_from",
            "$http_",
            "$proxy_add_x_forwarded_for",
        )
    ):
        raise ValidationError("trusted proxy configuration may not trust or append inbound client identity")

    expected_headers = {
        "host": "$server_name",
        "cf-connecting-ip": "$remote_addr",
        "x-real-ip": "$remote_addr",
        "x-forwarded-for": "$remote_addr",
        "x-forwarded-proto": "$scheme",
        "forwarded": '""',
        "true-client-ip": '""',
        "x-client-ip": '""',
        "x-cluster-client-ip": '""',
        "x-forwarded-host": '""',
        "x-forwarded-port": '""',
        "x-forwarded-server": '""',
        "x-original-forwarded-for": '""',
    }
    protected: dict[str, str] = {}
    for line in lines:
        matched = re.fullmatch(r"proxy_set_header\s+([A-Za-z0-9-]+)\s+(.+);", line)
        if matched is None:
            continue
        name = matched.group(1).lower()
        if name not in expected_headers:
            continue
        if name in protected:
            raise ValidationError(
                f"trusted proxy configuration duplicates protected header: {name}"
            )
        protected[name] = matched.group(2)
    if protected != expected_headers:
        raise ValidationError(
            "trusted proxy configuration does not canonicalize the protected header set"
        )


def _environment_assignments(payload: str, *, context: str) -> dict[str, str]:
    assignments: dict[str, str] = {}
    for raw_line in payload.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValidationError(f"{context} contains an invalid active line")
        name, value = line.split("=", 1)
        if re.fullmatch(r"[A-Z][A-Z0-9_]*", name) is None or name in assignments:
            raise ValidationError(f"{context} contains an invalid or duplicate assignment")
        assignments[name] = value
    return assignments


def _require_environment_values(
    assignments: dict[str, str],
    expected: dict[str, str],
    *,
    allowed_keys: set[str],
    context: str,
) -> None:
    if set(assignments) != allowed_keys:
        raise ValidationError(f"{context} variable set differs from the exact allowlist")
    for name, value in expected.items():
        if assignments.get(name) != value:
            raise ValidationError(f"{context} must assign exactly one {name}={value}")


def _systemd_unit_directives(text: str) -> dict[str, dict[str, list[str]]]:
    sections: dict[str, dict[str, list[str]]] = {}
    section: str | None = None
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if raw_line.rstrip().endswith("\\"):
            raise ValidationError("systemd unit must not use continued directives")
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1].strip()
            if not section or section in sections:
                raise ValidationError("systemd unit contains an invalid or duplicate section")
            sections[section] = {}
            continue
        if section is None or "=" not in line:
            raise ValidationError("systemd unit contains an invalid active directive")
        name, value = (part.strip() for part in line.split("=", 1))
        if not name or re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", name) is None:
            raise ValidationError("systemd unit contains an invalid directive name")
        sections[section].setdefault(name, []).append(value)
    return sections


def _require_exact_systemd_unit(
    text: str,
    *,
    expected: dict[str, dict[str, list[str]]],
    context: str,
) -> None:
    try:
        actual = _systemd_unit_directives(text)
    except ValidationError as exc:
        raise ValidationError(f"{context} is invalid: {exc}") from exc
    if actual != expected:
        raise ValidationError(f"{context} differs from the exact packaged service contract")


def _require_legacy_web_stub(
    path: Path,
    *,
    expected_sha256: str,
    context: str,
) -> None:
    if path.is_symlink() or not path.is_file():
        raise ValidationError(f"{context} is missing or is a symlink")
    text = path.read_text(encoding="utf-8")
    if (
        "LEGACY_REFERENCE_ONLY" not in "\n".join(text.splitlines()[:4])
        or "TECHNICALLY_CLOSED" not in "\n".join(text.splitlines()[:4])
        or hashlib.sha256(text.encode("utf-8")).hexdigest() != expected_sha256
    ):
        raise ValidationError(f"{context} differs from the exact legacy closure stub")


def _validate_runtime_contracts(source: Path) -> None:
    backend_config = (source / "deploy/config/walksafe-backend.env.example").read_text(encoding="utf-8")
    backend_environment = _environment_assignments(
        backend_config, context="backend runtime example"
    )
    _require_environment_values(
        backend_environment,
        {
            "WALKSAFE_ENVIRONMENT": "production",
            "WALKSAFE_BACKEND_WORKERS": "1",
            "WALKSAFE_BACKEND_REPLICAS": "1",
            "WALKSAFE_ACTOR_RATE_LIMIT_STORE": "postgresql",
            "DATABASE_URL": (
                "postgresql+psycopg://walksafe_backend_app:CHANGE_ME@db.example.invalid:5432/"
                "walksafe?sslmode=verify-full&gssencmode=disable"
            ),
            "WALKSAFE_DATABASE_AT_REST_ENCRYPTION_CONFIRMED": "true",
            "WALKSAFE_DATABASE_TRANSPORT_SECURITY_CONFIRMED": "true",
            "WALKSAFE_DATABASE_ENCRYPTION_KEY_BOUNDARY": (
                "CHANGE_ME_DATABASE_KMS_BOUNDARY"
            ),
            "WALKSAFE_REPORT_IMAGE_KEY_BOUNDARY": (
                "CHANGE_ME_REPORT_IMAGE_KEY_BOUNDARY"
            ),
            "UPLOAD_DIR": "/var/lib/walksafe/uploads",
            "WALKSAFE_UPLOAD_BACKUP_READER_GROUP": "walksafe-backup-readers",
            "WALKSAFE_REPORT_IMAGE_KEY_PROVIDER": "secret_file",
            "WALKSAFE_REPORT_IMAGE_KEY_FILE": (
                "/etc/walksafe/report-image-keyring.json"
            ),
            "WALKSAFE_REPORT_IMAGE_KMS_AGENT_SOCKET": "",
            "WALKSAFE_REPORT_IMAGE_KMS_AGENT_PEER_UID": "0",
            "WALKSAFE_REPORT_IMAGE_KMS_AGENT_TIMEOUT_SECONDS": "2.0",
            "WALKSAFE_REPORT_ORIGINAL_GRANT_TTL_SECONDS": "120",
            "ANDROID_DEBUG_LOG_ENABLED": "false",
            "ANDROID_DEBUG_LOG_DIR": "/var/lib/walksafe/android-debug-logs",
            "DETECT_V2_MODE": "real",
            "DETECT_V2_IMAGE_SIZE": "768",
            "INFERENCE_PROCESS_ISOLATION_ENABLED": "true",
            "WALKING_ROUTE_PROVIDER": "tmap_pedestrian",
            "TMAP_POI_PROVIDER": "live",
            "TMAP_READINESS_LIVE_PROBE_ENABLED": "true",
            "WALKSAFE_ALLOW_INSECURE_LOCAL_DEV": "false",
            "WALKSAFE_FIELD_TEST_SECURITY_ENABLED": "true",
            "WALKSAFE_ADMIN_TOKEN": "",
            "WALKSAFE_ADMIN_SECURITY_ENABLED": "true",
            "WALKSAFE_ADMIN_ID": "walksafe.admin",
            "WALKSAFE_ADMIN_TOTP_SECRET": (
                "CHANGE_ME_CANONICAL_UNPADDED_BASE32_MIN_160_BITS"
            ),
            "WALKSAFE_ADMIN_CREDENTIAL_ISSUER_KEY_FILE": (
                "/etc/walksafe/admin-credential-issuer.key"
            ),
            "WALKSAFE_ADMIN_SESSION_TTL_SECONDS": "43200",
            "WALKSAFE_ADMIN_STEP_UP_TTL_SECONDS": "300",
            "WALKSAFE_ADMIN_RECOVERY_TTL_SECONDS": "900",
            "WALKSAFE_ADMIN_AUTH_RATE_LIMIT_ATTEMPTS": "5",
            "WALKSAFE_ADMIN_AUTH_RATE_LIMIT_WINDOW_SECONDS": "300",
            "WALKSAFE_GATEWAY_SESSION_SECRET": (
                "CHANGE_ME_RANDOM_SESSION_SECRET_AT_LEAST_32_CHARACTERS"
            ),
            "WALKSAFE_PRIVACY_HMAC_SECRET": (
                "CHANGE_ME_RANDOM_PRIVACY_HMAC_SECRET_AT_LEAST_32_UTF8_BYTES"
            ),
            "WALKSAFE_PRIVACY_HMAC_KEY_VERSION": "1",
            "WALKSAFE_MAINTENANCE_LOCK_PATH": (
                "/run/walksafe-maintenance-lock/maintenance.lock"
            ),
            "WALKSAFE_MAINTENANCE_LOCK_GROUP": "walksafe-maintenance-lock",
        },
        allowed_keys=set(
            """WALKSAFE_ENVIRONMENT WALKSAFE_SOURCE_COMMIT WALKSAFE_BACKEND_WORKERS
            WALKSAFE_BACKEND_REPLICAS WALKSAFE_ACTOR_RATE_LIMIT_STORE DATABASE_URL
            DATABASE_CONNECT_TIMEOUT_SECONDS DATABASE_STATEMENT_TIMEOUT_MS UPLOAD_DIR
            WALKSAFE_UPLOAD_BACKUP_READER_GROUP
            WALKSAFE_DATABASE_AT_REST_ENCRYPTION_CONFIRMED
            WALKSAFE_DATABASE_TRANSPORT_SECURITY_CONFIRMED
            WALKSAFE_DATABASE_ENCRYPTION_KEY_BOUNDARY WALKSAFE_REPORT_IMAGE_KEY_BOUNDARY
            WALKSAFE_REPORT_IMAGE_KEY_PROVIDER WALKSAFE_REPORT_IMAGE_KEY_FILE
            WALKSAFE_REPORT_IMAGE_KMS_AGENT_SOCKET WALKSAFE_REPORT_IMAGE_KMS_AGENT_PEER_UID
            WALKSAFE_REPORT_IMAGE_KMS_AGENT_TIMEOUT_SECONDS
            WALKSAFE_REPORT_ORIGINAL_GRANT_TTL_SECONDS
            MAX_UPLOAD_BYTES MAX_REPORT_METADATA_BYTES ANDROID_DEBUG_LOG_ENABLED
            ANDROID_DEBUG_LOG_DIR DETECT_V2_MODE DETECT_V2_IMAGE_SIZE
            DETECT_V2_UNIFIED_MODEL_PATH DETECT_V2_RUNTIME_CONFIG_PATH
            INFERENCE_PROCESS_ISOLATION_ENABLED INFERENCE_TIMEOUT_SECONDS
            INFERENCE_STARTUP_TIMEOUT_SECONDS WALKING_ROUTE_PROVIDER TMAP_APP_KEY
            TMAP_PEDESTRIAN_ROUTE_URL TMAP_POI_SEARCH_URL TMAP_POI_PROVIDER
            TMAP_TIMEOUT_SECONDS TMAP_READINESS_LIVE_PROBE_ENABLED CORS_ORIGINS
            WALKSAFE_ALLOW_INSECURE_LOCAL_DEV WALKSAFE_FIELD_TEST_SECURITY_ENABLED
            WALKSAFE_FIELD_TEST_TOKEN WALKSAFE_ADMIN_TOKEN WALKSAFE_ADMIN_SECURITY_ENABLED
            WALKSAFE_ADMIN_ID WALKSAFE_ADMIN_TOTP_SECRET
            WALKSAFE_ADMIN_CREDENTIAL_ISSUER_KEY_FILE
            WALKSAFE_ADMIN_SESSION_TTL_SECONDS WALKSAFE_ADMIN_STEP_UP_TTL_SECONDS
            WALKSAFE_ADMIN_RECOVERY_TTL_SECONDS WALKSAFE_ADMIN_AUTH_RATE_LIMIT_ATTEMPTS
            WALKSAFE_ADMIN_AUTH_RATE_LIMIT_WINDOW_SECONDS
            WALKSAFE_GATEWAY_SESSION_SECRET WALKSAFE_PRIVACY_HMAC_SECRET
            WALKSAFE_PRIVACY_HMAC_KEY_VERSION WALKSAFE_MAINTENANCE_LOCK_PATH
            WALKSAFE_MAINTENANCE_LOCK_GROUP""".split()
        ),
        context="backend runtime example",
    )
    database_parameters = parse_qs(
        urlsplit(backend_environment["DATABASE_URL"]).query,
        keep_blank_values=True,
    )
    if (
        database_parameters.get("sslmode") != ["verify-full"]
        or database_parameters.get("gssencmode") != ["disable"]
    ):
        raise ValidationError(
            "backend runtime DATABASE_URL must set sslmode=verify-full and gssencmode=disable"
        )
    migration_config = (
        source / "deploy/config/walksafe-backend-migration.env.example"
    ).read_text(encoding="utf-8")
    migration_environment = _environment_assignments(
        migration_config, context="backend migration example"
    )
    _require_environment_values(
        migration_environment,
        {
            "WALKSAFE_ENVIRONMENT": "production",
            "WALKSAFE_RUNTIME_DATABASE_ROLE": "walksafe_backend_app",
            "WALKSAFE_MIGRATION_DATABASE_URL": (
                "postgresql+psycopg://walksafe_migrator:CHANGE_ME@db.example.invalid:5432/"
                "walksafe?sslmode=verify-full&gssencmode=disable"
            ),
            "WALKSAFE_PRIVACY_HMAC_SECRET": (
                "CHANGE_ME_RANDOM_PRIVACY_HMAC_SECRET_AT_LEAST_32_UTF8_BYTES"
            ),
            "WALKSAFE_PRIVACY_HMAC_KEY_VERSION": "1",
        },
        allowed_keys={
            "WALKSAFE_ENVIRONMENT",
            "WALKSAFE_RUNTIME_DATABASE_ROLE",
            "WALKSAFE_MIGRATION_DATABASE_URL",
            "WALKSAFE_PRIVACY_HMAC_SECRET",
            "WALKSAFE_PRIVACY_HMAC_KEY_VERSION",
        },
        context="backend migration example",
    )
    migration_parameters = parse_qs(
        urlsplit(migration_environment["WALKSAFE_MIGRATION_DATABASE_URL"]).query,
        keep_blank_values=True,
    )
    if (
        migration_parameters.get("sslmode") != ["verify-full"]
        or migration_parameters.get("gssencmode") != ["disable"]
    ):
        raise ValidationError(
            "backend migration database URL must set sslmode=verify-full and gssencmode=disable"
        )
    if (
        urlsplit(backend_environment["DATABASE_URL"]).username
        == urlsplit(migration_environment["WALKSAFE_MIGRATION_DATABASE_URL"]).username
    ):
        raise ValidationError("backend runtime and migration database roles must differ")
    if (
        urlsplit(backend_environment["DATABASE_URL"]).username
        != migration_environment["WALKSAFE_RUNTIME_DATABASE_ROLE"]
    ):
        raise ValidationError(
            "backend runtime and migration examples must agree on the runtime role"
        )
    retention_config = (
        source / "deploy/config/walksafe-report-retention.env.example"
    ).read_text(encoding="utf-8")
    retention_environment = _environment_assignments(
        retention_config, context="report retention runtime example"
    )
    _require_environment_values(
        retention_environment,
        {
            "WALKSAFE_ENVIRONMENT": "production",
            "DATABASE_URL": (
                "postgresql+psycopg://walksafe_retention:CHANGE_ME@db.example.invalid:5432/"
                "walksafe?sslmode=verify-full&gssencmode=disable"
            ),
            "DATABASE_CONNECT_TIMEOUT_SECONDS": "5",
            "DATABASE_STATEMENT_TIMEOUT_MS": "10000",
            "UPLOAD_DIR": "/var/lib/walksafe/uploads",
            "WALKSAFE_UPLOAD_BACKUP_READER_GROUP": "walksafe-backup-readers",
            "WALKSAFE_MAINTENANCE_LOCK_PATH": (
                "/run/walksafe-maintenance-lock/maintenance.lock"
            ),
            "WALKSAFE_MAINTENANCE_LOCK_GROUP": "walksafe-maintenance-lock",
            "WALKSAFE_RETENTION_PYTHON": "/srv/walksafe/backend/.venv/bin/python",
            "WALKSAFE_REPORT_RETENTION_MANIFEST_DIR": (
                "/var/lib/walksafe/report-retention"
            ),
            "GNUPGHOME": "/var/lib/walksafe/report-retention/gnupg",
            "WALKSAFE_REPORT_RETENTION_BACKUP_MANIFEST": (
                "/var/lib/walksafe/backups/CHANGE_ME_RUN_ID/manifest.json"
            ),
            "WALKSAFE_REPORT_RETENTION_RESTORE_RECEIPT": (
                "/var/lib/walksafe/restore-receipts/CHANGE_ME_RECEIPT.json"
            ),
            "WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT": (
                "CHANGE_ME_40_HEX_BACKUP_SIGNER_FINGERPRINT"
            ),
            "WALKSAFE_RESTORE_TRUSTED_SIGNER_FINGERPRINT": (
                "CHANGE_ME_40_HEX_RESTORE_SIGNER_FINGERPRINT"
            ),
            "WALKSAFE_REPORT_RETENTION_ACTOR_ID": "retention.scheduler",
            "WALKSAFE_REPORT_RETENTION_BATCH_SIZE": "500",
        },
        allowed_keys={
            "WALKSAFE_ENVIRONMENT",
            "DATABASE_URL",
            "DATABASE_CONNECT_TIMEOUT_SECONDS",
            "DATABASE_STATEMENT_TIMEOUT_MS",
            "UPLOAD_DIR",
            "WALKSAFE_UPLOAD_BACKUP_READER_GROUP",
            "WALKSAFE_MAINTENANCE_LOCK_PATH",
            "WALKSAFE_MAINTENANCE_LOCK_GROUP",
            "WALKSAFE_RETENTION_PYTHON",
            "WALKSAFE_REPORT_RETENTION_MANIFEST_DIR",
            "GNUPGHOME",
            "WALKSAFE_REPORT_RETENTION_BACKUP_MANIFEST",
            "WALKSAFE_REPORT_RETENTION_RESTORE_RECEIPT",
            "WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT",
            "WALKSAFE_RESTORE_TRUSTED_SIGNER_FINGERPRINT",
            "WALKSAFE_REPORT_RETENTION_ACTOR_ID",
            "WALKSAFE_REPORT_RETENTION_BATCH_SIZE",
        },
        context="report retention runtime example",
    )
    retention_database_url = urlsplit(retention_environment["DATABASE_URL"])
    retention_database_parameters = parse_qs(
        retention_database_url.query,
        keep_blank_values=True,
    )
    if (
        retention_database_url.scheme != "postgresql+psycopg"
        or retention_database_parameters.get("sslmode") != ["verify-full"]
        or retention_database_parameters.get("gssencmode") != ["disable"]
    ):
        raise ValidationError(
            "report retention DATABASE_URL must use postgresql+psycopg, sslmode=verify-full, and gssencmode=disable"
        )
    retention_runner = (
        source / "scripts/run_walksafe_report_retention_20260717.sh"
    ).read_text(encoding="utf-8")
    retention_apply = (
        source / "scripts/check_report_retention_dry_run.py"
    ).read_text(encoding="utf-8")
    runner_requirements = {
        "set -euo pipefail",
        "umask 077",
        '[[ "${WALKSAFE_ENVIRONMENT}" == "production" ]]',
        "DATABASE_CONNECT_TIMEOUT_SECONDS",
        "DATABASE_STATEMENT_TIMEOUT_MS",
        'database_parameters.get("sslmode") != "verify-full"',
        'database_parameters.get("gssencmode") != "disable"',
        '[[ "${GNUPGHOME}" == /* ]]',
        "${SCRIPT_DIR}/check_report_retention_dry_run.py",
        "--apply",
        "--backup-manifest",
        "--restore-receipt",
        "--trusted-backup-signer-fingerprint",
        "--trusted-restore-signer-fingerprint",
        "--maintenance-lock",
        "--confirm DELETE-EXPIRED-REPORTS",
        ">/dev/null 2>&1",
        "/usr/bin/setsid --wait",
        "child_termination_in_progress",
        'job_pids="$(jobs -pr 2>/dev/null || true)"',
        'kill -TERM -- "-${pid}"',
        'child_is_running "${pid}" || child_group_is_running "${pid}"',
        'kill -KILL -- "-${pid}"',
        'wait "${pid}"',
        '"${WALKSAFE_RETENTION_PYTHON}" -I -S -B -',
        "RENAME_NOREPLACE = 1",
        "rename_noreplace(directory_fd, pending.name, final_name)",
        "rename_noreplace(directory_fd, final_name, pending.name)",
        "preserve_exact_fd_link(",
        "inode_identity(published_metadata) != inode_identity(metadata)",
        "publication_link_created and not publication_complete",
        "state_fields_by_status",
        "set(payload) != base_fields | state_fields_by_status.get(status_value, state_fields)",
        'expected_age_days = (as_of - created_at).days',
        'candidate["age_days"] != expected_age_days',
        'reason != expected_reason',
        'status_value == "completed" and payload.get("cleanup_errors") != []',
        '"reconciled_runs"',
        'image_fields = {',
        '"envelope_sha256"',
        '"envelope_size"',
        'reconciled_fields = {"run_id", "status", "report_ids"}',
        '"RECONCILED_PRECOMMIT_ABORTED"',
        '"RECONCILED_POSTCOMMIT_COMPLETED"',
        "current_reconciliations = [",
        'current_reconciliations[0]["report_ids"] != candidate_ids',
        'status_value == "failed_before_commit"',
        'status_value == "recovery_copied" and not candidates',
        "0o600",
        "gnupg_home.parent != manifest_directory",
        "resolved != path",
        "metadata.st_uid != os.geteuid()",
        "stat.S_IMODE(metadata.st_mode) != 0o700",
        "mktemp -u",
        "new pending manifest path",
        "a nonempty pending-manifest path was preserved",
    }
    if (
        "--database-url" in retention_runner
        or "chmod 600" in retention_runner
        or "os.unlink(" in retention_runner
        or any(required not in retention_runner for required in runner_requirements)
        or any(name not in retention_runner for name in retention_environment)
    ):
        raise ValidationError("report retention runner differs from the fail-closed contract")
    apply_requirements = {
        "with exclusive_maintenance_lock(",
        "validate_predelete_backup(",
        "validate_predelete_restore_receipt(",
        'APPLY_CONFIRMATION = "DELETE-EXPIRED-REPORTS"',
        'args.database_url = os.getenv("DATABASE_URL")',
        "validated_retention_database_url(",
        "create_retention_database_engine(",
        '"connect_timeout": connect_timeout_seconds',
        '"options": f"-c statement_timeout={statement_timeout_ms}"',
        "validated_as_of(",
            "signal_guard = RetentionSignalGuard()",
            "signal_guard.install()",
            "signal_guard.begin_cleanup()",
            "with signal_guard.blocked():",
            "signal_guard.close()",
            "RetentionManifestPublisher(",
            "retention manifest path must be a new non-alias file",
            "retention manifest directory must be disjoint from the upload root",
            "retention manifest temporary descriptor identity is unavailable",
            "--output-md is dry-run only and is forbidden with --apply",
            "database_committed = True",
        "if database_committed:",
        "_open_canonical_directory(",
            "_rename_noreplace(",
            "_rename_exchange(",
            "file_rename_stable_identity(",
        "st_nlink",
            "partial retention recovery cleanup failed",
            "partial retention recovery descriptor is invalid",
            "_copy_recovery_file_descriptors(",
            "pre-delete restore target upload root must differ from its source",
        "unlink_file_with_identity(",
        "require_file_identity(",
    }
    if any(required not in retention_apply for required in apply_requirements):
        raise ValidationError("report retention apply differs from the guarded safety contract")
    voice_config = (source / "deploy/config/walksafe-voice.env.example").read_text(encoding="utf-8")
    voice_environment = _environment_assignments(voice_config, context="Voice runtime example")
    _require_environment_values(
        voice_environment,
        {
            "WALKSAFE_ENVIRONMENT": "production",
            "VOICE_SERVICE_WORKERS": "1",
            "VOICE_SERVICE_REPLICAS": "1",
            "VOICE_SERVICE_PROCESS_LOCK_PATH": "/run/walksafe-voice/voice-process.lock",
            "VOICE_CORS_ORIGINS": "https://walksafe.example.invalid",
            "VOICE_STT_GLOBAL_RATE_LIMIT": "120",
            "VOICE_STT_MAX_CONCURRENCY": "1",
            "VOICE_STT_MODEL_REVISION": "CHANGE_ME_40_HEX_HF_COMMIT",
            "VOICE_STT_MODEL_MANIFEST_PATH": "/etc/walksafe/voice-stt-model-files.json",
            "VOICE_STT_MODEL_MANIFEST_SHA256": "CHANGE_ME_64_HEX_MANIFEST_SHA256",
            "VOICE_TTS_MODEL_REVISION": "CHANGE_ME_40_HEX_HF_COMMIT",
            "VOICE_TTS_MODEL_MANIFEST_PATH": "/etc/walksafe/voice-tts-model-files.json",
            "VOICE_TTS_MODEL_MANIFEST_SHA256": "CHANGE_ME_64_HEX_MANIFEST_SHA256",
            "VOICE_TTS_CACHE_DIR": "/var/cache/walksafe/voice/tts",
        },
        allowed_keys=set(
            """WALKSAFE_ENVIRONMENT VOICE_SERVICE_TOKEN WALKSAFE_FIELD_TEST_TOKEN
            WALKSAFE_ADMIN_TOKEN WALKSAFE_GATEWAY_SESSION_SECRET VOICE_SERVICE_WORKERS
            VOICE_SERVICE_REPLICAS VOICE_SERVICE_PROCESS_LOCK_PATH VOICE_CORS_ORIGINS
            VOICE_MAX_UPLOAD_BYTES VOICE_STT_MAX_REQUEST_BYTES
            VOICE_STT_MAX_AUDIO_DURATION_SECONDS VOICE_STT_MAX_UPLOAD_CONCURRENCY
            VOICE_STT_UPLOAD_QUEUE_TIMEOUT_SECONDS VOICE_STT_ACTOR_RATE_LIMIT
            VOICE_STT_IP_RATE_LIMIT VOICE_STT_GLOBAL_RATE_LIMIT
            VOICE_STT_RATE_WINDOW_SECONDS VOICE_STT_MAX_CONCURRENCY
            VOICE_STT_QUEUE_TIMEOUT_SECONDS VOICE_STT_INFERENCE_TIMEOUT_SECONDS
            VOICE_READY_TIMEOUT_SECONDS HF_HOME VOICE_STT_MODEL VOICE_STT_MODEL_REVISION
            VOICE_STT_MODEL_MANIFEST_PATH VOICE_STT_MODEL_MANIFEST_SHA256
            VOICE_STT_LANGUAGE VOICE_TTS_MODEL VOICE_TTS_MODEL_REVISION
            VOICE_TTS_MODEL_MANIFEST_PATH VOICE_TTS_MODEL_MANIFEST_SHA256 VOICE_TTS_MODE
            VOICE_TTS_LANGUAGE VOICE_TTS_CACHE_DIR VOICE_TTS_CACHE_MAX_FILES
            VOICE_TTS_CACHE_MAX_BYTES VOICE_TTS_INFERENCE_TIMEOUT_SECONDS""".split()
        ),
        context="Voice runtime example",
    )
    _require_legacy_web_stub(
        source / "deploy/config/walksafe-web.env.example",
        expected_sha256=_LEGACY_WEB_ENV_STUB_SHA256,
        context="Web runtime example",
    )
    _validate_trusted_proxy_contract(source)
    common_service_hardening = {
        "NoNewPrivileges": ["true"],
        "PrivateTmp": ["true"],
        "PrivateDevices": ["true"],
        "ProtectSystem": ["strict"],
        "ProtectHome": ["true"],
        "ProtectKernelTunables": ["true"],
        "ProtectKernelModules": ["true"],
        "ProtectControlGroups": ["true"],
    }
    _require_legacy_web_stub(
        source / "deploy/systemd/walksafe-web.service",
        expected_sha256=_LEGACY_WEB_SYSTEMD_STUB_SHA256,
        context="Web runtime unit",
    )
    expected_backend_unit = {
        "Unit": {
            "Description": ["WalkSafe Backend API"],
            "Requires": ["walksafe-backend-migrate.service"],
            "After": ["network-online.target walksafe-backend-migrate.service"],
            "Wants": ["network-online.target"],
        },
        "Service": {
            "Type": ["simple"],
            "User": ["walksafe-backend"],
            "Group": ["walksafe-backend"],
            "SupplementaryGroups": ["walksafe-maintenance-lock"],
            "WorkingDirectory": ["/srv/walksafe/backend"],
            "Environment": [
                "PYTHONPATH=/srv/walksafe/backend",
                "PYTHONDONTWRITEBYTECODE=1",
            ],
            "EnvironmentFile": ["/etc/walksafe/backend-runtime.env"],
            "RuntimeDirectory": ["walksafe-backend"],
            "RuntimeDirectoryMode": ["0700"],
            "StateDirectory": ["walksafe/android-debug-logs walksafe/capacity"],
            "StateDirectoryMode": ["0700"],
            "ExecStart": [
                "/srv/walksafe/backend/.venv/bin/python -m uvicorn backend.app.main:app "
                "--host 127.0.0.1 --port 8000 --workers 1"
            ],
            "Restart": ["on-failure"],
            "RestartSec": ["3"],
            "LimitCORE": ["0"],
            **common_service_hardening,
            "ReadOnlyPaths": [
                "/etc/walksafe/report-image-keyring.json "
                "/etc/walksafe/admin-credential-issuer.key "
                "/run/walksafe-maintenance-lock"
            ],
            "InaccessiblePaths": [
                "/etc/walksafe/backend-migration.env /etc/walksafe/backend.env "
                "/etc/walksafe/backup.env /etc/walksafe/backup-key-control.json "
                "/etc/walksafe/backup-key-control.json.sig "
                "/etc/walksafe/backup-key-control.lock "
                "-/var/lib/walksafe-backup -/var/lib/walksafe-backup-gnupg "
                "-/run/walksafe-backup"
            ],
            "ReadWritePaths": [
                "/var/lib/walksafe/uploads /var/lib/walksafe/android-debug-logs "
                "/var/lib/walksafe/capacity /run/walksafe-backend"
            ],
        },
        "Install": {"WantedBy": ["multi-user.target"]},
    }
    _require_exact_systemd_unit(
        (source / "deploy/systemd/walksafe-backend.service").read_text(encoding="utf-8"),
        expected=expected_backend_unit,
        context="backend runtime unit",
    )
    expected_report_retention_unit = {
        "Unit": {
            "Description": ["WalkSafe report row and upload retention"],
            "Requires": ["walksafe-backend.service"],
            "After": ["network-online.target walksafe-backend.service"],
            "Wants": ["network-online.target"],
        },
        "Service": {
            "Type": ["oneshot"],
            "User": ["walksafe-backend"],
            "Group": ["walksafe-backend"],
            "SupplementaryGroups": ["walksafe-maintenance-lock"],
            "WorkingDirectory": ["/srv/walksafe/backend"],
            "Environment": [
                "PYTHONPATH=/srv/walksafe/backend",
                "PYTHONDONTWRITEBYTECODE=1",
            ],
            "EnvironmentFile": ["/etc/walksafe/report-retention.env"],
            "StateDirectory": ["walksafe/report-retention"],
            "StateDirectoryMode": ["0700"],
            "UMask": ["0077"],
            "ExecStart": [
                "/usr/bin/bash /srv/walksafe/backend/scripts/"
                "run_walksafe_report_retention_20260717.sh"
            ],
            "TimeoutStartSec": ["1h"],
            "TimeoutStopSec": ["130s"],
            "LimitCORE": ["0"],
            **common_service_hardening,
            "ReadOnlyPaths": ["/run/walksafe-maintenance-lock"],
            "InaccessiblePaths": [
                "/etc/walksafe/backup.env /etc/walksafe/backup-key-control.lock "
                "-/var/lib/walksafe-backup -/var/lib/walksafe-backup-gnupg "
                "-/run/walksafe-backup"
            ],
            "ReadWritePaths": [
                "/var/lib/walksafe/uploads /var/lib/walksafe/report-retention"
            ],
        },
    }
    _require_exact_systemd_unit(
        (source / "deploy/systemd/walksafe-report-retention.service").read_text(
            encoding="utf-8"
        ),
        expected=expected_report_retention_unit,
        context="report retention runtime unit",
    )
    expected_report_retention_timer = {
        "Unit": {"Description": ["Run WalkSafe report retention daily"]},
        "Timer": {
            "OnCalendar": ["*-*-* 03:30:00"],
            "Persistent": ["true"],
            "RandomizedDelaySec": ["30m"],
            "AccuracySec": ["1m"],
            "Unit": ["walksafe-report-retention.service"],
        },
        "Install": {"WantedBy": ["timers.target"]},
    }
    _require_exact_systemd_unit(
        (source / "deploy/systemd/walksafe-report-retention.timer").read_text(
            encoding="utf-8"
        ),
        expected=expected_report_retention_timer,
        context="report retention timer",
    )
    expected_migration_unit = {
        "Unit": {
            "Description": ["WalkSafe Backend Alembic migration"],
            "After": ["network-online.target"],
            "Wants": ["network-online.target"],
            "Before": ["walksafe-backend.service"],
        },
        "Service": {
            "Type": ["oneshot"],
            "User": ["walksafe-maintenance"],
            "Group": ["walksafe-maintenance"],
            "WorkingDirectory": ["/srv/walksafe/backend"],
            "Environment": [
                "PYTHONPATH=/srv/walksafe/backend",
                "PYTHONDONTWRITEBYTECODE=1",
            ],
            "EnvironmentFile": ["/etc/walksafe/backend-migration.env"],
            "UMask": ["0077"],
            "ExecStart": [
                "/srv/walksafe/backend/.venv/bin/python -m alembic "
                "-c backend/alembic.ini upgrade head"
            ],
            "LimitCORE": ["0"],
            **common_service_hardening,
            "InaccessiblePaths": [
                "/etc/walksafe/backend-runtime.env /etc/walksafe/backend.env "
                "/etc/walksafe/admin-credential-issuer.key"
            ],
        },
    }
    _require_exact_systemd_unit(
        (source / "deploy/systemd/walksafe-backend-migrate.service").read_text(
            encoding="utf-8"
        ),
        expected=expected_migration_unit,
        context="backend migration unit",
    )
    expected_issuer_binding_unit = {
        "Unit": {
            "Description": ["WalkSafe one-time administrator issuer-key binding"],
            "After": [
                "network-online.target walksafe-backend-migrate.service"
            ],
            "Wants": ["network-online.target"],
            "Requires": ["walksafe-backend-migrate.service"],
            "Before": ["walksafe-backend.service"],
            "Conflicts": ["walksafe-backend.service"],
        },
        "Service": {
            "Type": ["oneshot"],
            "User": ["walksafe-issuer-bind"],
            "Group": ["walksafe-issuer-bind"],
            "WorkingDirectory": ["/srv/walksafe/backend"],
            "Environment": [
                "PYTHONPATH=/srv/walksafe/backend",
                "PYTHONDONTWRITEBYTECODE=1",
            ],
            "EnvironmentFile": ["/etc/walksafe/backend-migration.env"],
            "UMask": ["0077"],
            "LoadCredential": [
                "admin-credential-issuer.key:"
                "/etc/walksafe/admin-credential-issuer.key"
            ],
            "ExecStart": [
                "/srv/walksafe/backend/.venv/bin/python "
                "/srv/walksafe/backend/scripts/"
                "bind_walksafe_admin_credential_issuer_key.py "
                "--admin-id walksafe.admin "
                "--issuer-key-file %d/admin-credential-issuer.key "
                "--issuer-key-source systemd-credential"
            ],
            "LimitCORE": ["0"],
            **common_service_hardening,
            "CapabilityBoundingSet": [""],
            "AmbientCapabilities": [""],
            "InaccessiblePaths": [
                "/etc/walksafe/backend-runtime.env "
                "/etc/walksafe/backend-migration.env /etc/walksafe/backend.env "
                "/etc/walksafe/admin-credential-issuer.key"
            ],
        },
    }
    _require_exact_systemd_unit(
        (
            source / "deploy/systemd/walksafe-admin-issuer-bind.service"
        ).read_text(encoding="utf-8"),
        expected=expected_issuer_binding_unit,
        context="backend issuer binding unit",
    )
    expected_sysusers = (
        "g walksafe-maintenance-lock -\n"
        "g walksafe-backup-readers -\n"
        'u walksafe-backup - "WalkSafe encrypted backup" '
        "/nonexistent /usr/sbin/nologin\n"
        'u walksafe-maintenance - "WalkSafe database migration" '
        "/nonexistent /usr/sbin/nologin\n"
        'u walksafe-issuer-bind - "WalkSafe issuer-key binding" '
        "/nonexistent /usr/sbin/nologin\n"
    )
    if (
        source / "deploy/sysusers.d/walksafe-backend.conf"
    ).read_text(encoding="utf-8") != expected_sysusers:
        raise ValidationError("backend service-account provisioning differs")
    expected_voice_unit = {
        "Unit": {
            "Description": ["WalkSafe Voice API (single replica)"],
            "After": ["network-online.target"],
            "Wants": ["network-online.target"],
        },
        "Service": {
            "Type": ["simple"],
            "User": ["walksafe-voice"],
            "Group": ["walksafe-voice"],
            "WorkingDirectory": ["/srv/walksafe/voice"],
            "Environment": [
                "PYTHONPATH=/srv/walksafe/voice",
                "PYTHONDONTWRITEBYTECODE=1",
            ],
            "EnvironmentFile": ["/etc/walksafe/voice.env"],
            "RuntimeDirectory": ["walksafe-voice"],
            "RuntimeDirectoryMode": ["0700"],
            "CacheDirectory": ["walksafe/voice"],
            "CacheDirectoryMode": ["0700"],
            "StateDirectory": ["walksafe/voice-model-cache"],
            "StateDirectoryMode": ["0700"],
            "ExecStart": [
                "/srv/walksafe/voice/.venv/bin/python -m uvicorn voice.server:app "
                "--host 127.0.0.1 --port 9001 --workers 1"
            ],
            "Restart": ["on-failure"],
            "RestartSec": ["3"],
            **common_service_hardening,
            "ReadOnlyPaths": ["/var/lib/walksafe/voice-model-cache"],
            "ReadWritePaths": ["/var/cache/walksafe/voice /run/walksafe-voice"],
        },
        "Install": {"WantedBy": ["multi-user.target"]},
    }
    _require_exact_systemd_unit(
        (source / "deploy/systemd/walksafe-voice.service").read_text(encoding="utf-8"),
        expected=expected_voice_unit,
        context="Voice runtime unit",
    )
    android_build = (source / "apps/android/app/build.gradle.kts").read_text(encoding="utf-8")
    if "lockMode.set(LockMode.STRICT)" not in android_build or "lockAllConfigurations()" not in android_build:
        raise ValidationError("Android build does not enforce strict dependency locking")


def _validate_full_rc_snapshot(
    source_root: Path,
    rc_root: Path,
    java_path: Path,
    expected_java_sha256: str,
    apksigner_jar_path: Path,
    expected_apksigner_jar_sha256: str,
) -> dict[str, Any]:
    try:
        source_identity = verify_exact_git_source(source_root, context="validator source")
    except ReleaseIntegrityError as exc:
        raise ValidationError(str(exc)) from exc
    source = source_identity.root
    source_commit = source_identity.commit
    source_tree = source_identity.tree
    if not FULL_SHA.fullmatch(source_commit) or not FULL_SHA.fullmatch(source_tree):
        raise ValidationError("source Git identity is not a full SHA")
    if rc_root.is_symlink() or not rc_root.is_dir():
        raise ValidationError("RC root must be a real directory")
    rc = rc_root.resolve()
    manifest_path = rc / "walksafe-full-rc-manifest.json"
    manifest_snapshot, manifest_bytes = _snapshot_file(
        manifest_path,
        "walksafe-full-rc-manifest.json",
        "full RC manifest",
        capture=True,
    )
    assert manifest_bytes is not None
    try:
        manifest = strict_json_bytes(manifest_bytes, context="full RC manifest")
    except ReleaseIntegrityError as exc:
        raise ValidationError(str(exc)) from exc
    if not isinstance(manifest, dict):
        raise ValidationError("full RC manifest must contain a JSON object")
    if set(manifest) != FULL_RC_MANIFEST_KEYS:
        raise ValidationError("full RC manifest top-level fields differ from the exact contract")
    if manifest.get("schema_version") != "walksafe.full-rc-manifest.v2":
        raise ValidationError("full RC manifest schema is unsupported")
    source_record = manifest.get("source")
    if (
        not isinstance(source_record, dict)
        or set(source_record) != {"commit", "tree", "worktree_clean"}
        or source_record.get("commit") != source_commit
    ):
        raise ValidationError("full RC manifest source commit does not match actual HEAD")
    if source_record.get("tree") != source_tree or source_record.get("worktree_clean") is not True:
        raise ValidationError("full RC manifest source tree identity is stale")
    release_state = manifest.get("release_state")
    if not _json_exact_equal(release_state, EXPECTED_RELEASE_STATE):
        raise ValidationError("full RC release state differs from the exact candidate contract")
    authentication = manifest.get("authentication")
    if not _json_exact_equal(
        authentication,
        {
            "state": "external-attestation-required",
            "included": False,
            "deployment_allowed": False,
        },
    ):
        raise ValidationError("full RC authentication state is not fail-closed")

    closure_rows = manifest.get("files")
    if not isinstance(closure_rows, list) or not closure_rows:
        raise ValidationError("full RC file closure is missing")
    closure_by_path: dict[str, dict[str, Any]] = {}
    for row in closure_rows:
        path = _record_path(row, rc, "full RC closure file")
        relative = path.relative_to(rc).as_posix()
        if relative == "walksafe-full-rc-manifest.json" or relative in closure_by_path:
            raise ValidationError("full RC file closure contains its manifest or a duplicate path")
        closure_by_path[relative] = row
    canonical_closure = "".join(
        f"{closure_by_path[path]['sha256']} {closure_by_path[path]['bytes']} {path}\n"
        for path in sorted(closure_by_path)
    ).encode("utf-8")
    closure = manifest.get("closure")
    if not isinstance(closure, dict) or not _json_exact_equal(
        closure,
        {
            "algorithm": "walksafe-path-size-sha256-lines.v1",
            "sha256": hashlib.sha256(canonical_closure).hexdigest(),
        },
    ):
        raise ValidationError("full RC file closure digest is invalid")

    source_inputs = manifest.get("source_inputs")
    if not isinstance(source_inputs, list):
        raise ValidationError("full RC source provenance is missing")
    if any(
        not isinstance(row, dict) or set(row) != ARTIFACT_RECORD_KEYS
        for row in source_inputs
    ):
        raise ValidationError(
            "full RC source provenance fields differ from the exact record contract"
        )
    records_by_path = {
        row.get("path"): row for row in source_inputs if isinstance(row, dict) and isinstance(row.get("path"), str)
    }
    if set(records_by_path) != REQUIRED_PROVENANCE_PATHS or len(records_by_path) != len(source_inputs):
        raise ValidationError("full RC source provenance set is incomplete or duplicated")
    for relative, record in records_by_path.items():
        source_file = source / relative
        if source_file.is_symlink() or not source_file.is_file():
            raise ValidationError(f"source provenance input is missing: {relative}")
        if (
            type(record.get("bytes")) is not int
            or type(record.get("sha256")) is not str
            or record["bytes"] != source_file.stat().st_size
            or record["sha256"] != sha256_file(source_file)
        ):
            raise ValidationError(f"source provenance input differs from HEAD: {relative}")

    components = manifest.get("components")
    if not isinstance(components, dict) or set(components) != {"web", "android", "backend", "voice"}:
        raise ValidationError("full RC component set is incomplete")
    for component_name, component in components.items():
        if (
            not isinstance(component, dict)
            or set(component) != COMPONENT_RECORD_KEYS[component_name]
        ):
            raise ValidationError(
                f"component record fields differ from the exact contract: {component_name}"
            )
        if any(
            not _json_exact_equal(component.get(field), value)
            for field, value in EXPECTED_COMPONENT_METADATA[component_name].items()
        ):
            raise ValidationError(
                f"component metadata differs from the exact contract: {component_name}"
            )
        _record_path(component.get("artifact"), rc, f"{component_name} artifact")

    android = components["android"]
    if android.get("signing_status") != "unsigned" or android.get("deployable") is not False:
        raise ValidationError("Android component must be explicitly unsigned and non-deployable")
    android_apk = _record_path(android["artifact"], rc, "Android unsigned APK")
    java = _capture_tool(
        java_path,
        expected_java_sha256,
        label="Java executable",
        executable=True,
    )
    try:
        try:
            java_runtime_trust = root_owned_system_trust(
                java.source_path,
                context="Java runtime",
                tree_root=java.source_path.parent.parent,
            )
        except ReleaseIntegrityError as exc:
            raise ValidationError(str(exc)) from exc
        apksigner_jar = _capture_tool(
            apksigner_jar_path,
            expected_apksigner_jar_sha256,
            label="apksigner.jar",
            executable=False,
        )
        try:
            _validate_unsigned_apk(
                android_apk,
                source_commit,
                java,
                apksigner_jar,
                source,
            )
            if not java.matches_path(java_path) or not apksigner_jar.matches_path(apksigner_jar_path):
                raise ValidationError("Java/apksigner.jar changed during unsigned APK validation")
            try:
                if root_owned_system_trust(
                    java.source_path,
                    context="Java runtime",
                    tree_root=java.source_path.parent.parent,
                ) != java_runtime_trust:
                    raise ValidationError("Java runtime changed during unsigned APK validation")
            except ReleaseIntegrityError as exc:
                raise ValidationError(str(exc)) from exc
            tool_records = {
                "java": {
                    **java.record(path=str(java.source_path)),
                    "runtime_trust": java_runtime_trust,
                },
                "apksigner_jar": apksigner_jar.record(path=str(apksigner_jar.source_path)),
            }
        finally:
            apksigner_jar.close()
    finally:
        java.close()

    backend_expected = {
        "backend/__init__.py",
        "backend/alembic.ini",
        "backend/requirements.txt",
        "backend/requirements.lock",
        "backend/.env.example",
        "model/two_model_runtime.py",
        "configs/walksafe_unified_epoch270_field_20260711.json",
        "deploy/config/walksafe-backend.env.example",
        "deploy/config/walksafe-backend-migration.env.example",
        "deploy/config/walksafe-report-retention.env.example",
        "deploy/systemd/walksafe-backend.service",
        "deploy/systemd/walksafe-backend-migrate.service",
        "deploy/systemd/walksafe-admin-issuer-bind.service",
        "deploy/sysusers.d/walksafe-backend.conf",
        "deploy/systemd/walksafe-report-retention.service",
        "deploy/systemd/walksafe-report-retention.timer",
        "scripts/bind_walksafe_admin_credential_issuer_key.py",
        "scripts/check_report_retention_dry_run.py",
        "scripts/run_walksafe_report_retention_20260717.sh",
        "scripts/walksafe_backup_integrity.py",
        "scripts/walksafe_environment_identity.py",
        "scripts/walksafe_release_integrity.py",
    }
    backend_expected.update(_source_python_files(source, "backend/app"))
    backend_expected.update(_source_python_files(source, "backend/alembic"))
    _validate_runtime_archive(
        _record_path(components["backend"]["artifact"], rc, "backend runtime archive"),
        source=source,
        root_name="walksafe-backend",
        expected_relative_files=backend_expected,
    )

    voice_expected = {
        "voice/requirements.txt",
        "voice/requirements.lock",
        "deploy/config/walksafe-voice.env.example",
        "deploy/systemd/walksafe-voice.service",
    }
    voice_expected.update(_source_python_files(source, "voice"))
    _validate_runtime_archive(
        _record_path(components["voice"]["artifact"], rc, "Voice runtime archive"),
        source=source,
        root_name="walksafe-voice",
        expected_relative_files=voice_expected,
    )

    web = components["web"]
    if (
        web.get("trusted_proxy_configuration") != "deploy/nginx/walksafe-web.conf.example"
        or web.get("trusted_proxy_runtime_check") != "scripts/check_walksafe_trusted_proxy_20260716.py"
    ):
        raise ValidationError("Web component does not bind the packaged trusted proxy runtime contract")
    support_expected = {
        "deploy/config/walksafe-web.env.example",
        "deploy/nginx/walksafe-web.conf.example",
        "deploy/systemd/walksafe-web.service",
        "scripts/check_walksafe_trusted_proxy_20260716.py",
        "scripts/run_walksafe_web_single_instance_20260713.py",
    }
    support_archive = _record_path(web.get("runtime_support"), rc, "Web runtime support archive")
    _validate_runtime_archive(
        support_archive,
        source=source,
        root_name="walksafe-web-runtime-support",
        expected_relative_files=support_expected,
    )
    web_manifest_path = _record_path(web.get("build_manifest"), rc, "Web build manifest")
    web_manifest = _load_json(web_manifest_path, "Web build manifest")
    if (
        set(web_manifest) != WEB_BUILD_MANIFEST_KEYS
        or web_manifest.get("schema_version") != "walksafe.web-build-manifest.v4"
        or web_manifest.get("source_commit") != source_commit
        or web_manifest.get("build_id") != source_commit
    ):
        raise ValidationError("Web build manifest fields or source identity are invalid")
    web_archive = _record_path(web["artifact"], rc, "Web deployment archive")
    deployment_record = web_manifest.get("deployment_archive")
    deployment_record = _require_record_fields(
        deployment_record,
        WEB_NAMED_ARTIFACT_RECORD_KEYS,
        "Web deployment archive record",
    )
    if (
        type(deployment_record.get("name")) is not str
        or type(deployment_record.get("path")) is not str
        or type(deployment_record.get("bytes")) is not int
        or type(deployment_record.get("sha256")) is not str
    ):
        raise ValidationError("Web deployment archive record types are invalid")
    deployment_relative = _safe_path(
        deployment_record["path"],
        "Web deployment archive record",
    )
    assert deployment_relative is not None
    if deployment_record.get("name") != deployment_relative.name:
        raise ValidationError("Web deployment archive name is not canonical")
    expected_web_path = f"web/{deployment_record.get('path', '')}"
    if web["artifact"].get("path") != expected_web_path:
        raise ValidationError("full RC Web archive path differs from Web build manifest")
    if (
        web["artifact"].get("sha256") != deployment_record.get("sha256")
        or web["artifact"].get("bytes") != deployment_record.get("bytes")
    ):
        raise ValidationError("full RC Web archive hash differs from Web build manifest")
    _inspect_web_archive(web_archive, web_manifest)

    evidence_rows = web.get("evidence_files")
    if not isinstance(evidence_rows, list) or not evidence_rows:
        raise ValidationError("Web provenance evidence records are missing")
    evidence_paths = {_record_path(row, rc, "Web evidence file") for row in evidence_rows}
    web_inputs = web_manifest.get("inputs")
    if not isinstance(web_inputs, dict) or set(web_inputs) != {
        "package_json",
        "package_lock",
        "node_toolchain_lock",
    }:
        raise ValidationError("Web build input provenance set is incomplete")
    if any(
        not isinstance(row, dict) or set(row) != ARTIFACT_RECORD_KEYS
        for row in web_inputs.values()
    ):
        raise ValidationError(
            "Web build input fields differ from the exact record contract"
        )
    web_receipts = web_manifest.get("quality_receipts")
    if not isinstance(web_receipts, list) or len(web_receipts) != len(WEB_QUALITY_RECEIPTS):
        raise ValidationError("Web quality receipt set is incomplete")
    if any(
        not isinstance(row, dict) or set(row) != WEB_NAMED_ARTIFACT_RECORD_KEYS
        for row in web_receipts
    ):
        raise ValidationError(
            "Web quality receipt fields differ from the exact record contract"
        )
    for row in web_receipts:
        relative = _safe_path(str(row.get("path", "")), "Web quality receipt")
        assert relative is not None
        if relative.name != f"{row.get('name')}.log":
            raise ValidationError("Web quality receipt name does not match its file")
    receipt_names = {
        row.get("name") for row in web_receipts if isinstance(row, dict)
    }
    if receipt_names != WEB_QUALITY_RECEIPTS:
        raise ValidationError("Web quality receipt names are incomplete or duplicated")
    toolchain = web_manifest.get("toolchain")
    if not isinstance(toolchain, dict) or set(toolchain) != {"node", "npm"} or any(
        not isinstance(toolchain[name], str) or SEMANTIC_VERSION.fullmatch(toolchain[name]) is None
        for name in ("node", "npm")
    ):
        raise ValidationError("Web build manifest toolchain is not pinned to exact versions")
    if not _json_exact_equal(
        web_manifest.get("build_environment"), WEB_BUILD_ENVIRONMENT
    ):
        raise ValidationError("Web build manifest public build environment is invalid")
    node_receipt = next(
        row
        for row in web_receipts
        if isinstance(row, dict) and row.get("name") == "node-toolchain"
    )
    _validate_web_node_toolchain(
        lock_path=_record_path(
            web_inputs["node_toolchain_lock"], rc / "web", "Web Node toolchain lock"
        ),
        attestation_path=_record_path(
            node_receipt,
            rc / "web",
            "Web Node toolchain attestation",
            expected_fields=WEB_NAMED_ARTIFACT_RECORD_KEYS,
        ),
        toolchain=toolchain,
    )
    post_node_receipt = next(
        row
        for row in web_receipts
        if isinstance(row, dict) and row.get("name") == "node-toolchain-post"
    )
    _validate_web_node_toolchain(
        lock_path=_record_path(
            web_inputs["node_toolchain_lock"], rc / "web", "Web Node toolchain lock"
        ),
        attestation_path=_record_path(
            post_node_receipt,
            rc / "web",
            "Web post-build Node toolchain attestation",
            expected_fields=WEB_NAMED_ARTIFACT_RECORD_KEYS,
        ),
        toolchain=toolchain,
    )
    required_web_records = [web_manifest.get("deployment_archive")]
    required_web_records.extend(web_inputs.values())
    required_web_records.extend(web_receipts)
    required_web_relative = {"web/web-build-manifest.json"}
    required_web_evidence_paths = {web_manifest_path}
    for row in required_web_records:
        if not isinstance(row, dict):
            raise ValidationError("Web provenance record is malformed")
        if (
            type(row.get("path")) is not str
            or type(row.get("bytes")) is not int
            or type(row.get("sha256")) is not str
        ):
            raise ValidationError("Web provenance record types are invalid")
        relative = _safe_path(row["path"], "Web provenance record")
        assert relative is not None
        path = rc / "web" / relative.as_posix()
        if path not in evidence_paths:
            raise ValidationError("Web provenance file is not recorded in full RC manifest")
        if row.get("bytes") != path.stat().st_size or row.get("sha256") != sha256_file(path):
            raise ValidationError("Web provenance bytes differ from Web build manifest")
        required_web_evidence_paths.add(path)
        required_web_relative.add(f"web/{relative.as_posix()}")
    if (
        len(evidence_paths) != len(evidence_rows)
        or evidence_paths != required_web_evidence_paths
    ):
        raise ValidationError(
            "Web provenance evidence file set is incomplete, duplicated, or unexpected"
        )
    source_inputs = {
        "package_json": source / "apps/web/package.json",
        "package_lock": source / "apps/web/package-lock.json",
        "node_toolchain_lock": source / "configs/walksafe_node_toolchain_lock_20260715.json",
    }
    for name, source_path in source_inputs.items():
        record = web_inputs[name]
        relative = _safe_path(str(record.get("path", "")), f"Web input {name}")
        assert relative is not None
        preserved = rc / "web" / relative.as_posix()
        if source_path.is_symlink() or not source_path.is_file():
            raise ValidationError(f"Web source input is unavailable: {name}")
        if preserved.stat().st_size != source_path.stat().st_size or sha256_file(preserved) != sha256_file(source_path):
            raise ValidationError(f"Web input {name} differs from source HEAD")
    required_web_relative.add(web["runtime_support"]["path"])

    quality_paths, quality_summary = _validate_quality_bundle(
        quality=manifest.get("quality"),
        source=source,
        rc=rc,
        source_commit=source_commit,
        source_tree=source_tree,
        web_archive=web_archive,
        android_apk=android_apk,
    )

    sbom_path = _record_path(manifest.get("sbom"), rc, "SPDX SBOM")
    sbom = _load_json(sbom_path, "SPDX SBOM")
    _validate_sbom(sbom, source=source, source_commit=source_commit, components=components)
    _validate_runtime_contracts(source)

    external = manifest.get("external_runtime_inputs")
    if (
        not isinstance(external, list)
        or len(external) != len(EXPECTED_EXTERNAL_RUNTIME_REQUIREMENTS)
        or any(
            not isinstance(row, dict) or set(row) != {"id", "included", "required"}
            for row in external
        )
    ):
        raise ValidationError("external runtime blockers differ from the exact record contract")
    blocker_ids = [row["id"] for row in external]
    if (
        any(not isinstance(blocker_id, str) for blocker_id in blocker_ids)
        or len(blocker_ids) != len(set(blocker_ids))
        or set(blocker_ids) != set(EXPECTED_EXTERNAL_RUNTIME_REQUIREMENTS)
    ):
        raise ValidationError("external runtime blocker IDs differ from the exact unique set")
    external_by_id = {row["id"]: row for row in external}
    for blocker_id, requirement in EXPECTED_EXTERNAL_RUNTIME_REQUIREMENTS.items():
        if not _json_exact_equal(
            external_by_id[blocker_id],
            {
                "id": blocker_id,
                "included": False,
                "required": requirement,
            },
        ):
            raise ValidationError(
                f"external runtime blocker differs from the exact contract: {blocker_id}"
            )
    packaging = manifest.get("packaging_policy")
    if not _json_exact_equal(packaging, EXPECTED_PACKAGING_POLICY):
        raise ValidationError("full RC packaging policy differs from the exact contract")

    expected_files = {
        "walksafe-full-rc-manifest.json",
        manifest["sbom"]["path"],
        components["android"]["artifact"]["path"],
        components["backend"]["artifact"]["path"],
        components["voice"]["artifact"]["path"],
        *required_web_relative,
        *quality_paths,
    }
    validated_rc = _snapshot_rc(rc)
    actual_files = {str(record["path"]) for record in validated_rc["files"]}
    if actual_files != expected_files:
        raise ValidationError("full RC contains unrecorded or missing files")
    if set(closure_by_path) != expected_files - {"walksafe-full-rc-manifest.json"}:
        raise ValidationError("full RC file closure differs from semantic artifact records")
    expected_snapshot_files = [manifest_snapshot]
    expected_snapshot_files.extend(
        {
            "path": path,
            "bytes": closure_by_path[path]["bytes"],
            "sha256": closure_by_path[path]["sha256"],
        }
        for path in sorted(closure_by_path)
    )
    expected_snapshot_files.sort(key=lambda record: str(record["path"]))
    if validated_rc["files"] != expected_snapshot_files:
        raise ValidationError("full RC changed during validation")
    try:
        verify_exact_git_source(
            source,
            context="validator source after validation",
            expected_commit=source_commit,
            expected_tree=source_tree,
        )
    except ReleaseIntegrityError as exc:
        raise ValidationError(str(exc)) from exc
    return {
        "source_commit": source_commit,
        "source_tree": source_tree,
        "deployment_complete": False,
        "android_signing_status": "unsigned",
        "files": len(actual_files),
        "sbom_packages": len(sbom["packages"]),
        "closure_sha256": closure["sha256"],
        "blocker_ids": sorted(external_by_id),
        "quality": quality_summary,
        "validated_rc": validated_rc,
        "tools": tool_records,
    }


def validate_full_rc(
    source_root: Path,
    rc_root: Path,
    java_path: Path,
    expected_java_sha256: str,
    apksigner_jar_path: Path,
    expected_apksigner_jar_sha256: str,
) -> dict[str, Any]:
    original_rc = rc_root.expanduser().absolute()
    try:
        with DirectorySnapshot.capture(original_rc, context="full RC") as rc_snapshot:
            initial_record = rc_snapshot.record(manifest_name="walksafe-full-rc-manifest.json")
            result = _validate_full_rc_snapshot(
                source_root,
                rc_snapshot.root,
                java_path,
                expected_java_sha256,
                apksigner_jar_path,
                expected_apksigner_jar_sha256,
            )
        current_record = exact_directory_record(
            original_rc,
            context="full RC after validation",
            manifest_name="walksafe-full-rc-manifest.json",
        )
    except ReleaseIntegrityError as exc:
        raise ValidationError(str(exc)) from exc
    if not _json_exact_equal(
        result.get("validated_rc"), initial_record
    ) or not _json_exact_equal(current_record, initial_record):
        raise ValidationError("full RC changed during validation")
    result["validated_rc"] = initial_record
    return result


def write_validation_receipt(
    *,
    receipt_path: Path,
    source_root: Path,
    rc_root: Path,
    result: dict[str, Any],
) -> Path:
    source = source_root.resolve()
    rc = rc_root.resolve()
    running_validator = Path(__file__).resolve()
    validator_path = source / "scripts/validate_walksafe_full_rc_20260713.py"
    if validator_path.is_symlink() or not validator_path.is_file():
        raise ValidationError("validator script is missing from the validated source tree")
    if sha256_file(running_validator) != sha256_file(validator_path):
        raise ValidationError("running validator differs from the validated source tree")
    output = receipt_path.expanduser().absolute()
    if output.exists() or output.is_symlink():
        raise ValidationError("validation receipt output must not already exist")
    if (
        output.parent.is_symlink()
        or not output.parent.is_dir()
        or output.parent.resolve() != output.parent
    ):
        raise ValidationError("validation receipt parent must be an existing real directory")
    bundle_root = output.parent
    if bundle_root == rc or rc in bundle_root.parents:
        raise ValidationError("validation receipt bundle must be outside the RC directory")
    if bundle_root == source or source in bundle_root.parents:
        raise ValidationError("validation receipt bundle must be outside the validated source")
    if any(bundle_root.iterdir()):
        raise ValidationError("validation receipt bundle directory must be empty")
    quality = result.get("quality")
    if (
        not isinstance(quality, dict)
        or quality.get("policy_sha256") != QUALITY_POLICY_SHA256
        or not isinstance(quality.get("products"), dict)
        or set(quality["products"]) != set(QUALITY_PRODUCTS)
    ):
        raise ValidationError("validation result quality binding is incomplete")
    validated_rc = result.get("validated_rc")
    if not isinstance(validated_rc, dict) or not _json_exact_equal(
        _snapshot_rc(rc), validated_rc
    ):
        raise ValidationError("full RC changed after validation")
    manifest_snapshot = validated_rc.get("manifest")
    if (
        not isinstance(manifest_snapshot, dict)
        or manifest_snapshot.get("path") != "walksafe-full-rc-manifest.json"
    ):
        raise ValidationError("validation result manifest binding is incomplete")
    published_quality: list[tuple[Path, dict[str, Any]]] = []
    try:
        for product in QUALITY_PRODUCTS:
            record = quality["products"][product]
            relative = _safe_path(str(record.get("path", "")), f"{product} quality receipt")
            assert relative is not None
            source_receipt = rc.joinpath(*relative.parts)
            try:
                source_snapshot = FileSnapshot.capture(
                    source_receipt,
                    context=f"{product} quality receipt",
                    display_path=relative.as_posix(),
                )
            except ReleaseIntegrityError as exc:
                raise ValidationError(str(exc)) from exc
            if not _json_exact_equal(
                source_snapshot.record(path=relative.as_posix()), record
            ):
                source_snapshot.close()
                raise ValidationError(f"{product} quality receipt differs from the validation result")
            destination = bundle_root.joinpath(*relative.parts)
            if destination.exists() or destination.is_symlink():
                source_snapshot.close()
                raise ValidationError(f"{product} validation bundle quality receipt already exists")
            try:
                ensure_real_subdirectory(bundle_root, relative.parts[:-1])
            except ReleaseIntegrityError as exc:
                source_snapshot.close()
                raise ValidationError(str(exc)) from exc
            try:
                publish_snapshot(destination, source_snapshot, mode=0o644)
            except ReleaseIntegrityError as exc:
                raise ValidationError(str(exc)) from exc
            finally:
                source_snapshot.close()
            published_quality.append((destination, record))
    except Exception:
        raise
    payload = {
        "schema_version": "walksafe.full-rc-validation-receipt.v1",
        "result": "passed",
        "source": {
            "commit": result["source_commit"],
            "tree": result["source_tree"],
        },
        "validator": {
            "path": "scripts/validate_walksafe_full_rc_20260713.py",
            "sha256": sha256_file(validator_path),
        },
        "manifest": {
            "path": "walksafe-full-rc-manifest.json",
            "bytes": manifest_snapshot["bytes"],
            "sha256": manifest_snapshot["sha256"],
        },
        "closure_sha256": result["closure_sha256"],
        "blocker_ids": result["blocker_ids"],
        "quality": result["quality"],
        "tools": result["tools"],
        "deployment_complete": False,
    }
    try:
        rendered = (
            json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        ).encode("utf-8")
        for destination, expected_record in published_quality:
            relative_text = str(expected_record["path"])
            with FileSnapshot.capture(
                destination,
                context=f"validation bundle quality receipt {relative_text}",
                display_path=relative_text,
            ) as copied_snapshot:
                if not _json_exact_equal(
                    copied_snapshot.record(path=relative_text), expected_record
                ):
                    raise ValidationError(
                        f"validation bundle quality receipt changed: {relative_text}"
                    )
        if not _json_exact_equal(_snapshot_rc(rc), validated_rc):
            raise ValidationError("full RC changed during validation receipt creation")
        exclusive_atomic_publish(
            output,
            rendered,
            mode=0o644,
        )
    except ReleaseIntegrityError as exc:
        raise ValidationError(str(exc)) from exc
    return output


def main() -> int:
    print(
        "BLOCKED: the Web-inclusive full RC validator is LEGACY_REFERENCE_ONLY under FP-009.",
        file=sys.stderr,
    )
    return 78

    # Historical CLI implementation below is intentionally unreachable.
    try:
        require_isolated_python("WalkSafe full RC validator CLI")
    except ReleaseIntegrityError as exc:
        raise SystemExit(f"full RC validation failed: {exc}") from exc
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--rc-root", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--expected-java-sha256", required=True)
    parser.add_argument("--apksigner-jar", type=Path, required=True)
    parser.add_argument("--expected-apksigner-jar-sha256", required=True)
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()
    try:
        result = validate_full_rc(
            args.source_root,
            args.rc_root,
            args.java,
            args.expected_java_sha256,
            args.apksigner_jar,
            args.expected_apksigner_jar_sha256,
        )
    except ValidationError as exc:
        raise SystemExit(f"full RC validation failed: {exc}") from exc
    if args.receipt is not None:
        try:
            write_validation_receipt(
                receipt_path=args.receipt,
                source_root=args.source_root,
                rc_root=args.rc_root,
                result=result,
            )
        except ValidationError as exc:
            raise SystemExit(f"validation receipt creation failed: {exc}") from exc
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
