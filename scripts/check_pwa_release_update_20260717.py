#!/usr/bin/env python3
"""Prove a service-worker transition between two source-bound Web releases.

Both releases must be preserved v4 standalone artifacts, and the candidate
must come from an exact clean Git HEAD whose strict ancestor is the baseline.
This local headless check does not replace an HTTPS mobile field receipt.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import math
import os
import re
import secrets
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import types
import urllib.request
from datetime import UTC, datetime
from pathlib import Path, PurePosixPath
from typing import Any, NamedTuple
from urllib.parse import urlsplit

import websockets

from check_pwa_browser_lifecycle_20260711 import (
    acquire_lifecycle_lock,
    cdp_call,
    evaluate,
    new_lifecycle_credentials,
    require_isolated_loopback_ports,
    select_launched_chrome_page,
    stop_owned_process as _stop_owned_process,
)
from check_pwa_server_e2e import (
    CheckFailed,
    chrome_binary,
    wait_for_http,
)


REPO_ROOT = Path(__file__).resolve().parents[1]
COMMIT = re.compile(r"[0-9a-f]{40}")
SHA256 = re.compile(r"[0-9a-f]{64}")
MAXIMUM_MANIFEST_BYTES = 64 * 1024 * 1024
PROCESS_GROUP_RELEASE_TIMEOUT_SECONDS = 8.0
PROCESS_ACQUISITION_SIGNALS = {signal.SIGHUP, signal.SIGINT, signal.SIGTERM}
EXPECTED_WEB_BUILD_ENVIRONMENT = {
    "NEXT_PUBLIC_DETECTOR_MODE": "server-v2",
    "NEXT_PUBLIC_WALKSAFE_PWA_ENABLED": "true",
    "NEXT_PUBLIC_WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING": "false",
    "NEXT_PUBLIC_WALKSAFE_TEST_CAPTURE_PANEL": "false",
}
BASE_CHILD_ENVIRONMENT = {
    "PATH": "/usr/bin:/bin",
    "HOME": "/nonexistent",
    "LANG": "C",
    "LC_ALL": "C",
    "TZ": "UTC",
}
WEB_CHILD_ENVIRONMENT_KEYS = {
    "NEXT_PUBLIC_WALKSAFE_PWA_ENABLED",
    "NEXT_PUBLIC_DETECTOR_MODE",
    "BACKEND_API_BASE_URL",
    "VOICE_API_BASE_URL",
    "WALKSAFE_FIELD_TEST_TOKEN",
    "WALKSAFE_FIELD_ACCOUNTS_JSON",
    "WALKSAFE_GATEWAY_SESSION_SECRET",
    "WALKSAFE_ALLOW_INSECURE_LOCAL_DEV",
    "WALKSAFE_GATEWAY_RATE_LIMIT_DIR",
    "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER",
    "WALKSAFE_ENVIRONMENT",
    "WALKSAFE_WEB_REPLICAS",
    "WALKSAFE_FIELD_LOG_DIR",
    "NODE_ENV",
}
WEB_MANIFEST_FIELDS = {
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
WEB_INPUT_NAMES = {"package_json", "package_lock", "node_toolchain_lock"}
WEB_QUALITY_RECEIPT_NAMES = {
    "npm-ci",
    "npm-audit",
    "npm-lint",
    "npm-typecheck",
    "npm-test",
    "npm-build",
    "runtime-trace",
    "browser-lifecycle",
    "node-toolchain",
    "node-toolchain-post",
}
ROOT_SHELL_RELATIVE_PATH = ".next/server/app/index.html"
_INTEGRITY_SHA256 = "b084292b5a6e4befd82aea22ceac899527ab1ba1ffe51b64fa022c831b72a18e"
_WEB_MANIFEST_HELPER_SHA256 = "b3aa06e3e56d0af31a852f8410c9f87a0d597f1802426bfae566dc9137b95697"


def clean_child_environment(additions: dict[str, str] | None = None) -> dict[str, str]:
    environment = dict(BASE_CHILD_ENVIRONMENT)
    if additions:
        environment.update(additions)
    return environment


class DeferredProcessAcquisitionSignal(SystemExit):
    def __init__(self, signal_number: int) -> None:
        super().__init__(128 + signal_number)
        self.signal_number = signal_number


class OwnedProcessSignalGuard:
    def __init__(self) -> None:
        self.installed = False
        self._previous_handlers: dict[signal.Signals, Any] = {}

    @staticmethod
    def _defer(signal_number: int, _frame: Any) -> None:
        raise DeferredProcessAcquisitionSignal(signal_number)

    def install(self) -> None:
        if self.installed:
            raise CheckFailed("owned-process signal guard is already installed")
        if not hasattr(signal, "pthread_sigmask"):
            raise CheckFailed("signal masking is required for owned-process acquisition")
        if threading.current_thread() is not threading.main_thread() or threading.active_count() != 1:
            raise CheckFailed("owned-process acquisition requires one main Python thread")
        previous_mask = signal.pthread_sigmask(signal.SIG_BLOCK, PROCESS_ACQUISITION_SIGNALS)
        try:
            for signal_number in PROCESS_ACQUISITION_SIGNALS:
                self._previous_handlers[signal_number] = signal.signal(
                    signal_number,
                    self._defer,
                )
            self.installed = True
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
        except BaseException:
            signal.pthread_sigmask(signal.SIG_BLOCK, PROCESS_ACQUISITION_SIGNALS)
            for signal_number, previous_handler in self._previous_handlers.items():
                signal.signal(signal_number, previous_handler)
            self._previous_handlers.clear()
            self.installed = False
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)
            raise

    def block_for_spawn(self) -> set[signal.Signals]:
        if not self.installed:
            raise CheckFailed("owned-process signal guard is not installed")
        return signal.pthread_sigmask(signal.SIG_BLOCK, PROCESS_ACQUISITION_SIGNALS)

    def restore_spawn_mask(self, previous_mask: set[signal.Signals]) -> None:
        signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)

    def close(self) -> None:
        if not self.installed:
            return
        previous_mask = signal.pthread_sigmask(signal.SIG_BLOCK, PROCESS_ACQUISITION_SIGNALS)
        try:
            for signal_number, previous_handler in self._previous_handlers.items():
                signal.signal(signal_number, previous_handler)
            self._previous_handlers.clear()
            self.installed = False
        finally:
            signal.pthread_sigmask(signal.SIG_SETMASK, previous_mask)


def _load_pinned_source(module_name: str, path: Path, expected_sha256: str) -> types.ModuleType:
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
    identity = lambda item: (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
    if (
        not stat.S_ISREG(current.st_mode)
        or identity(before) != identity(after)
        or identity(after) != identity(current)
        or not payload
        or hashlib.sha256(payload).hexdigest() != expected_sha256
    ):
        raise RuntimeError(f"local source module differs from its pin: {candidate.name}")
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
    "_walksafe_release_integrity_for_pwa_update",
    Path(__file__).resolve().parent / "walksafe_release_integrity.py",
    _INTEGRITY_SHA256,
)
_web_manifest_helper = _load_pinned_source(
    "_walksafe_web_manifest_helper_for_pwa_update",
    Path(__file__).resolve().parent / "create_walksafe_web_build_manifest_20260711.py",
    _WEB_MANIFEST_HELPER_SHA256,
)
ReleaseIntegrityError = _integrity.ReleaseIntegrityError
strict_json_bytes = _integrity.strict_json_bytes
verify_exact_git_source = _integrity.verify_exact_git_source
exclusive_atomic_publish = _integrity.exclusive_atomic_publish
create_web_manifest = _web_manifest_helper.create_manifest


class ReleaseArtifact(NamedTuple):
    manifest_path: Path
    build_root: Path
    deployment_archive: Path
    source_commit: str
    manifest_sha256: str
    manifest_bytes: int
    deployment_archive_sha256: str
    deployment_archive_bytes: int
    build_root_sha256: str
    build_root_bytes: int
    build_root_file_count: int
    root_shell_sha256: str
    node_version: str
    node_sha256: str
    node_bytes: int


class GitDirectoryBoundary(NamedTuple):
    git_directory: Path
    git_identity: tuple[int, int]
    object_root: Path
    object_identity: tuple[int, int]


def _runtime_file_identity(metadata: os.stat_result) -> tuple[int, int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _sha256_descriptor(descriptor: int, size: int) -> str:
    digest = hashlib.sha256()
    offset = 0
    while offset < size:
        chunk = os.pread(descriptor, min(1024 * 1024, size - offset), offset)
        if not chunk:
            raise CheckFailed("verified Node descriptor ended before its attested size")
        digest.update(chunk)
        offset += len(chunk)
    if os.pread(descriptor, 1, size):
        raise CheckFailed("verified Node descriptor grew beyond its attested size")
    return digest.hexdigest()


class VerifiedNodeRuntime:
    def __init__(
        self,
        *,
        path: Path,
        descriptor: int,
        identity: tuple[int, int, int, int, int, int],
        sha256: str,
        size: int,
        version: str,
    ) -> None:
        self.path = path
        self.descriptor = descriptor
        self.identity = identity
        self.sha256 = sha256
        self.size = size
        self.version = version

    @property
    def executable(self) -> str:
        return f"/proc/self/fd/{self.descriptor}"

    def verify_identity(self) -> None:
        try:
            opened = os.fstat(self.descriptor)
            current = self.path.lstat()
            resolved = self.path.resolve(strict=True)
        except (OSError, RuntimeError) as exc:
            raise CheckFailed("verified Node runtime identity is unavailable") from exc
        if (
            resolved != self.path
            or not stat.S_ISREG(current.st_mode)
            or stat.S_ISLNK(current.st_mode)
            or _runtime_file_identity(opened) != self.identity
            or _runtime_file_identity(current) != self.identity
            or _sha256_descriptor(self.descriptor, self.size) != self.sha256
        ):
            raise CheckFailed("verified Node runtime identity or bytes changed")

    def verify_manifest_binding(self, releases: tuple[ReleaseArtifact, ReleaseArtifact]) -> None:
        expected = {(release.node_sha256, release.node_bytes, release.node_version) for release in releases}
        if len(expected) != 1 or next(iter(expected)) != (self.sha256, self.size, self.version):
            raise CheckFailed("Web manifests differ from the open verified Node runtime")

    def verify_execution(self, releases: tuple[ReleaseArtifact, ReleaseArtifact]) -> None:
        self.verify_manifest_binding(releases)
        self.verify_identity()
        try:
            result = subprocess.run(
                [self.executable, "--version"],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                env=clean_child_environment(),
                pass_fds=(self.descriptor,),
            )
        except (OSError, subprocess.CalledProcessError) as exc:
            raise CheckFailed("Node runtime version could not be verified") from exc
        self.verify_identity()
        if result.stdout.strip() != self.version:
            raise CheckFailed("Node runtime version differs from the manifest-attested toolchain")

    def close(self) -> None:
        if self.descriptor >= 0:
            os.close(self.descriptor)
            self.descriptor = -1


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise CheckFailed(f"could not hash release file: {path}") from exc
    return digest.hexdigest()


def require_real_file(path: Path, label: str, *, maximum_bytes: int | None = None) -> Path:
    candidate = path.expanduser().absolute()
    try:
        metadata = candidate.lstat()
        resolved = candidate.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise CheckFailed(f"{label} is unavailable") from exc
    if candidate.is_symlink() or not candidate.is_file() or resolved != candidate:
        raise CheckFailed(f"{label} must be a real regular file")
    if metadata.st_size <= 0:
        raise CheckFailed(f"{label} must not be empty")
    if maximum_bytes is not None and metadata.st_size > maximum_bytes:
        raise CheckFailed(f"{label} exceeds its size limit")
    return candidate


def stable_file_record(path: Path, label: str, *, maximum_bytes: int | None = None) -> dict[str, Any]:
    candidate = require_real_file(path, label, maximum_bytes=maximum_bytes)
    try:
        before = candidate.stat()
        digest = sha256_file(candidate)
        after = candidate.stat()
    except OSError as exc:
        raise CheckFailed(f"{label} could not be inspected") from exc
    identity = lambda item: (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
    if identity(before) != identity(after):
        raise CheckFailed(f"{label} changed while being inspected")
    return {"path": candidate, "sha256": digest, "bytes": before.st_size}


def require_manifest_relative_path(raw: object, label: str) -> str:
    if (
        not isinstance(raw, str)
        or not raw
        or "\\" in raw
        or any(ord(character) < 0x20 or ord(character) == 0x7F for character in raw)
    ):
        raise CheckFailed(f"{label} is not a canonical relative path")
    relative = PurePosixPath(raw)
    if relative.is_absolute() or raw != relative.as_posix() or any(part in {"", ".", ".."} for part in relative.parts):
        raise CheckFailed(f"{label} is not a canonical relative path")
    return raw


def read_manifest(path: Path, label: str) -> tuple[dict[str, Any], dict[str, Any]]:
    record = stable_file_record(path, label, maximum_bytes=MAXIMUM_MANIFEST_BYTES)
    try:
        raw = record["path"].read_bytes()
        if len(raw) != record["bytes"] or sha256_bytes(raw) != record["sha256"]:
            raise CheckFailed(f"{label} changed after it was inspected")
        payload = strict_json_bytes(raw, context=label)
    except (OSError, ReleaseIntegrityError) as exc:
        raise CheckFailed(f"{label} is not strict UTF-8 JSON") from exc
    if not isinstance(payload, dict):
        raise CheckFailed(f"{label} must contain a JSON object")
    return payload, record


def release_manifest_commit(path: Path, label: str) -> str:
    manifest, _ = read_manifest(path, f"{label} Web manifest")
    if set(manifest) != WEB_MANIFEST_FIELDS:
        raise CheckFailed(f"{label} Web manifest field set is not canonical v4")
    source_commit = manifest.get("source_commit")
    if (
        manifest.get("schema_version") != "walksafe.web-build-manifest.v4"
        or not isinstance(source_commit, str)
        or COMMIT.fullmatch(source_commit) is None
        or manifest.get("build_id") != source_commit
    ):
        raise CheckFailed(f"{label} Web manifest source identity is invalid")
    return source_commit


def verify_manifested_file(
    record: object,
    bundle_root: Path,
    label: str,
    *,
    named: bool = False,
) -> dict[str, Any]:
    required_fields = {"path", "sha256", "bytes"} | ({"name"} if named else set())
    if not isinstance(record, dict) or set(record) != required_fields:
        raise CheckFailed(f"{label} record is invalid")
    relative = require_manifest_relative_path(record.get("path"), f"{label} path")
    digest = record.get("sha256")
    size = record.get("bytes")
    if (
        not isinstance(digest, str)
        or SHA256.fullmatch(digest) is None
        or isinstance(size, bool)
        or not isinstance(size, int)
        or size <= 0
    ):
        raise CheckFailed(f"{label} record is invalid")
    if named:
        name = record.get("name")
        if not isinstance(name, str) or not name:
            raise CheckFailed(f"{label} record is invalid")
    path = bundle_root.joinpath(*PurePosixPath(relative).parts)
    actual = stable_file_record(path, label)
    if actual["bytes"] != size:
        raise CheckFailed(f"{label} bytes differ from the Web manifest")
    if actual["sha256"] != digest:
        raise CheckFailed(f"{label} SHA-256 differs from the Web manifest")
    return actual


def verify_canonical_archive_metadata(archive: Path, build_root: Path, label: str) -> None:
    try:
        header = archive.read_bytes()[:10]
    except OSError as exc:
        raise CheckFailed(f"{label} deployment archive header could not be read") from exc
    if header != b"\x1f\x8b\x08\x00\x00\x00\x00\x00\x00\x03":
        raise CheckFailed(f"{label} deployment archive is not canonical gzip -n output")

    expected_items = [build_root]

    def append_component_order(directory: Path) -> None:
        for item in sorted(directory.iterdir(), key=lambda child: child.name):
            expected_items.append(item)
            if item.is_dir():
                append_component_order(item)

    append_component_order(build_root)
    expected_names = [".", *(f"./{item.relative_to(build_root).as_posix()}" for item in expected_items[1:])]
    try:
        with tarfile.open(archive, mode="r:gz") as source:
            members = list(source)
    except (OSError, tarfile.TarError) as exc:
        raise CheckFailed(f"{label} deployment archive is unreadable") from exc
    if [member.name for member in members] != expected_names:
        raise CheckFailed(f"{label} deployment archive member order or set is not canonical")
    for item, member in zip(expected_items, members, strict=True):
        expected_mode = 0o755 if item.is_dir() else 0o644
        if (
            member.uid != 0
            or member.gid != 0
            or member.uname
            or member.gname
            or member.mtime != 0
            or member.pax_headers
            or member.mode != expected_mode
            or (item.is_dir() and not member.isdir())
            or (item.is_file() and not member.isreg())
        ):
            raise CheckFailed(f"{label} deployment archive metadata is not canonical: {member.name}")


def _require_canonical_executable(path: Path, label: str) -> Path:
    candidate = path.absolute()
    try:
        metadata = candidate.lstat()
    except OSError as exc:
        raise CheckFailed(f"canonical {label} executable is unavailable") from exc
    if (
        not stat.S_ISREG(metadata.st_mode)
        or stat.S_ISLNK(metadata.st_mode)
        or candidate.resolve(strict=True) != candidate
        or not os.access(candidate, os.X_OK)
    ):
        raise CheckFailed(f"canonical {label} executable is unavailable")
    return candidate


def verify_canonical_archive_bytes(archive: Path, build_root: Path, label: str) -> None:
    verify_canonical_archive_metadata(archive, build_root, label)
    tar = _require_canonical_executable(Path("/usr/bin/tar"), "tar")
    gzip = _require_canonical_executable(Path("/usr/bin/gzip"), "gzip")
    environment = clean_child_environment()
    try:
        with tempfile.TemporaryFile() as raw_tar, tempfile.TemporaryFile() as canonical_archive:
            tar_result = subprocess.run(
                [
                    str(tar),
                    "--sort=name",
                    "--mtime=UTC 1970-01-01",
                    "--owner=0",
                    "--group=0",
                    "--numeric-owner",
                    "-C",
                    str(build_root),
                    "-cf",
                    "-",
                    ".",
                ],
                check=False,
                stdout=raw_tar,
                stderr=subprocess.PIPE,
                env=environment,
            )
            if tar_result.returncode != 0:
                raise CheckFailed(f"{label} canonical tar bytes could not be regenerated")
            raw_tar.seek(0)
            gzip_result = subprocess.run(
                [str(gzip), "-n"],
                check=False,
                stdin=raw_tar,
                stdout=canonical_archive,
                stderr=subprocess.PIPE,
                env=environment,
            )
            if gzip_result.returncode != 0:
                raise CheckFailed(f"{label} canonical gzip bytes could not be regenerated")
            canonical_archive.seek(0)
            with archive.open("rb") as current:
                while True:
                    expected_chunk = canonical_archive.read(1024 * 1024)
                    current_chunk = current.read(1024 * 1024)
                    if expected_chunk != current_chunk:
                        raise CheckFailed(
                            f"{label} deployment archive differs from exact canonical tar.gz bytes"
                        )
                    if not expected_chunk:
                        break
    except OSError as exc:
        raise CheckFailed(f"{label} canonical deployment archive comparison failed") from exc


def read_quality_text(record: dict[str, Any], label: str) -> str:
    if record["bytes"] > 16 * 1024 * 1024:
        raise CheckFailed(f"{label} quality receipt exceeds its semantic size limit")
    try:
        payload = record["path"].read_bytes()
        text = payload.decode("utf-8")
    except (OSError, UnicodeError) as exc:
        raise CheckFailed(f"{label} quality receipt is not readable UTF-8") from exc
    if len(payload) != record["bytes"] or sha256_bytes(payload) != record["sha256"] or "\0" in text:
        raise CheckFailed(f"{label} quality receipt changed or is malformed")
    return text


def verify_quality_receipt_semantics(
    receipts: dict[str, dict[str, Any]],
    source_commit: str,
    label: str,
) -> None:
    texts = {name: read_quality_text(record, f"{label} {name}") for name, record in receipts.items()}
    if texts["npm-audit"].strip() != "found 0 vulnerabilities":
        raise CheckFailed(f"{label} npm-audit receipt does not prove zero vulnerabilities")
    required_markers = {
        "npm-ci": ("added ", "audited ", "found 0 vulnerabilities"),
        "npm-lint": ("eslint app lib types --max-warnings=0",),
        "npm-typecheck": ("tsc --noEmit",),
        "npm-test": (
            "bash ../../scripts/check_frontend_policy_suite.sh",
            "PWA install/update/offline shell policy checks passed",
            "walksafe test log policy checks passed",
        ),
        "npm-build": ("next build", "/api/release-identity", "/sw-version.js"),
    }
    for name, markers in required_markers.items():
        if any(marker not in texts[name] for marker in markers):
            raise CheckFailed(f"{label} {name} receipt is missing its success semantics")
    if re.fullmatch(r"Web runtime trace scope PASS: \d+ traces, \d+ unique files\s*", texts["runtime-trace"]) is None:
        raise CheckFailed(f"{label} runtime-trace receipt is not a canonical PASS")

    browser_text = texts["browser-lifecycle"]
    prefix = "PASS: PWA browser lifecycle, server-v2 privacy controls, and offline safety boundary\n"
    if not browser_text.startswith(prefix):
        raise CheckFailed(f"{label} browser-lifecycle receipt is not a canonical PASS")
    raw_json = browser_text[len(prefix) :]
    try:
        _, end = json.JSONDecoder().raw_decode(raw_json)
        browser = strict_json_bytes(raw_json[:end].encode("utf-8"), context=f"{label} browser lifecycle")
    except (json.JSONDecodeError, ReleaseIntegrityError) as exc:
        raise CheckFailed(f"{label} browser-lifecycle receipt JSON is invalid") from exc
    if not raw_json[end:].startswith("\nlogs="):
        raise CheckFailed(f"{label} browser-lifecycle receipt trailer is invalid")
    expected_cache = f"walksafe-assist-source-{source_commit}"
    if (
        not isinstance(browser, dict)
        or browser.get("schema_version") != "walksafe.pwa_browser_evidence.v2"
        or browser.get("service_worker_version") != f"source-{source_commit}"
        or browser.get("cache_names") != [expected_cache]
        or any(
            browser.get(name) is not True
            for name in (
                "manifest_installable",
                "service_worker_active",
                "service_worker_controls_page",
                "offline_shell_available",
                "offline_reload_hydrated",
                "offline_authentication_gate_only",
                "offline_safety_api_unavailable",
            )
        )
    ):
        raise CheckFailed(f"{label} browser-lifecycle receipt does not match the release source")


def verify_release_artifact(
    manifest_path: Path,
    build_root: Path,
    deployment_archive: Path,
    source_root: Path,
    *,
    label: str,
) -> ReleaseArtifact:
    manifest, manifest_record = read_manifest(manifest_path, f"{label} Web manifest")
    source_commit = manifest.get("source_commit")
    if set(manifest) != WEB_MANIFEST_FIELDS:
        raise CheckFailed(f"{label} Web manifest field set is not canonical v4")
    if (
        manifest.get("schema_version") != "walksafe.web-build-manifest.v4"
        or not isinstance(source_commit, str)
        or COMMIT.fullmatch(source_commit) is None
        or manifest.get("build_id") != source_commit
    ):
        raise CheckFailed(f"{label} Web manifest source identity is invalid")

    bundle_root = manifest_record["path"].parent
    if manifest_record["path"].name != "web-build-manifest.json":
        raise CheckFailed(f"{label} Web manifest must use its canonical filename")
    expected_root = bundle_root / f"web-standalone-{source_commit}"
    if build_root.expanduser().absolute() != expected_root:
        raise CheckFailed(f"{label} standalone build root is not the manifest-bound canonical path")
    root = build_root.expanduser().absolute()
    try:
        resolved_root = root.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise CheckFailed(f"{label} standalone build root is unavailable") from exc
    if root.is_symlink() or not root.is_dir() or resolved_root != root:
        raise CheckFailed(f"{label} standalone build root must be a real directory")
    if stat.S_IMODE(root.stat().st_mode) != 0o755:
        raise CheckFailed(f"{label} standalone build root mode is not canonical")

    inputs = manifest.get("inputs")
    if not isinstance(inputs, dict) or set(inputs) != WEB_INPUT_NAMES:
        raise CheckFailed(f"{label} Web manifest input set is invalid")
    input_paths = {
        name: verify_manifested_file(record, bundle_root, f"{label} Web input {name}")
        for name, record in inputs.items()
    }
    source_inputs = {
        "package_json": source_root / "apps/web/package.json",
        "package_lock": source_root / "apps/web/package-lock.json",
        "node_toolchain_lock": source_root / "configs/walksafe_node_toolchain_lock_20260715.json",
    }
    for name, source_path in source_inputs.items():
        source_record = stable_file_record(source_path, f"{label} source input {name}")
        if (source_record["sha256"], source_record["bytes"]) != (
            input_paths[name]["sha256"],
            input_paths[name]["bytes"],
        ):
            raise CheckFailed(f"{label} preserved Web input differs from the exact source: {name}")

    toolchain = manifest.get("toolchain")
    if (
        not isinstance(toolchain, dict)
        or set(toolchain) != {"node", "npm"}
        or not all(isinstance(toolchain.get(name), str) and toolchain[name].strip() for name in ("node", "npm"))
    ):
        raise CheckFailed(f"{label} Web manifest toolchain is invalid")

    quality_receipts = manifest.get("quality_receipts")
    if not isinstance(quality_receipts, list):
        raise CheckFailed(f"{label} Web manifest quality receipts are invalid")
    receipt_names = [receipt.get("name") if isinstance(receipt, dict) else None for receipt in quality_receipts]
    if receipt_names != sorted(WEB_QUALITY_RECEIPT_NAMES):
        raise CheckFailed(f"{label} Web manifest quality receipt set or order is invalid")
    receipt_paths = {
        receipt["name"]: verify_manifested_file(
            receipt,
            bundle_root,
            f"{label} Web quality receipt {receipt['name']}",
            named=True,
        )
        for receipt in quality_receipts
    }

    current_files: dict[str, Path] = {}
    try:
        for item in root.rglob("*"):
            relative = item.relative_to(root).as_posix()
            if item.is_symlink():
                raise CheckFailed(f"{label} standalone build contains a symlink: {relative}")
            if item.is_file():
                if stat.S_IMODE(item.stat().st_mode) != 0o644:
                    raise CheckFailed(f"{label} standalone file mode is not canonical: {relative}")
                current_files[relative] = item
            elif item.is_dir():
                if stat.S_IMODE(item.stat().st_mode) != 0o755:
                    raise CheckFailed(f"{label} standalone directory mode is not canonical: {relative}")
            else:
                raise CheckFailed(f"{label} standalone build contains an unsupported entry: {relative}")
    except OSError as exc:
        raise CheckFailed(f"{label} standalone build could not be enumerated") from exc

    expected_build_id = source_commit.encode("ascii")
    for relative, description in (
        ("BUILD_ID", "top-level BUILD_ID"),
        (".next/BUILD_ID", ".next/BUILD_ID"),
    ):
        path = current_files.get(relative)
        if path is None:
            raise CheckFailed(f"{label} standalone build is missing {description}")
        try:
            payload = path.read_bytes()
        except OSError as exc:
            raise CheckFailed(f"{label} standalone {description} could not be read") from exc
        if payload != expected_build_id:
            raise CheckFailed(f"{label} standalone {description} must exactly equal source_commit")

    actual_archive = verify_manifested_file(
        manifest.get("deployment_archive"),
        bundle_root,
        f"{label} deployment archive",
        named=True,
    )
    if manifest["deployment_archive"]["name"] != actual_archive["path"].name:
        raise CheckFailed(f"{label} deployment archive name differs from the Web manifest")
    if deployment_archive.expanduser().absolute() != actual_archive["path"]:
        raise CheckFailed(f"{label} deployment archive input is not the manifest-bound file")
    try:
        regenerated = create_web_manifest(
            root,
            source_commit,
            package_json=input_paths["package_json"]["path"],
            package_lock=input_paths["package_lock"]["path"],
            node_toolchain_lock=input_paths["node_toolchain_lock"]["path"],
            node_version=toolchain["node"],
            npm_version=toolchain["npm"],
            build_environment=manifest.get("build_environment"),
            quality_receipts={name: record["path"] for name, record in receipt_paths.items()},
            deployment_archive=actual_archive["path"],
            artifact_root=bundle_root,
        )
    except ValueError as exc:
        raise CheckFailed(f"{label} Web v4 artifact could not be canonically regenerated: {exc}") from exc
    if regenerated != manifest:
        raise CheckFailed(f"{label} Web manifest differs from the regenerated canonical v4 artifact")

    verify_quality_receipt_semantics(receipt_paths, source_commit, label)
    verify_canonical_archive_bytes(actual_archive["path"], root, label)

    try:
        node_lock = strict_json_bytes(
            input_paths["node_toolchain_lock"]["path"].read_bytes(),
            context=f"{label} Web Node toolchain lock",
        )
    except (OSError, ReleaseIntegrityError) as exc:
        raise CheckFailed(f"{label} Web Node toolchain lock is invalid") from exc
    node_record = node_lock.get("node") if isinstance(node_lock, dict) else None
    if (
        not isinstance(node_record, dict)
        or set(node_record) != {"version", "path", "type", "mode", "bytes", "sha256", "target"}
        or node_record.get("path") != "bin/node"
        or node_record.get("type") != "regular"
        or node_record.get("mode") != "0755"
        or node_record.get("target") != ""
        or isinstance(node_record.get("bytes"), bool)
        or not isinstance(node_record.get("bytes"), int)
        or node_record["bytes"] <= 0
        or not isinstance(node_record.get("sha256"), str)
        or SHA256.fullmatch(node_record["sha256"]) is None
    ):
        raise CheckFailed(f"{label} Web Node toolchain binary record is invalid")

    expected_files = {entry["path"]: entry["sha256"] for entry in regenerated["files"]}
    if set(current_files) != set(expected_files):
        raise CheckFailed(f"{label} standalone build file set changed during canonical regeneration")
    root_shell_sha256 = expected_files.get(ROOT_SHELL_RELATIVE_PATH)
    if not isinstance(root_shell_sha256, str) or SHA256.fullmatch(root_shell_sha256) is None:
        raise CheckFailed(f"{label} standalone build is missing its canonical root shell")
    root_digest = hashlib.sha256()
    root_bytes = 0
    for relative in sorted(current_files):
        path = current_files[relative]
        digest = sha256_file(path)
        if digest != expected_files.get(relative):
            raise CheckFailed(f"{label} standalone build changed after canonical regeneration: {relative}")
        size = path.stat().st_size
        root_bytes += size
        root_digest.update(relative.encode("utf-8"))
        root_digest.update(b"\0")
        root_digest.update(str(size).encode("ascii"))
        root_digest.update(b"\0")
        root_digest.update(bytes.fromhex(digest))

    if "server.js" not in current_files:
        raise CheckFailed(f"{label} standalone build is missing server.js")
    if f".next/static/{source_commit}/_buildManifest.js" not in current_files:
        raise CheckFailed(f"{label} standalone build is missing its source-bound deployment probe")

    return ReleaseArtifact(
        manifest_path=manifest_record["path"],
        build_root=root,
        deployment_archive=actual_archive["path"],
        source_commit=source_commit,
        manifest_sha256=manifest_record["sha256"],
        manifest_bytes=manifest_record["bytes"],
        deployment_archive_sha256=actual_archive["sha256"],
        deployment_archive_bytes=actual_archive["bytes"],
        build_root_sha256=root_digest.hexdigest(),
        build_root_bytes=root_bytes,
        build_root_file_count=len(current_files),
        root_shell_sha256=root_shell_sha256,
        node_version=toolchain["node"],
        node_sha256=node_record["sha256"],
        node_bytes=node_record["bytes"],
    )


def require_distinct_releases(baseline_build_id: str, candidate_build_id: str) -> None:
    if COMMIT.fullmatch(baseline_build_id) is None or COMMIT.fullmatch(candidate_build_id) is None:
        raise CheckFailed("both release BUILD_ID values must be full lowercase Git commits")
    if baseline_build_id == candidate_build_id:
        raise CheckFailed("baseline and candidate release BUILD_ID values must differ")


def git_command(root: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    git = Path("/usr/bin/git")
    if git.is_symlink() or not git.is_file() or not os.access(git, os.X_OK):
        raise CheckFailed("canonical Git executable is unavailable")
    environment = clean_child_environment({
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_SYSTEM": "/dev/null",
        "GIT_CONFIG_GLOBAL": "/dev/null",
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_NO_REPLACE_OBJECTS": "1",
    })
    try:
        result = subprocess.run(
            [
                str(git),
                "-c",
                "core.fsmonitor=false",
                "-c",
                "core.untrackedCache=false",
                "-C",
                str(root),
                *arguments,
            ],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=environment,
        )
    except OSError as exc:
        raise CheckFailed(f"Git command could not run: {' '.join(arguments[:2])}") from exc
    if check and result.returncode != 0:
        raise CheckFailed(f"Git command failed: {' '.join(arguments[:2])}")
    return result


def _stable_directory_metadata(path: Path, label: str) -> os.stat_result:
    try:
        metadata = path.lstat()
        resolved = path.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise CheckFailed(f"{label} is unavailable") from exc
    if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode) or resolved != path:
        raise CheckFailed(f"{label} must be a real directory without symlink ancestry")
    return metadata


def _directory_snapshot_identity(metadata: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def require_independent_git_directory(root: Path, label: str) -> GitDirectoryBoundary:
    root_metadata = _stable_directory_metadata(root, f"{label} root")
    git_directory = root / ".git"
    git_metadata = _stable_directory_metadata(git_directory, f"{label} .git directory")
    common_raw = git_command(root, "rev-parse", "--git-common-dir").stdout.decode("utf-8").strip()
    common = Path(common_raw)
    if not common.is_absolute():
        common = root / common
    common = common.absolute()
    if common.resolve(strict=True) != git_directory or common != git_directory:
        raise CheckFailed(f"{label} must use its own Git object store")
    alternates = git_directory / "objects/info/alternates"
    if alternates.exists() or alternates.is_symlink():
        raise CheckFailed(f"{label} Git object store must not use alternates")
    object_root = git_directory / "objects"
    object_metadata = _stable_directory_metadata(object_root, f"{label} Git object root")
    try:
        for item in object_root.rglob("*"):
            metadata = item.lstat()
            if stat.S_ISLNK(metadata.st_mode):
                raise CheckFailed(f"{label} Git object store contains a symlink")
            if stat.S_ISREG(metadata.st_mode) and metadata.st_nlink != 1:
                raise CheckFailed(f"{label} Git object store contains shared hardlinks; use clone --no-local")
            if not stat.S_ISREG(metadata.st_mode) and not stat.S_ISDIR(metadata.st_mode):
                raise CheckFailed(f"{label} Git object store contains an unsupported entry")
    except OSError as exc:
        raise CheckFailed(f"{label} Git object store could not be inspected") from exc
    if (
        _directory_snapshot_identity(_stable_directory_metadata(root, f"{label} root"))
        != _directory_snapshot_identity(root_metadata)
        or _directory_snapshot_identity(
            _stable_directory_metadata(git_directory, f"{label} .git directory")
        )
        != _directory_snapshot_identity(git_metadata)
        or _directory_snapshot_identity(
            _stable_directory_metadata(object_root, f"{label} Git object root")
        )
        != _directory_snapshot_identity(object_metadata)
    ):
        raise CheckFailed(f"{label} Git directory identity changed while it was inspected")
    return GitDirectoryBoundary(
        git_directory=git_directory,
        git_identity=(git_metadata.st_dev, git_metadata.st_ino),
        object_root=object_root,
        object_identity=(object_metadata.st_dev, object_metadata.st_ino),
    )


def require_distinct_git_boundaries(
    baseline: GitDirectoryBoundary,
    candidate: GitDirectoryBoundary,
) -> None:
    if (
        baseline.git_directory == candidate.git_directory
        or baseline.git_directory.resolve(strict=True) == candidate.git_directory.resolve(strict=True)
        or baseline.git_identity == candidate.git_identity
        or baseline.object_root == candidate.object_root
        or baseline.object_root.resolve(strict=True) == candidate.object_root.resolve(strict=True)
        or baseline.object_identity == candidate.object_identity
    ):
        raise CheckFailed("baseline and candidate must use independent Git and object-store inodes")


def verify_release_history(
    baseline_source_root: Path,
    candidate_source_root: Path,
    baseline_commit: str,
    candidate_commit: str,
) -> dict[str, Any]:
    if COMMIT.fullmatch(baseline_commit) is None or COMMIT.fullmatch(candidate_commit) is None:
        raise CheckFailed("release history commits must be full lowercase Git commits")
    if baseline_commit == candidate_commit:
        raise CheckFailed("baseline must be a strict ancestor of the distinct candidate commit")
    baseline_root = baseline_source_root.expanduser().absolute()
    candidate_root = candidate_source_root.expanduser().absolute()
    if baseline_root == candidate_root:
        raise CheckFailed("baseline and candidate must use separate clean source checkouts")
    try:
        baseline_identity = verify_exact_git_source(
            baseline_root,
            context="baseline release source",
            expected_commit=baseline_commit,
        )
        candidate_identity = verify_exact_git_source(
            candidate_root,
            context="candidate release source",
            expected_commit=candidate_commit,
        )
    except ReleaseIntegrityError as exc:
        raise CheckFailed(f"release source is not an exact clean HEAD: {exc}") from exc
    baseline_git = require_independent_git_directory(baseline_root, "baseline release source")
    candidate_git = require_independent_git_directory(candidate_root, "candidate release source")
    require_distinct_git_boundaries(baseline_git, candidate_git)

    baseline_object = git_command(
        candidate_root,
        "rev-parse",
        "--verify",
        f"{baseline_commit}^{{commit}}",
    ).stdout.decode("ascii").strip().lower()
    baseline_tree = git_command(
        candidate_root,
        "rev-parse",
        "--verify",
        f"{baseline_commit}^{{tree}}",
    ).stdout.decode("ascii").strip().lower()
    if baseline_object != baseline_commit or baseline_tree != baseline_identity.tree:
        raise CheckFailed("candidate Git history does not contain the exact baseline source")
    ancestor = git_command(
        candidate_root,
        "merge-base",
        "--is-ancestor",
        baseline_commit,
        candidate_commit,
        check=False,
    )
    if ancestor.returncode == 1:
        raise CheckFailed("baseline commit is not a strict ancestor of the candidate")
    if ancestor.returncode != 0:
        raise CheckFailed("Git could not verify the baseline/candidate ancestry")
    return {
        "baseline": baseline_commit,
        "head": candidate_commit,
        "relationship": "strict-ancestor",
        "baseline_tree": baseline_identity.tree,
        "candidate_tree": candidate_identity.tree,
        "baseline_inventory": baseline_identity.inventory,
        "candidate_inventory": candidate_identity.inventory,
        "separate_source_checkouts": True,
        "independent_git_object_stores": True,
        "no_alternates_or_shared_object_hardlinks": True,
    }


def require_distinct_worker_graphs(baseline: dict[str, Any], candidate: dict[str, Any]) -> None:
    baseline_hash = baseline.get("sw_graph_sha256")
    candidate_hash = candidate.get("sw_graph_sha256")
    if (
        not isinstance(baseline_hash, str)
        or not isinstance(candidate_hash, str)
        or baseline_hash == candidate_hash
    ):
        raise CheckFailed("baseline and candidate service-worker script graphs must differ")


def require_canonical_worker_url(raw_url: object, expected_origin: str) -> str:
    if not isinstance(raw_url, str):
        raise CheckFailed(f"service-worker URL is missing: {raw_url!r}")
    parsed = urlsplit(raw_url)
    expected = urlsplit(expected_origin)
    if (
        parsed.scheme != expected.scheme
        or parsed.netloc != expected.netloc
        or parsed.path != "/sw.js"
        or parsed.query
        or parsed.fragment
        or parsed.username is not None
        or parsed.password is not None
    ):
        raise CheckFailed(f"service-worker URL is not the canonical same-origin /sw.js: {raw_url}")
    return raw_url


def fetch_bytes(url: str, *, timeout: float) -> tuple[bytes, dict[str, str]]:
    request = urllib.request.Request(url, method="GET", headers={"Cache-Control": "no-store"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if response.status != 200:
                raise CheckFailed(f"GET {url} returned {response.status}")
            return response.read(), {key.lower(): value for key, value in response.headers.items()}
    except CheckFailed:
        raise
    except Exception as exc:  # noqa: BLE001 - preserve the failing release URL
        raise CheckFailed(f"GET {url} failed: {exc}") from exc


def capture_release_http(origin: str, standalone_root: str, build_id: str, *, timeout: float) -> dict[str, Any]:
    identity_bytes, identity_headers = fetch_bytes(f"{origin}/api/release-identity", timeout=timeout)
    try:
        identity = json.loads(identity_bytes.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise CheckFailed("release identity response was not valid UTF-8 JSON") from exc
    if not isinstance(identity, dict) or identity.get("status") != "ready" or identity.get("source_commit") != build_id:
        raise CheckFailed(f"release identity did not match {build_id}: {identity}")
    if identity_headers.get("cache-control") != "no-store":
        raise CheckFailed("release identity must use Cache-Control: no-store")

    worker_bytes, worker_headers = fetch_bytes(f"{origin}/sw.js", timeout=timeout)
    version_bytes, version_headers = fetch_bytes(f"{origin}/sw-version.js", timeout=timeout)
    expected_version_script = f'self.WALKSAFE_SW_SOURCE_COMMIT = "{build_id}";\n'.encode()
    if version_bytes != expected_version_script:
        raise CheckFailed(f"service-worker release script did not match BUILD_ID {build_id}")
    if version_headers.get("cache-control") != "no-store":
        raise CheckFailed("service-worker release script must use Cache-Control: no-store")
    if not version_headers.get("content-type", "").lower().startswith("application/javascript"):
        raise CheckFailed("service-worker release script must use a JavaScript content type")
    if b'importScripts("/sw-version.js")' not in worker_bytes:
        raise CheckFailed("canonical service worker does not import the build-bound release script")
    if not worker_headers.get("content-type", "").lower().startswith("application/javascript"):
        raise CheckFailed("canonical service worker must use a JavaScript content type")

    return {
        "standalone_root": standalone_root,
        "build_id": build_id,
        "release_identity": identity,
        "release_identity_sha256": sha256_bytes(identity_bytes),
        "sw_sha256": sha256_bytes(worker_bytes),
        "sw_version_script_sha256": sha256_bytes(version_bytes),
        "sw_graph_sha256": sha256_bytes(worker_bytes + b"\0" + version_bytes),
    }


def release_environment(
    base: dict[str, str],
    *,
    build_id: str,
    process_lock_path: Path,
) -> dict[str, str]:
    environment = clean_child_environment(
        {name: base[name] for name in WEB_CHILD_ENVIRONMENT_KEYS if name in base}
    )
    environment.update(
        {
            "WALKSAFE_SOURCE_COMMIT": build_id,
            "WALKSAFE_WEB_PROCESS_LOCK_PATH": str(process_lock_path),
        }
    )
    return environment


def start_child_process(
    command: list[str],
    *,
    cwd: Path,
    env: dict[str, str],
    log_path: Path,
    pass_fds: tuple[int, ...] = (),
    registry: list[subprocess.Popen[str]] | None = None,
    port: int = 0,
    label: str = "child process",
    signal_guard: OwnedProcessSignalGuard,
) -> subprocess.Popen[str]:
    if threading.current_thread() is not threading.main_thread() or threading.active_count() != 1:
        raise CheckFailed("owned-process acquisition requires one main Python thread")
    log_file = log_path.open("w", encoding="utf-8")
    process: subprocess.Popen[str] | None = None
    previous_signal_mask: set[signal.Signals] | None = None
    signals_blocked = False
    try:
        previous_signal_mask = signal_guard.block_for_spawn()
        signals_blocked = True

        def restore_child_signal_mask() -> None:
            assert previous_signal_mask is not None
            signal_guard.restore_spawn_mask(previous_signal_mask)

        process = subprocess.Popen(
            command,
            cwd=str(cwd),
            env=env,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            text=True,
            start_new_session=True,
            pass_fds=pass_fds,
            preexec_fn=restore_child_signal_mask,
        )
        if registry is not None:
            registry.append(process)
        log_file.close()
        signals_blocked = False
        assert previous_signal_mask is not None
        signal_guard.restore_spawn_mask(previous_signal_mask)
        return process
    except BaseException as acquisition_error:
        if not signals_blocked:
            signal_guard.block_for_spawn()
            signals_blocked = True
        if process is not None:
            try:
                stop_owned_process(process, port, f"{label} after ownership registration failure")
            except BaseException as cleanup_error:
                raise CheckFailed(
                    f"{label} ownership registration failed "
                    f"({type(acquisition_error).__name__}: {acquisition_error}); "
                    "owned-process cleanup also failed "
                    f"({type(cleanup_error).__name__}: {cleanup_error})"
                ) from acquisition_error
            if registry is not None:
                registry[:] = [owned for owned in registry if owned is not process]
        raise
    finally:
        if signals_blocked and previous_signal_mask is not None:
            signal_guard.restore_spawn_mask(previous_signal_mask)
        if not log_file.closed:
            log_file.close()


def wait_for_owned_process_group_release(
    process_group_id: int,
    label: str,
    *,
    timeout: float = PROCESS_GROUP_RELEASE_TIMEOUT_SECONDS,
) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            os.killpg(process_group_id, 0)
        except ProcessLookupError:
            return
        except PermissionError as exc:
            raise CheckFailed(f"could not verify the owned process group for {label}") from exc
        time.sleep(0.05)
    raise CheckFailed(f"owned process group remained after shutdown: {label}")


def stop_owned_process(
    process: subprocess.Popen[str],
    port: int,
    label: str,
) -> None:
    process_group_id = process.pid
    _stop_owned_process(process, port, label)
    wait_for_owned_process_group_release(
        process_group_id,
        label,
        timeout=PROCESS_GROUP_RELEASE_TIMEOUT_SECONDS,
    )


def start_registered_process(
    command: list[str],
    *,
    cwd: Path,
    env: dict[str, str],
    log_path: Path,
    pass_fds: tuple[int, ...],
    registry: list[subprocess.Popen[str]],
    port: int,
    label: str,
    signal_guard: OwnedProcessSignalGuard,
) -> subprocess.Popen[str]:
    process: subprocess.Popen[str] | None = None
    try:
        process = start_child_process(
            command,
            cwd=cwd,
            env=env,
            log_path=log_path,
            pass_fds=pass_fds,
            registry=registry,
            port=port,
            label=label,
            signal_guard=signal_guard,
        )
        if not any(owned is process for owned in registry):
            registry.append(process)
        return process
    except BaseException as acquisition_error:
        if process is not None:
            try:
                stop_owned_process(process, port, f"{label} after ownership registration failure")
            except BaseException as cleanup_error:
                raise CheckFailed(
                    f"{label} ownership registration failed "
                    f"({type(acquisition_error).__name__}: {acquisition_error}); "
                    "owned-process cleanup also failed "
                    f"({type(cleanup_error).__name__}: {cleanup_error})"
                ) from acquisition_error
            registry[:] = [owned for owned in registry if owned is not process]
        raise


def start_release_server(
    args: argparse.Namespace,
    build_root: Path,
    environment: dict[str, str],
    log_path: Path,
    owned_processes: list[subprocess.Popen[str]],
) -> subprocess.Popen[str]:
    environment = environment.copy()
    environment.update({"HOSTNAME": "127.0.0.1", "PORT": str(args.web_port)})
    node_runtime: VerifiedNodeRuntime = args.node_runtime
    node_runtime.verify_identity()
    process = start_registered_process(
        [node_runtime.executable, "server.js"],
        cwd=build_root,
        env=environment,
        log_path=log_path,
        pass_fds=(node_runtime.descriptor,),
        registry=owned_processes,
        port=args.web_port,
        label="release Web process",
        signal_guard=args.signal_guard,
    )
    try:
        node_runtime.verify_identity()
        wait_for_http(f"http://127.0.0.1:{args.web_port}/api/release-identity", timeout=60.0)
        if process.poll() is not None:
            raise CheckFailed(f"release Web process exited early; see {log_path}")
    except BaseException as startup_error:
        try:
            stop_owned_process(process, args.web_port, "the release Web process after startup failure")
        except BaseException as cleanup_error:
            raise CheckFailed(
                "release Web startup failed "
                f"({type(startup_error).__name__}: {startup_error}); "
                "owned-process cleanup also failed "
                f"({type(cleanup_error).__name__}: {cleanup_error})"
            ) from startup_error
        owned_processes.remove(process)
        raise
    return process


def cleanup_owned_processes(
    args: argparse.Namespace,
    owned_chrome_processes: list[subprocess.Popen[str]],
    owned_web_processes: list[subprocess.Popen[str]],
) -> None:
    failures: list[str] = []
    for index, process in enumerate(reversed(owned_chrome_processes), start=1):
        try:
            stop_owned_process(
                process,
                args.chrome_debug_port,
                f"release-update Chromium process {index}",
            )
        except BaseException as exc:
            failures.append(f"Chromium[{index}]:{type(exc).__name__}:{exc}")
    for index, process in enumerate(reversed(owned_web_processes), start=1):
        try:
            stop_owned_process(
                process,
                args.web_port,
                f"release Web process {index}",
            )
        except BaseException as exc:
            failures.append(f"Web[{index}]:{type(exc).__name__}:{exc}")
    if failures:
        raise CheckFailed("owned-process cleanup failed: " + "; ".join(failures))


def cleanup_after_failure(
    primary_error: BaseException,
    args: argparse.Namespace,
    owned_chrome_processes: list[subprocess.Popen[str]],
    owned_web_processes: list[subprocess.Popen[str]],
) -> None:
    try:
        cleanup_owned_processes(args, owned_chrome_processes, owned_web_processes)
    except BaseException as cleanup_error:
        raise CheckFailed(
            "release-update check failed "
            f"({type(primary_error).__name__}: {primary_error}); "
            "owned-process cleanup also failed "
            f"({type(cleanup_error).__name__}: {cleanup_error})"
        ) from primary_error
    raise primary_error


async def wait_for_state(
    websocket: Any,
    message_id: int,
    expression: str,
    predicate: Any,
    description: str,
    timeout_seconds: float,
) -> tuple[dict[str, Any], int]:
    deadline = time.monotonic() + timeout_seconds
    last_state: dict[str, Any] | None = None
    while time.monotonic() < deadline:
        state = await evaluate(
            websocket,
            expression,
            message_id=message_id,
            timeout_seconds=deadline - time.monotonic(),
        )
        message_id += 1
        last_state = state if isinstance(state, dict) else None
        if last_state is not None and predicate(last_state):
            return last_state, message_id
        await asyncio.sleep(0.25)
    raise CheckFailed(f"Timed out waiting for {description}: {last_state}")


def worker_state_expression(expected_commit: str) -> str:
    return f"""
