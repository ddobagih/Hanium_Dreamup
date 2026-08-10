#!/usr/bin/env python3
"""Run the fixed WalkSafe product quality policy and emit a structured receipt."""

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
            "WalkSafe product quality CLI requires Python -I -S -B before any release code runs"
        )

import argparse
import base64
import binascii
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tarfile
import tempfile
import types
from typing import Any, Callable, Mapping, Sequence
from urllib.parse import urlsplit


_SCRIPT_DIRECTORY = Path(__file__).resolve().parent
_INTEGRITY_SHA256 = "b084292b5a6e4befd82aea22ceac899527ab1ba1ffe51b64fa022c831b72a18e"
_ISOLATED_BOOTSTRAP_SHA256 = "ab8d6aa25f608e4cbe72aa090b078ef46f6aa4a022ee15f1ea57f53363e0b64c"
_NODE_TOOLCHAIN_CHECKER_SHA256 = "dd88cd342addda35bc486440091e39e3b16d064c89e0f79d62fc3c03d21cdcfe"


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
    "_walksafe_release_integrity_for_quality_runner",
    _SCRIPT_DIRECTORY / "walksafe_release_integrity.py",
    _INTEGRITY_SHA256,
)
FileSnapshot = _integrity.FileSnapshot
ReleaseIntegrityError = _integrity.ReleaseIntegrityError
exclusive_atomic_publish = _integrity.exclusive_atomic_publish
exclusive_directory_publish = _integrity.exclusive_directory_publish
exact_directory_record = _integrity.exact_directory_record
require_isolated_python = _integrity.require_isolated_python
strict_json_snapshot = _integrity.strict_json_snapshot
strict_json_bytes = _integrity.strict_json_bytes
verify_exact_git_source = _integrity.verify_exact_git_source

_ISOLATED_BOOTSTRAP_PATH = _SCRIPT_DIRECTORY / "run_walksafe_isolated_python_20260713.py"
isolated_python = _load_local_source(
    "_walksafe_isolated_python_bootstrap_under_runner",
    _ISOLATED_BOOTSTRAP_PATH,
    _ISOLATED_BOOTSTRAP_SHA256,
)
_NODE_TOOLCHAIN_CHECKER_PATH = _SCRIPT_DIRECTORY / "check_walksafe_node_toolchain_20260715.py"
node_toolchain = _load_local_source(
    "_walksafe_node_toolchain_checker_under_quality_runner",
    _NODE_TOOLCHAIN_CHECKER_PATH,
    _NODE_TOOLCHAIN_CHECKER_SHA256,
)


REPO_ROOT = Path(__file__).resolve().parents[1]
POLICY_PATH = REPO_ROOT / "configs" / "walksafe_product_quality_policy_20260713.json"
EXPECTED_POLICY_SHA256 = "c4a9dccedcee730f6542ed2a5b627780bb9419c13a10ce7fd011ecdc8e73a6f5"
POLICY_SCHEMA = "walksafe.product-quality-policy.v2"
RECEIPT_SCHEMA = "walksafe.product-quality-receipt.v3"
STEP_LOG_SCHEMA = "walksafe.product-quality-step-log.v1"
PRODUCTS = frozenset({"web", "android", "backend", "voice"})
WEB_BUILD_VARIANT_FILES = frozenset(
    {
        ".next/prerender-manifest.json",
        ".next/required-server-files.json",
        ".next/server/server-reference-manifest.js",
        ".next/server/server-reference-manifest.json",
        "server.js",
    }
)
WEB_BUILD_VARIANT_MAX_BYTES = 1024 * 1024
DECLARED_IGNORED_OUTPUT_ROOTS = {
    "web": (
        "apps/web/.next",
        "apps/web/node_modules",
        "apps/web/walksafe-test-logs",
        "apps/web/tsconfig.tsbuildinfo",
    ),
    "android": (
        "apps/android/.gradle",
        "apps/android/build",
        "apps/android/app/build",
    ),
    "backend": (),
    "voice": (),
}
STEP_KEYS = frozenset(
    {
        "id",
        "argv",
        "cwd",
        "fixed_environment",
        "ambient_environment",
        "derived_environment",
    }
)


class QualityRunnerError(RuntimeError):
    """A fail-closed policy, source, command, or receipt error."""


@dataclass(frozen=True)
class ExecutionResult:
    returncode: int
    stdout: bytes = b""
    stderr: bytes = b""


Executor = Callable[[Sequence[str], Path, Mapping[str, str]], ExecutionResult]
NodeToolchainAttestor = Callable[[Path, Path], dict[str, Any]]
_DEFAULT_NODE_TOOLCHAIN_ATTESTOR: NodeToolchainAttestor = node_toolchain.verify_node_toolchain


def _resolve_node_bin_dir(path: Path | None) -> tuple[Path, Path]:
    if path is None:
        raise QualityRunnerError("node_bin_dir is required for Web quality execution")
    candidate = path.expanduser().absolute()
    try:
        metadata = candidate.lstat()
        resolved = candidate.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise QualityRunnerError("node_bin_dir is unavailable") from exc
    if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode) or resolved != candidate:
        raise QualityRunnerError("node_bin_dir must be a real canonical directory")
    root = candidate.parent
    try:
        root_metadata = root.lstat()
    except OSError as exc:
        raise QualityRunnerError("Node root is unavailable") from exc
    if not stat.S_ISDIR(root_metadata.st_mode) or stat.S_ISLNK(root_metadata.st_mode):
        raise QualityRunnerError("Node root must be a real directory")
    return candidate, root


def _attest_node_toolchain(
    attestor: NodeToolchainAttestor,
    node_root: Path,
    lock_path: Path,
) -> dict[str, Any]:
    try:
        attestation = attestor(node_root, lock_path)
    except (OSError, ValueError) as exc:
        raise QualityRunnerError(f"Node toolchain verification failed: {exc}") from exc
    if not isinstance(attestation, dict):
        raise QualityRunnerError("Node toolchain verifier returned an invalid attestation")
    return attestation


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _regular_file(path: Path, context: str, *, nonempty: bool = False) -> Path:
    if path.is_symlink() or not path.is_file():
        raise QualityRunnerError(f"{context} must be a regular non-symlink file")
    resolved = path.resolve()
    if nonempty and resolved.stat().st_size <= 0:
        raise QualityRunnerError(f"{context} must not be empty")
    return resolved


def _file_record(path: Path, *, label: str, display_path: str) -> dict[str, object]:
    resolved = _regular_file(path, label, nonempty=True)
    return {
        "path": display_path,
        "bytes": resolved.stat().st_size,
        "sha256": sha256_file(resolved),
    }


def _safe_relative(raw: str, context: str, *, allow_dot: bool = False) -> PurePosixPath:
    if not isinstance(raw, str) or not raw or "\\" in raw:
        raise QualityRunnerError(f"{context} is not a canonical repository-relative path")
    if raw == "." and allow_dot:
        return PurePosixPath(".")
    path = PurePosixPath(raw)
    if path.is_absolute() or not path.parts or ".." in path.parts or "." in path.parts:
        raise QualityRunnerError(f"{context} is not a canonical repository-relative path")
    return path


def _repository_directory(repo_root: Path, raw: str, context: str) -> tuple[PurePosixPath, Path]:
    relative = _safe_relative(raw, context, allow_dot=True)
    path = repo_root if relative == PurePosixPath(".") else repo_root.joinpath(*relative.parts)
    if path.is_symlink() or not path.is_dir():
        raise QualityRunnerError(f"{context} does not exist")
    resolved = path.resolve()
    if resolved != repo_root and repo_root not in resolved.parents:
        raise QualityRunnerError(f"{context} escaped the repository")
    return relative, resolved


