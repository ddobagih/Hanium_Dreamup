#!/usr/bin/env python3
"""Build a deterministic, source-bound WalkSafe full-product RC directory.

The Android artifact remains deliberately unsigned and non-deployable. This
builder never reads signing keys, secrets, or external model weights.
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
            "WalkSafe full RC builder CLI requires Python -I -S -B before any release code runs"
        )
    print(
        "BLOCKED: the Web-inclusive full RC builder is LEGACY_REFERENCE_ONLY under FP-009.",
        file=sys.stderr,
    )
    raise SystemExit(78)

import argparse
import base64
import binascii
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import tarfile
import tempfile
import types
from typing import Any, Iterable
from urllib.parse import quote, urlsplit
import xml.etree.ElementTree as ET
import zipfile


_SCRIPT_DIRECTORY = Path(__file__).resolve().parent
_INTEGRITY_SHA256 = "b084292b5a6e4befd82aea22ceac899527ab1ba1ffe51b64fa022c831b72a18e"
_DEX_BINDING_SHA256 = "cfcc330fcf8a5faf4ee8a11adc6168bd2df98d40b09e4a5471586013a7fcab7e"


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
    "_walksafe_release_integrity_for_full_rc_builder",
    _SCRIPT_DIRECTORY / "walksafe_release_integrity.py",
    _INTEGRITY_SHA256,
)
FileSnapshot = _integrity.FileSnapshot
ReleaseIntegrityError = _integrity.ReleaseIntegrityError
exclusive_directory_publish = _integrity.exclusive_directory_publish
exact_directory_record = _integrity.exact_directory_record
require_isolated_python = _integrity.require_isolated_python
strict_json_bytes = _integrity.strict_json_bytes
strict_json_snapshot = _integrity.strict_json_snapshot
verify_exact_git_source = _integrity.verify_exact_git_source

_dex_binding = _load_local_source(
    "_walksafe_android_dex_binding_for_full_rc_builder",
    _SCRIPT_DIRECTORY / "walksafe_android_dex_binding.py",
    _DEX_BINDING_SHA256,
)
DexBindingError = _dex_binding.DexBindingError
validate_walksafe_release_binding = _dex_binding.validate_walksafe_release_binding


SCHEMA_VERSION = "walksafe.full-rc-manifest.v2"
SPDX_VERSION = "SPDX-2.3"
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
PINNED_REQUIREMENT = re.compile(
    r"^([A-Za-z0-9_.-]+)(\[[A-Za-z0-9_,.-]+\])?==([^\s;]+)$"
)
ANDROID_PLUGIN = re.compile(
    r"\bid\(\"([^\"]+)\"\)\s+version\s+\"([^\"]+)\""
)
FORBIDDEN_LOOSE_MODEL_SUFFIXES = {
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
SEMANTIC_VERSION = re.compile(r"^v?\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")
QUALITY_POLICY_SHA256 = "c4a9dccedcee730f6542ed2a5b627780bb9419c13a10ce7fd011ecdc8e73a6f5"
QUALITY_PRODUCTS = ("android", "backend", "voice", "web")
QUALITY_RECEIPT_SCHEMA = "walksafe.product-quality-receipt.v3"
QUALITY_STEP_LOG_SCHEMA = "walksafe.product-quality-step-log.v1"
NODE_TOOLCHAIN_CHECKER_SHA256 = "dd88cd342addda35bc486440091e39e3b16d064c89e0f79d62fc3c03d21cdcfe"

# These files are provenance inputs, not a package allowlist. The independent
# validator reconstructs its expectations from the source tree itself.
RELEASE_SOURCE_INPUTS = (
    "apps/web/package.json",
    "apps/web/package-lock.json",
    "apps/web/quality-requirements.txt",
    "apps/web/quality-requirements.lock",
    "apps/web/.env.example",
    "apps/android/app/build.gradle.kts",
    "apps/android/build.gradle.kts",
    "apps/android/settings.gradle.kts",
    "apps/android/gradle/wrapper/gradle-wrapper.properties",
    "apps/android/gradle/wrapper/gradle-wrapper.jar",
    "apps/android/app/gradle.lockfile",
    "apps/android/gradle/verification-metadata.xml",
    "apps/android/app/src/main/assets/model-config/two_model_runtime.json",
    "apps/android/app/src/main/assets/models/coco_yolo26n_float32.tflite",
    "apps/android/app/src/main/assets/models/custom_tactile_yolo26s_float32.tflite",
    "apps/android/app/src/main/assets/models/walksafe_unified_yolo26n_768_float32.tflite",
    "backend/requirements.txt",
    "backend/requirements.lock",
    "backend/.env.example",
    "voice/requirements.txt",
    "voice/requirements.lock",
    "voice/quality-requirements.txt",
    "voice/quality-requirements.lock",
    "deploy/config/walksafe-backend.env.example",
    "deploy/config/walksafe-report-retention.env.example",
    "deploy/config/walksafe-voice.env.example",
    "deploy/config/walksafe-web.env.example",
    "deploy/nginx/walksafe-web.conf.example",
    "deploy/systemd/walksafe-backend-migrate.service",
    "deploy/systemd/walksafe-backend.service",
    "deploy/systemd/walksafe-report-retention.service",
    "deploy/systemd/walksafe-report-retention.timer",
    "deploy/systemd/walksafe-voice.service",
    "deploy/systemd/walksafe-web.service",
    "configs/walksafe_product_quality_policy_20260713.json",
    "configs/walksafe_node_toolchain_lock_20260715.json",
    "scripts/run_walksafe_web_single_instance_20260713.py",
    "scripts/run_walksafe_product_quality_20260713.py",
    "scripts/run_walksafe_isolated_python_20260713.py",
    "scripts/walksafe_release_integrity.py",
    "scripts/walksafe_backup_integrity.py",
    "scripts/walksafe_environment_identity.py",
    "scripts/walksafe_external_check_receipt.py",
    "scripts/walksafe_android_dex_binding.py",
    "scripts/build_walksafe_web_release_20260711.sh",
    "scripts/create_walksafe_web_build_manifest_20260711.py",
    "scripts/check_web_runtime_trace_scope_20260713.py",
    "scripts/check_pwa_browser_lifecycle_20260711.py",
    "scripts/check_pwa_release_update_20260717.py",
    "scripts/check_walksafe_trusted_proxy_20260716.py",
    "scripts/check_walksafe_node_toolchain_20260715.py",
    "scripts/check_report_retention_dry_run.py",
    "scripts/run_walksafe_report_retention_20260717.sh",
    "scripts/check_android_apk_model_asset_20260713.py",
    "scripts/build_walksafe_full_rc_20260713.py",
    "scripts/validate_walksafe_full_rc_20260713.py",
    "scripts/verify_walksafe_operator_attestation_20260713.py",
    "scripts/verify_walksafe_signed_android_release_20260713.py",
    "docs/release/walksafe_full_rc_20260713.md",
)


class ReleaseBuildError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _strict_json_file(path: Path, context: str) -> dict[str, Any]:
    try:
        snapshot = FileSnapshot.capture(path, context=context, display_path=path.name)
        try:
            payload = strict_json_snapshot(snapshot, context=context)
        finally:
            snapshot.close()
    except ReleaseIntegrityError as exc:
        raise ReleaseBuildError(str(exc)) from exc
    if not isinstance(payload, dict):
        raise ReleaseBuildError(f"{context} must contain a JSON object")
    return payload


def _clean_source_identity(source_root: Path) -> tuple[Path, str, str]:
    try:
        identity = verify_exact_git_source(source_root, context="full RC source")
    except ReleaseIntegrityError as exc:
        raise ReleaseBuildError(str(exc)) from exc
    return identity.root, identity.commit, identity.tree


def _safe_relative_path(raw_path: str, context: str) -> PurePosixPath:
    if not raw_path or "\\" in raw_path:
        raise ReleaseBuildError(f"{context} contains an unsafe path: {raw_path!r}")
    path = PurePosixPath(raw_path)
    if path.is_absolute() or ".." in path.parts:
        raise ReleaseBuildError(f"{context} contains an unsafe path: {raw_path!r}")
    parts = tuple(part for part in path.parts if part not in {"", "."})
    if not parts:
        raise ReleaseBuildError(f"{context} path must not be empty")
    return PurePosixPath(*parts)


def _real_file(root: Path, relative: str) -> Path:
    safe = _safe_relative_path(relative, "source input")
    candidate = root.joinpath(*safe.parts)
    current = root
    for part in safe.parts:
        current = current / part
        if current.is_symlink():
            raise ReleaseBuildError(f"source input must not contain symlinks: {relative}")
    if not candidate.is_file():
        raise ReleaseBuildError(f"required source input is missing: {relative}")
    resolved = candidate.resolve()
    if root not in resolved.parents:
        raise ReleaseBuildError(f"source input escaped source root: {relative}")
    return resolved


def _artifact_record(path: Path, artifact_root: Path) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size <= 0:
        raise ReleaseBuildError(f"artifact is missing, empty, or a symlink: {path}")
    root = artifact_root.resolve()
    resolved = path.resolve()
    if root not in resolved.parents:
        raise ReleaseBuildError(f"artifact escaped output root: {path}")
    return {
        "path": resolved.relative_to(root).as_posix(),
        "sha256": sha256_file(resolved),
        "bytes": resolved.stat().st_size,
    }


def _file_closure(root: Path) -> tuple[list[dict[str, Any]], str]:
    records = [
        _artifact_record(path, root)
        for path in sorted(root.rglob("*"))
        if path.is_file()
    ]
    canonical = "".join(
        f"{record['sha256']} {record['bytes']} {record['path']}\n" for record in records
    ).encode("utf-8")
    return records, hashlib.sha256(canonical).hexdigest()


def _validate_input_record(record: Any, root: Path, context: str) -> tuple[PurePosixPath, Path]:
    if not isinstance(record, dict):
        raise ReleaseBuildError(f"{context} must be an artifact record")
    relative = _safe_relative_path(str(record.get("path", "")), context)
    path = root.joinpath(*relative.parts)
    if path.is_symlink() or not path.is_file():
        raise ReleaseBuildError(f"{context} file is missing or a symlink: {relative}")
    resolved = path.resolve()
    if root.resolve() not in resolved.parents:
        raise ReleaseBuildError(f"{context} escaped its artifact root: {relative}")
    if record.get("bytes") != resolved.stat().st_size or record.get("sha256") != sha256_file(resolved):
        raise ReleaseBuildError(f"{context} hash or byte count does not match the file")
    return relative, resolved


def _source_file_record(root: Path, relative: str) -> dict[str, Any]:
    path = _real_file(root, relative)
    return {"path": relative, "bytes": path.stat().st_size, "sha256": sha256_file(path)}


def _tracked_source_inventory(root: Path) -> dict[str, Any]:
    try:
        return verify_exact_git_source(root, context="full RC quality source").inventory
    except ReleaseIntegrityError as exc:
        raise ReleaseBuildError(str(exc)) from exc


def _load_quality_policy(source_root: Path) -> dict[str, Any]:
    path = _real_file(source_root, "configs/walksafe_product_quality_policy_20260713.json")
    try:
        with FileSnapshot.capture(path, context="quality policy") as snapshot:
            if snapshot.sha256 != QUALITY_POLICY_SHA256:
                raise ReleaseBuildError("quality policy differs from the builder-pinned policy")
            policy = strict_json_snapshot(snapshot, context="quality policy")
    except ReleaseIntegrityError as exc:
        raise ReleaseBuildError(str(exc)) from exc
    if (
        not isinstance(policy, dict)
        or policy.get("schema_version") != "walksafe.product-quality-policy.v2"
        or policy.get("receipt_schema_version") != QUALITY_RECEIPT_SCHEMA
        or not isinstance(policy.get("products"), dict)
        or set(policy["products"]) != set(QUALITY_PRODUCTS)
        or not isinstance(policy.get("ambient_environment_allowlist"), list)
    ):
        raise ReleaseBuildError("quality policy schema or product set is invalid")
    return policy


def _quality_environment_record(
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
        raise ReleaseBuildError("quality step environment record is malformed")
    allowlisted = recorded["allowlisted_ambient_presence"]
    if (
        not isinstance(allowlisted, dict)
        or not set(allowlisted).issubset(ambient_allowlist)
        or allowlisted.get("PATH") != {"present": True}
        or any(value != {"present": True} for value in allowlisted.values())
    ):
        raise ReleaseBuildError("quality step ambient environment record is invalid")
    expected_required = {
        name: {"present": True} for name in step.get("ambient_environment", [])
    }
    if recorded["required_ambient_presence"] != expected_required:
        raise ReleaseBuildError("quality step required environment record is invalid")
    expected_derived = {
        destination: {"source": source, "present": True}
        for destination, source in step.get("derived_environment", {}).items()
    }
    if recorded["derived_secret_presence"] != expected_derived:
        raise ReleaseBuildError("quality step derived environment record is invalid")
    expected_fixed: dict[str, str] = {}
    for name, value in step.get("fixed_environment", {}).items():
        expected_fixed[name] = {
            "{repo_root}": "{repo_root}",
            "{source_commit}": source_commit,
            "{android_gateway_origin}": android_gateway_origin,
        }.get(value, value)
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
    if recorded["fixed_public"] != expected_fixed:
        raise ReleaseBuildError("quality step fixed environment record is invalid")


def _validate_tested_environment(
    *,
    product: str,
    definition: dict[str, Any],
    recorded: Any,
    source_root: Path,
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
            raise ReleaseBuildError(f"{product} quality receipt has an unexpected tested environment")
        return
    if not isinstance(recorded, dict) or set(recorded) != {
        "python", "locks", "locked_distributions", "installed_distributions", "site_packages"
    }:
        raise ReleaseBuildError(f"{product} tested environment record is malformed")
    trusted_tools = definition.get("trusted_tool_distributions")
    if not isinstance(trusted_tools, dict) or any(
        not isinstance(name, str)
        or re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name) is None
        or not isinstance(version, str)
        or not version
        or any(character in version for character in "\0\r\n")
        for name, version in trusted_tools.items()
    ):
        raise ReleaseBuildError(f"{product} trusted tool policy is malformed")
    expected_locks: list[dict[str, Any]] = []
    locked_by_name: dict[str, dict[str, Any]] = {}
    for lock_relative in lock_relatives:
        lock = _real_file(source_root, lock_relative)
        expected_locks.append(
            {
                "path": f"repository:{lock_relative}",
                "bytes": lock.stat().st_size,
                "sha256": sha256_file(lock),
            }
        )
        requirements_relative = lock_relative.removesuffix(".lock") + ".txt"
        for item in _parse_python_lock(
            lock,
            lock_relative,
            _parse_python_requirements(
                _real_file(source_root, requirements_relative),
                requirements_relative,
            ),
        ):
            previous = locked_by_name.get(item["name"])
            if previous is not None and previous["version"] != item["version"]:
                raise ReleaseBuildError(f"{product} tested environment locks disagree")
            locked_by_name[item["name"]] = item
    if recorded.get("locks") != expected_locks:
        raise ReleaseBuildError(f"{product} tested environment lock differs from source")
    canonical = "".join(
        f"{name}=={locked_by_name[name]['version']}\n" for name in sorted(locked_by_name)
    ).encode("utf-8")
    if recorded.get("locked_distributions") != {
        "count": len(locked_by_name),
        "sha256": hashlib.sha256(canonical).hexdigest(),
    }:
        raise ReleaseBuildError(f"{product} tested distribution identity differs from its lock")
    collisions = sorted(set(locked_by_name) & set(trusted_tools))
    if collisions:
        raise ReleaseBuildError(f"{product} trusted tool policy collides with its fixed locks")
    installed_by_name = {
        **{name: item["version"] for name, item in locked_by_name.items()},
        **trusted_tools,
    }
    installed_canonical = "".join(
        f"{name}=={installed_by_name[name]}\n" for name in sorted(installed_by_name)
    ).encode("utf-8")
    if recorded.get("installed_distributions") != {
        "count": len(installed_by_name),
        "sha256": hashlib.sha256(installed_canonical).hexdigest(),
    }:
        raise ReleaseBuildError(
            f"{product} installed distribution identity differs from its exact policy set"
        )
    site_packages = recorded.get("site_packages")
    if not isinstance(site_packages, dict) or set(site_packages) != {
        "format", "roots", "closure"
    }:
        raise ReleaseBuildError(f"{product} tested site-packages record is malformed")
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
        or re.fullmatch(r"[0-9a-f]{64}", str(closure.get("sha256", ""))) is None
    ):
        raise ReleaseBuildError(f"{product} tested site-packages record is malformed")
    if {
        "format": site_packages["format"],
        "closure": site_packages["closure"],
    } != definition.get("tested_site_packages"):
        raise ReleaseBuildError(
            f"{product} tested site-packages differs from the source-pinned policy"
        )
    python = recorded.get("python")
    if not isinstance(python, dict) or set(python) != {"path", "bytes", "sha256"}:
        raise ReleaseBuildError(f"{product} tested Python record is malformed")
    python_path = Path(str(python.get("path", "")))
    if (
        not python_path.is_absolute()
        or python_path.is_symlink()
        or not python_path.is_file()
        or python.get("bytes") != python_path.stat().st_size
        or python.get("sha256") != sha256_file(python_path)
    ):
        raise ReleaseBuildError(f"{product} tested Python executable evidence is stale")
    python_resolved = str(python_path.resolve())
    for step, record in zip(definition["steps"], step_records, strict=True):
        if step["argv"][0] == "{python}" and record["executable"]["resolved_path"] != python_resolved:
            raise ReleaseBuildError(f"{product} quality step used a different Python environment")


def _validate_quality_node_toolchain(
    *,
    product: str,
    recorded: Any,
    source_root: Path,
) -> None:
    if product != "web":
        if recorded is not None:
            raise ReleaseBuildError(f"{product} quality receipt has unexpected Node evidence")
        return
    if not isinstance(recorded, dict) or set(recorded) != {
        "checker", "lock", "before", "after"
    }:
        raise ReleaseBuildError("Web quality Node toolchain evidence is malformed")
    checker = _source_file_record(
        source_root, "scripts/check_walksafe_node_toolchain_20260715.py"
    )
    lock_record = _source_file_record(
        source_root, "configs/walksafe_node_toolchain_lock_20260715.json"
    )
    if checker["sha256"] != NODE_TOOLCHAIN_CHECKER_SHA256:
        raise ReleaseBuildError("Web quality Node checker differs from its approved pin")
    if recorded.get("checker") != checker or recorded.get("lock") != lock_record:
        raise ReleaseBuildError("Web quality Node provenance differs from source")
    lock_path = _real_file(
        source_root, "configs/walksafe_node_toolchain_lock_20260715.json"
    )
    lock = _strict_json_file(lock_path, "Web quality Node toolchain lock")
    if (
        set(lock) != {"schema_version", "official_archive", "platform", "root", "node", "npm"}
        or lock.get("schema_version") != "walksafe.node-toolchain.v2"
    ):
        raise ReleaseBuildError("Web quality Node toolchain lock is invalid")
    expected = {
        "schema_version": "walksafe.node-toolchain-attestation.v2",
        "lock_sha256": lock_record["sha256"],
        "official_archive": lock["official_archive"],
        "platform": lock["platform"],
        "root": lock["root"],
        "node": lock["node"],
        "npm": lock["npm"],
    }
    if recorded.get("before") != expected or recorded.get("after") != expected:
        raise ReleaseBuildError("Web quality Node attestation differs from the source lock")


def _validate_quality_receipt(
    *,
    receipt_path: Path,
    product: str,
    source_root: Path,
    source_commit: str,
    source_tree: str,
    policy: dict[str, Any],
    expected_outputs: dict[str, Path],
    web_archive_name: str | None = None,
) -> list[Path]:
    expected_name = f"walksafe-{product}-quality-receipt.json"
    if receipt_path.name != expected_name or receipt_path.is_symlink() or not receipt_path.is_file():
        raise ReleaseBuildError(f"{product} quality receipt must be named {expected_name}")
    receipt = _strict_json_file(receipt_path, f"{product} quality receipt")
    if not isinstance(receipt, dict) or set(receipt) != {
        "schema_version",
        "product",
        "exit_code",
        "result",
        "source",
        "provenance",
        "inputs",
        "tested_environment",
        "node_toolchain",
        "public_arguments",
        "steps",
        "outputs",
    }:
        raise ReleaseBuildError(f"{product} quality receipt fields are invalid")
    if (
        receipt.get("schema_version") != QUALITY_RECEIPT_SCHEMA
        or receipt.get("product") != product
        or receipt.get("exit_code") != 0
        or receipt.get("result") != "passed"
        or receipt.get("source")
        != {
            "commit": source_commit,
            "git_tree": source_tree,
            "tree_clean_before": True,
            "tree_clean_after": True,
        }
    ):
        raise ReleaseBuildError(f"{product} quality receipt does not bind the clean source HEAD")
    expected_provenance = {
        "runner": _source_file_record(source_root, "scripts/run_walksafe_product_quality_20260713.py"),
        "bootstrap": _source_file_record(
            source_root, "scripts/run_walksafe_isolated_python_20260713.py"
        ),
        "policy": _source_file_record(source_root, "configs/walksafe_product_quality_policy_20260713.json"),
    }
    if receipt.get("provenance") != expected_provenance:
        raise ReleaseBuildError(f"{product} quality receipt provenance differs from source")
    inventory = _tracked_source_inventory(source_root)
    if receipt.get("inputs") != {
        "tracked_source_before": inventory,
        "tracked_source_after": inventory,
    }:
        raise ReleaseBuildError(f"{product} quality receipt source inventory differs from HEAD")

    public_arguments = receipt.get("public_arguments")
    android_origin: str | None = None
    if product == "web":
        if public_arguments != {"web_release_archive": web_archive_name}:
            raise ReleaseBuildError("Web quality receipt does not bind the supplied archive name")
    elif product == "android":
        if not isinstance(public_arguments, dict) or set(public_arguments) != {"android_gateway_origin"}:
            raise ReleaseBuildError("Android quality receipt gateway origin is missing")
        android_origin = public_arguments["android_gateway_origin"]
        if not isinstance(android_origin, str):
            raise ReleaseBuildError("Android quality receipt gateway origin is invalid")
        parsed = urlsplit(android_origin)
        try:
            port = parsed.port
        except ValueError as exc:
            raise ReleaseBuildError("Android quality receipt gateway origin is invalid") from exc
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
            raise ReleaseBuildError("Android quality receipt gateway origin is not canonical HTTPS")
    elif public_arguments != {}:
        raise ReleaseBuildError(f"{product} quality receipt has unexpected public arguments")

    definition = policy["products"][product]
    expected_steps = definition.get("steps")
    steps = receipt.get("steps")
    if not isinstance(expected_steps, list) or not isinstance(steps, list) or len(steps) != len(expected_steps):
        raise ReleaseBuildError(f"{product} quality receipt step set is incomplete")
    ambient_allowlist = set(policy["ambient_environment_allowlist"])
    evidence = [receipt_path.resolve()]
    log_root = receipt_path.parent / f"{receipt_path.stem}.logs"
    if log_root.is_symlink() or not log_root.is_dir():
        raise ReleaseBuildError(f"{product} quality log directory is missing")
    for index, (recorded, expected) in enumerate(zip(steps, expected_steps, strict=True), start=1):
        if not isinstance(recorded, dict) or set(recorded) != {
            "id", "argv", "cwd", "environment", "executable", "exit_code", "result", "log"
        }:
            raise ReleaseBuildError(f"{product} quality step record is malformed")
        step_id = expected.get("id")
        expected_argv = expected.get("argv")
        argv = recorded.get("argv")
        executable = recorded.get("executable")
        if (
            recorded.get("id") != step_id
            or recorded.get("cwd") != expected.get("cwd")
            or recorded.get("exit_code") != 0
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
            or not isinstance(executable.get("bytes"), int)
            or executable["bytes"] <= 0
            or re.fullmatch(r"[0-9a-f]{64}", str(executable.get("sha256", ""))) is None
        ):
            raise ReleaseBuildError(f"{product} quality step differs from the fixed policy: {step_id}")
        executable_path = Path(executable["resolved_path"])
        if (
            not executable_path.is_absolute()
            or executable_path.is_symlink()
            or not executable_path.is_file()
            or executable_path.stat().st_size != executable["bytes"]
            or sha256_file(executable_path) != executable["sha256"]
        ):
            raise ReleaseBuildError(f"{product} quality executable evidence is stale: {step_id}")
        _quality_environment_record(
            product=product,
            step=expected,
            recorded=recorded["environment"],
            source_commit=source_commit,
            android_gateway_origin=android_origin,
            ambient_allowlist=ambient_allowlist,
        )
        log_name = f"{index:02d}-{step_id}.json"
        log_record = recorded.get("log")
        expected_relative = f"{log_root.name}/{log_name}"
        if not isinstance(log_record, dict) or set(log_record) != {"path", "bytes", "sha256"}:
            raise ReleaseBuildError(f"{product} quality log record is malformed")
        if log_record.get("path") != expected_relative:
            raise ReleaseBuildError(f"{product} quality log path is not canonical")
        _relative, log_path = _validate_input_record(
            log_record,
            receipt_path.parent,
            f"{product} quality log {step_id}",
        )
        log_payload = _strict_json_file(log_path, f"{product} quality log")
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
        if log_payload != expected_log:
            raise ReleaseBuildError(f"{product} quality log differs from its receipt")
        evidence.append(log_path)
    actual_log_files = {path.resolve() for path in log_root.rglob("*") if path.is_file()}
    if actual_log_files != set(evidence[1:]) or any(path.is_symlink() for path in log_root.rglob("*")):
        raise ReleaseBuildError(f"{product} quality log file set is incomplete or unexpected")

    _validate_tested_environment(
        product=product,
        definition=definition,
        recorded=receipt.get("tested_environment"),
        source_root=source_root,
        step_records=steps,
    )
    _validate_quality_node_toolchain(
        product=product,
        recorded=receipt.get("node_toolchain"),
        source_root=source_root,
    )

    output_definitions = definition.get("outputs")
    outputs = receipt.get("outputs")
    if not isinstance(output_definitions, list) or not isinstance(outputs, list) or len(outputs) != len(output_definitions):
        raise ReleaseBuildError(f"{product} quality output set is incomplete")
    if {item.get("name") for item in output_definitions} != set(expected_outputs):
        raise ReleaseBuildError(f"{product} quality expected output binding is incomplete")
    for recorded, expected in zip(outputs, output_definitions, strict=True):
        name = expected["name"]
        path = expected_outputs[name].resolve()
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
        if recorded != expected_record:
            raise ReleaseBuildError(f"{product} quality output differs from the supplied artifact: {name}")
    return evidence


def _copy_quality_evidence(
    *,
    receipt_paths: dict[str, Path],
    source_root: Path,
    source_commit: str,
    source_tree: str,
    web_archive_path: Path,
    unsigned_apk_path: Path,
    destination_root: Path,
) -> dict[str, Any]:
    if set(receipt_paths) != set(QUALITY_PRODUCTS):
        raise ReleaseBuildError("quality receipts must contain exactly android, backend, voice, and web")
    policy = _load_quality_policy(source_root)
    expected_outputs = {
        "web": {"web_release_archive": web_archive_path},
        "android": {"unsigned_apk": unsigned_apk_path},
        "backend": {
            "openapi_contract": _real_file(source_root, "contracts/walksafe.openapi.json"),
            "walking_route_fixture": _real_file(source_root, "contracts/fixtures/walking-route-v1.json"),
        },
        "voice": {},
    }
    products: dict[str, Any] = {}
    for product in QUALITY_PRODUCTS:
        receipt = receipt_paths[product].expanduser().absolute()
        evidence = _validate_quality_receipt(
            receipt_path=receipt,
            product=product,
            source_root=source_root,
            source_commit=source_commit,
            source_tree=source_tree,
            policy=policy,
            expected_outputs=expected_outputs[product],
            web_archive_name=web_archive_path.name if product == "web" else None,
        )
        product_root = destination_root / product
        product_root.mkdir(parents=True, exist_ok=False)
        copied: list[Path] = []
        for source_path in evidence:
            relative = source_path.relative_to(receipt.parent)
            destination = product_root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source_path, destination)
            os.chmod(destination, 0o644)
            copied.append(destination)
        receipt_copy = product_root / receipt.name
        products[product] = {
            "receipt": _artifact_record(receipt_copy, destination_root.parent),
            "evidence_files": [
                _artifact_record(path, destination_root.parent) for path in sorted(copied)
            ],
        }
    return {
        "policy": _source_file_record(
            source_root, "configs/walksafe_product_quality_policy_20260713.json"
        ),
        "products": products,
    }


def _validate_web_node_toolchain(
    *,
    lock_path: Path,
    attestation_path: Path,
    toolchain: dict[str, Any],
) -> None:
    lock = _strict_json_file(lock_path, "Web Node toolchain lock")
    if (
        set(lock) != {"schema_version", "official_archive", "platform", "root", "node", "npm"}
        or lock.get("schema_version") != "walksafe.node-toolchain.v2"
    ):
        raise ReleaseBuildError("Web Node toolchain lock schema is invalid")
    node = lock.get("node")
    npm = lock.get("npm")
    if (
        not isinstance(node, dict)
        or not isinstance(npm, dict)
        or node.get("version") != toolchain["node"]
        or npm.get("version") != toolchain["npm"]
    ):
        raise ReleaseBuildError("Web Node toolchain versions differ from the source lock")
    expected = {
        "schema_version": "walksafe.node-toolchain-attestation.v2",
        "lock_sha256": sha256_file(lock_path),
        "official_archive": lock["official_archive"],
        "platform": lock["platform"],
        "root": lock["root"],
        "node": node,
        "npm": npm,
    }
    if _strict_json_file(attestation_path, "Web Node toolchain attestation") != expected:
        raise ReleaseBuildError("Web Node toolchain attestation differs from the source lock")


def _copy_web_evidence(
    *,
    manifest_path: Path,
    artifact_root: Path,
    archive_path: Path,
    destination_root: Path,
    source_root: Path,
    source_commit: str,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    if manifest_path.is_symlink() or not manifest_path.is_file():
        raise ReleaseBuildError("Web build manifest is missing or a symlink")
    web_root = artifact_root.resolve()
    if artifact_root.is_symlink() or not web_root.is_dir():
        raise ReleaseBuildError("Web artifact root must be a real directory")
    manifest = _strict_json_file(manifest_path, "Web build manifest")
    if manifest.get("schema_version") != "walksafe.web-build-manifest.v4":
        raise ReleaseBuildError("Web build manifest schema is not v4")
    if manifest.get("source_commit") != source_commit or manifest.get("build_id") != source_commit:
        raise ReleaseBuildError("Web build manifest is not bound to the source HEAD")

    inputs = manifest.get("inputs")
    if not isinstance(inputs, dict) or set(inputs) != {
        "package_json",
        "package_lock",
        "node_toolchain_lock",
    }:
        raise ReleaseBuildError("Web build input provenance set is incomplete")
    records: list[tuple[PurePosixPath, Path]] = []
    preserved_inputs: dict[str, Path] = {}
    source_inputs = {
        "package_json": _real_file(source_root, "apps/web/package.json"),
        "package_lock": _real_file(source_root, "apps/web/package-lock.json"),
        "node_toolchain_lock": _real_file(
            source_root, "configs/walksafe_node_toolchain_lock_20260715.json"
        ),
    }
    for name, record in sorted(inputs.items()):
        relative, preserved = _validate_input_record(record, web_root, f"Web input {name}")
        source = source_inputs[name]
        if preserved.stat().st_size != source.stat().st_size or sha256_file(preserved) != sha256_file(source):
            raise ReleaseBuildError(f"Web input {name} differs from the source HEAD")
        preserved_inputs[name] = preserved
        records.append((relative, preserved))
    toolchain = manifest.get("toolchain")
    if not isinstance(toolchain, dict) or set(toolchain) != {"node", "npm"} or any(
        not isinstance(toolchain[name], str) or SEMANTIC_VERSION.fullmatch(toolchain[name]) is None
        for name in ("node", "npm")
    ):
        raise ReleaseBuildError("Web build manifest toolchain is not pinned to exact versions")
    if manifest.get("build_environment") != WEB_BUILD_ENVIRONMENT:
        raise ReleaseBuildError("Web build manifest public build environment is invalid")
    receipts = manifest.get("quality_receipts")
    if not isinstance(receipts, list) or not receipts:
        raise ReleaseBuildError("Web build manifest has no quality receipts")
    receipt_names = {
        record.get("name") for record in receipts if isinstance(record, dict)
    }
    if receipt_names != WEB_QUALITY_RECEIPTS or len(receipts) != len(receipt_names):
        raise ReleaseBuildError("Web build manifest quality receipt set is incomplete or duplicated")
    receipt_paths: dict[str, Path] = {}
    for index, record in enumerate(receipts):
        relative, preserved = _validate_input_record(
            record, web_root, f"Web quality receipt {index}"
        )
        records.append((relative, preserved))
        receipt_paths[str(record["name"])] = preserved
    _validate_web_node_toolchain(
        lock_path=preserved_inputs["node_toolchain_lock"],
        attestation_path=receipt_paths["node-toolchain"],
        toolchain=toolchain,
    )
    _validate_web_node_toolchain(
        lock_path=preserved_inputs["node_toolchain_lock"],
        attestation_path=receipt_paths["node-toolchain-post"],
        toolchain=toolchain,
    )
    archive_relative, recorded_archive = _validate_input_record(
        manifest.get("deployment_archive"), web_root, "Web deployment archive"
    )
    if archive_path.is_symlink() or archive_path.resolve() != recorded_archive:
        raise ReleaseBuildError("explicit Web archive does not match the Web build manifest")
    records.append((archive_relative, recorded_archive))

    destination_root.mkdir(parents=True, exist_ok=False)
    copied_paths: set[PurePosixPath] = set()
    for relative, source in sorted(records, key=lambda item: item[0].as_posix()):
        if relative in copied_paths:
            continue
        copied_paths.add(relative)
        destination = destination_root.joinpath(*relative.parts)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        os.chmod(destination, 0o644)
    copied_manifest = destination_root / "web-build-manifest.json"
    shutil.copyfile(manifest_path, copied_manifest)
    os.chmod(copied_manifest, 0o644)

    evidence_records = [_artifact_record(copied_manifest, destination_root.parent)]
    evidence_records.extend(
        _artifact_record(destination_root.joinpath(*relative.parts), destination_root.parent)
        for relative in sorted(copied_paths, key=lambda item: item.as_posix())
    )
    return (
        {
            "artifact": _artifact_record(
                destination_root.joinpath(*archive_relative.parts), destination_root.parent
            ),
            "build_manifest": _artifact_record(copied_manifest, destination_root.parent),
            "evidence_files": evidence_records,
        },
        evidence_records,
    )


def _collect_python_tree(root: Path, relative_root: str) -> list[str]:
    directory = _real_file(root, f"{relative_root}/__init__.py").parent if (
        root / relative_root / "__init__.py"
    ).is_file() else root / relative_root
    if directory.is_symlink() or not directory.is_dir():
        raise ReleaseBuildError(f"runtime source directory is missing: {relative_root}")
    relative_files: list[str] = []
    for item in sorted(directory.rglob("*")):
        if item.is_symlink():
            raise ReleaseBuildError(f"runtime source contains a symlink: {item}")
        if item.is_file() and item.suffix == ".py":
            relative_files.append(item.relative_to(root).as_posix())
    if not relative_files:
        raise ReleaseBuildError(f"runtime source has no Python files: {relative_root}")
    return relative_files


def _backend_runtime_files(root: Path) -> list[str]:
    files = [
        "backend/__init__.py",
        "backend/alembic.ini",
        "backend/requirements.txt",
        "backend/requirements.lock",
        "backend/.env.example",
        "model/two_model_runtime.py",
        "configs/walksafe_unified_epoch270_field_20260711.json",
        "deploy/config/walksafe-backend.env.example",
        "deploy/config/walksafe-report-retention.env.example",
        "deploy/systemd/walksafe-backend.service",
        "deploy/systemd/walksafe-backend-migrate.service",
        "deploy/systemd/walksafe-report-retention.service",
        "deploy/systemd/walksafe-report-retention.timer",
        "scripts/check_report_retention_dry_run.py",
        "scripts/run_walksafe_report_retention_20260717.sh",
        "scripts/walksafe_backup_integrity.py",
        "scripts/walksafe_environment_identity.py",
        "scripts/walksafe_release_integrity.py",
    ]
    files.extend(_collect_python_tree(root, "backend/app"))
    files.extend(_collect_python_tree(root, "backend/alembic"))
    return sorted(set(files))


def _voice_runtime_files(root: Path) -> list[str]:
    files = [
        "voice/requirements.txt",
        "voice/requirements.lock",
        "deploy/config/walksafe-voice.env.example",
        "deploy/systemd/walksafe-voice.service",
    ]
    files.extend(_collect_python_tree(root, "voice"))
    return sorted(set(files))


def _web_runtime_support_files(_root: Path) -> list[str]:
    return [
        "deploy/config/walksafe-web.env.example",
        "deploy/nginx/walksafe-web.conf.example",
        "deploy/systemd/walksafe-web.service",
        "scripts/check_walksafe_trusted_proxy_20260716.py",
        "scripts/run_walksafe_web_single_instance_20260713.py",
    ]


def create_deterministic_tar_gz(
    output_path: Path,
    *,
    source_root: Path,
    archive_root_name: str,
    relative_files: Iterable[str],
) -> None:
    selected: list[tuple[str, Path]] = []
    for relative in sorted(set(relative_files)):
        source = _real_file(source_root, relative)
        if source.suffix.lower() in FORBIDDEN_LOOSE_MODEL_SUFFIXES:
            raise ReleaseBuildError(f"loose model weight is forbidden from runtime archives: {relative}")
        selected.append((f"{archive_root_name}/{relative}", source))
    if not selected:
        raise ReleaseBuildError("runtime archive must contain files")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with output_path.open("xb") as raw_output:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw_output, mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
                    for archive_name, source in selected:
                        content = source.read_bytes()
                        info = tarfile.TarInfo(archive_name)
                        info.size = len(content)
                        info.mode = 0o644
                        info.mtime = 0
                        info.uid = info.gid = 0
                        info.uname = info.gname = ""
                        archive.addfile(info, fileobj=io.BytesIO(content))
            raw_output.flush()
            os.fsync(raw_output.fileno())
        os.chmod(output_path, 0o644)
    except FileExistsError as exc:
        raise ReleaseBuildError("runtime archive output already exists") from exc


def _validate_android_model_assets(
    archive: zipfile.ZipFile,
    names: set[str],
    source_root: Path,
) -> None:
    config_relative = "apps/android/app/src/main/assets/model-config/two_model_runtime.json"
    config_path = _real_file(source_root, config_relative)
    config_bytes = config_path.read_bytes()
    try:
        config = strict_json_bytes(config_bytes, context="Android source model config")
    except ReleaseIntegrityError as exc:
        raise ReleaseBuildError(str(exc)) from exc
    models = config.get("models")
    if not isinstance(models, dict) or not models:
        raise ReleaseBuildError("Android source model config has no models")
    expected: dict[str, tuple[Path, str]] = {}
    for model_name, model in models.items():
        if not isinstance(model, dict):
            raise ReleaseBuildError(f"Android model config entry is invalid: {model_name}")
        raw_asset = model.get("asset")
        expected_hash = model.get("artifact_sha256")
        if not isinstance(raw_asset, str):
            raise ReleaseBuildError(f"Android model asset is missing: {model_name}")
        asset = _safe_relative_path(raw_asset, f"Android model asset {model_name}")
        if asset.parent != PurePosixPath("models"):
            raise ReleaseBuildError(f"Android model asset is outside models/: {raw_asset}")
        if not isinstance(expected_hash, str) or re.fullmatch(r"[0-9a-f]{64}", expected_hash) is None:
            raise ReleaseBuildError(f"Android model hash is invalid: {model_name}")
        source_asset = _real_file(
            source_root,
            f"apps/android/app/src/main/assets/{asset.as_posix()}",
        )
        if sha256_file(source_asset) != expected_hash:
            raise ReleaseBuildError(f"Android source model hash differs from config: {model_name}")
        expected[f"assets/{asset.as_posix()}"] = (source_asset, expected_hash)

    config_entry = "assets/model-config/two_model_runtime.json"
    if config_entry not in names or archive.read(config_entry) != config_bytes:
        raise ReleaseBuildError("Android APK model config differs from source HEAD")
    packaged_models = {
        name for name in names if name.startswith("assets/models/") and not name.endswith("/")
    }
    if packaged_models != set(expected):
        raise ReleaseBuildError("Android APK model asset set differs from source config")
    for name, (source_asset, expected_hash) in expected.items():
        payload = archive.read(name)
        if hashlib.sha256(payload).hexdigest() != expected_hash or len(payload) != source_asset.stat().st_size:
            raise ReleaseBuildError(f"Android APK model asset differs from source HEAD: {name}")


def _validate_unsigned_apk_payload(path: Path, source_commit: str, source_root: Path) -> None:
    if path.name != "app-release-unsigned.apk":
        raise ReleaseBuildError("Android input must be Gradle's app-release-unsigned.apk")
    if path.is_symlink() or not path.is_file() or path.stat().st_size <= 0:
        raise ReleaseBuildError("unsigned Android APK is missing, empty, or a symlink")
    try:
        with zipfile.ZipFile(path) as archive:
            names: set[str] = set()
            for info in archive.infolist():
                relative = _safe_relative_path(info.filename, "Android APK")
                normalized = relative.as_posix()
                if normalized in names:
                    raise ReleaseBuildError(f"Android APK contains a duplicate path: {normalized}")
                names.add(normalized)
                mode = (info.external_attr >> 16) & 0o170000
                if mode == 0o120000:
                    raise ReleaseBuildError(f"Android APK contains a symlink: {normalized}")
                if info.is_dir():
                    continue
            dex_names = {
                name for name in names if name.startswith("classes") and name.endswith(".dex")
            }
            try:
                validate_walksafe_release_binding(
                    {name: archive.read(name) for name in dex_names},
                    source_commit,
                )
            except DexBindingError as exc:
                raise ReleaseBuildError("Android APK BuildConfig release binding is invalid") from exc
            _validate_android_model_assets(archive, names, source_root)
    except (OSError, zipfile.BadZipFile) as exc:
        raise ReleaseBuildError("Android input is not a readable APK/ZIP") from exc


def _parse_python_requirements(path: Path, source_relative: str) -> list[dict[str, str]]:
    dependencies: list[dict[str, str]] = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        matched = PINNED_REQUIREMENT.fullmatch(line)
        if matched is None:
            raise ReleaseBuildError(f"runtime requirement must be directly pinned with ==: {source_relative}:{line_number}")
        name = re.sub(r"[-_.]+", "-", matched.group(1)).lower()
        dependencies.append(
            {
                "name": name,
                "version": matched.group(3),
                "source": f"{source_relative}#line={line_number}",
                "purl": f"pkg:pypi/{quote(name)}@{quote(matched.group(3))}",
            }
        )
    if not dependencies:
        raise ReleaseBuildError(f"runtime requirements are empty: {source_relative}")
    return dependencies


def _parse_python_lock(
    lock_path: Path,
    lock_relative: str,
    direct_requirements: list[dict[str, str]],
) -> list[dict[str, Any]]:
    logical_lines: list[str] = []
    pending = ""
    for raw_line in lock_path.read_text(encoding="utf-8").splitlines():
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped.startswith("--") and not pending:
            raise ReleaseBuildError(f"Python lock contains a global installer option: {lock_relative}")
        if stripped.endswith("\\"):
            pending += stripped[:-1].strip() + " "
            continue
        logical_lines.append((pending + stripped).strip())
        pending = ""
    if pending:
        raise ReleaseBuildError(f"Python lock has an incomplete continuation: {lock_relative}")

    locked: dict[str, dict[str, Any]] = {}
    pattern = re.compile(
        r"^([A-Za-z0-9_.-]+)(?:\[[A-Za-z0-9_,.-]+\])?==([^\s;]+)"
        r"((?:\s+--hash=sha256:[0-9a-f]{64})+)$"
    )
    for line_number, line in enumerate(logical_lines, start=1):
        match = pattern.fullmatch(line)
        if match is None:
            raise ReleaseBuildError(
                f"Python lock entry is not exactly pinned with SHA-256 hashes: {lock_relative}:{line_number}"
            )
        name = re.sub(r"[-_.]+", "-", match.group(1)).lower()
        if name in locked:
            raise ReleaseBuildError(f"Python lock contains a duplicate package: {lock_relative}:{name}")
        hashes = sorted(set(re.findall(r"--hash=sha256:([0-9a-f]{64})", match.group(3))))
        locked[name] = {
            "name": name,
            "version": match.group(2),
            "source": f"{lock_relative}#package/{name}",
            "purl": f"pkg:pypi/{quote(name)}@{quote(match.group(2))}",
            "hashes": hashes,
        }
    if not locked:
        raise ReleaseBuildError(f"Python lock contains no packages: {lock_relative}")
    for direct in direct_requirements:
        locked_direct = locked.get(direct["name"])
        if locked_direct is None or locked_direct["version"] != direct["version"]:
            raise ReleaseBuildError(
                f"Python lock does not preserve direct requirement {direct['name']}=={direct['version']}"
            )
    return [locked[name] for name in sorted(locked)]


def _parse_web_lock(path: Path) -> list[dict[str, Any]]:
    payload = _strict_json_file(path, "Web package-lock.json")
    if payload.get("lockfileVersion") != 3 or not isinstance(payload.get("packages"), dict):
        raise ReleaseBuildError("Web package-lock.json must use lockfileVersion 3")
    dependencies: list[dict[str, Any]] = []
    for package_path, package in sorted(payload["packages"].items()):
        if not package_path:
            continue
        if not isinstance(package, dict) or not isinstance(package.get("version"), str):
            raise ReleaseBuildError(f"Web lock package has no exact version: {package_path}")
        integrity = package.get("integrity")
        if not isinstance(integrity, str) or re.fullmatch(r"sha512-[A-Za-z0-9+/]+={0,2}", integrity) is None:
            raise ReleaseBuildError(f"Web lock package has no exact SHA-512 integrity: {package_path}")
        try:
            integrity_bytes = base64.b64decode(integrity.removeprefix("sha512-"), validate=True)
        except (ValueError, binascii.Error) as exc:
            raise ReleaseBuildError(f"Web lock package SHA-512 integrity is invalid: {package_path}") from exc
        if len(integrity_bytes) != 64:
            raise ReleaseBuildError(f"Web lock package SHA-512 integrity is invalid: {package_path}")
        name = package_path.rsplit("node_modules/", 1)[-1]
        dependencies.append(
            {
                "name": name,
                "version": package["version"],
                "source": f"apps/web/package-lock.json#packages/{package_path}",
                "purl": f"pkg:npm/{quote(name, safe='/')}@{quote(package['version'])}",
                "hashes": [integrity_bytes.hex()],
                "checksum_algorithm": "SHA512",
            }
        )
    if not dependencies:
        raise ReleaseBuildError("Web package-lock.json contains no installed packages")
    return dependencies


def _gradle_verification_hashes(root: Path) -> dict[tuple[str, str, str], list[str]]:
    metadata = _real_file(root, "apps/android/gradle/verification-metadata.xml")
    try:
        document = ET.parse(metadata)
    except (OSError, ET.ParseError) as exc:
        raise ReleaseBuildError("Android dependency verification metadata is invalid") from exc
    namespace = document.getroot().tag.partition("}")[0].removeprefix("{")
    if not namespace:
        raise ReleaseBuildError("Android dependency verification metadata has no namespace")
    result: dict[tuple[str, str, str], list[str]] = {}
    for component in document.findall(f".//{{{namespace}}}component"):
        key = (
            component.attrib.get("group", ""),
            component.attrib.get("name", ""),
            component.attrib.get("version", ""),
        )
        hashes = sorted(
            {
                item.attrib.get("value", "")
                for item in component.findall(f".//{{{namespace}}}sha256")
                if re.fullmatch(r"[0-9a-f]{64}", item.attrib.get("value", ""))
            }
        )
        if not all(key) or not hashes or key in result:
            raise ReleaseBuildError("Android dependency verification component is invalid or duplicated")
        result[key] = hashes
    return result


def _parse_android_dependencies(root: Path) -> list[dict[str, Any]]:
    verification = _gradle_verification_hashes(root)
    lock_relative = "apps/android/app/gradle.lockfile"
    dependencies: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str]] = set()
    for line_number, raw_line in enumerate(
        _real_file(root, lock_relative).read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("empty="):
            continue
        if "=" not in line:
            raise ReleaseBuildError(f"Android dependency lock entry is malformed: {line_number}")
        coordinate, raw_configurations = line.split("=", 1)
        configurations = set(raw_configurations.split(","))
        if "releaseRuntimeClasspath" not in configurations:
            continue
        parts = coordinate.split(":")
        if len(parts) != 3 or not all(parts):
            raise ReleaseBuildError(f"Android release dependency is malformed: {coordinate}")
        key = (parts[0], parts[1], parts[2])
        if key in seen or key not in verification:
            raise ReleaseBuildError(f"Android release dependency is duplicated or unverified: {coordinate}")
        seen.add(key)
        group, artifact, version = key
        dependencies.append(
            {
                "name": f"{group}:{artifact}",
                "version": version,
                "source": f"{lock_relative}#releaseRuntimeClasspath/{coordinate}",
                "purl": f"pkg:maven/{quote(group)}/{quote(artifact)}@{quote(version)}",
                "hashes": verification[key],
                "relationship": "DEPENDS_ON",
            }
        )
    if not dependencies:
        raise ReleaseBuildError("Android release dependency lock is empty")

    root_relative = "apps/android/build.gradle.kts"
    root_text = _real_file(root, root_relative).read_text(encoding="utf-8")
    for plugin, version in ANDROID_PLUGIN.findall(root_text):
        plugin_key = (plugin, f"{plugin}.gradle.plugin", version)
        if plugin_key not in verification:
            raise ReleaseBuildError(f"Android Gradle plugin is absent from verification metadata: {plugin}")
        dependencies.append(
            {
                "name": plugin,
                "version": version,
                "source": f"{root_relative}#plugin/{plugin}",
                "purl": f"pkg:gradle-plugin/{quote(plugin)}@{quote(version)}",
                "hashes": verification[plugin_key],
                "relationship": "DEPENDS_ON",
            }
        )
    wrapper_relative = "apps/android/gradle/wrapper/gradle-wrapper.properties"
    wrapper_text = _real_file(root, wrapper_relative).read_text(encoding="utf-8")
    wrapper_match = re.search(r"gradle-([0-9][0-9A-Za-z.-]*)-bin\.zip", wrapper_text)
    wrapper_hash = re.search(r"(?m)^distributionSha256Sum=([0-9a-f]{64})$", wrapper_text)
    if wrapper_match is None or wrapper_hash is None:
        raise ReleaseBuildError("Android Gradle wrapper version or distribution hash is not pinned")
    version = wrapper_match.group(1)
    dependencies.append(
        {
            "name": "gradle",
            "version": version,
            "source": f"{wrapper_relative}#distributionUrl",
            "purl": f"pkg:generic/gradle@{quote(version)}",
            "hashes": [wrapper_hash.group(1)],
            "relationship": "DEPENDS_ON",
        }
    )

    config_relative = "apps/android/app/src/main/assets/model-config/two_model_runtime.json"
    config = _strict_json_file(_real_file(root, config_relative), "Android model config")
    for model_name, model in sorted(config["models"].items()):
        asset = model["asset"]
        digest = model["artifact_sha256"]
        dependencies.append(
            {
                "name": f"WalkSafe Android model: {model_name}",
                "version": config.get("version", "source-bound"),
                "source": f"{config_relative}#model/{model_name}",
                "purl": f"pkg:generic/walksafe-android-model-{quote(model_name)}@{quote(digest)}",
                "hashes": [digest],
                "relationship": "CONTAINS",
            }
        )
    if not dependencies:
        raise ReleaseBuildError("Android dependency inputs are empty")
    return dependencies


def _spdx_id(kind: str, value: str) -> str:
    return f"SPDXRef-{kind}-{hashlib.sha256(value.encode()).hexdigest()[:20]}"


def _spdx_package(
    *,
    spdx_id: str,
    name: str,
    version: str,
    comment: str,
    checksums: Iterable[str] | None = None,
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
        "comment": f"source={comment}",
    }
    if checksums is not None:
        unique_checksums = sorted(set(checksums))
        expected_length = {"SHA256": 64, "SHA512": 128}.get(checksum_algorithm)
        if expected_length is None or not unique_checksums or any(
            re.fullmatch(rf"[0-9a-f]{{{expected_length}}}", item) is None
            for item in unique_checksums
        ):
            raise ReleaseBuildError(f"SPDX package has invalid checksums: {name}")
        package["checksums"] = [
            {"algorithm": checksum_algorithm, "checksumValue": item} for item in unique_checksums
        ]
    if purl is not None:
        package["externalRefs"] = [
            {
                "referenceCategory": "PACKAGE-MANAGER",
                "referenceType": "purl",
                "referenceLocator": purl,
            }
        ]
    return package


def _create_spdx_sbom(
    *,
    source_root: Path,
    source_commit: str,
    component_records: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    component_ids: dict[str, str] = {}
    packages: list[dict[str, Any]] = []
    relationships: list[dict[str, str]] = []
    for component_name in ("web", "android", "backend", "voice"):
        spdx_id = f"SPDXRef-WalkSafe-{component_name.capitalize()}"
        component_ids[component_name] = spdx_id
        artifact = component_records[component_name]["artifact"]
        packages.append(
            _spdx_package(
                spdx_id=spdx_id,
                name=f"WalkSafe {component_name}",
                version=source_commit,
                comment=artifact["path"],
                checksums=[artifact["sha256"]],
            )
        )
        relationships.append(
            {
                "spdxElementId": "SPDXRef-DOCUMENT",
                "relationshipType": "DESCRIBES",
                "relatedSpdxElement": spdx_id,
            }
        )
    web_support = component_records["web"]["runtime_support"]
    packages.append(
        _spdx_package(
            spdx_id="SPDXRef-WalkSafe-WebRuntimeSupport",
            name="WalkSafe web single-instance runtime support",
            version=source_commit,
            comment=web_support["path"],
            checksums=[web_support["sha256"]],
        )
    )
    relationships.append(
        {
            "spdxElementId": "SPDXRef-DOCUMENT",
            "relationshipType": "DESCRIBES",
            "relatedSpdxElement": "SPDXRef-WalkSafe-WebRuntimeSupport",
        }
    )

    dependency_groups = {
        "backend": _parse_python_lock(
            _real_file(source_root, "backend/requirements.lock"),
            "backend/requirements.lock",
            _parse_python_requirements(
                _real_file(source_root, "backend/requirements.txt"),
                "backend/requirements.txt",
            ),
        ),
        "voice": _parse_python_lock(
            _real_file(source_root, "voice/requirements.lock"),
            "voice/requirements.lock",
            _parse_python_requirements(
                _real_file(source_root, "voice/requirements.txt"),
                "voice/requirements.txt",
            ),
        ),
        "web": _parse_web_lock(_real_file(source_root, "apps/web/package-lock.json")),
        "android": _parse_android_dependencies(source_root),
    }
    for component_name, dependencies in dependency_groups.items():
        for dependency in dependencies:
            dependency_id = _spdx_id(component_name, dependency["source"])
            packages.append(
                _spdx_package(
                    spdx_id=dependency_id,
                    name=dependency["name"],
                    version=dependency["version"],
                    comment=dependency["source"],
                    checksums=dependency.get("hashes"),
                    checksum_algorithm=dependency.get("checksum_algorithm", "SHA256"),
                    purl=dependency["purl"],
                )
            )
            relationships.append(
                {
                    "spdxElementId": component_ids[component_name],
                    "relationshipType": dependency.get("relationship", "DEPENDS_ON"),
                    "relatedSpdxElement": dependency_id,
                }
            )
    return {
        "spdxVersion": SPDX_VERSION,
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": f"WalkSafe full RC {source_commit}",
        "documentNamespace": f"https://walksafe.invalid/spdx/full-rc/{source_commit}",
        "creationInfo": {
            "created": "1970-01-01T00:00:00Z",
            "creators": ["Tool: build_walksafe_full_rc_20260713.py"],
        },
        "packages": sorted(packages, key=lambda package: package["SPDXID"]),
        "relationships": sorted(
            relationships,
            key=lambda relationship: (
                relationship["spdxElementId"],
                relationship["relationshipType"],
                relationship["relatedSpdxElement"],
            ),
        ),
    }


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("x", encoding="utf-8") as output:
            output.write(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
            output.flush()
            os.fsync(output.fileno())
    except FileExistsError as exc:
        raise ReleaseBuildError("JSON output already exists") from exc
    os.chmod(path, 0o644)


def build_full_rc(
    *,
    source_root: Path,
    web_manifest_path: Path,
    web_artifact_root: Path,
    web_archive_path: Path,
    unsigned_apk_path: Path,
    quality_receipt_paths: dict[str, Path],
    output_root: Path,
) -> Path:
    root, source_commit, source_tree = _clean_source_identity(source_root)
    output = output_root.expanduser().absolute()
    if output.exists() or output.is_symlink():
        raise ReleaseBuildError("full RC output path must not already exist")
    if (
        output.parent.is_symlink()
        or not output.parent.is_dir()
        or output.parent.resolve() != output.parent
    ):
        raise ReleaseBuildError("full RC output parent must be an existing real directory")
    _validate_unsigned_apk_payload(unsigned_apk_path, source_commit, root)
    for relative in RELEASE_SOURCE_INPUTS:
        _real_file(root, relative)

    temporary = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
    try:
        web_component, _web_evidence = _copy_web_evidence(
            manifest_path=web_manifest_path,
            artifact_root=web_artifact_root,
            archive_path=web_archive_path,
            destination_root=temporary / "web",
            source_root=root,
            source_commit=source_commit,
        )

        android_output = temporary / "android" / "app-release-unsigned.apk"
        android_output.parent.mkdir(parents=True)
        shutil.copyfile(unsigned_apk_path, android_output)
        os.chmod(android_output, 0o644)

        backend_output = temporary / "backend" / f"walksafe-backend-{source_commit}.tar.gz"
        create_deterministic_tar_gz(
            backend_output,
            source_root=root,
            archive_root_name="walksafe-backend",
            relative_files=_backend_runtime_files(root),
        )
        voice_output = temporary / "voice" / f"walksafe-voice-{source_commit}.tar.gz"
        create_deterministic_tar_gz(
            voice_output,
            source_root=root,
            archive_root_name="walksafe-voice",
            relative_files=_voice_runtime_files(root),
        )
        web_support_output = temporary / "web" / f"walksafe-web-runtime-support-{source_commit}.tar.gz"
        create_deterministic_tar_gz(
            web_support_output,
            source_root=root,
            archive_root_name="walksafe-web-runtime-support",
            relative_files=_web_runtime_support_files(root),
        )

        component_records: dict[str, dict[str, Any]] = {
            "web": web_component,
            "android": {
                "artifact": _artifact_record(android_output, temporary),
                "signing_status": "unsigned",
                "deployable": False,
                "deployment_blocker": "operator signing and certificate verification are required",
            },
            "backend": {
                "artifact": _artifact_record(backend_output, temporary),
                "runtime_unit": "deploy/systemd/walksafe-backend.service",
                "migration_unit": "deploy/systemd/walksafe-backend-migrate.service",
                "configuration_example": "deploy/config/walksafe-backend.env.example",
            },
            "voice": {
                "artifact": _artifact_record(voice_output, temporary),
                "runtime_unit": "deploy/systemd/walksafe-voice.service",
                "configuration_example": "deploy/config/walksafe-voice.env.example",
            },
        }
        component_records["web"].update(
            {
                "runtime_support": _artifact_record(web_support_output, temporary),
                "runtime_unit": "deploy/systemd/walksafe-web.service",
                "configuration_example": "deploy/config/walksafe-web.env.example",
                "trusted_proxy_configuration": "deploy/nginx/walksafe-web.conf.example",
                "trusted_proxy_runtime_check": "scripts/check_walksafe_trusted_proxy_20260716.py",
            }
        )

        quality_records = _copy_quality_evidence(
            receipt_paths=quality_receipt_paths,
            source_root=root,
            source_commit=source_commit,
            source_tree=source_tree,
            web_archive_path=web_archive_path,
            unsigned_apk_path=unsigned_apk_path,
            destination_root=temporary / "quality",
        )

        sbom_path = temporary / "sbom" / "walksafe-full-rc.spdx.json"
        _write_json(
            sbom_path,
            _create_spdx_sbom(
                source_root=root,
                source_commit=source_commit,
                component_records=component_records,
            ),
        )
        source_inputs = [
            {
                "path": relative,
                "sha256": sha256_file(_real_file(root, relative)),
                "bytes": _real_file(root, relative).stat().st_size,
            }
            for relative in sorted(RELEASE_SOURCE_INPUTS)
        ]
        closure_files, closure_sha256 = _file_closure(temporary)
        manifest = {
            "schema_version": SCHEMA_VERSION,
            "source": {
                "commit": source_commit,
                "tree": source_tree,
                "worktree_clean": True,
            },
            "release_state": {
                "kind": "release_candidate",
                "deployment_complete": False,
                "reason": (
                    "Android signing, server/Voice model inputs, secrets, trusted edge TLS identity, "
                    "shared storage, ffprobe, runtime images, operator attestation, and external "
                    "acceptance remain operator-provided blockers"
                ),
            },
            "components": component_records,
            "quality": quality_records,
            "sbom": _artifact_record(sbom_path, temporary),
            "source_inputs": source_inputs,
            "files": closure_files,
            "closure": {
                "algorithm": "walksafe-path-size-sha256-lines.v1",
                "sha256": closure_sha256,
            },
            "authentication": {
                "state": "external-attestation-required",
                "included": False,
                "deployment_allowed": False,
            },
            "external_runtime_inputs": [
                {
                    "id": "operator-secrets",
                    "included": False,
                    "required": "database, TMAP, gateway, backend, Voice, and session credentials",
                },
                {
                    "id": "trusted-edge-tls-identity",
                    "included": False,
                    "required": (
                        "operator-provisioned public DNS, TLS certificate/private key, and firewall "
                        "that exposes only TLS port 443 and denies direct Web port 3000 for the "
                        "packaged trusted proxy configuration"
                    ),
                },
                {
                    "id": "backend-model-weight",
                    "included": False,
                    "required": "hash-approved img768 detector weight at DETECT_V2_UNIFIED_MODEL_PATH",
                },
                {
                    "id": "voice-model-weights",
                    "included": False,
                    "required": "operator-provisioned STT/TTS model caches and optional reference audio",
                },
                {
                    "id": "voice-ffprobe",
                    "included": False,
                    "required": "operator-installed ffprobe for non-WAV audio duration validation",
                },
                {
                    "id": "shared-postgis-and-upload-storage",
                    "included": False,
                    "required": "PostGIS atomic limiter store and shared durable UPLOAD_DIR for multi-replica backend",
                },
                {
                    "id": "android-operator-signing",
                    "included": False,
                    "required": "operator keystore, approved certificate digest, signing, and apksigner gate",
                },
                {
                    "id": "external-acceptance",
                    "included": False,
                    "required": "physical-device, outdoor, and institutional acceptance receipts",
                },
                {
                    "id": "runtime-images-and-native-libraries",
                    "included": False,
                    "required": (
                        "operator-attested immutable Node/CPython runtime image digests and native "
                        "library inventory matching the structured quality receipts"
                    ),
                },
                {
                    "id": "operator-release-attestation",
                    "included": False,
                    "required": (
                        "independent validation receipt and detached signature from an out-of-band "
                        "approved release-reviewer key"
                    ),
                },
            ],
            "packaging_policy": {
                "secrets_included": False,
                "loose_server_or_voice_model_weights_included": False,
                "android_embedded_model_note": (
                    "The APK retains its separately audited embedded on-device model assets; "
                    "no loose model file is added by this builder."
                ),
            },
        }
        manifest_path = temporary / "walksafe-full-rc-manifest.json"
        _write_json(manifest_path, manifest)
        try:
            verify_exact_git_source(
                root,
                context="full RC source after build",
                expected_commit=source_commit,
                expected_tree=source_tree,
            )
        except ReleaseIntegrityError as exc:
            raise ReleaseBuildError(str(exc)) from exc
        try:
            prepared_rc = exact_directory_record(
                temporary,
                context="full RC before publication",
                manifest_name="walksafe-full-rc-manifest.json",
            )
        except ReleaseIntegrityError as exc:
            raise ReleaseBuildError(str(exc)) from exc
        try:
            exclusive_directory_publish(temporary, output)
        except ReleaseIntegrityError as exc:
            raise ReleaseBuildError(str(exc)) from exc
        try:
            if exact_directory_record(
                output,
                context="full RC after publication",
                manifest_name="walksafe-full-rc-manifest.json",
            ) != prepared_rc:
                raise ReleaseBuildError("full RC changed during publication")
        except ReleaseIntegrityError as exc:
            raise ReleaseBuildError(str(exc)) from exc
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    return output / "walksafe-full-rc-manifest.json"


def main() -> int:
    print(
        "BLOCKED: the Web-inclusive full RC builder is LEGACY_REFERENCE_ONLY under FP-009.",
        file=sys.stderr,
    )
    return 78

    # Historical CLI implementation below is intentionally unreachable.
    try:
        require_isolated_python("WalkSafe full RC builder CLI")
    except ReleaseIntegrityError as exc:
        raise SystemExit(f"full RC build failed: {exc}") from exc
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--web-build-manifest", type=Path, required=True)
    parser.add_argument("--web-artifact-root", type=Path, required=True)
    parser.add_argument("--web-deployment-archive", type=Path, required=True)
    parser.add_argument("--unsigned-apk", type=Path, required=True)
    for product in QUALITY_PRODUCTS:
        parser.add_argument(
            f"--{product}-quality-receipt",
            dest=f"{product}_quality_receipt",
            type=Path,
            required=True,
        )
    parser.add_argument("--output-root", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = build_full_rc(
            source_root=args.source_root,
            web_manifest_path=args.web_build_manifest,
            web_artifact_root=args.web_artifact_root,
            web_archive_path=args.web_deployment_archive,
            unsigned_apk_path=args.unsigned_apk,
            quality_receipt_paths={
                product: getattr(args, f"{product}_quality_receipt")
                for product in QUALITY_PRODUCTS
            },
            output_root=args.output_root,
        )
    except ReleaseBuildError as exc:
        raise SystemExit(f"full RC build failed: {exc}") from exc
    print(f"manifest={manifest}")
    print(f"manifest_sha256={sha256_file(manifest)}")
    print("deployment_complete=false")
    print("android_signing_status=unsigned")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