(async () => {{
  const workerVersion = async (worker) => {{
    if (!worker) return null;
    return await new Promise((resolve) => {{
      const channel = new MessageChannel();
      const timer = setTimeout(() => resolve(null), 3000);
      channel.port1.onmessage = (event) => {{
        clearTimeout(timer);
        resolve(typeof event.data?.version === 'string' ? event.data.version : null);
      }};
      worker.postMessage({{ type: 'GET_VERSION' }}, [channel.port2]);
    }});
  }};
  const registration = await navigator.serviceWorker.getRegistration('/');
  const active = registration?.active ?? null;
  const controller = navigator.serviceWorker.controller;
  const cacheName = 'walksafe-assist-source-' + {json.dumps(expected_commit)};
  const cacheNames = await caches.keys();
  const rootCached = cacheNames.includes(cacheName)
    ? Boolean(await caches.open(cacheName).then((cache) => cache.match('/')))
    : false;
  return {{
    activeState: active?.state ?? null,
    activeUrl: active?.scriptURL ?? null,
    controllerUrl: controller?.scriptURL ?? null,
    activeVersion: await workerVersion(active),
    controllerVersion: await workerVersion(controller),
    cacheNames,
    rootCached,
    offlineShellReady: document.documentElement?.dataset.walksafeOfflineShellReady === 'true'
  }};
}})()
"""


WAITING_STATE_EXPRESSION = """
(async () => {
  const workerVersion = async (worker) => {
    if (!worker) return null;
    return await new Promise((resolve) => {
      const channel = new MessageChannel();
      const timer = setTimeout(() => resolve(null), 3000);
      channel.port1.onmessage = (event) => {
        clearTimeout(timer);
        resolve(typeof event.data?.version === 'string' ? event.data.version : null);
      };
      worker.postMessage({ type: 'GET_VERSION' }, [channel.port2]);
    });
  };
  const registration = await navigator.serviceWorker.getRegistration('/');
  const button = [...document.querySelectorAll('button')]
    .find((item) => item.innerText.includes('오프라인 셸 업데이트 적용'));
  return {
    activeUrl: registration?.active?.scriptURL ?? null,
    waitingUrl: registration?.waiting?.scriptURL ?? null,
    controllerUrl: navigator.serviceWorker.controller?.scriptURL ?? null,
    activeVersion: await workerVersion(registration?.active ?? null),
    waitingVersion: await workerVersion(registration?.waiting ?? null),
    controllerVersion: await workerVersion(navigator.serviceWorker.controller),
    waitingState: registration?.waiting?.state ?? null,
    buttonEnabled: Boolean(button && !button.disabled),
    text: document.body?.innerText ?? ''
  };
})()
"""


def candidate_root_probe_expression(candidate_commit: str) -> str:
    return f"""