def _validate_policy(payload: object) -> dict[str, Any]:
    if not isinstance(payload, dict) or set(payload) != {
        "schema_version",
        "receipt_schema_version",
        "ambient_environment_allowlist",
        "products",
    }:
        raise QualityRunnerError("quality policy has unexpected top-level fields")
    if payload.get("schema_version") != POLICY_SCHEMA or payload.get("receipt_schema_version") != RECEIPT_SCHEMA:
        raise QualityRunnerError("quality policy schema is unsupported")
    allowlist = payload.get("ambient_environment_allowlist")
    if (
        not isinstance(allowlist, list)
        or not allowlist
        or any(not isinstance(name, str) or not name or "=" in name for name in allowlist)
        or len(set(allowlist)) != len(allowlist)
    ):
        raise QualityRunnerError("quality policy ambient environment allowlist is invalid")
    products = payload.get("products")
    if not isinstance(products, dict) or set(products) != PRODUCTS:
        raise QualityRunnerError("quality policy product set is incomplete or unexpected")
    for product, definition in products.items():
        if not isinstance(definition, dict) or set(definition) != {
            "tested_environment_lock",
            "tested_tool_lock",
            "tested_site_packages",
            "trusted_tool_distributions",
            "required_arguments",
            "steps",
            "outputs",
        }:
            raise QualityRunnerError(f"quality policy product is malformed: {product}")
        required = definition.get("required_arguments")
        if not isinstance(required, list) or any(not isinstance(name, str) or not name for name in required):
            raise QualityRunnerError(f"quality policy arguments are malformed: {product}")
        if len(required) != len(set(required)):
            raise QualityRunnerError(f"quality policy arguments are duplicated: {product}")
        steps = definition.get("steps")
        if not isinstance(steps, list) or not steps:
            raise QualityRunnerError(f"quality policy steps are missing: {product}")
        step_ids: set[str] = set()
        for step in steps:
            if (
                not isinstance(step, dict)
                or not {"id", "argv", "cwd"}.issubset(step)
                or not set(step).issubset(STEP_KEYS)
            ):
                raise QualityRunnerError(f"quality policy step is malformed: {product}")
            step_id = step.get("id")
            argv = step.get("argv")
            if not isinstance(step_id, str) or not step_id or step_id in step_ids:
                raise QualityRunnerError(f"quality policy step id is invalid: {product}")
            step_ids.add(step_id)
            if (
                not isinstance(argv, list)
                or not argv
                or any(not isinstance(value, str) or not value or "\0" in value or "\n" in value for value in argv)
            ):
                raise QualityRunnerError(f"quality policy argv is invalid: {product}/{step_id}")
            _safe_relative(step.get("cwd"), f"quality policy cwd {product}/{step_id}", allow_dot=True)
            for key in ("fixed_environment", "derived_environment"):
                values = step.get(key, {})
                if not isinstance(values, dict) or any(
                    not isinstance(name, str)
                    or not name
                    or "=" in name
                    or not isinstance(value, str)
                    or "\0" in value
                    for name, value in values.items()
                ):
                    raise QualityRunnerError(f"quality policy {key} is invalid: {product}/{step_id}")
            ambient = step.get("ambient_environment", [])
            if not isinstance(ambient, list) or any(not isinstance(name, str) or not name for name in ambient):
                raise QualityRunnerError(f"quality policy ambient environment is invalid: {product}/{step_id}")
        environment_lock = definition.get("tested_environment_lock")
        if environment_lock is not None:
            _safe_relative(environment_lock, f"quality policy tested environment lock {product}")
        if (product in {"backend", "voice"}) != (environment_lock is not None):
            raise QualityRunnerError(f"quality policy tested environment lock is invalid: {product}")
        tool_lock = definition.get("tested_tool_lock")
        if tool_lock is not None:
            _safe_relative(tool_lock, f"quality policy tested tool lock {product}")
        if (product in {"web", "voice"}) != (tool_lock is not None):
            raise QualityRunnerError(f"quality policy tested tool lock is invalid: {product}")
        tested_site_packages = definition.get("tested_site_packages")
        if product == "android":
            if tested_site_packages is not None:
                raise QualityRunnerError(
                    f"quality policy tested site-packages is invalid: {product}"
                )
        else:
            if (
                not isinstance(tested_site_packages, dict)
                or set(tested_site_packages) != {"format", "closure"}
                or tested_site_packages.get("format")
                != "walksafe.record-claimed-site-closure.v2"
            ):
                raise QualityRunnerError(
                    f"quality policy tested site-packages is invalid: {product}"
                )
            closure = tested_site_packages.get("closure")
            if (
                not isinstance(closure, dict)
                or set(closure) != {"count", "sha256"}
                or not isinstance(closure.get("count"), int)
                or isinstance(closure.get("count"), bool)
                or closure["count"] <= 0
                or re.fullmatch(r"[0-9a-f]{64}", str(closure.get("sha256", ""))) is None
            ):
                raise QualityRunnerError(
                    f"quality policy tested site-packages is invalid: {product}"
                )
        trusted_tools = definition.get("trusted_tool_distributions")
        if not isinstance(trusted_tools, dict) or any(
            not isinstance(name, str)
            or re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name) is None
            or not isinstance(version, str)
            or not version
            or any(character in version for character in "\0\r\n")
            for name, version in trusted_tools.items()
        ):
            raise QualityRunnerError(
                f"quality policy trusted tool distributions are invalid: {product}"
            )
        if (product in {"web", "backend", "voice"}) != bool(trusted_tools):
            raise QualityRunnerError(
                f"quality policy trusted tool distributions are invalid: {product}"
            )
        outputs = definition.get("outputs")
        if not isinstance(outputs, list):
            raise QualityRunnerError(f"quality policy outputs are invalid: {product}")
        output_names: set[str] = set()
        for output in outputs:
            if not isinstance(output, dict) or set(output) not in (
                {"name", "path"},
                {"name", "path", "producer_step", "verification"},
                {"name", "path_argument", "verification_step", "verification"},
                {"name", "path_argument", "producer_step", "verification"},
            ):
                raise QualityRunnerError(f"quality policy output is malformed: {product}")
            name = output.get("name")
            if not isinstance(name, str) or not name or name in output_names:
                raise QualityRunnerError(f"quality policy output name is invalid: {product}")
            output_names.add(name)
            step_binding = output.get("producer_step", output.get("verification_step"))
            if step_binding is not None and (
                step_binding not in step_ids
                or not isinstance(output.get("verification"), str)
            ):
                raise QualityRunnerError(f"quality policy output producer is invalid: {product}/{name}")
            if "path" in output:
                _safe_relative(output["path"], f"quality policy output {product}/{name}")
            elif output.get("path_argument") not in required:
                raise QualityRunnerError(f"quality policy output argument is undeclared: {product}/{name}")
    return payload


def _load_fixed_policy(policy_path: Path, expected_sha256: str) -> tuple[dict[str, Any], dict[str, object]]:
    try:
        snapshot = FileSnapshot.capture(
            policy_path,
            context="quality policy",
            display_path="configs/walksafe_product_quality_policy_20260713.json",
        )
    except ReleaseIntegrityError as exc:
        raise QualityRunnerError(str(exc)) from exc
    try:
        if snapshot.sha256 != expected_sha256:
            raise QualityRunnerError("quality policy hash differs from the runner-pinned policy")
        try:
            payload = strict_json_snapshot(snapshot, context="quality policy")
        except ReleaseIntegrityError as exc:
            raise QualityRunnerError(str(exc)) from exc
        return _validate_policy(payload), snapshot.record()
    finally:
        snapshot.close()


