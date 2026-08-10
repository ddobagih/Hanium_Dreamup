#!/usr/bin/env python3
"""Build and check the W3 internal engineering-evidence bundle.

The run directory is an immutable-during-generation raw input with this layout:

* ``command-receipt.json``: a fingerprinted object containing ``commands``.
  Every command has exactly ``command_id``, ``role``, ``argv``, ``cwd``,
  ``tool_version``, ``start``, ``end``, ``exit_code``, ``log_path``,
  ``log_sha256`` and ``output_paths``.  The exact command IDs are
  ``android-internal-build``, ``android-artifact-stage``,
  ``gateway-internal-build``, ``gateway-artifact-package`` and
  ``source-subject-capture``; each has a fixed role/cwd/argv/log/output set.
* ``logs/**``: one non-empty log per command, bound by ``log_sha256``.
* ``artifacts/user-app-internal.apk``,
  ``artifacts/admin-app-internal.apk`` and
  ``artifacts/android-gateway-dist.tar.gz``.
* ``subjects/backend.json``, ``subjects/model.json`` and
  ``subjects/config.json``: fingerprinted source-subject objects made with
  :func:`build_subject_payload`.  Each contains the exact repository-relative
  path/SHA-256 set and its path/content-set hashes.

The command receipt must list the six artifact/subject paths exactly once.
``run-dir`` and ``output-dir`` must be non-overlapping siblings in the path
tree, and each is a closed set with no extra files, directories or symlinks.
This tool never invokes Git, Gradle, npm or a product build.  It traverses the
filesystem, excludes generated/cache trees, verifies the raw run against the
current dirty-worktree bytes, and emits deterministic internal evidence only.
APK signing is not assessed because no apksigner receipt is an input.  This
tool does not claim signing state, release eligibility, deployment or approval.
"""

from __future__ import annotations

import argparse
import base64
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tarfile
from typing import Any, Iterable
from urllib.parse import quote
import uuid
import xml.etree.ElementTree as ET
import zipfile