(async () => {{
  const digest = async (response) => {{
    const bytes = new Uint8Array(await response.arrayBuffer());
    const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
    return [...hash].map((value) => value.toString(16).padStart(2, '0')).join('');
  }};
  const cacheName = 'walksafe-assist-source-' + {json.dumps(candidate_commit)};
  const cachedResponse = await caches.open(cacheName).then((cache) => cache.match('/'));
  const onlineResponse = await fetch('/', {{ cache: 'reload' }});
  return {{
    cached: Boolean(cachedResponse),
    cachedSha256: cachedResponse === undefined ? null : await digest(cachedResponse),
    onlineOk: onlineResponse.ok,
    onlineStatus: onlineResponse.status,
    onlineSha256: await digest(onlineResponse)
  }};
}})()
"""


def require_candidate_root_probe(
    probe: object,
    candidate_commit: str,
    expected_artifact_sha256: str,
) -> str:
    if (
        not isinstance(expected_artifact_sha256, str)
        or SHA256.fullmatch(expected_artifact_sha256) is None
        or not isinstance(probe, dict)
        or probe.get("cached") is not True
        or probe.get("onlineOk") is not True
        or probe.get("onlineStatus") != 200
        or probe.get("cachedSha256") != expected_artifact_sha256
        or probe.get("onlineSha256") != expected_artifact_sha256
    ):
        raise CheckFailed(
            "candidate cached and online roots did not exactly match the verified artifact shell "
            f"for {candidate_commit}: {probe}"
        )
    return expected_artifact_sha256


def require_offline_probe(
    offline_state: object,
    expected_root_sha256: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    if not isinstance(offline_state, dict):
        raise CheckFailed(f"offline state was not returned: {offline_state}")
    offline_shell = offline_state.get("shell")
    offline_api = offline_state.get("api")
    if (
        not isinstance(offline_shell, dict)
        or offline_shell.get("ok") is not True
        or offline_shell.get("sha256") != expected_root_sha256
    ):
        raise CheckFailed(f"offline root did not match the verified candidate shell: {offline_state}")
    if (
        not isinstance(offline_api, dict)
        or offline_api.get("rejected") is not True
        or offline_api.get("ok") is not False
        or offline_api.get("status") is not None
    ):
        raise CheckFailed(f"safety API was not transport-rejected offline: {offline_state}")
    return offline_shell, offline_api


def applied_state_expression(candidate_commit: str, baseline_commit: str) -> str:
    return f"""