def _expand_token(value: str, substitutions: Mapping[str, str], context: str) -> str:
    if value in substitutions:
        return substitutions[value]
    if "{" in value or "}" in value:
        raise QualityRunnerError(f"unknown policy token in {context}")
    return value


def _normalized_https_origin(raw: str | None) -> str:
    value = (raw or "").strip().rstrip("/")
    parsed = urlsplit(value)
    if (
        parsed.scheme.lower() != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
    ):
        raise QualityRunnerError("android_gateway_origin must be one root HTTPS origin")
    try:
        port = parsed.port
    except ValueError as exc:
        raise QualityRunnerError("android_gateway_origin has an invalid port") from exc
    suffix = "" if port is None else f":{port}"
    return f"https://{parsed.hostname.lower()}{suffix}"


def _resolve_arguments(product: str, required: Sequence[str], supplied: Mapping[str, str]) -> dict[str, str]:
    if set(supplied) != set(required):
        raise QualityRunnerError(f"{product} arguments must exactly match the fixed policy")
    resolved = dict(supplied)
    if "android_gateway_origin" in resolved:
        resolved["android_gateway_origin"] = _normalized_https_origin(resolved["android_gateway_origin"])
    if "web_release_archive" in resolved:
        archive = _regular_file(
            Path(resolved["web_release_archive"]).expanduser(),
            "Web release archive",
            nonempty=True,
        )
        if not archive.name.endswith(".tar.gz"):
            raise QualityRunnerError("Web release archive must use a .tar.gz filename")
        resolved["web_release_archive"] = str(archive)
    return resolved


def _build_environment(
    *,
    policy: Mapping[str, Any],
    step: Mapping[str, Any],
    ambient: Mapping[str, str],
    substitutions: Mapping[str, str],
    node_bin_dir: Path | None = None,
) -> tuple[dict[str, str], dict[str, object]]:
    environment: dict[str, str] = {}
    passthrough: dict[str, dict[str, bool]] = {}
    for name in policy["ambient_environment_allowlist"]:
        value = ambient.get(name)
        if value is not None:
            environment[name] = value
            passthrough[name] = {"present": True}
    if "PATH" not in environment or not environment["PATH"]:
        raise QualityRunnerError("allowlisted PATH must be present")

    required_presence: dict[str, dict[str, bool]] = {}
    for name in step.get("ambient_environment", []):
        value = ambient.get(name)
        if value is None or not value.strip():
            raise QualityRunnerError(f"required environment is absent: {name}")
        environment[name] = value
        required_presence[name] = {"present": True}

    derived_presence: dict[str, dict[str, object]] = {}
    for destination, source in step.get("derived_environment", {}).items():
        value = ambient.get(source)
        if value is None or not value.strip():
            raise QualityRunnerError(f"required environment is absent: {source}")
        environment[destination] = value
        derived_presence[destination] = {"source": source, "present": True}

    fixed_public: dict[str, str] = {}
    for name, configured in step.get("fixed_environment", {}).items():
        value = _expand_token(configured, substitutions, f"environment {name}")
        environment[name] = value
        fixed_public[name] = "{repo_root}" if configured == "{repo_root}" else value
    private_home = substitutions["{private_quality_home}"]
    environment.update(
        {
            "HOME": private_home,
            "GRADLE_USER_HOME": str(Path(private_home) / ".gradle"),
            "NPM_CONFIG_USERCONFIG": str(Path(private_home) / ".npmrc"),
            "PYTHONNOUSERSITE": "1",
        }
    )
    fixed_public.update(
        {
            "HOME": "{private_quality_home}",
            "GRADLE_USER_HOME": "{private_quality_home}/.gradle",
            "NPM_CONFIG_USERCONFIG": "{private_quality_home}/.npmrc",
            "PYTHONNOUSERSITE": "1",
        }
    )
    if node_bin_dir is not None:
        environment["PATH"] = f"{node_bin_dir}:/usr/bin:/bin"
        fixed_public["PATH"] = "{locked_node_bin}:/usr/bin:/bin"
    return environment, {
        "allowlisted_ambient_presence": passthrough,
        "required_ambient_presence": required_presence,
        "derived_secret_presence": derived_presence,
        "fixed_public": fixed_public,
    }


def _resolve_executable(
    argv: list[str], cwd: Path, environment: Mapping[str, str]
) -> tuple[list[str], dict[str, object]]:
    executable = argv[0]
    launcher: Path | None = None
    if executable.startswith("./"):
        path = cwd / executable[2:]
        resolved_for_execution = _regular_file(path, "policy executable", nonempty=True)
        if not os.access(resolved_for_execution, os.X_OK):
            raise QualityRunnerError("policy executable is not executable")
    elif "/" in executable:
        path = Path(executable).expanduser().absolute()
        if path == Path(sys.executable).absolute():
            if path.parent.resolve() != path.parent or not os.access(path, os.X_OK):
                raise QualityRunnerError("tested Python launcher path is not trusted")
            try:
                mode = path.lstat().st_mode
            except OSError as exc:
                raise QualityRunnerError("tested Python launcher is unavailable") from exc
            if not (stat.S_ISREG(mode) or stat.S_ISLNK(mode)):
                raise QualityRunnerError("tested Python launcher is not a file")
            resolved_for_execution = path
            launcher = path
        else:
            resolved_for_execution = _regular_file(path, "policy executable", nonempty=True)
    else:
        found = shutil.which(executable, path=environment.get("PATH"))
        if found is None:
            raise QualityRunnerError(f"policy executable is unavailable: {executable}")
        resolved_for_execution = Path(found).absolute()
        if not resolved_for_execution.exists():
            raise QualityRunnerError(f"policy executable is unavailable: {executable}")
    target = resolved_for_execution.resolve()
    _regular_file(target, "resolved policy executable", nonempty=True)
    execution_path = target if launcher is None else launcher
    argv[0] = str(execution_path)
    return argv, {
        "path": str(execution_path),
        "resolved_path": str(target),
        "bytes": target.stat().st_size,
        "sha256": sha256_file(target),
    }