SCHEMA_PREFIX = "walksafe.w3-engineering-evidence"
FINGERPRINT_CANONICALIZATION = (
    "UTF-8 JSON, ensure_ascii=false, sort_keys=true, separators=(comma,colon), "
    "integrity.content_fingerprint.value=null, trailing LF"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
PACKAGE_RE = re.compile(r"^([A-Za-z0-9_.-]+)==([^\s\\]+)")
GRADLE_COORDINATE_RE = re.compile(r"^([^:=\s]+):([^:=\s]+):([^=\s]+)=([^\n]+)$")
NPM_PACKAGE_SEGMENT_PATTERN = r"[A-Za-z0-9][A-Za-z0-9._~-]*"
NPM_PACKAGE_NAME_PATTERN = (
    rf"(?:@{NPM_PACKAGE_SEGMENT_PATTERN}/{NPM_PACKAGE_SEGMENT_PATTERN}|"
    rf"{NPM_PACKAGE_SEGMENT_PATTERN})"
)
NPM_INSTALLATION_PATH_RE = re.compile(
    rf"^node_modules/{NPM_PACKAGE_NAME_PATTERN}"
    rf"(?:/node_modules/{NPM_PACKAGE_NAME_PATTERN})*$"
)
PYTHON_LOGICAL_REQUIREMENT_RE = re.compile(
    r"^(?P<name>[A-Za-z0-9][A-Za-z0-9_.-]*)"
    r"(?:\[(?P<extras>[A-Za-z0-9_.-]+(?:,[A-Za-z0-9_.-]+)*)\])?"
    r"==(?P<version>[A-Za-z0-9][A-Za-z0-9_.+!-]*)"
    r"(?:\s*;\s*(?P<marker>.*?))?"
    r"(?P<hashes>(?:\s+--hash=sha256:[0-9a-f]{64})+)$"
)

EXCLUDED_DIRECTORY_NAMES = {
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    ".mypy_cache",
    ".next",
    ".pytest_cache",
    ".ruff_cache",
    ".tox",
    ".venv",
    "__pycache__",
    "build",
    "coverage",
    "dist",
    "node_modules",
    "out",
    "target",
    "venv",
}

W3_ARTIFACT_TYPE_IDS = [
    "DLV-DEV-01",
    "DLV-DEV-02",
    "DLV-DEV-03",
    "DLV-DEV-04",
    "DLV-DEV-05",
    "DLV-DEV-06",
    "DLV-DEV-07",
    "DLV-DEV-08",
    "DLV-DEV-09",
    "DLV-DEV-12",
    "DLV-DEV-14",
    "DLV-DEV-16",
    "DLV-DEV-17",
    "DLV-DEV-18",
    "DLV-DEV-19",
    "DLV-DEV-20",
    "DLV-DEV-21",
]

EXPECTED_RUN_OUTPUTS = {
    "artifacts/user-app-internal.apk": ("USER_APK", "user"),
    "artifacts/admin-app-internal.apk": ("ADMIN_APK", "admin"),
    "artifacts/android-gateway-dist.tar.gz": ("GATEWAY_DIST_ARCHIVE", "gateway"),
    "subjects/backend.json": ("BACKEND_SOURCE_SUBJECT", "backend"),
    "subjects/model.json": ("MODEL_SOURCE_SUBJECT", "model-config"),
    "subjects/config.json": ("CONFIG_SOURCE_SUBJECT", "model-config"),
}

COMMAND_CONTRACTS = {
    "android-internal-build": {
        "role": "ANDROID_INTERNAL_BUILD",
        "cwd": "apps/android",
        "log_path": "logs/android-internal-build.log",
        "output_paths": [],
        "argv": [
            "./gradlew",
            ":app:assembleDebug",
            ":adminapp:assembleDebug",
        ],
    },
    "android-artifact-stage": {
        "role": "ANDROID_ARTIFACT_STAGE",
        "cwd": ".",
        "log_path": "logs/android-artifact-stage.log",
        "output_paths": [
            "artifacts/user-app-internal.apk",
            "artifacts/admin-app-internal.apk",
        ],
        "argv": None,
    },
    "gateway-internal-build": {
        "role": "GATEWAY_INTERNAL_BUILD",
        "cwd": "apps/android-gateway",
        "log_path": "logs/gateway-internal-build.log",
        "output_paths": [],
        "argv": ["npm", "run", "build"],
    },
    "gateway-artifact-package": {
        "role": "GATEWAY_ARTIFACT_PACKAGE",
        "cwd": ".",
        "log_path": "logs/gateway-artifact-package.log",
        "output_paths": ["artifacts/android-gateway-dist.tar.gz"],
        "argv": None,
    },
    "source-subject-capture": {
        "role": "SOURCE_SUBJECT_CAPTURE",
        "cwd": ".",
        "log_path": "logs/source-subject-capture.log",
        "output_paths": [
            "subjects/backend.json",
            "subjects/model.json",
            "subjects/config.json",
        ],
        "argv": None,
    },
}

ANDROID_STAGE_PROGRAM = """\
import shutil
import sys
from pathlib import Path

repo = Path.cwd().resolve()
run = (repo / sys.argv[1]).resolve() if not Path(sys.argv[1]).is_absolute() else Path(sys.argv[1]).resolve()
sources = {
    "user-app-internal.apk": repo / "apps/android/app/build/outputs/apk/debug/app-debug.apk",
    "admin-app-internal.apk": repo / "apps/android/adminapp/build/outputs/apk/debug/adminapp-debug.apk",
}
for name, source in sources.items():
    if source.is_symlink() or not source.is_file() or source.stat().st_size <= 0:
        raise SystemExit(f"invalid Android build output: {source}")
    target = run / "artifacts" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
"""

GATEWAY_PACKAGE_PROGRAM = """\
import sys
import tarfile
from pathlib import Path

repo = Path.cwd().resolve()
run = (repo / sys.argv[1]).resolve() if not Path(sys.argv[1]).is_absolute() else Path(sys.argv[1]).resolve()
root = repo / "apps/android-gateway"
paths = [root / "package.json", root / "package-lock.json"]
paths.extend(sorted((root / "dist").rglob("*")))
files = []
for source in paths:
    if source.is_symlink():
        raise SystemExit(f"gateway package symlink rejected: {source}")
    if source.is_file():
        files.append(source)
target = run / "artifacts/android-gateway-dist.tar.gz"
target.parent.mkdir(parents=True, exist_ok=True)
with tarfile.open(target, "w:gz", format=tarfile.PAX_FORMAT) as archive:
    for source in files:
        relative = source.relative_to(root).as_posix()
        info = tarfile.TarInfo(relative)
        data = source.read_bytes()
        info.size = len(data)
        info.mode = 0o644
        info.mtime = 0
        archive.addfile(info, __import__("io").BytesIO(data))
"""

SUBJECT_CAPTURE_PROGRAM = """\
import sys
from pathlib import Path
from scripts import build_walksafe_w3_engineering_evidence_20260726 as builder

repo = Path.cwd().resolve()
run = (repo / sys.argv[1]).resolve() if not Path(sys.argv[1]).is_absolute() else Path(sys.argv[1]).resolve()
for subject_id in ("backend", "model", "config"):
    path = run / "subjects" / f"{subject_id}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(builder._json_bytes(builder.build_subject_payload(repo, subject_id)))
"""

OUTPUT_FILENAMES = (
    "source-snapshot.json",
    "module-inventory.json",
    "lock-inventory.json",
    "fixture-inventory.json",
    "sbom.spdx.json",
    "sbom.cyclonedx.json",
    "internal-build-provenance.json",
    "engineering-evidence-manifest.json",
)

MODULE_DEFINITIONS = (
    {
        "module_id": "app",
        "component_scope": "user",
        "roots": ("apps/android/app",),
        "excluded_roots": (
            "apps/android/app/src/main/assets/model-config",
            "apps/android/app/src/main/assets/models",
        ),
    },
    {
        "module_id": "adminapp",
        "component_scope": "admin",
        "roots": ("apps/android/adminapp",),
        "excluded_roots": (),
    },
    {
        "module_id": "android-gateway",
        "component_scope": "gateway",
        "roots": ("apps/android-gateway",),
        "excluded_roots": (),
    },
    {
        "module_id": "backend",
        "component_scope": "backend",
        "roots": ("backend",),
        "excluded_roots": (),
    },
    {
        "module_id": "model-config",
        "component_scope": "model-config",
        "roots": (
            "model",
            "configs",
            "apps/android/app/src/main/assets/model-config",
            "apps/android/app/src/main/assets/models",
        ),
        "excluded_roots": (),
    },
)

SUBJECT_DEFINITIONS = {
    "backend": ("backend",),
    "model": ("model", "apps/android/app/src/main/assets/models"),
    "config": ("configs", "apps/android/app/src/main/assets/model-config"),
}

LOCK_DEFINITIONS = (
    ("app-gradle", "gradle", "user", "apps/android/app/gradle.lockfile"),
    ("adminapp-gradle", "gradle", "admin", "apps/android/adminapp/gradle.lockfile"),
    (
        "android-verification-metadata",
        "gradle-verification",
        "user+admin",
        "apps/android/gradle/verification-metadata.xml",
    ),
    (
        "android-gateway-package-lock",
        "npm",
        "gateway",
        "apps/android-gateway/package-lock.json",
    ),
    ("backend-requirements-lock", "python", "backend", "backend/requirements.lock"),
)

FORMAL_TEST_REGISTER = "docs/deliverables/06-testing/registers/test-cases.json"
CANONICAL_FORMAL_TEST_REGISTER_SHA256 = (
    "19a6b425bf3a0ee880f1c9229a42b96845ab4c3a1f99037783fe872426e0deee"
)
CANONICAL_FORMAL_TEST_ID_SET_SHA256 = (
    "8ab0a039d0a6f9fcaad753bac08428da0f74ef27d40426ad4da49a28d5a0f167"
)
CANONICAL_FORMAL_TEST_ORDER_SHA256 = (
    "681b6ad6c222aa73dfcf1bedb021279eca72342a108017688817e3afbfdfe395"
)
GENERATOR_RELATIVE_PATH = "scripts/build_walksafe_w3_engineering_evidence_20260726.py"
CANONICAL_W3_EXECUTION_ROOT = PurePosixPath(
    "docs/control/execution/artifact-remediation/20260726/w3"
)
RESERVED_W3_EXECUTION_DIRECTORY_RE = re.compile(
    r"^(?:run|evidence|aux-execution)-[0-9]{8}-[0-9]{3}$"
)
CAPTURED_SOURCE_BYTE_PATHS = {
    *(definition[3] for definition in LOCK_DEFINITIONS),
    FORMAL_TEST_REGISTER,
    GENERATOR_RELATIVE_PATH,
}


class EvidenceError(RuntimeError):
    """Raised when raw evidence, repository state or generated output is invalid."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceError(message)


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
        + b"\n"
    )


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            indent=2,
            allow_nan=False,
        ).encode("utf-8")
        + b"\n"
    )


def _content_set_sha256(records: Iterable[dict[str, Any]]) -> str:
    normalized = [
        {
            "path": str(record["path"]),
            "sha256": str(record["sha256"]),
            "bytes": int(record["bytes"]),
        }
        for record in records
    ]
    paths = [record["path"] for record in normalized]
    _require(len(paths) == len(set(paths)), "content set has duplicate paths")
    normalized.sort(key=lambda record: record["path"])
    return _sha256_bytes(_canonical_bytes(normalized))


def _path_set_sha256(paths: Iterable[str]) -> str:
    ordered = sorted(paths)
    return _sha256_bytes(("".join(f"{path}\n" for path in ordered)).encode("utf-8"))


def seal_payload(payload: dict[str, Any]) -> dict[str, Any]:
    """Return a deep-copied payload with the common self-fingerprint applied."""

    value = copy.deepcopy(payload)
    integrity = value.setdefault("integrity", {})
    _require(isinstance(integrity, dict), "integrity must be an object")
    integrity["content_fingerprint"] = {
        "algorithm": "SHA-256",
        "canonicalization": FINGERPRINT_CANONICALIZATION,
        "value": None,
    }
    integrity["content_fingerprint"]["value"] = _sha256_bytes(_canonical_bytes(value))
    return value


def _validate_fingerprint(payload: dict[str, Any], context: str) -> None:
    fingerprint = payload.get("integrity", {}).get("content_fingerprint")
    _require(isinstance(fingerprint, dict), f"{context}: content fingerprint missing")
    _require(
        set(fingerprint) == {"algorithm", "canonicalization", "value"},
        f"{context}: content fingerprint fields differ",
    )
    _require(fingerprint["algorithm"] == "SHA-256", f"{context}: fingerprint algorithm differs")
    _require(
        fingerprint["canonicalization"] == FINGERPRINT_CANONICALIZATION,
        f"{context}: fingerprint canonicalization differs",
    )
    recorded = fingerprint["value"]
    _require(
        isinstance(recorded, str) and SHA256_RE.fullmatch(recorded) is not None,
        f"{context}: fingerprint value invalid",
    )
    candidate = copy.deepcopy(payload)
    candidate["integrity"]["content_fingerprint"]["value"] = None
    _require(
        _sha256_bytes(_canonical_bytes(candidate)) == recorded,
        f"{context}: content fingerprint mismatch",
    )


def _reject_constant(value: str) -> None:
    raise EvidenceError(f"non-standard JSON number is not allowed: {value}")


def _object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        _require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_strict_json(path: Path, context: str) -> dict[str, Any]:
    return load_strict_json_bytes(path.read_bytes(), context)


def load_strict_json_bytes(raw: bytes, context: str) -> dict[str, Any]:
    _require(not raw.startswith(b"\xef\xbb\xbf"), f"{context}: UTF-8 BOM is not allowed")
    try:
        payload = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_object_pairs,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise EvidenceError(f"{context}: invalid JSON: {exc}") from exc
    _require(isinstance(payload, dict), f"{context}: JSON root must be an object")
    return payload


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _safe_relative(raw: str, context: str) -> PurePosixPath:
    _require(isinstance(raw, str) and raw and "\\" not in raw, f"{context}: unsafe path")
    relative = PurePosixPath(raw)
    _require(not relative.is_absolute(), f"{context}: absolute path is not allowed")
    _require(".." not in relative.parts, f"{context}: parent traversal is not allowed")
    parts = tuple(part for part in relative.parts if part not in {"", "."})
    _require(parts, f"{context}: empty path")
    return PurePosixPath(*parts)


def _reject_original_path_symlinks(path: Path, context: str) -> Path:
    original = Path(os.fspath(path))
    if original.is_absolute():
        current = Path(original.anchor)
        parts = original.parts[1:]
    else:
        current = Path.cwd()
        parts = original.parts
    for part in parts:
        if part in {"", "."}:
            continue
        if part == "..":
            current = current.parent
            continue
        current = current / part
        _require(not current.is_symlink(), f"{context} contains a symlink: {current}")
    return current


def _validated_root(path: Path, context: str) -> Path:
    original = _reject_original_path_symlinks(path, context)
    _require(
        original.exists() and original.is_dir() and not original.is_symlink(),
        f"{context} must be a directory",
    )
    return original.resolve()


def _validated_input_file(root: Path, relative: str, context: str) -> Path:
    safe = _safe_relative(relative, context)
    current = root
    for part in safe.parts:
        current = current / part
        _require(not current.is_symlink(), f"{context}: symlink is not allowed: {relative}")
    resolved = current.resolve()
    _require(_is_within(resolved, root), f"{context}: path escaped its root")
    _require(resolved.is_file(), f"{context}: required file missing: {relative}")
    _require(stat.S_ISREG(resolved.stat().st_mode), f"{context}: not a regular file")
    _require(resolved.stat().st_size > 0, f"{context}: empty file: {relative}")
    return resolved


def _stable_regular_file_read(
    path: Path,
    *,
    capture_bytes: bool = False,
    context: str,
) -> tuple[tuple[int, int, int, int, str], bytes | None]:
    descriptor: int | None = None
    try:
        try:
            path_before = path.lstat()
            _require(
                not stat.S_ISLNK(path_before.st_mode),
                f"{context}: file symlink is not allowed: {path}",
            )
            _require(
                stat.S_ISREG(path_before.st_mode),
                f"{context}: not a regular file: {path}",
            )
            flags = (
                os.O_RDONLY
                | getattr(os, "O_NOFOLLOW", 0)
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NONBLOCK", 0)
                | getattr(os, "O_BINARY", 0)
            )
            descriptor = os.open(path, flags)
            before = os.fstat(descriptor)
            _require(
                stat.S_ISREG(before.st_mode),
                f"{context}: opened entry is not a regular file: {path}",
            )
            _require(
                (path_before.st_dev, path_before.st_ino)
                == (before.st_dev, before.st_ino),
                f"{context}: file path changed before stable read: {path}",
            )
            digest = hashlib.sha256()
            captured = bytearray() if capture_bytes else None
            byte_count = 0
            while True:
                chunk = os.read(descriptor, 1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
                byte_count += len(chunk)
                if captured is not None:
                    captured.extend(chunk)
            after = os.fstat(descriptor)
            _require(
                stat.S_ISREG(after.st_mode),
                f"{context}: entry ceased to be a regular file: {path}",
            )
            before_signature = (
                before.st_dev,
                before.st_ino,
                before.st_size,
                before.st_mtime_ns,
            )
            after_signature = (
                after.st_dev,
                after.st_ino,
                after.st_size,
                after.st_mtime_ns,
            )
            _require(
                before_signature == after_signature,
                f"{context}: file changed while reading: {path}",
            )
            _require(
                byte_count == after.st_size,
                f"{context}: stable read byte count differs: {path}",
            )
        finally:
            if descriptor is not None:
                os.close(descriptor)
                descriptor = None
        path_after = path.lstat()
    except OSError as exc:
        raise EvidenceError(
            f"{context}: stable regular file I/O failed: {path}"
        ) from exc
    _require(
        not stat.S_ISLNK(path_after.st_mode),
        f"{context}: file path became a symlink: {path}",
    )
    _require(
        stat.S_ISREG(path_after.st_mode),
        f"{context}: file path is not a regular file after read: {path}",
    )
    _require(
        (
            path_after.st_dev,
            path_after.st_ino,
            path_after.st_size,
            path_after.st_mtime_ns,
        )
        == after_signature,
        f"{context}: file path changed after stable read: {path}",
    )
    return (
        (*after_signature, digest.hexdigest()),
        bytes(captured) if captured is not None else None,
    )


def _stable_file_record(
    path: Path, root: Path, *, capture_bytes: bool = False
) -> tuple[dict[str, Any], tuple[int, int, int, int], bytes | None]:
    resolved = path.resolve()
    _require(_is_within(resolved, root), f"file escaped root: {path}")
    path_before = path.lstat()
    _require(not stat.S_ISLNK(path_before.st_mode), f"file symlink is not allowed: {path}")
    _require(stat.S_ISREG(path_before.st_mode), f"not a regular file: {path}")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        before = os.fstat(descriptor)
        _require(
            (path_before.st_dev, path_before.st_ino) == (before.st_dev, before.st_ino),
            f"file path changed before stable read: {path}",
        )
        digest = hashlib.sha256()
        captured: list[bytes] | None = [] if capture_bytes else None
        with os.fdopen(descriptor, "rb", closefd=False) as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
                if captured is not None:
                    captured.append(chunk)
        after = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    path_after = path.lstat()
    _require(
        (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
        == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns),
        f"file changed while hashing: {path}",
    )
    _require(
        (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
        == (
            path_after.st_dev,
            path_after.st_ino,
            path_after.st_size,
            path_after.st_mtime_ns,
        ),
        f"file path changed after stable read: {path}",
    )
    signature = (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
    return (
        {
            "path": resolved.relative_to(root).as_posix(),
            "sha256": digest.hexdigest(),
            "bytes": after.st_size,
        },
        signature,
        b"".join(captured) if captured is not None else None,
    )


def _file_record(path: Path, root: Path) -> dict[str, Any]:
    return _stable_file_record(path, root)[0]


def _reserved_w3_execution_roots(repo_root: Path) -> tuple[Path, ...]:
    current = repo_root
    for part in CANONICAL_W3_EXECUTION_ROOT.parts:
        current = current / part
        _require(
            _is_within(current, repo_root),
            "canonical W3 execution root escapes repository",
        )
        if current.is_symlink() or not current.is_dir():
            return ()
    return tuple(
        sorted(
            (
                child
                for child in current.iterdir()
                if RESERVED_W3_EXECUTION_DIRECTORY_RE.fullmatch(child.name)
                and not child.is_symlink()
                and child.is_dir()
            ),
            key=lambda path: path.name,
        )
    )


def _repository_excluded_roots(
    repo_root: Path,
    run_root: Path,
    output_root: Path,
) -> tuple[Path, ...]:
    return (run_root, output_root, *_reserved_w3_execution_roots(repo_root))


def _walk_files(root: Path, excluded_roots: Iterable[Path] = ()) -> list[Path]:
    root = root.resolve()
    exclusions = tuple(path.resolve() for path in excluded_roots)
    result: list[Path] = []
    for current_raw, directories, filenames in os.walk(root, topdown=True, followlinks=False):
        current = Path(current_raw)
        kept_directories: list[str] = []
        for name in sorted(directories):
            candidate = current / name
            _require(not candidate.is_symlink(), f"source directory symlink is not allowed: {candidate}")
            if name in EXCLUDED_DIRECTORY_NAMES:
                continue
            if any(_is_within(candidate.resolve(), excluded) for excluded in exclusions):
                continue
            kept_directories.append(name)
        directories[:] = kept_directories
        for name in sorted(filenames):
            path = current / name
            _require(not path.is_symlink(), f"source file symlink is not allowed: {path}")
            if any(_is_within(path.resolve(), excluded) for excluded in exclusions):
                continue
            if path.is_file():
                result.append(path)
    return sorted(result, key=lambda value: value.relative_to(root).as_posix())


def _repository_snapshot(
    repo_root: Path, *, excluded_roots: Iterable[Path]
) -> tuple[
    dict[str, Any],
    dict[str, dict[str, Any]],
    dict[str, bytes],
    dict[str, tuple[int, int, int, int]],
]:
    pre_files = _walk_files(repo_root, excluded_roots)
    pre_stats: dict[str, tuple[int, int, int, int]] = {}
    for path in pre_files:
        info = path.lstat()
        pre_stats[path.relative_to(repo_root).as_posix()] = (
            info.st_dev,
            info.st_ino,
            info.st_size,
            info.st_mtime_ns,
        )
    stable = [
        _stable_file_record(
            path,
            repo_root,
            capture_bytes=path.relative_to(repo_root).as_posix()
            in CAPTURED_SOURCE_BYTE_PATHS,
        )
        for path in pre_files
    ]
    records = [record for record, _signature, _raw in stable]
    _require(
        {
            record["path"]: signature
            for record, signature, _raw in stable
        }
        == pre_stats,
        "repository path/stat set changed before stable reads completed",
    )
    post_files = _walk_files(repo_root, excluded_roots)
    post_stats: dict[str, tuple[int, int, int, int]] = {}
    for path in post_files:
        info = path.lstat()
        post_stats[path.relative_to(repo_root).as_posix()] = (
            info.st_dev,
            info.st_ino,
            info.st_size,
            info.st_mtime_ns,
        )
    _require(pre_stats == post_stats, "repository pre/post path and stat set differs")
    _require(records, "repository source inventory is empty")
    paths = [record["path"] for record in records]
    _require(len(paths) == len(set(paths)), "repository source inventory has duplicate paths")
    payload = seal_payload(
        {
            "schema_version": f"{SCHEMA_PREFIX}.source-snapshot.v1",
            "document_id": "WALKSAFE-W3-DIRTY-WORKTREE-SOURCE-SNAPSHOT-20260726",
            "prepared_on": "2026-07-26",
            "source_commit": None,
            "source_identity_basis": "FILESYSTEM_TRAVERSAL_NOT_GIT",
            "worktree_state": "DIRTY_WORKTREE_EXACT_CONTENT_SNAPSHOT",
            "exclusion_policy": {
                "directory_names": sorted(EXCLUDED_DIRECTORY_NAMES),
                "dynamic_run_and_output_roots_excluded": True,
                "canonical_w3_execution_root": CANONICAL_W3_EXECUTION_ROOT.as_posix(),
                "reserved_direct_child_pattern": (
                    RESERVED_W3_EXECUTION_DIRECTORY_RE.pattern
                ),
            },
            "files": records,
            "summary": {
                "file_count": len(records),
                "path_set_sha256": _path_set_sha256(paths),
                "content_set_sha256": _content_set_sha256(records),
                "stable_file_read_count": len(records),
                "pre_post_path_stat_set_match": True,
            },
            "claim_boundary": {
                "git_invoked": False,
                "clean_commit_claimed": False,
                "release_or_approval_claimed": False,
            },
        }
    )
    source_bytes = {
        record["path"]: raw
        for record, _signature, raw in stable
        if raw is not None
    }
    _require(
        set(source_bytes) == CAPTURED_SOURCE_BYTE_PATHS,
        "captured source byte set differs from required parser inputs",
    )
    return (
        payload,
        {record["path"]: record for record in records},
        source_bytes,
        pre_stats,
    )


def _recheck_repository_stat_set(
    repo_root: Path,
    excluded_roots: Iterable[Path],
    expected: dict[str, tuple[int, int, int, int]],
) -> None:
    actual = _repository_stat_set(repo_root, excluded_roots)
    _require(actual == expected, "repository changed during evidence generation")


def _recheck_repository_content_map(
    repo_root: Path,
    excluded_roots: Iterable[Path],
    expected: dict[str, dict[str, Any]],
) -> None:
    files = _walk_files(repo_root, excluded_roots)
    actual_paths = {path.relative_to(repo_root).as_posix() for path in files}
    _require(
        actual_paths == set(expected),
        "repository included path set changed during evidence generation",
    )
    for path in files:
        record = _file_record(path, repo_root)
        _require(
            record == expected[record["path"]],
            f"repository source SHA-256 changed: {record['path']}",
        )


def _repository_stat_set(
    repo_root: Path,
    excluded_roots: Iterable[Path],
) -> dict[str, tuple[int, int, int, int]]:
    actual: dict[str, tuple[int, int, int, int]] = {}
    for path in _walk_files(repo_root, excluded_roots):
        info = path.lstat()
        actual[path.relative_to(repo_root).as_posix()] = (
            info.st_dev,
            info.st_ino,
            info.st_size,
            info.st_mtime_ns,
        )
    return actual


def _path_is_under(path: str, root: str) -> bool:
    candidate = PurePosixPath(path)
    base = PurePosixPath(root)
    return candidate == base or base in candidate.parents


def _module_owners(path: str) -> list[str]:
    owners: list[str] = []
    for definition in MODULE_DEFINITIONS:
        included = any(_path_is_under(path, root) for root in definition["roots"])
        excluded = any(_path_is_under(path, root) for root in definition["excluded_roots"])
        if included and not excluded:
            owners.append(str(definition["module_id"]))
    return owners


def _build_module_inventory(source_by_path: dict[str, dict[str, Any]], repo_root: Path) -> dict[str, Any]:
    modules: list[dict[str, Any]] = []
    owned_paths: set[str] = set()
    for definition in MODULE_DEFINITIONS:
        for root in definition["roots"]:
            _require((repo_root / root).is_dir(), f"module root missing: {root}")
        records = [
            copy.deepcopy(record)
            for path, record in sorted(source_by_path.items())
            if definition["module_id"] in _module_owners(path)
        ]
        _require(records, f"module has no source files: {definition['module_id']}")
        paths = [record["path"] for record in records]
        overlap = owned_paths.intersection(paths)
        if overlap:
            raise EvidenceError(f"duplicate module ownership: {sorted(overlap)[0]}")
        owned_paths.update(paths)
        modules.append(
            {
                "module_id": definition["module_id"],
                "component_scope": definition["component_scope"],
                "actual_roots": list(definition["roots"]),
                "excluded_subtrees": list(definition["excluded_roots"]),
                "source_files": records,
                "source_file_count": len(records),
                "path_set_sha256": _path_set_sha256(paths),
                "content_set_sha256": _content_set_sha256(records),
            }
        )
    payload = seal_payload(
        {
            "schema_version": f"{SCHEMA_PREFIX}.module-inventory.v1",
            "document_id": "WALKSAFE-W3-MODULE-INVENTORY-20260726",
            "prepared_on": "2026-07-26",
            "source_commit": None,
            "ownership_rule": "EXACTLY_ONE_OWNER_WITH_EXPLICIT_MODEL_CONFIG_SUBTREE_EXCLUSIONS",
            "modules": modules,
            "summary": {
                "module_count": len(modules),
                "owned_source_file_count": len(owned_paths),
                "duplicate_ownership_count": 0,
                "module_ids": [module["module_id"] for module in modules],
                "component_scopes": [module["component_scope"] for module in modules],
            },
            "claim_boundary": {
                "inventory_is_implementation_evidence": True,
                "deployment_or_release_claimed": False,
            },
        }
    )
    _validate_module_inventory_payload(payload)
    return payload


def _validate_module_inventory_payload(payload: dict[str, Any]) -> None:
    modules = payload.get("modules")
    _require(isinstance(modules, list), "module inventory: modules must be a list")
    expected_ids = [definition["module_id"] for definition in MODULE_DEFINITIONS]
    _require(
        [module.get("module_id") for module in modules if isinstance(module, dict)] == expected_ids,
        "module inventory: module set or order differs",
    )
    owner_by_path: dict[str, str] = {}
    for module in modules:
        _require(isinstance(module, dict), "module inventory: module must be an object")
        records = module.get("source_files")
        _require(isinstance(records, list) and records, "module inventory: source files missing")
        paths = [record.get("path") for record in records if isinstance(record, dict)]
        _require(len(paths) == len(records), "module inventory: malformed source record")
        _require(len(paths) == len(set(paths)), "module inventory: duplicate path inside module")
        _require(
            module.get("source_file_count") == len(records),
            "module inventory: source file count differs",
        )
        for path in paths:
            _require(isinstance(path, str), "module inventory: source path invalid")
            _require(
                path not in owner_by_path,
                f"module inventory: duplicate ownership for {path}",
            )
            owner_by_path[path] = str(module["module_id"])
            _require(
                _module_owners(path) == [module["module_id"]],
                f"module inventory: configured ownership differs for {path}",
            )
    summary = payload.get("summary")
    _require(isinstance(summary, dict), "module inventory: summary missing")
    _require(summary.get("duplicate_ownership_count") == 0, "module inventory: duplicate count differs")
    _require(
        summary.get("owned_source_file_count") == len(owner_by_path),
        "module inventory: owned file count differs",
    )


def _parse_verification_metadata(raw: bytes) -> dict[str, list[dict[str, str]]]:
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as exc:
        raise EvidenceError(f"invalid Gradle verification metadata: {exc}") from exc
    result: dict[str, list[dict[str, str]]] = {}
    for component in root.findall(".//{*}component"):
        group = component.get("group")
        name = component.get("name")
        version = component.get("version")
        _require(group and name and version, "Gradle verification component is incomplete")
        artifacts: list[dict[str, str]] = []
        artifact_names: set[str] = set()
        for artifact in component.findall("./{*}artifact"):
            artifact_name = artifact.get("name")
            _require(artifact_name, "Gradle verification artifact name missing")
            _require(
                artifact_name not in artifact_names,
                f"duplicate Gradle verification artifact name: {artifact_name}",
            )
            artifact_names.add(artifact_name)
            sha_nodes = artifact.findall("./{*}sha256")
            _require(
                len(sha_nodes) == 1,
                f"Gradle verification artifact must have exactly one SHA-256: {artifact_name}",
            )
            value = sha_nodes[0].get("value")
            _require(
                isinstance(value, str) and SHA256_RE.fullmatch(value) is not None,
                f"Gradle verification hash invalid: {artifact_name}",
            )
            artifacts.append({"artifact": artifact_name, "sha256": value})
        _require(
            artifacts,
            f"Gradle verification component has no artifacts: {group}:{name}:{version}",
        )
        artifact_keys = [(item["artifact"], item["sha256"]) for item in artifacts]
        _require(
            len(artifact_keys) == len(set(artifact_keys)),
            f"duplicate Gradle artifact checksum: {group}:{name}:{version}",
        )
        coordinate = f"{group}:{name}:{version}"
        _require(
            coordinate not in result,
            f"duplicate Gradle verification component: {coordinate}",
        )
        result[coordinate] = sorted(
            artifacts, key=lambda item: (item["artifact"], item["sha256"])
        )
    _require(result, "Gradle verification metadata has no components")
    return result


def _parse_gradle_lock(
    raw: bytes,
    *,
    label: str,
    scope: str,
    verification: dict[str, list[dict[str, str]]],
) -> list[dict[str, Any]]:
    dependencies: list[dict[str, Any]] = []
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise EvidenceError(f"Gradle lock is not UTF-8: {label}") from exc
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("empty="):
            continue
        match = GRADLE_COORDINATE_RE.fullmatch(line)
        _require(match is not None, f"invalid Gradle lock line: {line}")
        group, name, version, configurations = match.groups()
        coordinate = f"{group}:{name}:{version}"
        artifacts = verification.get(coordinate, [])
        _require(artifacts, f"Gradle lock dependency lacks artifact checksum: {coordinate}")
        dependencies.append(
            {
                "ecosystem": "maven",
                "scope": scope,
                "name": f"{group}:{name}",
                "version": version,
                "coordinate": coordinate,
                "configurations": sorted(set(configurations.split(","))),
                "verification_artifacts": artifacts,
                "verification_status": "HASHED" if artifacts else "NO_MATCHING_METADATA_HASH",
                "purl": f"pkg:maven/{quote(group, safe='')}/{quote(name, safe='')}@{quote(version, safe='')}",
            }
        )
    _require(dependencies, f"Gradle lock is empty: {label}")
    coordinates = [item["coordinate"] for item in dependencies]
    _require(
        len(coordinates) == len(set(coordinates)),
        f"Gradle lock has duplicate coordinates: {label}",
    )
    return sorted(dependencies, key=lambda item: (item["scope"], item["coordinate"]))


def _parse_npm_lock(raw: bytes) -> list[dict[str, Any]]:
    payload = load_strict_json_bytes(raw, "gateway package-lock")
    _require(payload.get("lockfileVersion") == 3, "gateway package-lock version must be 3")
    packages = payload.get("packages")
    _require(isinstance(packages, dict), "gateway package-lock packages missing")
    root_package = packages.get("")
    _require(isinstance(root_package, dict), "gateway package-lock root package missing")
    _require(
        isinstance(root_package.get("name"), str)
        and root_package["name"]
        and isinstance(root_package.get("version"), str)
        and root_package["version"],
        "gateway package-lock root package identity missing",
    )
    dependencies: list[dict[str, Any]] = []
    installation_paths: set[str] = set()
    for package_path, item in sorted(packages.items()):
        if package_path == "":
            continue
        _require(isinstance(item, dict), f"gateway lock package invalid: {package_path}")
        safe_installation = _safe_relative(package_path, "npm installation path").as_posix()
        _require(
            package_path == safe_installation,
            f"gateway lock installation path is not canonical: {package_path}",
        )
        _require(
            NPM_INSTALLATION_PATH_RE.fullmatch(safe_installation) is not None,
            f"gateway lock installation path invalid: {package_path}",
        )
        _require(
            safe_installation not in installation_paths,
            f"duplicate gateway installation path: {package_path}",
        )
        installation_paths.add(safe_installation)
        name = safe_installation.rsplit("/node_modules/", 1)[-1].removeprefix(
            "node_modules/"
        )
        actual_name = item.get("name", name)
        _require(
            isinstance(actual_name, str) and actual_name == name,
            f"gateway lock actual package name differs: {package_path}",
        )
        version = item.get("version")
        integrity = item.get("integrity")
        _require(isinstance(version, str) and version, f"gateway lock version missing: {name}")
        hashes: list[dict[str, str]] = []
        _require(isinstance(integrity, str) and integrity, f"gateway lock SRI missing: {name}")
        sri_items = integrity.split()
        _require(sri_items, f"gateway lock SRI token list is empty: {name}")
        _require(
            len(sri_items) == len(set(sri_items)),
            f"duplicate gateway lock SRI token: {name}",
        )
        seen_sri_hashes: set[tuple[str, str]] = set()
        for sri_item in sri_items:
            _require("-" in sri_item, f"gateway lock SRI invalid: {name}")
            algorithm, encoded = sri_item.split("-", 1)
            expected_lengths = {"sha256": 32, "sha384": 48, "sha512": 64}
            _require(algorithm in expected_lengths, f"gateway lock SRI algorithm invalid: {name}")
            try:
                decoded_bytes = base64.b64decode(encoded, validate=True)
            except (ValueError, base64.binascii.Error) as exc:
                raise EvidenceError(f"gateway lock integrity invalid: {name}") from exc
            _require(
                len(decoded_bytes) == expected_lengths[algorithm],
                f"gateway lock SRI digest length invalid: {name}",
            )
            digest = decoded_bytes.hex()
            sri_key = (algorithm, digest)
            _require(
                sri_key not in seen_sri_hashes,
                f"duplicate gateway lock SRI hash: {name}",
            )
            seen_sri_hashes.add(sri_key)
            hashes.append({"algorithm": algorithm.upper(), "value": digest})
        dependencies.append(
            {
                "ecosystem": "npm",
                "scope": "gateway",
                "name": actual_name,
                "installation_path": safe_installation,
                "version": version,
                "development": bool(item.get("dev", False)),
                "integrity": hashes,
                "sri": integrity,
                "purl": f"pkg:npm/{quote(actual_name, safe='/')}@{quote(version, safe='')}",
            }
        )
    _require(dependencies, "gateway package-lock has no resolved dependencies")
    return dependencies


def _parse_python_lock(raw: bytes) -> list[dict[str, Any]]:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise EvidenceError("backend requirements.lock is not UTF-8") from exc

    logical_blocks: list[tuple[int, str]] = []
    parts: list[str] = []
    block_start = 0
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if not parts:
            block_start = line_number
        continues = stripped.endswith("\\")
        part = stripped[:-1].rstrip() if continues else stripped
        _require(part, f"backend lock empty logical line at line {line_number}")
        parts.append(part)
        if not continues:
            logical_blocks.append((block_start, " ".join(parts)))
            parts = []
    _require(not parts, f"backend lock unterminated continuation at line {block_start}")

    dependencies: list[dict[str, Any]] = []
    for line_number, block in logical_blocks:
        match = PYTHON_LOGICAL_REQUIREMENT_RE.fullmatch(block)
        _require(
            match is not None,
            f"backend lock logical requirement is invalid at line {line_number}",
        )
        name = match.group("name")
        version = match.group("version")
        extras_raw = match.group("extras")
        marker_raw = match.group("marker")
        extras = sorted(extras_raw.split(",")) if extras_raw else []
        _require(len(extras) == len(set(extras)), f"duplicate Python extra: {name}")
        marker = marker_raw.strip() if marker_raw is not None else None
        _require(marker is None or marker, f"empty Python environment marker: {name}")
        _require(
            marker is None or "--hash" not in marker,
            f"Python environment marker contains hash syntax: {name}",
        )
        hashes = re.findall(r"--hash=sha256:([0-9a-f]{64})", match.group("hashes"))
        _require(hashes, f"backend lock dependency has no SHA-256: {name}")
        _require(
            len(hashes) == len(set(hashes)),
            f"duplicate backend lock hash in requirement: {name}",
        )
        dependencies.append(
            {
                "ecosystem": "pypi",
                "scope": "backend",
                "name": name,
                "extras": extras,
                "marker": marker,
                "version": version,
                "sha256": hashes,
                "purl": f"pkg:pypi/{quote(name, safe='')}@{quote(version, safe='')}",
            }
        )
    _require(dependencies, "backend requirements.lock has no pinned dependencies")
    keys = [re.sub(r"[-_.]+", "-", item["name"]).lower() for item in dependencies]
    _require(len(keys) == len(set(keys)), "backend requirements.lock has duplicate pins")
    return dependencies


def _build_lock_inventory(
    source_by_path: dict[str, dict[str, Any]],
    source_bytes: dict[str, bytes],
) -> dict[str, Any]:
    source_records: list[dict[str, Any]] = []
    for lock_id, ecosystem, scope, relative in LOCK_DEFINITIONS:
        record = source_by_path.get(relative)
        _require(record is not None, f"lock input absent from source snapshot: {relative}")
        source_records.append(
            {
                "lock_id": lock_id,
                "ecosystem": ecosystem,
                "scope": scope,
                **copy.deepcopy(record),
            }
        )
    verification = _parse_verification_metadata(
        source_bytes["apps/android/gradle/verification-metadata.xml"]
    )
    dependencies = (
        _parse_gradle_lock(
            source_bytes["apps/android/app/gradle.lockfile"],
            label="apps/android/app/gradle.lockfile",
            scope="user",
            verification=verification,
        )
        + _parse_gradle_lock(
            source_bytes["apps/android/adminapp/gradle.lockfile"],
            label="apps/android/adminapp/gradle.lockfile",
            scope="admin",
            verification=verification,
        )
        + _parse_npm_lock(source_bytes["apps/android-gateway/package-lock.json"])
        + _parse_python_lock(source_bytes["backend/requirements.lock"])
    )
    dependency_keys = [
        (
            item["scope"],
            item["ecosystem"],
            item.get("coordinate", item.get("installation_path", item["name"])),
            item["version"],
        )
        for item in dependencies
    ]
    _require(
        len(dependency_keys) == len(set(dependency_keys)),
        "lock inventory has duplicate scoped dependencies",
    )
    by_scope = {
        scope: sum(1 for item in dependencies if item["scope"] == scope)
        for scope in ("user", "admin", "gateway", "backend")
    }
    locked_gradle_coordinates = {
        item["coordinate"]
        for item in dependencies
        if item["ecosystem"] == "maven"
    }
    unmatched_gradle_coordinates = sorted(
        set(verification).difference(locked_gradle_coordinates)
    )
    return seal_payload(
        {
            "schema_version": f"{SCHEMA_PREFIX}.lock-inventory.v1",
            "document_id": "WALKSAFE-W3-DEPENDENCY-LOCK-INVENTORY-20260726",
            "prepared_on": "2026-07-26",
            "source_commit": None,
            "lock_sources": source_records,
            "dependencies": dependencies,
            "summary": {
                "lock_source_count": len(source_records),
                "dependency_count": len(dependencies),
                "dependency_count_by_scope": by_scope,
                "unmatched_gradle_verification_component_count": len(
                    unmatched_gradle_coordinates
                ),
                "unmatched_gradle_verification_coordinates": unmatched_gradle_coordinates,
                "lock_source_content_set_sha256": _content_set_sha256(source_records),
            },
            "claim_boundary": {
                "dependency_presence_derived_from_locks": True,
                "license_approval_claimed": False,
                "release_approval_claimed": False,
            },
        }
    )


def _is_fixture_path(path: str) -> bool:
    relative = PurePosixPath(path)
    parts = {part.lower() for part in relative.parts[:-1]}
    name = relative.name.lower()
    return bool(
        parts.intersection({"fixture", "fixtures", "testdata", "test-data", "golden"})
        or ".fixture." in name
        or name.startswith("fixture.")
    )


def _build_fixture_inventory(
    source_by_path: dict[str, dict[str, Any]],
    source_bytes: dict[str, bytes],
) -> dict[str, Any]:
    register_record = source_by_path.get(FORMAL_TEST_REGISTER)
    _require(register_record is not None, "formal test register absent from source snapshot")
    _require(
        register_record["sha256"] == CANONICAL_FORMAL_TEST_REGISTER_SHA256,
        "formal test register differs from canonical SHA-256",
    )
    register = load_strict_json_bytes(
        source_bytes[FORMAL_TEST_REGISTER],
        "formal test register",
    )
    cases = register.get("test_cases")
    _require(isinstance(cases, list), "formal test register cases missing")
    _require(len(cases) == 279, f"formal test count must be 279, got {len(cases)}")
    test_ids: list[str] = []
    normalized_cases: list[dict[str, Any]] = []
    for case in cases:
        _require(isinstance(case, dict), "formal test case must be an object")
        test_id = case.get("test_case_id")
        _require(isinstance(test_id, str) and test_id, "formal test ID missing")
        _require(case.get("execution_status") == "NOT_RUN", f"formal test is not NOT_RUN: {test_id}")
        _require(case.get("result") is None, f"formal test result must be null: {test_id}")
        declared = case.get("test_data_ids", [])
        _require(
            isinstance(declared, list) and all(isinstance(value, str) for value in declared),
            f"formal test_data_ids invalid: {test_id}",
        )
        test_ids.append(test_id)
        normalized_cases.append({"test_case_id": test_id, "declared_test_data_ids": declared})
    _require(len(test_ids) == len(set(test_ids)), "formal test IDs are not unique")
    _require(
        _path_set_sha256(test_ids) == CANONICAL_FORMAL_TEST_ID_SET_SHA256,
        "formal test ID set differs from canonical 279 IDs",
    )
    _require(
        _sha256_bytes(_canonical_bytes(test_ids)) == CANONICAL_FORMAL_TEST_ORDER_SHA256,
        "formal test ID order differs from canonical register",
    )

    fixtures: list[dict[str, Any]] = []
    aliases: dict[str, str] = {}
    for path, record in sorted(source_by_path.items()):
        if not _is_fixture_path(path):
            continue
        fixture_id = f"FIX-{_sha256_bytes(path.encode('utf-8'))[:16].upper()}"
        item = {"fixture_id": fixture_id, **copy.deepcopy(record)}
        fixtures.append(item)
        for alias in (fixture_id, path, PurePosixPath(path).name, PurePosixPath(path).stem):
            previous = aliases.get(alias)
            _require(
                previous is None or previous == fixture_id,
                f"duplicate fixture alias: {alias}",
            )
            aliases[alias] = fixture_id
    _require(fixtures, "source fixture inventory is empty")

    links: list[dict[str, Any]] = []
    explicit_count = 0
    unresolved_declared_count = 0
    for case in normalized_cases:
        declared = case["declared_test_data_ids"]
        matched = sorted({aliases[value] for value in declared if value in aliases})
        unresolved = sorted(value for value in declared if value not in aliases)
        if matched and not unresolved:
            status = "EXPLICIT_SOURCE_FIXTURE_LINKED"
            explicit_count += 1
        elif unresolved:
            status = "GAP_REMAINS_DECLARED_TEST_DATA_ID_UNRESOLVED"
            unresolved_declared_count += 1
        else:
            status = "GAP_REMAINS_NO_EXPLICIT_SOURCE_FIXTURE_ASSIGNMENT"
        links.append(
            {
                "test_case_id": case["test_case_id"],
                "declared_test_data_ids": declared,
                "source_fixture_ids": matched,
                "unresolved_test_data_ids": unresolved,
                "linkage_status": status,
            }
        )
    return seal_payload(
        {
            "schema_version": f"{SCHEMA_PREFIX}.fixture-inventory.v1",
            "document_id": "WALKSAFE-W3-FIXTURE-AND-FORMAL-TEST-INVENTORY-20260726",
            "prepared_on": "2026-07-26",
            "source_commit": None,
            "formal_test_register": copy.deepcopy(register_record),
            "fixtures": fixtures,
            "formal_test_fixture_links": links,
            "summary": {
                "formal_test_count": len(test_ids),
                "formal_test_unique_count": len(set(test_ids)),
                "formal_test_id_set_sha256": _path_set_sha256(test_ids),
                "formal_test_order_sha256": _sha256_bytes(_canonical_bytes(test_ids)),
                "fixture_count": len(fixtures),
                "fixture_content_set_sha256": _content_set_sha256(fixtures),
                "explicit_source_fixture_link_count": explicit_count,
                "unresolved_declared_test_data_count": unresolved_declared_count,
                "unassigned_formal_test_count": len(test_ids) - explicit_count,
                "fixture_assignment_gap_count": len(test_ids) - explicit_count,
                "all_fixture_assignments_complete": False,
                "all_formal_tests_not_run": True,
            },
            "assignment_boundary": (
                "Inventory linkage records every one of the 279 formal test IDs. "
                "An empty source_fixture_ids list is not an invented assignment."
            ),
            "claim_boundary": {
                "formal_tests_executed": False,
                "formal_tests_passed": False,
                "fixture_assignment_completion_claimed": False,
                "release_gate_waived": False,
            },
        }
    )


def _subject_source_records(
    repo_root: Path,
    source_by_path: dict[str, dict[str, Any]],
    subject_id: str,
) -> list[dict[str, Any]]:
    roots = SUBJECT_DEFINITIONS.get(subject_id)
    _require(roots is not None, f"unknown subject: {subject_id}")
    for relative in roots:
        root = repo_root / relative
        _require(root.is_dir() and not root.is_symlink(), f"subject root missing: {relative}")
    records = [
        copy.deepcopy(record)
        for path, record in source_by_path.items()
        if any(_path_is_under(path, root) for root in roots)
    ]
    records.sort(key=lambda item: item["path"])
    paths = [record["path"] for record in records]
    _require(records, f"subject has no source files: {subject_id}")
    _require(len(paths) == len(set(paths)), f"subject has duplicate paths: {subject_id}")
    return records


def _subject_payload(subject_id: str, records: list[dict[str, Any]]) -> dict[str, Any]:
    return seal_payload(
        {
            "schema_version": f"{SCHEMA_PREFIX}.source-subject.v1",
            "subject_id": subject_id,
            "source_commit": None,
            "source_identity_basis": "FILESYSTEM_TRAVERSAL_NOT_GIT",
            "roots": list(SUBJECT_DEFINITIONS[subject_id]),
            "paths": records,
            "summary": {
                "path_count": len(records),
                "path_set_sha256": _path_set_sha256(record["path"] for record in records),
                "content_set_sha256": _content_set_sha256(records),
            },
            "claim_boundary": {
                "build_or_test_result_claimed": False,
                "release_or_approval_claimed": False,
            },
        }
    )


def build_subject_payload(repo_root: Path, subject_id: str) -> dict[str, Any]:
    """Create one canonical raw source-subject payload for an executor."""

    root = _validated_root(repo_root, "repository root")
    excluded = _reserved_w3_execution_roots(root)
    roots = SUBJECT_DEFINITIONS.get(subject_id)
    _require(roots is not None, f"unknown subject: {subject_id}")
    records: list[dict[str, Any]] = []
    for relative in roots:
        subject_root = root / relative
        _require(
            subject_root.is_dir() and not subject_root.is_symlink(),
            f"subject root missing: {relative}",
        )
        records.extend(
            _file_record(path, root)
            for path in _walk_files(subject_root, excluded)
        )
    records.sort(key=lambda item: item["path"])
    _content_set_sha256(records)
    return _subject_payload(subject_id, records)


def build_command_receipt_payload(commands: list[dict[str, Any]]) -> dict[str, Any]:
    """Create a fingerprinted raw command receipt for an executor."""

    return seal_payload(
        {
            "schema_version": f"{SCHEMA_PREFIX}.command-receipt.v1",
            "source_commit": None,
            "source_identity_basis": "FILESYSTEM_TRAVERSAL_NOT_GIT",
            "commands": commands,
            "claim_boundary": {
                "internal_execution_evidence_only": True,
                "signing_status": "NOT_ASSESSED",
                "signing_release_or_approval_claimed": False,
            },
        }
    )


def _parse_time(value: Any, context: str) -> datetime:
    _require(isinstance(value, str) and value, f"{context}: timestamp missing")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise EvidenceError(f"{context}: invalid timestamp") from exc
    _require(parsed.tzinfo is not None, f"{context}: timezone is required")
    return parsed


def _validate_command_receipt(
    receipt: dict[str, Any], repo_root: Path, run_root: Path
) -> tuple[list[dict[str, Any]], dict[str, str], list[Path]]:
    _validate_fingerprint(receipt, "command receipt")
    _require(
        set(receipt)
        == {
            "schema_version",
            "source_commit",
            "source_identity_basis",
            "commands",
            "claim_boundary",
            "integrity",
        },
        "command receipt top-level fields differ",
    )
    _require(
        receipt.get("schema_version") == f"{SCHEMA_PREFIX}.command-receipt.v1",
        "command receipt schema differs",
    )
    _require(receipt.get("source_commit") is None, "command receipt source_commit must be null")
    _require(
        receipt.get("source_identity_basis") == "FILESYSTEM_TRAVERSAL_NOT_GIT",
        "command receipt source identity basis differs",
    )
    _require(
        receipt.get("claim_boundary")
        == {
            "internal_execution_evidence_only": True,
            "signing_status": "NOT_ASSESSED",
            "signing_release_or_approval_claimed": False,
        },
        "command receipt claim boundary differs",
    )
    commands = receipt.get("commands")
    _require(isinstance(commands, list) and commands, "command receipt commands missing")
    expected_fields = {
        "command_id",
        "role",
        "argv",
        "cwd",
        "tool_version",
        "start",
        "end",
        "exit_code",
        "log_path",
        "log_sha256",
        "output_paths",
    }
    output_to_command: dict[str, str] = {}
    command_ids: set[str] = set()
    raw_paths: list[Path] = []
    normalized: list[dict[str, Any]] = []
    seen_logs: set[str] = set()
    for command in commands:
        _require(isinstance(command, dict), "command receipt entry must be an object")
        _require(set(command) == expected_fields, "command receipt command fields differ")
        command_id = command["command_id"]
        _require(isinstance(command_id, str) and command_id, "command_id missing")
        _require(command_id not in command_ids, f"duplicate command_id: {command_id}")
        command_ids.add(command_id)
        contract = COMMAND_CONTRACTS.get(command_id)
        _require(contract is not None, f"unapproved command producer: {command_id}")
        _require(command["role"] == contract["role"], f"{command_id}: role differs")
        argv = command["argv"]
        _require(
            isinstance(argv, list)
            and argv
            and all(isinstance(value, str) and value for value in argv),
            f"{command_id}: argv invalid",
        )
        if command_id in {
            "android-artifact-stage",
            "gateway-artifact-package",
            "source-subject-capture",
        }:
            try:
                run_argument = run_root.relative_to(repo_root).as_posix()
            except ValueError:
                run_argument = str(run_root)
            programs = {
                "android-artifact-stage": ANDROID_STAGE_PROGRAM,
                "gateway-artifact-package": GATEWAY_PACKAGE_PROGRAM,
                "source-subject-capture": SUBJECT_CAPTURE_PROGRAM,
            }
            expected_argv = [
                "python3",
                "-B",
                "-c",
                programs[command_id],
                run_argument,
            ]
        else:
            expected_argv = contract["argv"]
        _require(argv == expected_argv, f"{command_id}: argv differs from contract")
        cwd_raw = command["cwd"]
        _require(cwd_raw == contract["cwd"], f"{command_id}: cwd differs from contract")
        if cwd_raw == ".":
            cwd_path = repo_root
        else:
            cwd_relative = _safe_relative(cwd_raw, f"{command_id} cwd")
            cwd_path = repo_root
            for part in cwd_relative.parts:
                cwd_path = cwd_path / part
                _require(not cwd_path.is_symlink(), f"{command_id}: cwd symlink is not allowed")
        _require(
            cwd_path.is_dir() and _is_within(cwd_path.resolve(), repo_root),
            f"{command_id}: cwd invalid",
        )
        _require(
            isinstance(command["tool_version"], str) and command["tool_version"],
            f"{command_id}: tool_version missing",
        )
        start = _parse_time(command["start"], f"{command_id} start")
        end = _parse_time(command["end"], f"{command_id} end")
        _require(end >= start, f"{command_id}: end precedes start")
        _require(
            type(command["exit_code"]) is int and command["exit_code"] == 0,
            f"{command_id}: exit_code must be integer zero",
        )
        log_relative = _safe_relative(command["log_path"], f"{command_id} log")
        log_string = log_relative.as_posix()
        _require(log_string.startswith("logs/"), f"{command_id}: log must be under logs/")
        _require(log_string == contract["log_path"], f"{command_id}: log path differs")
        _require(log_string not in seen_logs, f"duplicate command log: {log_string}")
        seen_logs.add(log_string)
        log_path = _validated_input_file(run_root, log_string, f"{command_id} log")
        log_sha = command["log_sha256"]
        _require(
            isinstance(log_sha, str) and SHA256_RE.fullmatch(log_sha) is not None,
            f"{command_id}: log SHA-256 invalid",
        )
        _require(_sha256_file(log_path) == log_sha, f"{command_id}: log SHA-256 mismatch")
        raw_paths.append(log_path)
        outputs = command["output_paths"]
        _require(
            isinstance(outputs, list)
            and all(isinstance(value, str) for value in outputs),
            f"{command_id}: output_paths invalid",
        )
        _require(outputs == contract["output_paths"], f"{command_id}: output role differs")
        normalized_outputs: list[str] = []
        for raw_output in outputs:
            relative = _safe_relative(raw_output, f"{command_id} output")
            output = relative.as_posix()
            _require(output in EXPECTED_RUN_OUTPUTS, f"{command_id}: unexpected output: {output}")
            _require(output not in output_to_command, f"duplicate command output: {output}")
            output_to_command[output] = command_id
            raw_paths.append(_validated_input_file(run_root, output, f"{command_id} output"))
            normalized_outputs.append(output)
        normalized.append(
            {
                **copy.deepcopy(command),
                "output_paths": normalized_outputs,
                "log_path": log_string,
            }
        )
    _require(
        set(output_to_command) == set(EXPECTED_RUN_OUTPUTS),
        "command receipt must bind the exact user/admin/gateway/backend/model/config output set",
    )
    _require(
        [command["command_id"] for command in normalized] == list(COMMAND_CONTRACTS),
        "command receipt command set or order differs",
    )
    for previous, current in zip(normalized, normalized[1:]):
        _require(
            _parse_time(previous["end"], previous["command_id"])
            <= _parse_time(current["start"], current["command_id"]),
            (
                "command receipt chronology overlaps or reverses: "
                f"{previous['command_id']} -> {current['command_id']}"
            ),
        )
    return normalized, output_to_command, raw_paths


def _validate_subject_inputs(
    repo_root: Path,
    run_root: Path,
    source_by_path: dict[str, dict[str, Any]],
) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for subject_id in SUBJECT_DEFINITIONS:
        relative = f"subjects/{subject_id}.json"
        path = _validated_input_file(run_root, relative, f"{subject_id} subject")
        actual = load_strict_json(path, f"{subject_id} subject")
        _validate_fingerprint(actual, f"{subject_id} subject")
        expected = _subject_payload(
            subject_id,
            _subject_source_records(repo_root, source_by_path, subject_id),
        )
        _require(actual == expected, f"{subject_id} subject differs from current repository bytes")
        result[subject_id] = actual
    return result


def _zip_closure(path: Path) -> dict[str, Any]:
    try:
        with zipfile.ZipFile(path) as archive:
            records: list[dict[str, Any]] = []
            seen: set[str] = set()
            for info in sorted(archive.infolist(), key=lambda item: item.filename):
                if info.is_dir():
                    continue
                unix_mode = info.external_attr >> 16
                _require(
                    not stat.S_ISLNK(unix_mode),
                    f"APK symlink entry is not allowed: {info.filename}",
                )
                relative = _safe_relative(info.filename, f"APK entry in {path.name}").as_posix()
                _require(relative not in seen, f"duplicate APK entry: {relative}")
                seen.add(relative)
                digest = hashlib.sha256()
                size = 0
                with archive.open(info) as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(chunk)
                        size += len(chunk)
                records.append({"path": relative, "sha256": digest.hexdigest(), "bytes": size})
    except (zipfile.BadZipFile, OSError) as exc:
        raise EvidenceError(f"invalid APK archive: {path}") from exc
    _require(records, f"APK archive is empty: {path}")
    names = {record["path"] for record in records}
    _require("AndroidManifest.xml" in names, f"APK AndroidManifest.xml missing: {path}")
    _require(
        any(re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex", name) for name in names),
        f"APK classes*.dex missing: {path}",
    )
    by_name = {record["path"]: record for record in records}
    _require(
        by_name["AndroidManifest.xml"]["bytes"] > 0,
        f"APK AndroidManifest.xml is empty: {path}",
    )
    _require(
        all(
            record["bytes"] > 0
            for record in records
            if re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex", record["path"])
        ),
        f"APK classes*.dex is empty: {path}",
    )
    return {
        "entry_count": len(records),
        "path_set_sha256": _path_set_sha256(record["path"] for record in records),
        "content_set_sha256": _content_set_sha256(records),
        "entries": records,
    }


def _tar_closure(path: Path) -> dict[str, Any]:
    try:
        with tarfile.open(path, mode="r:*") as archive:
            records: list[dict[str, Any]] = []
            seen: set[str] = set()
            for member in sorted(archive.getmembers(), key=lambda item: item.name):
                if member.isdir():
                    continue
                _require(member.isfile(), f"gateway archive has non-file member: {member.name}")
                relative = _safe_relative(member.name, "gateway archive entry").as_posix()
                _require(relative not in seen, f"duplicate gateway archive entry: {relative}")
                seen.add(relative)
                stream = archive.extractfile(member)
                _require(stream is not None, f"gateway archive entry unreadable: {relative}")
                digest = hashlib.sha256()
                size = 0
                with stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(chunk)
                        size += len(chunk)
                _require(size == member.size, f"gateway archive entry size differs: {relative}")
                records.append({"path": relative, "sha256": digest.hexdigest(), "bytes": size})
    except (tarfile.TarError, OSError) as exc:
        raise EvidenceError(f"invalid gateway dist archive: {path}") from exc
    _require(records, "gateway dist archive is empty")
    required = {
        "package.json",
        "package-lock.json",
        "dist/server.js",
        "dist/src/routes.js",
    }
    names = {record["path"] for record in records}
    _require(required.issubset(names), "gateway dist archive required files missing")
    by_name = {record["path"]: record for record in records}
    _require(
        all(by_name[name]["bytes"] > 0 for name in required),
        "gateway dist archive required file is empty",
    )
    return {
        "entry_count": len(records),
        "path_set_sha256": _path_set_sha256(record["path"] for record in records),
        "content_set_sha256": _content_set_sha256(records),
        "entries": records,
    }


def _capture_raw_stamps(paths: Iterable[Path]) -> dict[Path, tuple[int, int, int, int, str]]:
    result: dict[Path, tuple[int, int, int, int, str]] = {}
    for path in sorted(set(paths)):
        result[path], _raw = _stable_regular_file_read(
            path,
            context="raw stamp capture",
        )
    return result


def _recheck_raw_stamps(stamps: dict[Path, tuple[int, int, int, int, str]]) -> None:
    for path, expected in stamps.items():
        actual, _raw = _stable_regular_file_read(
            path,
            context="raw stamp recheck",
        )
        _require(actual == expected, f"raw input changed during evidence generation: {path}")


def _build_raw_artifact_records(
    run_root: Path,
    output_to_command: dict[str, str],
    subjects: dict[str, dict[str, Any]],
    source_by_path: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for relative, (artifact_id, scope) in EXPECTED_RUN_OUTPUTS.items():
        path = _validated_input_file(run_root, relative, artifact_id)
        item: dict[str, Any] = {
            "artifact_id": artifact_id,
            "component_scope": scope,
            "classification": "INTERNAL_BUILD_EVIDENCE_SIGNING_NOT_ASSESSED",
            "produced_by_command_id": output_to_command[relative],
            "file": _file_record(path, run_root),
        }
        if relative.endswith(".apk"):
            item["container_closure"] = {"format": "ZIP/APK", **_zip_closure(path)}
            item["signing_status"] = "NOT_ASSESSED"
        elif relative.endswith(".tar.gz"):
            item["container_closure"] = {"format": "TAR_GZIP", **_tar_closure(path)}
            closure_by_path = {
                entry["path"]: entry
                for entry in item["container_closure"]["entries"]
            }
            for archive_path, source_path in (
                ("package.json", "apps/android-gateway/package.json"),
                ("package-lock.json", "apps/android-gateway/package-lock.json"),
            ):
                source = source_by_path.get(source_path)
                _require(source is not None, f"gateway source binding missing: {source_path}")
                _require(
                    closure_by_path[archive_path]["sha256"] == source["sha256"],
                    f"gateway archive differs from source: {archive_path}",
                )
        elif relative.startswith("subjects/"):
            subject_id = PurePosixPath(relative).stem
            item["source_subject"] = {
                "subject_id": subject_id,
                "path_count": subjects[subject_id]["summary"]["path_count"],
                "path_set_sha256": subjects[subject_id]["summary"]["path_set_sha256"],
                "content_set_sha256": subjects[subject_id]["summary"]["content_set_sha256"],
            }
        records.append(item)
    return records


def _component_id(scope: str, ecosystem: str, name: str, version: str) -> str:
    raw = f"{scope}-{ecosystem}-{name}-{version}"
    sanitized = re.sub(r"[^A-Za-z0-9.-]+", "-", raw).strip("-")
    return f"{sanitized[:80]}-{_sha256_bytes(raw.encode('utf-8'))[:12]}"


def _logical_components(
    modules: dict[str, Any], locks: dict[str, Any]
) -> tuple[list[dict[str, Any]], dict[str, list[str]]]:
    components: list[dict[str, Any]] = []
    dependencies_by_scope: dict[str, list[str]] = {
        scope: [] for scope in ("user", "admin", "gateway", "backend", "model-config")
    }
    for module in modules["modules"]:
        scope = module["component_scope"]
        component_ref = f"walksafe-module-{scope}"
        components.append(
            {
                "ref": component_ref,
                "kind": "module",
                "scope": scope,
                "ecosystem": "generic",
                "name": f"walksafe-{module['module_id']}",
                "version": f"dirty-{module['content_set_sha256'][:16]}",
                "purl": None,
                "checksums": [{"algorithm": "SHA256", "value": module["content_set_sha256"]}],
                "source": f"module-inventory:{module['module_id']}",
            }
        )
    for dependency in locks["dependencies"]:
        scope = dependency["scope"]
        ref = _component_id(
            scope,
            dependency["ecosystem"],
            dependency.get("coordinate", dependency["name"]),
            dependency["version"],
        )
        checksums: list[dict[str, str]] = []
        if dependency["ecosystem"] == "maven":
            checksums = []
        elif dependency["ecosystem"] == "npm":
            checksums = [
                {"algorithm": item["algorithm"].replace("-", ""), "value": item["value"]}
                for item in dependency["integrity"]
            ]
        elif dependency["ecosystem"] == "pypi":
            checksums = [
                {"algorithm": "SHA256", "value": value}
                for value in dependency["sha256"]
            ]
        checksums = [
            {"algorithm": algorithm, "value": value}
            for algorithm, value in sorted(
                {(item["algorithm"], item["value"]) for item in checksums}
            )
        ]
        components.append(
            {
                "ref": ref,
                "kind": "library",
                "scope": scope,
                "ecosystem": dependency["ecosystem"],
                "name": dependency["name"],
                "version": dependency["version"],
                "purl": dependency["purl"],
                "checksums": checksums,
                "source": "LOCK_AND_VERIFICATION_METADATA",
                "installation_path": dependency.get("installation_path"),
                "gradle_artifact_checksums": dependency.get(
                    "verification_artifacts", []
                ),
            }
        )
        dependencies_by_scope[scope].append(ref)
    refs = [item["ref"] for item in components]
    _require(len(refs) == len(set(refs)), "SBOM logical component refs are not unique")
    return components, dependencies_by_scope


def _build_spdx(
    modules: dict[str, Any],
    locks: dict[str, Any],
    source_snapshot: dict[str, Any],
    created: str,
) -> dict[str, Any]:
    logical, by_scope = _logical_components(modules, locks)
    packages: list[dict[str, Any]] = []
    for component in logical:
        package: dict[str, Any] = {
            "SPDXID": f"SPDXRef-{component['ref']}",
            "name": component["name"],
            "versionInfo": component["version"],
            "downloadLocation": "NOASSERTION",
            "filesAnalyzed": False,
            "licenseConcluded": "NOASSERTION",
            "licenseDeclared": "NOASSERTION",
            "copyrightText": "NOASSERTION",
            "comment": json.dumps(
                {
                    "component_scope": component["scope"],
                    "source": component["source"],
                    "signing_status": "NOT_ASSESSED",
                    "release_status": "NOT_ELIGIBLE",
                    "installation_path": component.get("installation_path"),
                    "gradle_artifact_checksums": component.get(
                        "gradle_artifact_checksums", []
                    ),
                },
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ),
        }
        if component["checksums"]:
            package["checksums"] = [
                {"algorithm": item["algorithm"], "checksumValue": item["value"]}
                for item in component["checksums"]
            ]
        if component["purl"]:
            package["externalRefs"] = [
                {
                    "referenceCategory": "PACKAGE-MANAGER",
                    "referenceType": "purl",
                    "referenceLocator": component["purl"],
                }
            ]
        packages.append(package)
    relationships: list[dict[str, str]] = []
    for scope in ("user", "admin", "gateway", "backend", "model-config"):
        module_ref = f"SPDXRef-walksafe-module-{scope}"
        relationships.append(
            {
                "spdxElementId": "SPDXRef-DOCUMENT",
                "relationshipType": "DESCRIBES",
                "relatedSpdxElement": module_ref,
            }
        )
        relationships.extend(
            {
                "spdxElementId": module_ref,
                "relationshipType": "DEPENDS_ON",
                "relatedSpdxElement": f"SPDXRef-{dependency}",
            }
            for dependency in by_scope[scope]
        )
    content_hash = source_snapshot["summary"]["content_set_sha256"]
    return {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": "WalkSafe W3 internal engineering snapshot",
        "documentNamespace": (
            "https://walksafe.invalid/spdx/w3/"
            f"{content_hash}"
        ),
        "creationInfo": {
            "created": created,
            "creators": [
                "Tool: build_walksafe_w3_engineering_evidence_20260726.py"
            ],
            "comment": (
                "Internal evidence. APK signing is not assessed; release and "
                "approval are not claimed."
            ),
        },
        "packages": packages,
        "relationships": relationships,
        "annotations": [
            {
                "annotator": "Tool: build_walksafe_w3_engineering_evidence_20260726.py",
                "annotationDate": created,
                "annotationType": "OTHER",
                "comment": json.dumps(
                    {
                        "component_scopes": [
                            "user",
                            "admin",
                            "gateway",
                            "backend",
                            "model-config",
                        ],
                        "source_commit": None,
                        "release_status": "NOT_ELIGIBLE",
                        "signing_status": "NOT_ASSESSED",
                        "approval_status": "NOT_CLAIMED",
                    },
                    sort_keys=True,
                    separators=(",", ":"),
                ),
            }
        ],
    }


def _build_cyclonedx(
    modules: dict[str, Any],
    locks: dict[str, Any],
    source_snapshot: dict[str, Any],
    created: str,
) -> dict[str, Any]:
    logical, by_scope = _logical_components(modules, locks)
    components: list[dict[str, Any]] = []
    for component in logical:
        item: dict[str, Any] = {
            "type": "application" if component["kind"] == "module" else "library",
            "bom-ref": component["ref"],
            "name": component["name"],
            "version": component["version"],
            "properties": [
                {"name": "walksafe:component-scope", "value": component["scope"]},
                {"name": "walksafe:source", "value": component["source"]},
                {"name": "walksafe:signing-status", "value": "NOT_ASSESSED"},
                *(
                    [
                        {
                            "name": "walksafe:npm-installation-path",
                            "value": component["installation_path"],
                        }
                    ]
                    if component.get("installation_path")
                    else []
                ),
                *[
                    {
                        "name": (
                            "walksafe:gradle-artifact-sha256:"
                            f"{artifact['artifact']}"
                        ),
                        "value": artifact["sha256"],
                    }
                    for artifact in component.get("gradle_artifact_checksums", [])
                ],
            ],
        }
        if component["checksums"]:
            item["hashes"] = [
                {
                    "alg": checksum["algorithm"]
                    .replace("SHA256", "SHA-256")
                    .replace("SHA384", "SHA-384")
                    .replace("SHA512", "SHA-512"),
                    "content": checksum["value"],
                }
                for checksum in component["checksums"]
            ]
        if component["purl"]:
            item["purl"] = component["purl"]
        components.append(item)
    root_ref = "walksafe-w3-engineering-snapshot"
    dependencies = [
        {
            "ref": root_ref,
            "dependsOn": [
                f"walksafe-module-{scope}"
                for scope in ("user", "admin", "gateway", "backend", "model-config")
            ],
        }
    ]
    dependencies.extend(
        {
            "ref": f"walksafe-module-{scope}",
            "dependsOn": sorted(by_scope[scope]),
        }
        for scope in ("user", "admin", "gateway", "backend", "model-config")
    )
    dependencies.extend(
        {"ref": component["ref"], "dependsOn": []}
        for component in logical
        if component["kind"] != "module"
    )
    source_hash = source_snapshot["summary"]["content_set_sha256"]
    serial = uuid.uuid5(uuid.NAMESPACE_URL, f"walksafe-w3:{source_hash}")
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "serialNumber": f"urn:uuid:{serial}",
        "version": 1,
        "metadata": {
            "timestamp": created,
            "tools": {
                "components": [
                    {
                        "type": "application",
                        "name": "build_walksafe_w3_engineering_evidence_20260726.py",
                        "version": "1.0.0",
                    }
                ]
            },
            "component": {
                "type": "application",
                "bom-ref": root_ref,
                "name": "walksafe-w3-engineering-snapshot",
                "version": f"dirty-{source_hash[:16]}",
                "properties": [
                    {"name": "walksafe:source-commit", "value": "null"},
                    {"name": "walksafe:release-status", "value": "NOT_ELIGIBLE"},
                    {"name": "walksafe:signing-status", "value": "NOT_ASSESSED"},
                ],
            },
        },
        "components": components,
        "dependencies": dependencies,
        "properties": [
            {
                "name": "walksafe:component-scopes",
                "value": "user,admin,gateway,backend,model-config",
            },
            {"name": "walksafe:signing-status", "value": "NOT_ASSESSED"},
            {"name": "walksafe:approval-status", "value": "NOT_CLAIMED"},
        ],
    }


def _build_provenance(
    run_root: Path,
    source_snapshot: dict[str, Any],
    source_by_path: dict[str, dict[str, Any]],
    locks: dict[str, Any],
    commands: list[dict[str, Any]],
    artifacts: list[dict[str, Any]],
    receipt_path: Path,
) -> dict[str, Any]:
    artifact_by_path = {item["file"]["path"]: item for item in artifacts}
    generator_record = source_by_path.get(GENERATOR_RELATIVE_PATH)
    _require(generator_record is not None, "W3 generator absent from source snapshot")
    command_records: list[dict[str, Any]] = []
    for command in commands:
        outputs = [artifact_by_path[path] for path in command["output_paths"]]
        command_records.append(
            {
                "command_id": command["command_id"],
                "argv": command["argv"],
                "cwd": command["cwd"],
                "tool_version": command["tool_version"],
                "start": command["start"],
                "end": command["end"],
                "exit_code": command["exit_code"],
                "inputs": {
                    "source_content_set_sha256": source_snapshot["summary"]["content_set_sha256"],
                    "lock_content_set_sha256": locks["summary"]["lock_source_content_set_sha256"],
                    "generator": generator_record,
                    "raw_command_receipt_sha256": _sha256_file(receipt_path),
                },
                "log": _file_record(
                    _validated_input_file(run_root, command["log_path"], command["command_id"]),
                    run_root,
                ),
                "outputs": [copy.deepcopy(item["file"]) for item in outputs],
                "result": "PASSED_INTERNAL_COMMAND",
            }
        )
    return seal_payload(
        {
            "schema_version": f"{SCHEMA_PREFIX}.internal-build-provenance.v2",
            "document_id": "WALKSAFE-W3-INTERNAL-BUILD-PROVENANCE-20260726",
            "prepared_on": "2026-07-26",
            "source_commit": None,
            "source_identity_basis": "DIRTY_WORKTREE_EXACT_PATH_SHA256_CONTENT_SET",
            "raw_command_receipt": _file_record(receipt_path, run_root),
            "commands": command_records,
            "build_subjects": artifacts,
            "immutability_check": "RAW_RECEIPT_LOGS_AND_OUTPUTS_STABLE_DURING_GENERATION",
            "claim_boundary": {
                "classification": "INTERNAL_BUILD_EVIDENCE_SIGNING_NOT_ASSESSED",
                "signing_status": "NOT_ASSESSED",
                "release_eligible": False,
                "deployment_performed": False,
                "formal_tests_executed": False,
                "owner_approval_claimed": False,
            },
        }
    )


def _validate_spdx_structure(payload: dict[str, Any]) -> None:
    allowed = {
        "spdxVersion",
        "dataLicense",
        "SPDXID",
        "name",
        "documentNamespace",
        "creationInfo",
        "packages",
        "relationships",
        "annotations",
    }
    required = allowed
    _require(set(payload) == required, "SPDX top-level allowed/required fields differ")
    _require(payload["spdxVersion"] == "SPDX-2.3", "SPDX version differs")
    _require(payload["dataLicense"] == "CC0-1.0", "SPDX data license differs")
    _require(payload["SPDXID"] == "SPDXRef-DOCUMENT", "SPDX document ID differs")
    _require(isinstance(payload["creationInfo"], dict), "SPDX creationInfo missing")
    _require(isinstance(payload["packages"], list) and payload["packages"], "SPDX packages missing")
    _require(
        isinstance(payload["relationships"], list) and payload["relationships"],
        "SPDX relationships missing",
    )
    _require(isinstance(payload["annotations"], list) and payload["annotations"], "SPDX annotation missing")
    for package in payload["packages"]:
        _require(
            set(package).issubset(
                {
                    "SPDXID",
                    "name",
                    "versionInfo",
                    "downloadLocation",
                    "filesAnalyzed",
                    "licenseConcluded",
                    "licenseDeclared",
                    "copyrightText",
                    "comment",
                    "checksums",
                    "externalRefs",
                }
            ),
            "SPDX package has a non-standard field",
        )
        _require(
            {
                "SPDXID",
                "name",
                "versionInfo",
                "downloadLocation",
                "filesAnalyzed",
                "licenseConcluded",
                "licenseDeclared",
                "copyrightText",
                "comment",
            }.issubset(package),
            "SPDX package required fields missing",
        )


def _validate_cyclonedx_structure(payload: dict[str, Any]) -> None:
    _require(
        set(payload)
        == {
            "bomFormat",
            "specVersion",
            "serialNumber",
            "version",
            "metadata",
            "components",
            "dependencies",
            "properties",
        },
        "CycloneDX top-level allowed/required fields differ",
    )
    _require(payload["bomFormat"] == "CycloneDX", "CycloneDX format differs")
    _require(payload["specVersion"] == "1.6", "CycloneDX version differs")
    _require(type(payload["version"]) is int and payload["version"] >= 1, "CycloneDX BOM version invalid")
    _require(isinstance(payload["metadata"], dict), "CycloneDX metadata missing")
    _require(isinstance(payload["components"], list) and payload["components"], "CycloneDX components missing")
    _require(isinstance(payload["dependencies"], list) and payload["dependencies"], "CycloneDX dependencies missing")
    for component in payload["components"]:
        _require(
            set(component).issubset(
                {"type", "bom-ref", "name", "version", "hashes", "properties", "purl"}
            ),
            "CycloneDX component has a non-standard field",
        )
        _require(
            {"type", "bom-ref", "name", "version", "properties"}.issubset(component),
            "CycloneDX component required fields missing",
        )


def _validate_generated_payload(name: str, payload: dict[str, Any]) -> None:
    if name == "sbom.spdx.json":
        _validate_spdx_structure(payload)
    elif name == "sbom.cyclonedx.json":
        _validate_cyclonedx_structure(payload)
    else:
        _validate_fingerprint(payload, name)
    serialized = json.dumps(payload, ensure_ascii=False, sort_keys=True).upper()
    _require(
        '"RELEASE_ELIGIBLE": TRUE' not in serialized
        and '"RELEASE_STATUS": "ELIGIBLE"' not in serialized
        and '"SIGNING_STATUS": "APPROVED"' not in serialized
        and '"APPROVAL_STATUS": "APPROVED"' not in serialized,
        f"{name}: prohibited release/signing/approval claim",
    )
    _require(
        '"COMPLETION_STATUS": "COMPLETED"' not in serialized
        and '"ALL_FIXTURE_ASSIGNMENTS_COMPLETE": TRUE' not in serialized,
        f"{name}: prohibited completion claim",
    )
    if name == "module-inventory.json":
        _validate_module_inventory_payload(payload)
    if name in {"sbom.spdx.json", "sbom.cyclonedx.json"}:
        scopes = {"user", "admin", "gateway", "backend", "model-config"}
        text = json.dumps(payload, ensure_ascii=False)
        _require(all(scope in text for scope in scopes), f"{name}: component scope coverage missing")


def _closed_tree(root: Path) -> tuple[set[str], set[str]]:
    files: set[str] = set()
    directories: set[str] = set()
    for current_raw, names, filenames in os.walk(root, topdown=True, followlinks=False):
        current = Path(current_raw)
        for name in sorted(names):
            path = current / name
            _require(not path.is_symlink(), f"closed tree directory symlink is not allowed: {path}")
            directories.add(path.relative_to(root).as_posix())
        for name in sorted(filenames):
            path = current / name
            _require(not path.is_symlink(), f"closed tree file symlink is not allowed: {path}")
            _require(path.is_file(), f"closed tree entry is not a file: {path}")
            files.add(path.relative_to(root).as_posix())
    return files, directories


def _validate_run_closed_set(run_root: Path) -> None:
    files, directories = _closed_tree(run_root)
    expected_files = {
        "command-receipt.json",
        *(contract["log_path"] for contract in COMMAND_CONTRACTS.values()),
        *EXPECTED_RUN_OUTPUTS,
    }
    _require(files == expected_files, "run directory file set is not exact")
    _require(directories == {"artifacts", "subjects", "logs"}, "run directory tree is not exact")


def _validate_output_closed_set(
    output_root: Path, *, allow_missing: bool, require_complete: bool
) -> None:
    if not output_root.exists():
        _require(allow_missing, "output directory missing")
        return
    _require(output_root.is_dir() and not output_root.is_symlink(), "output directory invalid")
    files, directories = _closed_tree(output_root)
    _require(not directories, "output directory must not contain subdirectories")
    expected = set(OUTPUT_FILENAMES)
    _require(files.issubset(expected), "output directory contains unexpected files")
    if require_complete:
        _require(files == expected, "output directory file set is not exact")


def build_outputs(
    repo_root: Path,
    run_dir: Path,
    output_dir: Path,
    *,
    _state: dict[str, Any] | None = None,
    _excluded_roots: tuple[Path, ...] | None = None,
) -> dict[str, dict[str, Any]]:
    repo = _validated_root(repo_root, "repository root")
    run = _validated_root(run_dir, "run directory")
    output = _reject_original_path_symlinks(output_dir, "output directory").resolve()
    _require(
        not _is_within(run, output) and not _is_within(output, run),
        "run and output directories must not overlap by ancestry",
    )
    _validate_run_closed_set(run)
    excluded = (
        _repository_excluded_roots(repo, run, output)
        if _excluded_roots is None
        else _excluded_roots
    )

    receipt_path = _validated_input_file(run, "command-receipt.json", "command receipt")
    receipt = load_strict_json(receipt_path, "command receipt")
    commands, output_to_command, raw_paths = _validate_command_receipt(receipt, repo, run)
    raw_stamps = _capture_raw_stamps([receipt_path, *raw_paths])

    (
        source_snapshot,
        source_by_path,
        source_bytes,
        source_stat_set,
    ) = _repository_snapshot(repo, excluded_roots=excluded)
    subjects = _validate_subject_inputs(repo, run, source_by_path)
    modules = _build_module_inventory(source_by_path, repo)
    locks = _build_lock_inventory(source_by_path, source_bytes)
    fixtures = _build_fixture_inventory(source_by_path, source_bytes)
    artifacts = _build_raw_artifact_records(
        run,
        output_to_command,
        subjects,
        source_by_path,
    )
    created_at = min(_parse_time(command["start"], command["command_id"]) for command in commands)
    created = (
        created_at.astimezone(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )
    spdx = _build_spdx(modules, locks, source_snapshot, created)
    cyclonedx = _build_cyclonedx(modules, locks, source_snapshot, created)
    provenance = _build_provenance(
        run,
        source_snapshot,
        source_by_path,
        locks,
        commands,
        artifacts,
        receipt_path,
    )

    outputs: dict[str, dict[str, Any]] = {
        "source-snapshot.json": source_snapshot,
        "module-inventory.json": modules,
        "lock-inventory.json": locks,
        "fixture-inventory.json": fixtures,
        "sbom.spdx.json": spdx,
        "sbom.cyclonedx.json": cyclonedx,
        "internal-build-provenance.json": provenance,
    }
    output_records = []
    for name, payload in sorted(outputs.items()):
        record = {
            "path": name,
            "sha256": _sha256_bytes(_json_bytes(payload)),
            "bytes": len(_json_bytes(payload)),
        }
        fingerprint = payload.get("integrity", {}).get("content_fingerprint", {}).get("value")
        if fingerprint is not None:
            record["content_fingerprint"] = fingerprint
        else:
            record["content_fingerprint"] = None
            record["integrity_binding"] = "MANIFEST_RAW_FILE_SHA256"
        output_records.append(record)
    outputs["engineering-evidence-manifest.json"] = seal_payload(
        {
            "schema_version": f"{SCHEMA_PREFIX}.manifest.v1",
            "document_id": "WALKSAFE-W3-ENGINEERING-EVIDENCE-MANIFEST-20260726",
            "prepared_on": "2026-07-26",
            "source_commit": None,
            "worktree_state": "DIRTY_WORKTREE_EXACT_CONTENT_SNAPSHOT",
            "wave_id": "W3",
            "covered_artifact_type_ids": W3_ARTIFACT_TYPE_IDS,
            "covered_artifact_type_count": len(W3_ARTIFACT_TYPE_IDS),
            "outputs": output_records,
            "input_bindings": {
                "source_content_set_sha256": source_snapshot["summary"]["content_set_sha256"],
                "module_content_sets": {
                    module["module_id"]: module["content_set_sha256"]
                    for module in modules["modules"]
                },
                "lock_source_content_set_sha256": locks["summary"]["lock_source_content_set_sha256"],
                "fixture_content_set_sha256": fixtures["summary"]["fixture_content_set_sha256"],
                "formal_test_id_set_sha256": fixtures["summary"]["formal_test_id_set_sha256"],
                "raw_command_receipt_sha256": _sha256_file(receipt_path),
            },
            "validation_summary": {
                "module_ownership_duplicate_count": 0,
                "formal_test_count": 279,
                "formal_test_execution_status": "NOT_RUN",
                "raw_required_output_count": len(EXPECTED_RUN_OUTPUTS),
                "sbom_formats": ["SPDX-2.3", "CycloneDX-1.6"],
                "sbom_standard_validation": (
                    "BUILTIN_STRICT_STANDARD_ALLOWED_REQUIRED_STRUCTURE; "
                    "LOCAL_SCHEMA_PACKAGES_NOT_AVAILABLE"
                ),
                "official_standard_schema_validation": {
                    "status": "STANDARD_SCHEMA_VALIDATION_REQUIRED_NOT_RUN",
                    "local_validator_or_schema_available": False,
                    "required_artifact_type_id": "DLV-DEV-19",
                    "completion_allowed": False,
                    "required_heavy_execution_evidence": [
                        "official-spdx-validator-result.json",
                        "official-spdx-validator.log",
                        "official-cyclonedx-validator-result.json",
                        "official-cyclonedx-validator.log",
                    ],
                    "current_run_contract_contains_required_evidence": False,
                },
                "component_scopes": ["user", "admin", "gateway", "backend", "model-config"],
            },
            "claim_boundary": {
                "result": "INTERNAL_EVIDENCE_GENERATED",
                "internal_build_evidence_only": True,
                "signing_status": "NOT_ASSESSED",
                "release_status": "NOT_ELIGIBLE",
                "deployment_status": "NOT_RUN",
                "formal_test_status": "NOT_RUN",
                "approval_status": "NOT_CLAIMED",
                "dev19_completion_status": (
                    "BLOCKED_PENDING_OFFICIAL_STANDARD_SCHEMA_VALIDATOR_RESULTS"
                ),
            },
        }
    )
    _require(set(outputs) == set(OUTPUT_FILENAMES), "generated output set differs")
    for name, payload in outputs.items():
        _validate_generated_payload(name, payload)
    _recheck_repository_stat_set(repo, excluded, source_stat_set)
    _recheck_repository_content_map(repo, excluded, source_by_path)
    _recheck_raw_stamps(raw_stamps)
    _validate_run_closed_set(run)
    if _state is not None:
        _state["source_by_path"] = copy.deepcopy(source_by_path)
        _state["excluded_roots"] = tuple(excluded)
    return outputs


def _write_atomic(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    _require(not path.is_symlink(), f"output symlink is not allowed: {path}")
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    _require(not temporary.exists(), f"temporary output already exists: {temporary}")
    try:
        with temporary.open("xb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def run(mode: str, repo_root: Path, run_dir: Path, output_dir: Path) -> dict[str, Any]:
    _require(mode in {"write", "check"}, "mode must be write or check")
    repo = _validated_root(repo_root, "repository root")
    run_root = _validated_root(run_dir, "run directory")
    output_root = _reject_original_path_symlinks(
        output_dir,
        "output directory",
    ).resolve()
    _require(
        not _is_within(run_root, output_root) and not _is_within(output_root, run_root),
        "run and output directories must not overlap by ancestry",
    )
    excluded = _repository_excluded_roots(repo, run_root, output_root)
    invocation_source_stats = _repository_stat_set(
        repo,
        excluded,
    )
    _validate_run_closed_set(run_root)
    run_files, _run_directories = _closed_tree(run_root)
    invocation_run_stamps = _capture_raw_stamps(
        run_root / relative for relative in run_files
    )
    _validate_output_closed_set(
        output_root,
        allow_missing=mode == "write",
        require_complete=mode == "check",
    )
    initial_output_stamps = (
        _capture_raw_stamps(output_root / name for name in OUTPUT_FILENAMES)
        if mode == "check"
        else {}
    )
    build_state: dict[str, Any] = {}
    expected = build_outputs(
        repo,
        run_root,
        output_root,
        _state=build_state,
        _excluded_roots=excluded,
    )
    if mode == "write":
        output_root.mkdir(parents=True, exist_ok=True)
        _require(output_root.is_dir() and not output_root.is_symlink(), "output directory invalid")
        for name in OUTPUT_FILENAMES:
            _write_atomic(output_root / name, _json_bytes(expected[name]))
        _validate_output_closed_set(
            output_root,
            allow_missing=False,
            require_complete=True,
        )
    else:
        for name in OUTPUT_FILENAMES:
            path = output_root / name
            _require(path.is_file() and not path.is_symlink(), f"generated output missing: {name}")
            actual = load_strict_json(path, name)
            _validate_generated_payload(name, actual)
            _require(actual == expected[name], f"generated output differs: {name}")
            _require(path.read_bytes() == _json_bytes(expected[name]), f"generated bytes differ: {name}")
    final_output_stamps: dict[Path, tuple[int, int, int, int, str]] = {}
    for name in OUTPUT_FILENAMES:
        path = output_root / name
        expected_bytes = _json_bytes(expected[name])
        expected_sha256 = _sha256_bytes(expected_bytes)
        stamp, actual_bytes = _stable_regular_file_read(
            path,
            capture_bytes=True,
            context=f"final generated output {name}",
        )
        _require(
            stamp[4] == expected_sha256,
            f"final generated SHA-256 differs: {name}",
        )
        _require(
            stamp[2] == len(expected_bytes),
            f"final generated size differs: {name}",
        )
        _require(
            actual_bytes == expected_bytes,
            f"final generated bytes differ: {name}",
        )
        final_output_stamps[path] = stamp
    _recheck_repository_stat_set(
        repo,
        excluded,
        invocation_source_stats,
    )
    _recheck_repository_content_map(
        repo,
        build_state["excluded_roots"],
        build_state["source_by_path"],
    )
    _recheck_raw_stamps(invocation_run_stamps)
    if initial_output_stamps:
        _recheck_raw_stamps(initial_output_stamps)
    _recheck_raw_stamps(final_output_stamps)
    _validate_run_closed_set(run_root)
    _validate_output_closed_set(
        output_root,
        allow_missing=False,
        require_complete=True,
    )
    return {
        "mode": mode,
        "result": "PASS",
        "output_count": len(expected),
        "source_commit": None,
        "release_status": "NOT_ELIGIBLE",
    }


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    result = run(
        "write" if args.write else "check",
        args.repo_root,
        args.run_dir,
        args.output_dir,
    )
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except EvidenceError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