(async () => {{
  const workerVersion = async (worker) => {{
    if (!worker) return null;
    return await new Promise((resolve) => {{
      const channel = new MessageChannel();
      const timer = setTimeout(() => resolve(null), 3000);
      channel.port1.onmessage = (event) => {{
        clearTimeout(timer);
        resolve(typeof event.data?.version === 'string' ? event.data.version : null);
      }};
      worker.postMessage({{ type: 'GET_VERSION' }}, [channel.port2]);
    }});
  }};
  const digest = async (response) => {{
    const bytes = new Uint8Array(await response.arrayBuffer());
    const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
    return [...hash].map((value) => value.toString(16).padStart(2, '0')).join('');
  }};
  const registration = await navigator.serviceWorker.getRegistration('/');
  const cacheName = 'walksafe-assist-source-' + {json.dumps(candidate_commit)};
  const baselineCacheName = 'walksafe-assist-source-' + {json.dumps(baseline_commit)};
  const cacheNames = await caches.keys();
  const cachedRoot = cacheNames.includes(cacheName)
    ? await caches.open(cacheName).then((cache) => cache.match('/'))
    : null;
  return {{
    activeUrl: registration?.active?.scriptURL ?? null,
    controllerUrl: navigator.serviceWorker.controller?.scriptURL ?? null,
    activeVersion: await workerVersion(registration?.active ?? null),
    controllerVersion: await workerVersion(navigator.serviceWorker.controller),
    waiting: Boolean(registration?.waiting),
    cacheNames,
    candidateRootCached: Boolean(cachedRoot),
    candidateRootSha256: cachedRoot === null ? null : await digest(cachedRoot),
    baselineCacheRemoved: !cacheNames.includes(baselineCacheName),
    offlineShellReady: document.documentElement?.dataset.walksafeOfflineShellReady === 'true',
    text: document.body?.innerText ?? ''
  }};
}})()
"""


async def run_browser_transition(
    args: argparse.Namespace,
    *,
    origin: str,
    chrome_instance_url: str,
    baseline_process: subprocess.Popen[str],
    candidate_environment: dict[str, str],
    baseline_release: dict[str, Any],
    candidate_artifact: ReleaseArtifact,
    actor_id: str,
    account_token: str,
    temp_directory: Path,
    owned_web_processes: list[subprocess.Popen[str]],
) -> tuple[dict[str, Any], dict[str, Any]]:
    targets_url = f"http://127.0.0.1:{args.chrome_debug_port}/json"
    page = select_launched_chrome_page(
        json.loads(fetch_bytes(targets_url, timeout=5.0)[0].decode("utf-8")),
        chrome_instance_url,
        args.chrome_debug_port,
    )
    async with websockets.connect(page["webSocketDebuggerUrl"], max_size=8 * 1024 * 1024) as websocket:
        message_id = 1
        for method, params in (
            ("Page.enable", {}),
            ("Runtime.enable", {}),
            ("Network.enable", {}),
            ("Page.navigate", {"url": origin}),
        ):
            await cdp_call(websocket, method, params, message_id=message_id)
            message_id += 1

        authentication = await evaluate(
            websocket,
            f"""