def _default_executor(
    argv: Sequence[str],
    cwd: Path,
    environment: Mapping[str, str],
    *,
    executable_snapshot: FileSnapshot | None = None,
) -> ExecutionResult:
    arguments = list(argv)
    launcher = Path(arguments[0]).absolute()
    if launcher == Path(sys.executable).absolute():
        try:
            if executable_snapshot is None:
                with FileSnapshot.capture(
                    launcher.resolve(),
                    context="tested Python executable",
                    private_mode=0o500,
                ) as captured:
                    return _default_executor(
                        arguments,
                        cwd,
                        environment,
                        executable_snapshot=captured,
                    )
            else:
                completed = subprocess.run(
                    arguments,
                    executable=executable_snapshot.proc_path,
                    cwd=cwd,
                    env=dict(environment),
                    check=False,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    pass_fds=(executable_snapshot.fd,),
                )
        except ReleaseIntegrityError as exc:
            raise QualityRunnerError(str(exc)) from exc
    else:
        completed = subprocess.run(
            arguments,
            cwd=cwd,
            env=dict(environment),
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    return ExecutionResult(completed.returncode)


def _json_bytes(payload: object) -> bytes:
    return (json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _write_bytes(path: Path, payload: bytes, mode: int = 0o644) -> None:
    with path.open("xb") as destination:
        os.chmod(path, mode)
        destination.write(payload)
        destination.flush()
        os.fsync(destination.fileno())


def _atomic_write(path: Path, payload: bytes) -> None:
    try:
        exclusive_atomic_publish(path, payload, mode=0o644)
    except ReleaseIntegrityError as exc:
        raise QualityRunnerError(str(exc)) from exc


def _collect_outputs(
    *,
    repo_root: Path,
    definitions: Sequence[Mapping[str, str]],
    arguments: Mapping[str, str],
) -> list[dict[str, object]]:
    records: list[dict[str, object]] = []
    for definition in definitions:
        name = definition["name"]
        if "path" in definition:
            relative = _safe_relative(definition["path"], f"output {name}")
            path = repo_root.joinpath(*relative.parts)
            resolved = _regular_file(path, f"output {name}", nonempty=True)
            if repo_root not in resolved.parents:
                raise QualityRunnerError(f"output {name} escaped the repository")
            display_path = f"repository:{relative.as_posix()}"
        else:
            path = Path(arguments[definition["path_argument"]])
            resolved = _regular_file(path, f"output {name}", nonempty=True)
            display_path = f"operator-artifact:{resolved.name}"
        record = {"name": name}
        step_key = "producer_step" if "producer_step" in definition else "verification_step"
        if step_key in definition:
            record.update(
                verification=definition["verification"],
            )
            record[step_key] = definition[step_key]
        record.update(_file_record(resolved, label=f"output {name}", display_path=display_path))
        records.append(record)
    return records


def _archive_relative(raw: str) -> PurePosixPath | None:
    if not raw or "\\" in raw:
        raise QualityRunnerError("Web release archive contains an unsafe path")
    path = PurePosixPath(raw)
    if path.is_absolute() or ".." in path.parts:
        raise QualityRunnerError("Web release archive contains an unsafe path")
    parts = tuple(part for part in path.parts if part not in {"", "."})
    return PurePosixPath(*parts) if parts else None


def _fresh_web_files(repo_root: Path, source_commit: str) -> dict[str, Path]:
    web_root = repo_root / "apps" / "web"
    build_root = web_root / ".next"
    build_id = _regular_file(build_root / "BUILD_ID", "fresh Web BUILD_ID", nonempty=True)
    if build_id.read_text(encoding="utf-8").strip().lower() != source_commit:
        raise QualityRunnerError("fresh Web BUILD_ID does not match the tested source commit")
    mappings = (
        (build_root / "standalone", PurePosixPath("."), True),
        (build_root / "static", PurePosixPath(".next/static"), True),
        (web_root / "public", PurePosixPath("public"), False),
    )
    expected: dict[str, Path] = {"BUILD_ID": build_id}
    for directory, prefix, required in mappings:
        if directory.is_symlink() or (required and not directory.is_dir()):
            raise QualityRunnerError("fresh Web standalone output is missing or contains a symlink")
        if not directory.exists():
            continue
        for item in directory.rglob("*"):
            if item.is_symlink():
                raise QualityRunnerError("fresh Web standalone output contains a symlink")
            if not item.is_file():
                continue
            relative = item.relative_to(directory)
            destination = (prefix / relative).as_posix()
            if destination.startswith("./"):
                destination = destination[2:]
            if destination in expected:
                raise QualityRunnerError(f"fresh Web standalone output collides at {destination}")
            expected[destination] = item.resolve()
    if len(expected) <= 1:
        raise QualityRunnerError("fresh Web standalone output contains no deployable files")
    return expected


def _strict_web_variant_json(payload: bytes, context: str) -> Any:
    try:
        return strict_json_bytes(payload, context=context)
    except ReleaseIntegrityError as exc:
        raise QualityRunnerError(str(exc)) from exc


def _replace_web_variant_value(
    payload: bytes,
    value: str,
    replacement: bytes,
    *,
    expected_count: int,
    context: str,
) -> bytes:
    raw_value = value.encode("utf-8")
    if not raw_value or payload.count(raw_value) != expected_count:
        raise QualityRunnerError(f"{context} raw value occurrence count is invalid")
    return payload.replace(raw_value, replacement)


def _validated_web_build_root(value: Any, expected: str | None, context: str) -> str:
    if not isinstance(value, str) or "\\" in value or "\x00" in value:
        raise QualityRunnerError(f"{context} build root is invalid")
    root = PurePosixPath(value)
    if (
        not root.is_absolute()
        or value.startswith("//")
        or root.as_posix() != value
        or ".." in root.parts
        or root.parts[-2:] != ("apps", "web")
        or (expected is not None and value != expected)
    ):
        raise QualityRunnerError(f"{context} build root is invalid")
    return value


def _validated_rsc_key(payload: Any, context: str) -> str:
    if not isinstance(payload, dict) or set(payload) != {"node", "edge", "encryptionKey"}:
        raise QualityRunnerError(f"{context} fields are invalid")
    if not isinstance(payload["node"], dict) or not isinstance(payload["edge"], dict):
        raise QualityRunnerError(f"{context} route maps are invalid")
    key = payload["encryptionKey"]
    if not isinstance(key, str) or len(key) != 44:
        raise QualityRunnerError(f"{context} encryption key is invalid")
    try:
        decoded = base64.b64decode(key, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise QualityRunnerError(f"{context} encryption key is invalid") from exc
    if (
        len(decoded) != 32
        or not any(decoded)
        or base64.b64encode(decoded).decode("ascii") != key
    ):
        raise QualityRunnerError(f"{context} encryption key is invalid")
    return key


def _normalize_web_build_variant(
    relative: str,
    payload: bytes,
    *,
    expected_web_root: str | None,
    context: str,
) -> tuple[bytes, dict[str, object]]:
    if not payload or len(payload) > WEB_BUILD_VARIANT_MAX_BYTES:
        raise QualityRunnerError(f"{context} has an invalid size")

    if relative == ".next/prerender-manifest.json":
        manifest = _strict_web_variant_json(payload, context)
        preview = manifest.get("preview") if isinstance(manifest, dict) else None
        expected_fields = {
            "previewModeId": 32,
            "previewModeSigningKey": 64,
            "previewModeEncryptionKey": 64,
        }
        if not isinstance(preview, dict) or set(preview) != set(expected_fields):
            raise QualityRunnerError(f"{context} preview fields are invalid")
        normalized = payload
        values: list[str] = []
        for field, length in expected_fields.items():
            value = preview[field]
            if (
                not isinstance(value, str)
                or re.fullmatch(f"[0-9a-f]{{{length}}}", value) is None
                or int(value, 16) == 0
            ):
                raise QualityRunnerError(f"{context} preview key is invalid")
            values.append(value)
            normalized = _replace_web_variant_value(
                normalized,
                value,
                f"<WALKSAFE_{field.upper()}>".encode("ascii"),
                expected_count=1,
                context=context,
            )
        if len(set(values)) != len(values):
            raise QualityRunnerError(f"{context} preview keys are not distinct")
        return normalized, {"kind": "preview", "values": tuple(values)}

    if relative == ".next/required-server-files.json":
        manifest = _strict_web_variant_json(payload, context)
        config = manifest.get("config") if isinstance(manifest, dict) else None
        turbopack = config.get("turbopack") if isinstance(config, dict) else None
        roots = (
            manifest.get("appDir") if isinstance(manifest, dict) else None,
            config.get("outputFileTracingRoot") if isinstance(config, dict) else None,
            turbopack.get("root") if isinstance(turbopack, dict) else None,
        )
        if roots[0] != roots[1] or roots[0] != roots[2]:
            raise QualityRunnerError(f"{context} build roots disagree")
        root = _validated_web_build_root(roots[0], expected_web_root, context)
        normalized = _replace_web_variant_value(
            payload,
            root,
            b"<WALKSAFE_WEB_BUILD_ROOT>",
            expected_count=3,
            context=context,
        )
        return normalized, {"kind": "root", "root": root}

    if relative == "server.js":
        prefix = b"const nextConfig = "
        lines = [line for line in payload.splitlines(keepends=True) if line.startswith(prefix)]
        if len(lines) != 1 or not lines[0].endswith(b"\n"):
            raise QualityRunnerError(f"{context} nextConfig assignment is invalid")
        config = _strict_web_variant_json(lines[0][len(prefix) : -1], context)
        turbopack = config.get("turbopack") if isinstance(config, dict) else None
        roots = (
            config.get("outputFileTracingRoot") if isinstance(config, dict) else None,
            turbopack.get("root") if isinstance(turbopack, dict) else None,
        )
        if roots[0] != roots[1]:
            raise QualityRunnerError(f"{context} build roots disagree")
        root = _validated_web_build_root(roots[0], expected_web_root, context)
        normalized = _replace_web_variant_value(
            payload,
            root,
            b"<WALKSAFE_WEB_BUILD_ROOT>",
            expected_count=2,
            context=context,
        )
        return normalized, {"kind": "root", "root": root}

    if relative == ".next/server/server-reference-manifest.json":
        manifest = _strict_web_variant_json(payload, context)
        key = _validated_rsc_key(manifest, context)
        normalized = _replace_web_variant_value(
            payload,
            key,
            b"<WALKSAFE_RSC_ENCRYPTION_KEY>",
            expected_count=1,
            context=context,
        )
        return normalized, {"kind": "rsc", "key": key}

    if relative == ".next/server/server-reference-manifest.js":
        prefix = b"self.__RSC_SERVER_MANIFEST="
        if not payload.startswith(prefix) or payload.count(prefix) != 1:
            raise QualityRunnerError(f"{context} wrapper is invalid")
        serialized = _strict_web_variant_json(payload[len(prefix) :], context)
        if not isinstance(serialized, str):
            raise QualityRunnerError(f"{context} wrapper is invalid")
        manifest = _strict_web_variant_json(serialized.encode("utf-8"), context)
        key = _validated_rsc_key(manifest, context)
        normalized = _replace_web_variant_value(
            payload,
            key,
            b"<WALKSAFE_RSC_ENCRYPTION_KEY>",
            expected_count=1,
            context=context,
        )
        return normalized, {"kind": "rsc", "key": key}

    raise QualityRunnerError(f"unsupported Web build variant file: {relative}")


def _verify_web_build_variants(
    *,
    repo_root: Path,
    expected: Mapping[str, Path],
    archived_payloads: Mapping[str, bytes],
) -> None:
    if set(archived_payloads) != set(WEB_BUILD_VARIANT_FILES):
        raise QualityRunnerError("Web release archive build-variant file set is incomplete")
    expected_root = str((repo_root / "apps" / "web").resolve())
    records: dict[str, dict[str, dict[str, object]]] = {"archive": {}, "fresh": {}}
    normalized: dict[str, dict[str, bytes]] = {"archive": {}, "fresh": {}}
    for relative in sorted(WEB_BUILD_VARIANT_FILES):
        source = expected.get(relative)
        if source is None or source.stat().st_size > WEB_BUILD_VARIANT_MAX_BYTES:
            raise QualityRunnerError("fresh Web build-variant file set is incomplete")
        payloads = {
            "archive": archived_payloads[relative],
            "fresh": source.read_bytes(),
        }
        for side, payload in payloads.items():
            normalized[side][relative], records[side][relative] = _normalize_web_build_variant(
                relative,
                payload,
                expected_web_root=expected_root if side == "fresh" else None,
                context=f"{side} Web {relative}",
            )
        if normalized["archive"][relative] != normalized["fresh"][relative]:
            raise QualityRunnerError(
                f"Web release archive differs from the fresh npm build: {relative}"
            )

    for side in ("archive", "fresh"):
        if (
            records[side][".next/required-server-files.json"]["root"]
            != records[side]["server.js"]["root"]
        ):
            raise QualityRunnerError(f"{side} Web build roots disagree across files")
        if (
            records[side][".next/server/server-reference-manifest.json"]["key"]
            != records[side][".next/server/server-reference-manifest.js"]["key"]
        ):
            raise QualityRunnerError(f"{side} Web RSC encryption keys disagree across files")

    archive_preview = records["archive"][".next/prerender-manifest.json"]["values"]
    fresh_preview = records["fresh"][".next/prerender-manifest.json"]["values"]
    if not isinstance(archive_preview, tuple) or not isinstance(fresh_preview, tuple):
        raise QualityRunnerError("Web preview key records are invalid")
    if any(left == right for left, right in zip(archive_preview, fresh_preview, strict=True)):
        raise QualityRunnerError("independent Web builds reused a preview key")
    if (
        records["archive"][".next/server/server-reference-manifest.json"]["key"]
        == records["fresh"][".next/server/server-reference-manifest.json"]["key"]
    ):
        raise QualityRunnerError("independent Web builds reused an RSC encryption key")


def _verify_web_archive_from_fresh_build(
    *, repo_root: Path, archive_path: Path, source_commit: str
) -> tuple[int, str]:
    expected = _fresh_web_files(repo_root, source_commit)
    observed: set[str] = set()
    archived_variants: dict[str, bytes] = {}
    candidate = archive_path.expanduser()
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(candidate, flags)
        with os.fdopen(descriptor, "rb") as archive_stream:
            before_stat = os.fstat(archive_stream.fileno())
            if not stat.S_ISREG(before_stat.st_mode) or before_stat.st_size <= 0:
                raise QualityRunnerError("Web release archive must be a nonempty regular file")
            before_digest = hashlib.sha256()
            for chunk in iter(lambda: archive_stream.read(1024 * 1024), b""):
                before_digest.update(chunk)
            archive_sha256 = before_digest.hexdigest()
            archive_stream.seek(0)

            with tarfile.open(fileobj=archive_stream, mode="r:gz") as archive:
                for member in archive:
                    relative = _archive_relative(member.name)
                    if relative is None:
                        if not member.isdir():
                            raise QualityRunnerError("Web release archive root is not a directory")
                        continue
                    relative_text = relative.as_posix()
                    if member.isdir():
                        continue
                    if not member.isreg() or relative_text in observed:
                        raise QualityRunnerError("Web release archive contains a duplicate or non-regular entry")
                    source = expected.get(relative_text)
                    if (
                        source is None
                        or member.mode != 0o644
                        or (
                            relative_text not in WEB_BUILD_VARIANT_FILES
                            and member.size != source.stat().st_size
                        )
                    ):
                        raise QualityRunnerError(
                            f"Web release archive differs from the fresh npm build: {relative_text}"
                        )
                    archived = archive.extractfile(member)
                    if archived is None:
                        raise QualityRunnerError("Web release archive entry cannot be read")
                    if relative_text in WEB_BUILD_VARIANT_FILES:
                        if member.size <= 0 or member.size > WEB_BUILD_VARIANT_MAX_BYTES:
                            raise QualityRunnerError(
                                f"Web release archive differs from the fresh npm build: {relative_text}"
                            )
                        payload = archived.read(WEB_BUILD_VARIANT_MAX_BYTES + 1)
                        if len(payload) != member.size:
                            raise QualityRunnerError("Web release archive entry has an invalid size")
                        archived_variants[relative_text] = payload
                    else:
                        digest = hashlib.sha256()
                        for chunk in iter(lambda: archived.read(1024 * 1024), b""):
                            digest.update(chunk)
                        if digest.hexdigest() != sha256_file(source):
                            raise QualityRunnerError(
                                f"Web release archive differs from the fresh npm build: {relative_text}"
                            )
                    observed.add(relative_text)

            _verify_web_build_variants(
                repo_root=repo_root,
                expected=expected,
                archived_payloads=archived_variants,
            )

            after_stat = os.fstat(archive_stream.fileno())
            archive_stream.seek(0)
            after_digest = hashlib.sha256()
            for chunk in iter(lambda: archive_stream.read(1024 * 1024), b""):
                after_digest.update(chunk)
            if (
                (after_stat.st_dev, after_stat.st_ino, after_stat.st_size)
                != (before_stat.st_dev, before_stat.st_ino, before_stat.st_size)
                or after_digest.hexdigest() != archive_sha256
            ):
                raise QualityRunnerError("Web release archive changed during fresh build verification")
            if candidate.is_symlink() or not candidate.is_file():
                raise QualityRunnerError("Web release archive changed during fresh build verification")
            path_stat = candidate.stat()
            if (
                (path_stat.st_dev, path_stat.st_ino, path_stat.st_size)
                != (before_stat.st_dev, before_stat.st_ino, before_stat.st_size)
                or sha256_file(candidate) != archive_sha256
            ):
                raise QualityRunnerError("Web release archive changed during fresh build verification")
    except (OSError, tarfile.TarError) as exc:
        raise QualityRunnerError("Web release archive is not a readable gzip tar archive") from exc
    if observed != set(expected):
        raise QualityRunnerError("Web release archive file set differs from the fresh npm build")
    return before_stat.st_size, archive_sha256


def _require_outputs_unchanged(
    *,
    repo_root: Path,
    definitions: Sequence[Mapping[str, str]],
    arguments: Mapping[str, str],
    snapshots: Mapping[str, tuple[int, str]],
) -> None:
    for definition in definitions:
        name = definition["name"]
        if "path" in definition:
            relative = _safe_relative(definition["path"], f"output {name}")
            path = repo_root.joinpath(*relative.parts)
        else:
            path = Path(arguments[definition["path_argument"]])
        current = _regular_file(path, f"output {name}", nonempty=True)
        if snapshots[name] != (current.stat().st_size, sha256_file(current)):
            raise QualityRunnerError(f"quality output changed after verification: {name}")


def _locked_versions(lock_path: Path) -> dict[str, str]:
    logical: list[str] = []
    pending = ""
    for raw_line in lock_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.endswith("\\"):
            pending += line[:-1].strip() + " "
            continue
        logical.append((pending + line).strip())
        pending = ""
    if pending:
        raise QualityRunnerError("tested environment lock has an incomplete continuation")
    pattern = re.compile(
        r"^([A-Za-z0-9_.-]+)(?:\[[A-Za-z0-9_,.-]+\])?==([^\s;]+)"
        r"((?:\s+--hash=sha256:[0-9a-f]{64})+)$"
    )
    versions: dict[str, str] = {}
    for line in logical:
        matched = pattern.fullmatch(line)
        if matched is None:
            raise QualityRunnerError("tested environment lock is not exactly pinned with SHA-256 hashes")
        name = re.sub(r"[-_.]+", "-", matched.group(1)).lower()
        if name in versions:
            raise QualityRunnerError("tested environment lock contains a duplicate distribution")
        versions[name] = matched.group(2)
    if not versions:
        raise QualityRunnerError("tested environment lock contains no distributions")
    return versions


def _tested_environment_record(
    *,
    repo_root: Path,
    lock_relatives: Sequence[str],
    expected_site_packages: Mapping[str, object] | None,
    trusted_tool_distributions: Mapping[str, str],
    installed_distributions: Mapping[str, str] | None,
) -> tuple[dict[str, object] | None, object]:
    if not lock_relatives:
        if trusted_tool_distributions or expected_site_packages is not None:
            raise QualityRunnerError(
                "trusted Python tools and site closure require a tested environment lock"
            )
        return None, None
    if expected_site_packages is None:
        raise QualityRunnerError("tested Python site closure policy is missing")
    locked: dict[str, str] = {}
    lock_records: list[dict[str, object]] = []
    for lock_relative in lock_relatives:
        relative = _safe_relative(lock_relative, "tested environment lock")
        lock = _regular_file(repo_root.joinpath(*relative.parts), "tested environment lock", nonempty=True)
        for name, version in _locked_versions(lock).items():
            if name in locked and locked[name] != version:
                raise QualityRunnerError("tested environment locks disagree on a distribution version")
            locked[name] = version
        lock_records.append(
            _file_record(
                lock,
                label="tested environment lock",
                display_path=f"repository:{relative.as_posix()}",
            )
        )
    trusted_tools = dict(trusted_tool_distributions)
    collisions = sorted(set(locked) & set(trusted_tools))
    if collisions:
        raise QualityRunnerError(
            f"trusted Python tools collide with fixed locks: {collisions[:3]}"
        )
    expected = {**locked, **trusted_tools}
    try:
        site_environment, _site_roots, site_identity = isolated_python.inspect_site_environment(
            expected,
            installed_distributions=installed_distributions,
        )
        if installed_distributions is None:
            isolated_python.require_expected_site_packages(
                site_environment,
                expected_site_packages,
            )
        else:
            observed_site_packages = site_environment["site_packages"]
            site_environment["site_packages"] = {
                "format": expected_site_packages["format"],
                "roots": observed_site_packages["roots"],
                "closure": expected_site_packages["closure"],
            }
    except isolated_python.IsolatedPythonError as exc:
        raise QualityRunnerError(str(exc)) from exc
    locked_canonical = "".join(
        f"{name}=={locked[name]}\n" for name in sorted(locked)
    ).encode("utf-8")
    python = Path(sys.executable).resolve()
    return (
        {
            "python": _file_record(
                python,
                label="tested Python interpreter",
                display_path=str(python),
            ),
            "locks": lock_records,
            "locked_distributions": {
                "count": len(locked),
                "sha256": hashlib.sha256(locked_canonical).hexdigest(),
            },
            "installed_distributions": site_environment["installed_distributions"],
            "site_packages": site_environment["site_packages"],
        },
        site_identity,
    )


def run_product_quality(
    *,
    repo_root: Path,
    product: str,
    receipt_path: Path,
    arguments: Mapping[str, str],
    executor: Executor = _default_executor,
    ambient_environment: Mapping[str, str] | None = None,
    policy_path: Path = POLICY_PATH,
    expected_policy_sha256: str = EXPECTED_POLICY_SHA256,
    runner_path: Path | None = None,
    installed_distributions: Mapping[str, str] | None = None,
    node_bin_dir: Path | None = None,
    node_toolchain_attestor: NodeToolchainAttestor = _DEFAULT_NODE_TOOLCHAIN_ATTESTOR,
) -> dict[str, object]:
    if executor is _default_executor:
        try:
            require_isolated_python("WalkSafe product quality CLI")
        except ReleaseIntegrityError as exc:
            raise QualityRunnerError(str(exc)) from exc
        if installed_distributions is not None:
            raise QualityRunnerError(
                "installed distribution injection is forbidden for production quality execution"
            )
        if node_toolchain_attestor is not _DEFAULT_NODE_TOOLCHAIN_ATTESTOR:
            raise QualityRunnerError(
                "Node toolchain attestor injection is forbidden for production quality execution"
            )
    root = repo_root.expanduser().resolve()
    if root.is_symlink() or not (root / ".git").exists():
        raise QualityRunnerError("repo_root must be a real Git worktree")
    if product not in PRODUCTS:
        raise QualityRunnerError("product is not supported by the fixed policy")
    locked_node_bin: Path | None = None
    locked_node_root: Path | None = None
    if product == "web":
        locked_node_bin, locked_node_root = _resolve_node_bin_dir(node_bin_dir)
    elif node_bin_dir is not None:
        raise QualityRunnerError("node_bin_dir is only valid for Web quality execution")
    policy, policy_record = _load_fixed_policy(policy_path, expected_policy_sha256)
    definition = policy["products"][product]
    resolved_arguments = _resolve_arguments(product, definition["required_arguments"], arguments)
    ambient = os.environ if ambient_environment is None else ambient_environment
    actual_runner = Path(__file__) if runner_path is None else runner_path
    runner_record = _file_record(
        actual_runner,
        label="quality runner",
        display_path="scripts/run_walksafe_product_quality_20260713.py",
    )
    bootstrap_path = root / "scripts/run_walksafe_isolated_python_20260713.py"
    bootstrap_record = _file_record(
        bootstrap_path,
        label="isolated Python bootstrap",
        display_path="scripts/run_walksafe_isolated_python_20260713.py",
    )
    node_toolchain_record: dict[str, object] | None = None
    node_lock_path = root / "configs/walksafe_node_toolchain_lock_20260715.json"
    node_checker_path = root / "scripts/check_walksafe_node_toolchain_20260715.py"
    if product == "web":
        assert locked_node_root is not None
        node_lock_record = _file_record(
            node_lock_path,
            label="Node toolchain lock",
            display_path="configs/walksafe_node_toolchain_lock_20260715.json",
        )
        node_checker_record = _file_record(
            node_checker_path,
            label="Node toolchain checker",
            display_path="scripts/check_walksafe_node_toolchain_20260715.py",
        )
        if node_checker_record["sha256"] != _NODE_TOOLCHAIN_CHECKER_SHA256:
            raise QualityRunnerError("Node toolchain checker differs from its runner pin")
        node_toolchain_before = _attest_node_toolchain(
            node_toolchain_attestor,
            locked_node_root,
            node_lock_path,
        )
        node_toolchain_record = {
            "checker": node_checker_record,
            "lock": node_lock_record,
            "before": node_toolchain_before,
        }
    try:
        source_before = verify_exact_git_source(
            root, context="quality source dirty before quality execution"
        )
    except ReleaseIntegrityError as exc:
        raise QualityRunnerError(str(exc)) from exc
    commit_before = source_before.commit
    git_tree_before = source_before.tree
    inputs_before = source_before.inventory

    output = receipt_path.expanduser().absolute()
    if output == root or root in output.parents:
        raise QualityRunnerError("receipt output must be outside the quality source")
    if (
        output.parent.is_symlink()
        or not output.parent.is_dir()
        or output.parent.resolve() != output.parent
    ):
        raise QualityRunnerError("receipt output parent must be an existing real directory")
    if output.is_symlink() or output.exists():
        raise QualityRunnerError("receipt output already exists")
    logs_path = output.parent / f"{output.stem}.logs"
    if logs_path.is_symlink() or logs_path.exists():
        raise QualityRunnerError("receipt log directory already exists")

    lock_relatives = [
        relative
        for relative in (
            definition["tested_environment_lock"],
            definition["tested_tool_lock"],
        )
        if relative is not None
    ]
    tested_environment_before, tested_environment_identity_before = _tested_environment_record(
        repo_root=root,
        lock_relatives=lock_relatives,
        expected_site_packages=definition["tested_site_packages"],
        trusted_tool_distributions=definition["trusted_tool_distributions"],
        installed_distributions=installed_distributions,
    )

    private_home_context = tempfile.TemporaryDirectory(prefix="walksafe-quality-home-")
    private_home = Path(private_home_context.name)
    os.chmod(private_home, 0o700)
    substitutions = {
        "{python}": str(Path(sys.executable).absolute()),
        "{repo_root}": str(root),
        "{source_commit}": commit_before,
        "{android_gateway_origin}": resolved_arguments.get("android_gateway_origin", ""),
        "{private_quality_home}": str(private_home),
    }

    try:
        temporary_logs = Path(tempfile.mkdtemp(prefix=f".{output.stem}.logs.", dir=output.parent))
    except Exception:
        private_home_context.cleanup()
        raise
    step_records: list[dict[str, object]] = []
    produced_output_snapshots: dict[str, tuple[int, str]] = {}
    try:
        for step in definition["steps"]:
            step_id = step["id"]
            if product == "web" and step_id == "npm-build":
                build_root = root / "apps" / "web" / ".next"
                if build_root.is_symlink():
                    raise QualityRunnerError("Web build output root must not be a symlink")
                try:
                    if build_root.exists():
                        shutil.rmtree(build_root, ignore_errors=False)
                except OSError as exc:
                    raise QualityRunnerError(
                        "Web build output root could not be cleared before npm build"
                    ) from exc
                if os.path.lexists(build_root):
                    raise QualityRunnerError(
                        "Web build output root could not be cleared before npm build"
                    )
            if product == "android" and step_id == "assemble-release":
                apk = root / "apps/android/app/build/outputs/apk/release/app-release-unsigned.apk"
                if apk.is_symlink():
                    raise QualityRunnerError("Android assemble output must not be a symlink")
                apk.unlink(missing_ok=True)
            cwd_relative, cwd = _repository_directory(root, step["cwd"], f"step cwd {product}/{step_id}")
            environment, environment_record = _build_environment(
                policy=policy,
                step=step,
                ambient=ambient,
                substitutions=substitutions,
                node_bin_dir=locked_node_bin,
            )
            argv = [
                _expand_token(value, substitutions, f"argv {product}/{step_id}")
                for value in step["argv"]
            ]
            argv, executable_record = _resolve_executable(argv, cwd, environment)
            executable_path = Path(executable_record["resolved_path"])
            try:
                with FileSnapshot.capture(
                    executable_path,
                    context=f"quality executable {product}/{step_id}",
                    private_mode=0o500,
                ) as executable_snapshot:
                    if (
                        executable_snapshot.size != executable_record["bytes"]
                        or executable_snapshot.sha256 != executable_record["sha256"]
                    ):
                        raise QualityRunnerError(
                            f"quality executable changed before step: {product}/{step_id}"
                        )
                    if executor is _default_executor:
                        result = _default_executor(
                            tuple(argv),
                            cwd,
                            environment,
                            executable_snapshot=executable_snapshot,
                        )
                    else:
                        result = executor(tuple(argv), cwd, environment)
                    if not executable_snapshot.matches_path(executable_path):
                        raise QualityRunnerError(
                            f"quality executable changed during step: {product}/{step_id}"
                        )
            except ReleaseIntegrityError as exc:
                raise QualityRunnerError(str(exc)) from exc
            if not isinstance(result, ExecutionResult):
                raise QualityRunnerError("quality executor returned an invalid result")
            if result.returncode != 0:
                raise QualityRunnerError(f"quality step {product}/{step_id} failed with exit code {result.returncode}")
            if product == "android" and step_id == "assemble-release":
                apk = _regular_file(
                    root / "apps/android/app/build/outputs/apk/release/app-release-unsigned.apk",
                    "fresh Android assemble output",
                    nonempty=True,
                )
                produced_output_snapshots["unsigned_apk"] = (apk.stat().st_size, sha256_file(apk))
            log_payload = {
                "schema_version": STEP_LOG_SCHEMA,
                "product": product,
                "step_id": step_id,
                "argv": argv,
                "cwd": cwd_relative.as_posix(),
                "environment": environment_record,
                "executable": executable_record,
                "exit_code": 0,
                "result": "passed",
            }
            log_file = temporary_logs / f"{len(step_records) + 1:02d}-{step_id}.json"
            _write_bytes(log_file, _json_bytes(log_payload))
            step_records.append(
                {
                    "id": step_id,
                    "argv": argv,
                    "cwd": cwd_relative.as_posix(),
                    "environment": environment_record,
                    "executable": executable_record,
                    "exit_code": 0,
                    "result": "passed",
                    "log": _file_record(
                        log_file,
                        label=f"quality log {product}/{step_id}",
                        display_path=f"{logs_path.name}/{log_file.name}",
                    ),
                }
            )

        if product == "web":
            produced_output_snapshots["web_release_archive"] = _verify_web_archive_from_fresh_build(
                repo_root=root,
                archive_path=Path(resolved_arguments["web_release_archive"]),
                source_commit=commit_before,
            )
            assert locked_node_root is not None and node_toolchain_record is not None
            node_toolchain_after = _attest_node_toolchain(
                node_toolchain_attestor,
                locked_node_root,
                node_lock_path,
            )
            if node_toolchain_after != node_toolchain_record["before"]:
                raise QualityRunnerError("Node toolchain changed during Web quality execution")
            node_toolchain_record["after"] = node_toolchain_after
        if product == "android":
            apk = _regular_file(
                root / "apps/android/app/build/outputs/apk/release/app-release-unsigned.apk",
                "fresh Android assemble output",
                nonempty=True,
            )
            if produced_output_snapshots.get("unsigned_apk") != (apk.stat().st_size, sha256_file(apk)):
                raise QualityRunnerError("Android assemble output changed after its producing step")
        outputs = _collect_outputs(repo_root=root, definitions=definition["outputs"], arguments=resolved_arguments)
        output_snapshots = {
            str(record["name"]): (int(record["bytes"]), str(record["sha256"]))
            for record in outputs
        }
        for name, snapshot in produced_output_snapshots.items():
            if output_snapshots.get(name) != snapshot:
                raise QualityRunnerError(f"quality output changed after its bound step verification: {name}")
        tested_environment_after, tested_environment_identity_after = _tested_environment_record(
            repo_root=root,
            lock_relatives=lock_relatives,
            expected_site_packages=definition["tested_site_packages"],
            trusted_tool_distributions=definition["trusted_tool_distributions"],
            installed_distributions=installed_distributions,
        )
        if (
            tested_environment_after != tested_environment_before
            or tested_environment_identity_after != tested_environment_identity_before
        ):
            raise QualityRunnerError("tested Python site closure changed during quality execution")
        tested_environment = tested_environment_before
        try:
            source_after = verify_exact_git_source(
                root,
                context="quality source dirty after quality execution",
                expected_commit=commit_before,
                expected_tree=git_tree_before,
                allowed_ignored_roots=DECLARED_IGNORED_OUTPUT_ROOTS[product],
            )
        except ReleaseIntegrityError as exc:
            raise QualityRunnerError(str(exc)) from exc
        commit_after = source_after.commit
        git_tree_after = source_after.tree
        inputs_after = source_after.inventory
        if (commit_after, git_tree_after, inputs_after) != (commit_before, git_tree_before, inputs_before):
            raise QualityRunnerError("source identity changed during quality execution")
        if sha256_file(_regular_file(policy_path, "quality policy", nonempty=True)) != policy_record["sha256"]:
            raise QualityRunnerError("quality policy changed during execution")
        if sha256_file(_regular_file(actual_runner, "quality runner", nonempty=True)) != runner_record["sha256"]:
            raise QualityRunnerError("quality runner changed during execution")
        if sha256_file(
            _regular_file(bootstrap_path, "isolated Python bootstrap", nonempty=True)
        ) != bootstrap_record["sha256"]:
            raise QualityRunnerError("isolated Python bootstrap changed during execution")
        if product == "web":
            assert node_toolchain_record is not None
            if _file_record(
                node_lock_path,
                label="Node toolchain lock",
                display_path="configs/walksafe_node_toolchain_lock_20260715.json",
            ) != node_toolchain_record["lock"]:
                raise QualityRunnerError("Node toolchain lock changed during execution")
            if _file_record(
                node_checker_path,
                label="Node toolchain checker",
                display_path="scripts/check_walksafe_node_toolchain_20260715.py",
            ) != node_toolchain_record["checker"]:
                raise QualityRunnerError("Node toolchain checker changed during execution")
        _require_outputs_unchanged(
            repo_root=root,
            definitions=definition["outputs"],
            arguments=resolved_arguments,
            snapshots=output_snapshots,
        )

        public_arguments: dict[str, str] = {}
        if "android_gateway_origin" in resolved_arguments:
            public_arguments["android_gateway_origin"] = resolved_arguments["android_gateway_origin"]
        if "web_release_archive" in resolved_arguments:
            public_arguments["web_release_archive"] = Path(resolved_arguments["web_release_archive"]).name
        receipt: dict[str, object] = {
            "schema_version": RECEIPT_SCHEMA,
            "product": product,
            "exit_code": 0,
            "result": "passed",
            "source": {
                "commit": commit_before,
                "git_tree": git_tree_before,
                "tree_clean_before": True,
                "tree_clean_after": True,
            },
            "provenance": {
                "runner": runner_record,
                "bootstrap": bootstrap_record,
                "policy": policy_record,
            },
            "inputs": {
                "tracked_source_before": inputs_before,
                "tracked_source_after": inputs_after,
            },
            "tested_environment": tested_environment,
            "node_toolchain": node_toolchain_record,
            "public_arguments": public_arguments,
            "steps": step_records,
            "outputs": outputs,
        }
        try:
            prepared_logs = exact_directory_record(
                temporary_logs,
                context="quality step logs before publication",
            )
        except ReleaseIntegrityError as exc:
            raise QualityRunnerError(str(exc)) from exc
        try:
            exclusive_directory_publish(temporary_logs, logs_path)
        except ReleaseIntegrityError as exc:
            raise QualityRunnerError(str(exc)) from exc
        try:
            if exact_directory_record(
                logs_path,
                context="quality step logs after publication",
            ) != prepared_logs:
                raise QualityRunnerError("quality step logs changed during publication")
        except ReleaseIntegrityError as exc:
            raise QualityRunnerError(str(exc)) from exc
        _atomic_write(output, _json_bytes(receipt))
        return receipt
    except Exception:
        raise
    finally:
        if temporary_logs.exists():
            shutil.rmtree(temporary_logs, ignore_errors=True)
        private_home_context.cleanup()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--product", required=True, choices=sorted(PRODUCTS))
    parser.add_argument("--receipt", required=True, type=Path)
    parser.add_argument("--web-release-archive", type=Path)
    parser.add_argument("--node-bin-dir", type=Path)
    parser.add_argument("--android-gateway-origin")
    return parser.parse_args()


def main() -> int:
    try:
        require_isolated_python("WalkSafe product quality CLI")
    except ReleaseIntegrityError as exc:
        print(f"WalkSafe product quality FAIL: {exc}", file=sys.stderr)
        return 2
    args = parse_args()
    if args.product == "web":
        print(
            "BLOCKED: Legacy Web/PWA product quality execution is LEGACY_REFERENCE_ONLY under FP-009.",
            file=sys.stderr,
        )
        return 78
    arguments: dict[str, str] = {}
    if args.web_release_archive is not None:
        arguments["web_release_archive"] = str(args.web_release_archive)
    if args.android_gateway_origin is not None:
        arguments["android_gateway_origin"] = args.android_gateway_origin
    try:
        receipt = run_product_quality(
            repo_root=REPO_ROOT,
            product=args.product,
            receipt_path=args.receipt,
            arguments=arguments,
            node_bin_dir=args.node_bin_dir,
        )
    except (OSError, UnicodeError, ValueError, QualityRunnerError) as exc:
        print(f"WalkSafe product quality FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"receipt={args.receipt.expanduser().absolute()}")
    print(f"receipt_sha256={sha256_file(args.receipt.expanduser().absolute())}")
    print(f"source_commit={receipt['source']['commit']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