(async () => {{
  const response = await fetch('/api/field-session', {{
    method: 'POST',
    headers: {{ 'content-type': 'application/json', 'x-real-ip': '127.0.0.1' }},
    body: JSON.stringify({{ actor_id: {json.dumps(actor_id)}, token: {json.dumps(account_token)} }}),
    cache: 'no-store'
  }});
  if (!response.ok) return {{ ok: false, status: response.status }};
  window.location.reload();
  return {{ ok: true, status: response.status }};
}})()
""",
            message_id=message_id,
        )
        message_id += 1
        if not isinstance(authentication, dict) or authentication.get("ok") is not True:
            raise CheckFailed(f"could not establish the release-update field session: {authentication}")

        baseline_version = f"source-{baseline_release['build_id']}"
        baseline_state, message_id = await wait_for_state(
            websocket,
            message_id,
            worker_state_expression(baseline_release["build_id"]),
            lambda state: (
                state.get("activeState") == "activated"
                and state.get("activeVersion") == baseline_version
                and state.get("controllerVersion") == baseline_version
                and state.get("rootCached") is True
                and state.get("offlineShellReady") is True
            ),
            "baseline worker/controller/cache",
            args.browser_timeout,
        )
        require_canonical_worker_url(baseline_state.get("activeUrl"), origin)
        require_canonical_worker_url(baseline_state.get("controllerUrl"), origin)

        expanded = await evaluate(
            websocket,
            """
(() => {
  const button = [...document.querySelectorAll('button')]
    .find((item) => item.innerText.trim() === '펼치기');
  if (button && !button.disabled) button.click();
  return Boolean(button);
})()
""",
            message_id=message_id,
        )
        message_id += 1
        if expanded is not True:
            raise CheckFailed("could not expose the service-worker update controls")

        stop_owned_process(baseline_process, args.web_port, "the baseline release origin")
        owned_web_processes.remove(baseline_process)
        candidate_process = start_release_server(
            args,
            candidate_artifact.build_root,
            candidate_environment,
            temp_directory / "candidate-web.log",
            owned_web_processes,
        )
        candidate_release = capture_release_http(
            origin,
            candidate_artifact.build_root.name,
            candidate_artifact.source_commit,
            timeout=args.browser_timeout,
        )
        require_distinct_worker_graphs(baseline_release, candidate_release)

        update_started = await evaluate(
            websocket,
            """
(async () => {
  const registration = await navigator.serviceWorker.getRegistration('/');
  if (!registration) return false;
  await registration.update();
  return true;
})()
""",
            message_id=message_id,
        )
        message_id += 1
        if update_started is not True:
            raise CheckFailed("could not request a canonical service-worker update")

        candidate_build_id = candidate_artifact.source_commit
        candidate_version = f"source-{candidate_build_id}"
        waiting_state, message_id = await wait_for_state(
            websocket,
            message_id,
            WAITING_STATE_EXPRESSION,
            lambda state: (
                state.get("waitingState") == "installed"
                and state.get("waitingVersion") == candidate_version
                and state.get("activeVersion") == baseline_version
                and state.get("controllerVersion") == baseline_version
                and state.get("buttonEnabled") is True
            ),
            "candidate waiting worker and user-held apply UI",
            args.browser_timeout,
        )
        for key in ("activeUrl", "waitingUrl", "controllerUrl"):
            require_canonical_worker_url(waiting_state.get(key), origin)

        candidate_root_probe = await evaluate(
            websocket,
            candidate_root_probe_expression(candidate_build_id),
            message_id=message_id,
        )
        message_id += 1
        candidate_root_sha256 = require_candidate_root_probe(
            candidate_root_probe,
            candidate_build_id,
            candidate_artifact.root_shell_sha256,
        )

        pre_click_state = await evaluate(
            websocket,
            WAITING_STATE_EXPRESSION,
            message_id=message_id,
        )
        message_id += 1
        if not isinstance(pre_click_state, dict) or pre_click_state.get("controllerVersion") != baseline_version:
            raise CheckFailed(f"candidate worker preempted the baseline before user apply: {pre_click_state}")

        clicked = await evaluate(
            websocket,
            """
(() => {
  const button = [...document.querySelectorAll('button')]
    .find((item) => item.innerText.includes('오프라인 셸 업데이트 적용'));
  if (!button || button.disabled) return false;
  button.click();
  return true;
})()
""",
            message_id=message_id,
        )
        message_id += 1
        if clicked is not True:
            raise CheckFailed("service-worker update apply button was not actionable")

        applied_state, message_id = await wait_for_state(
            websocket,
            message_id,
            applied_state_expression(candidate_build_id, baseline_release["build_id"]),
            lambda state: (
                state.get("waiting") is False
                and state.get("activeVersion") == candidate_version
                and state.get("controllerVersion") == candidate_version
                and state.get("candidateRootCached") is True
                and state.get("candidateRootSha256") == candidate_root_sha256
                and state.get("baselineCacheRemoved") is True
                and state.get("offlineShellReady") is True
                and "새 오프라인 셸 적용 완료" in str(state.get("text", ""))
            ),
            "user-applied candidate worker/controller/cache",
            args.browser_timeout,
        )
        require_canonical_worker_url(applied_state.get("activeUrl"), origin)
        require_canonical_worker_url(applied_state.get("controllerUrl"), origin)

        stop_owned_process(candidate_process, args.web_port, "the candidate release origin before offline proof")
        owned_web_processes.remove(candidate_process)
        await cdp_call(
            websocket,
            "Network.emulateNetworkConditions",
            {"offline": True, "latency": 0, "downloadThroughput": 0, "uploadThroughput": 0},
            message_id=message_id,
        )
        message_id += 1
        offline_state = await evaluate(
            websocket,
            """
(async () => {
  const digest = async (response) => {
    const bytes = new Uint8Array(await response.arrayBuffer());
    const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
    return [...hash].map((value) => value.toString(16).padStart(2, '0')).join('');
  };
  const shell = await fetch('/').then(async (response) => {
    return { ok: response.ok, status: response.status, sha256: await digest(response) };
  }).catch(() => null);
  const api = await fetch('/api/health', { cache: 'no-store' })
    .then((response) => ({ rejected: false, ok: response.ok, status: response.status }))
    .catch(() => ({ rejected: true, ok: false, status: null }));
  return { shell, api };
})()
""",
            message_id=message_id,
        )
        message_id += 1
        _offline_shell, offline_api = require_offline_probe(
            offline_state,
            candidate_root_sha256,
        )

        offline_url = f"{origin}/?walksafe_release_update_offline=1"
        await cdp_call(websocket, "Page.navigate", {"url": offline_url}, message_id=message_id)
        message_id += 1
        reload_state, message_id = await wait_for_state(
            websocket,
            message_id,
            """
(() => ({
  offlineReloadCommitted: location.search.includes('walksafe_release_update_offline=1'),
  readyState: document.readyState,
  nextScripts: [...document.scripts].filter((item) => item.src.includes('/_next/static/')).length,
  nextStyles: [...document.styleSheets].filter((item) => item.href?.includes('/_next/static/')).length,
  hydrated: document.documentElement?.dataset.walksafeHydrated === 'true'
}))()
""",
            lambda state: (
                state.get("offlineReloadCommitted") is True
                and state.get("readyState") == "complete"
                and state.get("nextScripts", 0) > 0
                and state.get("nextStyles", 0) > 0
                and state.get("hydrated") is True
            ),
            "candidate offline hydrated reload",
            args.browser_timeout,
        )

        return candidate_release, {
            "baseline_active_controller_version": baseline_version,
            "candidate_waiting_version": waiting_state.get("waitingVersion"),
            "controller_before_apply": pre_click_state.get("controllerVersion"),
            "user_apply_clicked": True,
            "controller_after_apply": applied_state.get("controllerVersion"),
            "candidate_cache": {
                "name": f"walksafe-assist-source-{candidate_build_id}",
                "root_cached": True,
                "root_sha256": candidate_root_sha256,
                "online_root_sha256": candidate_root_probe.get("onlineSha256"),
                "online_exact_match": True,
                "artifact_root_exact_match": True,
                "baseline_cache_removed": True,
            },
            "offline": {
                "root_fetch_ok": True,
                "root_sha256_match": True,
                "reload_hydrated": reload_state.get("hydrated") is True,
                "safety_api_unavailable": offline_api.get("rejected") is True,
                "safety_api_transport_rejected": offline_api.get("rejected") is True,
                "safety_api_status": offline_api.get("status"),
            },
        }


def artifact_evidence(artifact: ReleaseArtifact) -> dict[str, Any]:
    return {
        "source_commit": artifact.source_commit,
        "manifest": {
            "path": str(artifact.manifest_path),
            "sha256": artifact.manifest_sha256,
            "bytes": artifact.manifest_bytes,
        },
        "standalone_root": {
            "path": str(artifact.build_root),
            "file_count": artifact.build_root_file_count,
            "bytes": artifact.build_root_bytes,
            "inventory_sha256": artifact.build_root_sha256,
            "root_shell_sha256": artifact.root_shell_sha256,
        },
        "deployment_archive": {
            "path": str(artifact.deployment_archive),
            "sha256": artifact.deployment_archive_sha256,
            "bytes": artifact.deployment_archive_bytes,
            "canonical_tar_metadata": True,
            "exact_canonical_tar_gzip_bytes": True,
        },
        "node": {
            "version": artifact.node_version,
            "sha256": artifact.node_sha256,
            "bytes": artifact.node_bytes,
        },
    }


def publish_evidence(path: Path, evidence: dict[str, Any]) -> None:
    payload = (json.dumps(evidence, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    try:
        exclusive_atomic_publish(path, payload, mode=0o600)
    except ReleaseIntegrityError as exc:
        raise CheckFailed(f"could not publish release-update evidence without replacement: {exc}") from exc


def verify_node_runtime(
    node_bin: Path,
    releases: tuple[ReleaseArtifact, ReleaseArtifact],
    *,
    existing: VerifiedNodeRuntime | None = None,
) -> VerifiedNodeRuntime:
    if existing is not None:
        if node_bin.expanduser().absolute() != existing.path:
            raise CheckFailed("final Node runtime path differs from its open verified descriptor")
        existing.verify_execution(releases)
        return existing
    node = require_real_file(node_bin, "Node runtime")
    if not os.access(node, os.X_OK) or stat.S_IMODE(node.stat().st_mode) != 0o755:
        raise CheckFailed("Node runtime must be a canonical executable regular file")
    expected = {(release.node_sha256, release.node_bytes, release.node_version) for release in releases}
    if len(expected) != 1:
        raise CheckFailed("baseline and candidate Web manifests use different Node runtimes")
    expected_sha256, expected_bytes, expected_version = next(iter(expected))
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        before = node.lstat()
        descriptor = os.open(node, flags)
        opened = os.fstat(descriptor)
    except OSError as exc:
        raise CheckFailed("Node runtime could not be opened without following links") from exc
    runtime = VerifiedNodeRuntime(
        path=node,
        descriptor=descriptor,
        identity=_runtime_file_identity(opened),
        sha256=_sha256_descriptor(descriptor, opened.st_size),
        size=opened.st_size,
        version=expected_version,
    )
    try:
        if _runtime_file_identity(before) != runtime.identity:
            raise CheckFailed("Node runtime changed before its descriptor was pinned")
        if (runtime.sha256, runtime.size) != (expected_sha256, expected_bytes):
            raise CheckFailed("Node runtime bytes differ from the manifest-attested toolchain binary")
        runtime.verify_execution(releases)
    except BaseException:
        runtime.close()
        raise
    return runtime


def verify_unchanged_release_inputs(
    args: argparse.Namespace,
    initial_history: dict[str, Any],
    initial_baseline: ReleaseArtifact,
    initial_candidate: ReleaseArtifact,
    initial_node: VerifiedNodeRuntime,
) -> None:
    final_history = verify_release_history(
        args.baseline_source_root,
        args.candidate_source_root,
        initial_baseline.source_commit,
        initial_candidate.source_commit,
    )
    final_baseline = verify_release_artifact(
        args.baseline_manifest,
        args.baseline_standalone_root,
        args.baseline_deployment_archive,
        args.baseline_source_root.expanduser().absolute(),
        label="baseline",
    )
    final_candidate = verify_release_artifact(
        args.candidate_manifest,
        args.candidate_standalone_root,
        args.candidate_deployment_archive,
        args.candidate_source_root.expanduser().absolute(),
        label="candidate",
    )
    final_node = verify_node_runtime(
        initial_node.path,
        (final_baseline, final_candidate),
        existing=initial_node,
    )

    changed = []
    if final_history != initial_history:
        changed.append("source history")
    if final_baseline != initial_baseline:
        changed.append("baseline artifact")
    if final_candidate != initial_candidate:
        changed.append("candidate artifact")
    if final_node is not initial_node:
        changed.append("Node runtime descriptor")
    if changed:
        raise CheckFailed(
            "release inputs changed after the browser transition: " + ", ".join(changed)
        )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-source-root", required=True, type=Path)
    parser.add_argument("--baseline-manifest", required=True, type=Path)
    parser.add_argument("--baseline-standalone-root", required=True, type=Path)
    parser.add_argument("--baseline-deployment-archive", required=True, type=Path)
    parser.add_argument("--candidate-source-root", required=True, type=Path)
    parser.add_argument("--candidate-manifest", required=True, type=Path)
    parser.add_argument("--candidate-standalone-root", required=True, type=Path)
    parser.add_argument("--candidate-deployment-archive", required=True, type=Path)
    parser.add_argument("--node-bin", required=True, type=Path)
    parser.add_argument("--web-port", type=int, default=3101)
    parser.add_argument("--chrome-debug-port", type=int, default=9323)
    parser.add_argument("--chrome-bin", default=None)
    parser.add_argument("--browser-timeout", type=float, default=30.0)
    parser.add_argument("--evidence-out", type=Path, default=None)
    return parser.parse_args(argv)


def main() -> int:
    args = parse_args()
    if not math.isfinite(args.browser_timeout) or args.browser_timeout <= 0:
        raise CheckFailed("--browser-timeout must be a positive finite number")
    require_isolated_loopback_ports({"PWA Web": args.web_port, "Chromium debug": args.chrome_debug_port})
    baseline_build_id = release_manifest_commit(args.baseline_manifest, "baseline")
    candidate_build_id = release_manifest_commit(args.candidate_manifest, "candidate")
    require_distinct_releases(baseline_build_id, candidate_build_id)
    release_history = verify_release_history(
        args.baseline_source_root,
        args.candidate_source_root,
        baseline_build_id,
        candidate_build_id,
    )
    baseline_artifact = verify_release_artifact(
        args.baseline_manifest,
        args.baseline_standalone_root,
        args.baseline_deployment_archive,
        args.baseline_source_root.expanduser().absolute(),
        label="baseline",
    )
    candidate_artifact = verify_release_artifact(
        args.candidate_manifest,
        args.candidate_standalone_root,
        args.candidate_deployment_archive,
        args.candidate_source_root.expanduser().absolute(),
        label="candidate",
    )
    verified_node = verify_node_runtime(args.node_bin, (baseline_artifact, candidate_artifact))
    lifecycle_lock = None
    temp_directory: Path | None = None
    owned_web_processes: list[subprocess.Popen[str]] = []
    owned_chrome_processes: list[subprocess.Popen[str]] = []
    chrome_process: subprocess.Popen[str] | None = None
    signal_guard = OwnedProcessSignalGuard()

    try:
        signal_guard.install()
        args.node_runtime = verified_node
        args.signal_guard = signal_guard
        lifecycle_lock = acquire_lifecycle_lock()
        temp_directory = Path(tempfile.mkdtemp(prefix="walksafe-pwa-release-update-"))
        temp_directory.chmod(0o700)
        rate_limit_directory = temp_directory / "gateway-rate-limits"
        rate_limit_directory.mkdir(mode=0o700)
        field_log_directory = temp_directory / "web-field-logs"
        field_log_directory.mkdir(mode=0o700)
        actor_id, account_token, backend_token, session_secret = new_lifecycle_credentials()
        base_environment = clean_child_environment(
            {
                "NEXT_PUBLIC_WALKSAFE_PWA_ENABLED": "true",
                "NEXT_PUBLIC_DETECTOR_MODE": "server-v2",
                "BACKEND_API_BASE_URL": "http://127.0.0.1:8000",
                "VOICE_API_BASE_URL": "http://127.0.0.1:9001",
                "WALKSAFE_FIELD_TEST_TOKEN": backend_token,
                "WALKSAFE_FIELD_ACCOUNTS_JSON": json.dumps(
                    [{"actor_id": actor_id, "token": account_token}]
                ),
                "WALKSAFE_GATEWAY_SESSION_SECRET": session_secret,
                "WALKSAFE_ALLOW_INSECURE_LOCAL_DEV": "false",
                "WALKSAFE_GATEWAY_RATE_LIMIT_DIR": str(rate_limit_directory),
                "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER": "x-real-ip",
                "WALKSAFE_ENVIRONMENT": "test",
                "WALKSAFE_WEB_REPLICAS": "1",
                "WALKSAFE_FIELD_LOG_DIR": str(field_log_directory),
                "NODE_ENV": "production",
            }
        )
        origin = f"http://127.0.0.1:{args.web_port}"
        baseline_environment = release_environment(
            base_environment,
            build_id=baseline_build_id,
            process_lock_path=temp_directory / "baseline-web.lock",
        )
        candidate_environment = release_environment(
            base_environment,
            build_id=candidate_build_id,
            process_lock_path=temp_directory / "candidate-web.lock",
        )
        baseline_process = start_release_server(
            args,
            baseline_artifact.build_root,
            baseline_environment,
            temp_directory / "baseline-web.log",
            owned_web_processes,
        )
        baseline_release = capture_release_http(
            origin,
            baseline_artifact.build_root.name,
            baseline_build_id,
            timeout=args.browser_timeout,
        )

        profile_directory = temp_directory / "chrome-profile"
        chrome_instance_url = f"data:text/html,walksafe-pwa-release-update-{secrets.token_hex(16)}"
        chrome_process = start_registered_process(
            [
                chrome_binary(args.chrome_bin),
                "--headless=new",
                "--remote-debugging-address=127.0.0.1",
                f"--remote-debugging-port={args.chrome_debug_port}",
                f"--user-data-dir={profile_directory}",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-networking",
                "--disable-gpu",
                chrome_instance_url,
            ],
            cwd=REPO_ROOT,
            env=clean_child_environment(),
            log_path=temp_directory / "chrome.log",
            pass_fds=(),
            registry=owned_chrome_processes,
            port=args.chrome_debug_port,
            label="release-update Chromium process",
            signal_guard=signal_guard,
        )
        wait_for_http(f"http://127.0.0.1:{args.chrome_debug_port}/json", timeout=30.0)
        if chrome_process.poll() is not None:
            raise CheckFailed(f"Chromium exited early; see {temp_directory / 'chrome.log'}")

        candidate_release, transition = asyncio.run(
            run_browser_transition(
                args,
                origin=origin,
                chrome_instance_url=chrome_instance_url,
                baseline_process=baseline_process,
                candidate_environment=candidate_environment,
                baseline_release=baseline_release,
                candidate_artifact=candidate_artifact,
                actor_id=actor_id,
                account_token=account_token,
                temp_directory=temp_directory,
                owned_web_processes=owned_web_processes,
            )
        )
        stop_owned_process(chrome_process, args.chrome_debug_port, "the release-update Chromium process")
        owned_chrome_processes.remove(chrome_process)
        chrome_process = None
        verify_unchanged_release_inputs(
            args,
            release_history,
            baseline_artifact,
            candidate_artifact,
            verified_node,
        )
        evidence = {
            "schema_version": "walksafe.pwa_release_update_evidence.v2",
            "captured_at": datetime.now(UTC).isoformat(),
            "origin": origin,
            "single_chrome_profile": True,
            "canonical_script_url": "/sw.js",
            "release_history": release_history,
            "artifact_prechecks": {
                "baseline": artifact_evidence(baseline_artifact),
                "candidate": artifact_evidence(candidate_artifact),
            },
            "baseline": baseline_release,
            "candidate": candidate_release,
            "timeline": transition,
            "claim_limits": [
                "local loopback headless Chromium release transition only",
                "does not prove HTTPS deployment, mobile PWA installation, or mobile update UX",
                "does not prove live TMAP, real phone camera/GPS, WebXR, tactile, TTS, or TalkBack behavior",
            ],
        }
    except BaseException as primary_error:
        cleanup_after_failure(
            primary_error,
            args,
            owned_chrome_processes,
            owned_web_processes,
        )
    else:
        cleanup_owned_processes(args, owned_chrome_processes, owned_web_processes)
    finally:
        try:
            if lifecycle_lock is not None:
                lifecycle_lock.close()
        finally:
            try:
                verified_node.close()
            finally:
                signal_guard.close()

    if args.evidence_out:
        publish_evidence(args.evidence_out, evidence)
    print("PASS: real two-build PWA service-worker waiting/apply/offline transition")
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    print(f"logs={temp_directory}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except CheckFailed as exc:
        print(f"[FAIL] {exc}", file=os.sys.stderr)
        raise SystemExit(1)
