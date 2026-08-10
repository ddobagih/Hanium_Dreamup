#!/usr/bin/env python3
"""Run one source-bound submission command in its exact locked Python environment."""

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
            "WalkSafe submission runner requires Python -I -S -B before any submission code runs"
        )

import argparse
import base64
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import stat
import subprocess
import tempfile
import types
from typing import Mapping, Sequence


_SCRIPT_DIRECTORY = Path(__file__).resolve().parent
_BASE_BOOTSTRAP_PATH = _SCRIPT_DIRECTORY / "run_walksafe_isolated_python_20260713.py"
_BASE_BOOTSTRAP_SHA256 = "ab8d6aa25f608e4cbe72aa090b078ef46f6aa4a022ee15f1ea57f53363e0b64c"
_SUBMISSION_POLICY_PATH = _SCRIPT_DIRECTORY / "submission_manifest_policy.py"
_SUBMISSION_POLICY_SHA256 = "07a3df86f66cabe36b920b8507325497a755eec14c190811f9ea0d7bd5c9a8c4"
_TOOLCHAIN_LOCK_RELATIVE = "configs/submission_toolchain_lock_20260713.json"
_TOOLCHAIN_LOCK_SHA256 = "5ddd5ce86e3080ac278ec9e157e9a3bcb4642c996e1a38be1ba7f77df2a8fc81"
_LIBRARY_BUNDLE_FORMAT = "walksafe.semantic-record-bundle.v1"
_INSTALLER_LOCK_RELATIVE = "configs/submission_installer_requirements.lock"
_EXACT8_LOCK_RELATIVE = "configs/submission_exact8_requirements.lock"
_INSTALLER_VERSION = "26.1.1"
_INSTALL_FLAGS = ["--require-hashes", "--no-deps", "--no-compile"]
_EXPECTED_LIBRARY_NAMES = frozenset(
    {
        "Pillow",
        "python-docx",
        "python-pptx",
        "lxml",
        "opencv-python",
        "numpy",
        "typing-extensions",
        "XlsxWriter",
    }
)
_CANONICAL_ENTRYPOINTS = frozenset(
    {
        "scripts/audit_submission_visual_privacy_20260711.py",
        "scripts/build_design_documents_20260710.py",
        "scripts/build_submission_assets_20260710.py",
        "scripts/build_submission_forms_20260710.py",
        "scripts/promote_submission_final_20260713.py",
        "scripts/validate_submission_forms_20260710.py",
        "scripts/validate_submission_materials_20260710.py",
    }
)
_FULL_COMMIT = re.compile(r"[0-9a-f]{40}")
_SHA256 = re.compile(r"[0-9a-f]{64}")


class SubmissionRunnerError(RuntimeError):
    """Fail-closed submission startup, source, or Python environment error."""


def _stable_bytes(
    path: Path,
    context: str,
    *,
    nonempty: bool = True,
) -> tuple[bytes, tuple[int, int, int, int, int]]:
    candidate = path.absolute()
    if candidate.resolve() != candidate or candidate.is_symlink():
        raise SubmissionRunnerError(f"{context} must be a real file")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(candidate, flags)
        with os.fdopen(descriptor, "rb") as source:
            before = os.fstat(source.fileno())
            payload = source.read()
            after = os.fstat(source.fileno())
        current = os.stat(candidate, follow_symlinks=False)
    except OSError as exc:
        raise SubmissionRunnerError(f"{context} cannot be read safely") from exc
    identities = {
        (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
        for item in (before, after, current)
    }
    if (
        not stat.S_ISREG(current.st_mode)
        or len(identities) != 1
        or (nonempty and not payload)
    ):
        raise SubmissionRunnerError(f"{context} changed while it was read")
    return payload, identities.pop()


def _load_pinned_source(module_name: str, path: Path, expected_sha256: str) -> types.ModuleType:
    payload, _identity = _stable_bytes(path, path.name)
    if hashlib.sha256(payload).hexdigest() != expected_sha256:
        raise SubmissionRunnerError(f"{path.name} differs from its submission runner pin")
    module = types.ModuleType(module_name)
    module.__file__ = str(path)
    module.__loader__ = None
    module.__package__ = ""
    module.__spec__ = None
    sys.modules[module_name] = module
    try:
        exec(compile(payload, str(path), "exec", dont_inherit=True), module.__dict__)
    except BaseException:
        sys.modules.pop(module_name, None)
        raise
    return module


def _strict_json(payload: bytes, context: str) -> dict[str, object]:
    def pairs(values: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in values:
            if key in result:
                raise SubmissionRunnerError(f"{context} contains a duplicate JSON key")
            result[key] = value
        return result

    def constant(_value: str) -> object:
        raise SubmissionRunnerError(f"{context} contains a non-finite JSON number")

    try:
        value = json.loads(payload, object_pairs_hook=pairs, parse_constant=constant)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise SubmissionRunnerError(f"{context} is not strict JSON") from exc
    if not isinstance(value, dict):
        raise SubmissionRunnerError(f"{context} must contain a JSON object")
    return value


def _normalize_distribution_name(raw: object) -> str:
    if not isinstance(raw, str) or re.fullmatch(r"[A-Za-z0-9_.-]+", raw) is None:
        raise SubmissionRunnerError("submission library name is invalid")
    return re.sub(r"[-_.]+", "-", raw).lower()


def _python_identity() -> dict[str, object]:
    executable = Path(sys.executable).resolve()
    payload, _identity = _stable_bytes(executable, "submission Python executable")
    return {
        "implementation": platform.python_implementation(),
        "version": platform.python_version(),
        "platform": {"system": platform.system(), "machine": platform.machine()},
        "executable": {
            "resolved_path": str(executable),
            "sha256": hashlib.sha256(payload).hexdigest(),
            "bytes": len(payload),
        },
    }


def _require_locked_python_installation(repo_root: Path, raw: object) -> None:
    if not isinstance(raw, dict) or set(raw) != {
        "installer_lock",
        "exact8_lock",
        "pip_version",
        "install_flags",
    }:
        raise SubmissionRunnerError("submission Python installation lock has an invalid shape")
    if raw.get("pip_version") != _INSTALLER_VERSION or raw.get("install_flags") != _INSTALL_FLAGS:
        raise SubmissionRunnerError("submission Python installation recipe differs from the contract")
    expected_paths = {
        "installer_lock": _INSTALLER_LOCK_RELATIVE,
        "exact8_lock": _EXACT8_LOCK_RELATIVE,
    }
    for key, expected_relative in expected_paths.items():
        record = raw.get(key)
        if not isinstance(record, dict) or set(record) != {"path", "sha256", "bytes"}:
            raise SubmissionRunnerError("submission Python requirements lock has an invalid shape")
        expected_sha256 = record.get("sha256")
        expected_bytes = record.get("bytes")
        if (
            record.get("path") != expected_relative
            or not isinstance(expected_sha256, str)
            or _SHA256.fullmatch(expected_sha256) is None
            or isinstance(expected_bytes, bool)
            or not isinstance(expected_bytes, int)
            or expected_bytes < 1
        ):
            raise SubmissionRunnerError("submission Python requirements lock record is invalid")
        payload, _identity = _stable_bytes(
            repo_root / expected_relative,
            f"submission Python requirements lock {expected_relative}",
        )
        if len(payload) != expected_bytes or hashlib.sha256(payload).hexdigest() != expected_sha256:
            raise SubmissionRunnerError(
                f"submission Python requirements lock differs from its toolchain pin: {expected_relative}"
            )


def _locked_environment(
    repo_root: Path,
) -> tuple[dict[str, str], dict[str, dict[str, object]]]:
    lock_path = repo_root / _TOOLCHAIN_LOCK_RELATIVE
    payload, _identity = _stable_bytes(lock_path, "submission toolchain lock")
    if hashlib.sha256(payload).hexdigest() != _TOOLCHAIN_LOCK_SHA256:
        raise SubmissionRunnerError("submission toolchain lock differs from its runner pin")
    lock = _strict_json(payload, "submission toolchain lock")
    if lock.get("schema_version") != "walksafe.submission-toolchain.v4" or set(lock) != {
        "schema_version",
        "library_bundle_format",
        "python_installation",
        "python",
        "libraries",
        "tools",
        "fonts",
    }:
        raise SubmissionRunnerError("submission toolchain lock schema is unsupported")
    if lock.get("library_bundle_format") != _LIBRARY_BUNDLE_FORMAT:
        raise SubmissionRunnerError("submission library bundle format is unsupported")
    _require_locked_python_installation(repo_root, lock.get("python_installation"))
    if lock.get("python") != _python_identity():
        raise SubmissionRunnerError("submission Python differs from the pinned toolchain")
    libraries = lock.get("libraries")
    if not isinstance(libraries, list) or any(
        not isinstance(item, dict)
        or set(item) != {"distribution", "version", "file_count", "file_bundle_sha256"}
        for item in libraries
    ):
        raise SubmissionRunnerError("submission library lock has an invalid shape")
    names = [item.get("distribution") for item in libraries]
    if len(names) != len(set(names)) or set(names) != _EXPECTED_LIBRARY_NAMES:
        raise SubmissionRunnerError("submission library lock has an unexpected distribution set")
    versions: dict[str, str] = {}
    bundles: dict[str, dict[str, object]] = {}
    for item in libraries:
        raw_name = item["distribution"]
        raw_version = item["version"]
        file_count = item["file_count"]
        bundle_sha256 = item["file_bundle_sha256"]
        normalized = _normalize_distribution_name(raw_name)
        if (
            normalized in versions
            or not isinstance(raw_version, str)
            or not raw_version
            or any(character in raw_version for character in "\0\r\n")
            or isinstance(file_count, bool)
            or not isinstance(file_count, int)
            or file_count < 1
            or not isinstance(bundle_sha256, str)
            or _SHA256.fullmatch(bundle_sha256) is None
        ):
            raise SubmissionRunnerError("submission library lock entry is invalid")
        versions[normalized] = raw_version
        bundles[normalized] = item
    return versions, bundles


def _canonical_package_path(raw: str) -> str:
    parts = raw.split("/")
    seen_name = False
    if (
        not raw
        or "\\" in raw
        or PurePosixPath(raw).is_absolute()
        or PurePosixPath(raw).as_posix() != raw
        or any(part in {"", "."} for part in parts)
        or any(ord(character) < 32 or ord(character) == 127 for character in raw)
    ):
        raise SubmissionRunnerError("submission distribution file path is not canonical")
    for part in parts:
        if part == "..":
            if seen_name:
                raise SubmissionRunnerError("submission distribution file path is not canonical")
        else:
            seen_name = True
    if not seen_name:
        raise SubmissionRunnerError("submission distribution file path is not canonical")
    return raw


def _semantic_distribution_bundle(
    distribution: importlib.metadata.Distribution,
    name: str,
    roots: Sequence[Path],
) -> tuple[tuple[int, str], tuple[tuple[str, tuple[int, int, int, int, int]], ...], tuple[Path, ...]]:
    prefix = Path(sys.prefix).absolute()
    package_files = distribution.files
    if package_files is None:
        raise SubmissionRunnerError(f"submission distribution has no RECORD closure: {name}")
    claims: list[dict[str, object]] = []
    seen_relatives: set[str] = set()
    for package_file in sorted(package_files, key=lambda item: item.as_posix()):
        relative = _canonical_package_path(package_file.as_posix())
        if relative in seen_relatives:
            raise SubmissionRunnerError("submission distribution RECORD contains a duplicate path")
        seen_relatives.add(relative)
        installed = Path(os.path.abspath(distribution.locate_file(package_file)))
        try:
            installed.relative_to(prefix)
        except ValueError as exc:
            raise SubmissionRunnerError("submission distribution escaped its virtualenv") from exc
        hash_mode = package_file.hash.mode if package_file.hash is not None else ""
        hash_value = package_file.hash.value if package_file.hash is not None else ""
        if installed.suffix.lower() in {".pyc", ".pyo"}:
            if installed.exists() or hash_mode or package_file.size is not None:
                raise SubmissionRunnerError("submission distribution contains executable bytecode")
            claims.append(
                {
                    "relative": relative,
                    "installed": installed,
                    "present": False,
                    "hash_mode": "",
                    "hash_value": "",
                    "recorded_size": None,
                }
            )
            continue
        payload, identity = _stable_bytes(
            installed,
            f"submission distribution file {name}:{relative}",
            nonempty=False,
        )
        digest = hashlib.sha256(payload).hexdigest()
        if package_file.size is not None and package_file.size != len(payload):
            raise SubmissionRunnerError("submission file size differs from distribution RECORD")
        if not hash_mode:
            if not relative.endswith(".dist-info/RECORD"):
                raise SubmissionRunnerError("submission file is not hashed by distribution RECORD")
        elif hash_mode != "sha256":
            raise SubmissionRunnerError("submission distribution RECORD uses a non-SHA-256 hash")
        else:
            encoded = base64.urlsafe_b64encode(bytes.fromhex(digest)).rstrip(b"=").decode("ascii")
            if encoded != hash_value:
                raise SubmissionRunnerError("submission file differs from distribution RECORD")
        external = not any(installed.is_relative_to(root) for root in roots)
        launcher_shebang = b"#!" + os.fsencode(prefix / "bin/python") + b"\n"
        normalized = payload
        if external and installed.parent == prefix / "bin":
            if not payload.startswith(launcher_shebang):
                raise SubmissionRunnerError(
                    "submission virtualenv bin claim lacks the exact Python launcher shebang"
                )
            normalized = (
                b"#!/__walksafe_venv__/bin/python\n" + payload[len(launcher_shebang) :]
            )
        claims.append(
            {
                "relative": relative,
                "installed": installed,
                "present": True,
                "identity": identity,
                "payload": normalized,
                "hash_mode": hash_mode,
                "hash_value": hash_value,
                "recorded_size": package_file.size,
                "external": external,
            }
        )

    record_claims = [
        claim
        for claim in claims
        if not claim["hash_mode"] and str(claim["relative"]).endswith(".dist-info/RECORD")
    ]
    if len(record_claims) != 1:
        raise SubmissionRunnerError("submission distribution has no unique unhashed RECORD file")
    record_relative = str(record_claims[0]["relative"])
    requested_relative = f"{record_relative.removesuffix('/RECORD')}/REQUESTED"
    semantic_rows: list[str] = []
    for claim in claims:
        relative = str(claim["relative"])
        if relative == requested_relative:
            if claim.get("payload") != b"" or claim.get("hash_mode") != "sha256" or claim.get(
                "recorded_size"
            ) != 0:
                raise SubmissionRunnerError("submission installer REQUESTED marker is invalid")
            continue
        hash_mode = str(claim["hash_mode"])
        hash_value = str(claim["hash_value"])
        recorded_size = claim["recorded_size"]
        if claim.get("external"):
            normalized = claim["payload"]
            assert isinstance(normalized, bytes)
            hash_value = base64.urlsafe_b64encode(hashlib.sha256(normalized).digest()).rstrip(
                b"="
            ).decode("ascii")
            recorded_size = len(normalized)
        semantic_rows.append(
            f"claim\0{relative}\0{hash_mode}\0{hash_value}\0"
            f"{'' if recorded_size is None else recorded_size}\n"
        )
    semantic_record = "".join(sorted(semantic_rows)).encode("utf-8")

    closure: list[tuple[str, int, str]] = []
    identities: list[tuple[str, tuple[int, int, int, int, int]]] = []
    installed_paths: list[Path] = []
    for claim in claims:
        if not claim["present"]:
            continue
        relative = str(claim["relative"])
        installed = claim["installed"]
        identity = claim["identity"]
        assert isinstance(installed, Path) and isinstance(identity, tuple)
        identities.append((relative, identity))
        installed_paths.append(installed)
        if relative == requested_relative:
            continue
        if relative == record_relative:
            payload = semantic_record
        else:
            payload = claim["payload"]
            assert isinstance(payload, bytes)
        closure.append((relative, len(payload), hashlib.sha256(payload).hexdigest()))
    canonical = "".join(
        f"{digest}  {size}  {relative}\n" for relative, size, digest in closure
    ).encode("utf-8")
    return (
        (len(closure), hashlib.sha256(canonical).hexdigest()),
        tuple(identities),
        tuple(installed_paths),
    )


def _locked_library_bundles(
    helper: types.ModuleType,
    roots: Sequence[Path],
    expected_versions: Mapping[str, str],
    expected_bundles: Mapping[str, Mapping[str, object]],
) -> tuple[dict[str, tuple[int, str]], tuple[tuple[str, str, tuple[int, int, int, int, int]], ...]]:
    distributions = list(importlib.metadata.distributions(path=[str(root) for root in roots]))
    actual_versions: dict[str, str] = {}
    by_name: dict[str, importlib.metadata.Distribution] = {}
    for distribution in distributions:
        name = _normalize_distribution_name(distribution.metadata.get("Name"))
        if name in by_name:
            raise SubmissionRunnerError(f"submission environment duplicates distribution {name}")
        by_name[name] = distribution
        actual_versions[name] = distribution.version
    try:
        helper._exact_distributions(expected_versions, actual_versions.items())
    except helper.IsolatedPythonError as exc:
        raise SubmissionRunnerError(str(exc)) from exc

    records: dict[str, tuple[int, str]] = {}
    identities: list[tuple[str, str, tuple[int, int, int, int, int]]] = []
    installed_claims: dict[Path, str] = {}
    for name in sorted(expected_versions):
        actual, distribution_identities, installed_paths = _semantic_distribution_bundle(
            by_name[name],
            name,
            roots,
        )
        for installed in installed_paths:
            previous_claim = installed_claims.get(installed)
            if previous_claim is not None:
                raise SubmissionRunnerError(
                    "submission virtualenv path is claimed by multiple distributions: "
                    f"{previous_claim}, {name}"
                )
            installed_claims[installed] = name
        identities.extend((name, relative, identity) for relative, identity in distribution_identities)
        expected = expected_bundles[name]
        if actual != (expected["file_count"], expected["file_bundle_sha256"]):
            raise SubmissionRunnerError(f"submission distribution differs from lock: {name}")
        records[name] = actual
    return records, tuple(identities)


def _allowed_generated(relative: str, generated_paths: Sequence[str]) -> bool:
    return any(relative == path or relative.startswith(f"{path.rstrip('/')}/") for path in generated_paths)


def _require_canonical_command(command: Sequence[str]) -> None:
    if not command or command[0] not in _CANONICAL_ENTRYPOINTS:
        raise SubmissionRunnerError("submission command must be one canonical entrypoint")


def _canonical_repository_path(raw: str, context: str) -> str:
    relative = PurePosixPath(raw)
    if (
        not raw
        or "\\" in raw
        or relative.is_absolute()
        or relative.as_posix() != raw
        or any(part in {"", ".", ".."} for part in relative.parts)
        or any(ord(character) < 32 or ord(character) == 127 for character in raw)
    ):
        raise SubmissionRunnerError(f"{context} is not a canonical repository path")
    return raw


def _git_environment(private_home: Path) -> dict[str, str]:
    return {
        "LC_ALL": "C",
        "LANG": "C",
        "PATH": "/usr/bin:/bin",
        "HOME": str(private_home),
        "XDG_CONFIG_HOME": str(private_home / "xdg"),
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_SYSTEM": "/dev/null",
        "GIT_CONFIG_GLOBAL": "/dev/null",
        "GIT_NO_REPLACE_OBJECTS": "1",
        "GIT_OPTIONAL_LOCKS": "0",
        "GIT_TERMINAL_PROMPT": "0",
    }


def _configure_process_environment(private_home: Path, expected_commit: str) -> dict[str, str]:
    for name in tuple(os.environ):
        if name.startswith("GIT_") or name.startswith("PYTHON") or name in {
            "HOME",
            "XDG_CONFIG_HOME",
        }:
            os.environ.pop(name, None)
    git_environment = _git_environment(private_home)
    os.environ.update(git_environment)
    os.environ["WALKSAFE_SUBMISSION_SOURCE_COMMIT"] = expected_commit
    return git_environment


def _git(repo_root: Path, environment: Mapping[str, str], *arguments: str) -> bytes:
    try:
        completed = subprocess.run(
            [
                "/usr/bin/git",
                "-c",
                "core.fsmonitor=false",
                "-c",
                "core.untrackedCache=false",
                "-C",
                str(repo_root),
                *arguments,
            ],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=dict(environment),
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise SubmissionRunnerError(f"Git command failed: {' '.join(arguments[:2])}") from exc
    return completed.stdout


def _parse_git_tree(raw: bytes) -> dict[str, tuple[bytes, bytes]]:
    result: dict[str, tuple[bytes, bytes]] = {}
    for row in raw.split(b"\0"):
        if not row:
            continue
        try:
            metadata, raw_path = row.split(b"\t", 1)
            mode, kind, object_id = metadata.split(b" ", 2)
            relative = raw_path.decode("utf-8")
        except (UnicodeDecodeError, ValueError) as exc:
            raise SubmissionRunnerError("submission HEAD tree inventory is malformed") from exc
        _canonical_repository_path(relative, "submission HEAD tree path")
        if kind != b"blob" or mode not in {b"100644", b"100755"} or relative in result:
            raise SubmissionRunnerError("submission HEAD tree must contain unique regular files")
        result[relative] = (mode, object_id)
    if not result:
        raise SubmissionRunnerError("submission HEAD tree is empty")
    return result


def _parse_git_index(raw: bytes) -> dict[str, tuple[bytes, bytes]]:
    result: dict[str, tuple[bytes, bytes]] = {}
    for row in raw.split(b"\0"):
        if not row:
            continue
        try:
            metadata, raw_path = row.split(b"\t", 1)
            mode, object_id, stage = metadata.split(b" ", 2)
            relative = raw_path.decode("utf-8")
        except (UnicodeDecodeError, ValueError) as exc:
            raise SubmissionRunnerError("submission Git index inventory is malformed") from exc
        _canonical_repository_path(relative, "submission Git index path")
        if stage != b"0" or mode not in {b"100644", b"100755"} or relative in result:
            raise SubmissionRunnerError("submission Git index must contain unique regular stage-0 files")
        result[relative] = (mode, object_id)
    return result


def _source_git_state(
    repo_root: Path,
    environment: Mapping[str, str],
) -> dict[str, object]:
    try:
        top = Path(
            _git(repo_root, environment, "rev-parse", "--show-toplevel")
            .decode("utf-8")
            .strip()
        )
        commit = _git(repo_root, environment, "rev-parse", "--verify", "HEAD").decode("ascii").strip().lower()
        tree = _git(repo_root, environment, "rev-parse", "--verify", "HEAD^{tree}").decode("ascii").strip().lower()
        object_format = _git(repo_root, environment, "rev-parse", "--show-object-format").decode("ascii").strip()
    except (UnicodeDecodeError, ValueError) as exc:
        raise SubmissionRunnerError("submission Git identity is malformed") from exc
    if top.absolute().resolve() != repo_root or object_format != "sha1":
        raise SubmissionRunnerError("submission source must be the exact SHA-1 Git worktree root")
    if _FULL_COMMIT.fullmatch(commit) is None or _FULL_COMMIT.fullmatch(tree) is None:
        raise SubmissionRunnerError("submission Git identity is not a full SHA-1 object id")
    head = _parse_git_tree(_git(repo_root, environment, "ls-tree", "-r", "-z", "--full-tree", "HEAD"))
    index = _parse_git_index(_git(repo_root, environment, "ls-files", "--stage", "-z"))
    untracked = tuple(
        sorted(
            row
            for row in _git(
                repo_root,
                environment,
                "ls-files",
                "--others",
                "--exclude-standard",
                "-z",
            ).split(b"\0")
            if row
        )
    )
    replace_refs = _git(
        repo_root,
        environment,
        "for-each-ref",
        "--format=%(refname)",
        "refs/replace",
    )
    ignored = tuple(
        sorted(
            row
            for row in _git(
                repo_root,
                environment,
                "ls-files",
                "--others",
                "--ignored",
                "--exclude-standard",
                "-z",
            ).split(b"\0")
            if row
        )
    )
    return {
        "commit": commit,
        "tree": tree,
        "head": head,
        "index": index,
        "untracked": untracked,
        "replace_refs": replace_refs,
        "ignored": ignored,
    }


def _require_runner_checkout(repo_root: Path) -> None:
    expected_runner = (repo_root / "scripts" / Path(__file__).name).absolute()
    current_runner = Path(__file__).absolute()
    if (
        current_runner != expected_runner
        or current_runner.resolve() != current_runner
        or current_runner.is_symlink()
        or not current_runner.is_file()
        or _SCRIPT_DIRECTORY != (repo_root / "scripts").absolute()
    ):
        raise SubmissionRunnerError("submission runner and --repo-root must be the same checkout")


def _submission_generated_paths(policy: types.ModuleType) -> tuple[str, ...]:
    return tuple(
        sorted(
            _canonical_repository_path(relative, "submission generated path")
            for relative in policy.ALL_SUBMISSION_GENERATED_PATHS
        )
    )


def _verify_source(
    policy: types.ModuleType,
    repo_root: Path,
    expected_commit: str,
    git_environment: Mapping[str, str],
) -> dict[str, object]:
    generated = _submission_generated_paths(policy)
    git_payload, git_identity = _stable_bytes(Path("/usr/bin/git"), "trusted Git executable")
    before = _source_git_state(repo_root, git_environment)
    if before["commit"] != expected_commit:
        raise SubmissionRunnerError("submission source HEAD differs from the expected commit")
    if before["index"] != before["head"]:
        raise SubmissionRunnerError("submission Git index differs from the HEAD tree")
    if before["replace_refs"]:
        raise SubmissionRunnerError("submission source contains Git replace refs")
    for raw in before["untracked"]:
        try:
            relative = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise SubmissionRunnerError("submission untracked path is not UTF-8") from exc
        _canonical_repository_path(relative, "submission untracked path")
        if not _allowed_generated(relative, generated):
            raise SubmissionRunnerError(f"submission source contains an untracked path: {relative}")
    for raw in before["ignored"]:
        try:
            relative = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise SubmissionRunnerError("submission ignored path is not UTF-8") from exc
        _canonical_repository_path(relative, "submission ignored path")
        if not _allowed_generated(relative, generated):
            raise SubmissionRunnerError(f"submission source contains an undeclared ignored path: {relative}")

    for relative, (mode, object_id) in before["head"].items():
        if _allowed_generated(relative, generated):
            continue
        path = repo_root.joinpath(*PurePosixPath(relative).parts)
        payload, identity = _stable_bytes(
            path,
            f"submission tracked file {relative}",
            nonempty=False,
        )
        metadata = os.stat(path, follow_symlinks=False)
        current_identity = (
            metadata.st_dev,
            metadata.st_ino,
            metadata.st_size,
            metadata.st_mtime_ns,
            metadata.st_ctime_ns,
        )
        expected_executable = mode == b"100755"
        if current_identity != identity:
            raise SubmissionRunnerError(f"submission tracked file changed: {relative}")
        if bool(stat.S_IMODE(metadata.st_mode) & 0o111) != expected_executable:
            raise SubmissionRunnerError(f"submission tracked file mode differs from HEAD: {relative}")
        digest = hashlib.sha1()
        digest.update(f"blob {len(payload)}\0".encode("ascii"))
        digest.update(payload)
        if digest.hexdigest().encode("ascii") != object_id:
            raise SubmissionRunnerError(f"submission tracked file bytes differ from HEAD: {relative}")

    after = _source_git_state(repo_root, git_environment)
    final_git_payload, final_git_identity = _stable_bytes(Path("/usr/bin/git"), "trusted Git executable")
    if after != before or final_git_identity != git_identity or final_git_payload != git_payload:
        raise SubmissionRunnerError("submission source or trusted Git changed during verification")
    return {
        "source_commit": expected_commit,
        "source_dirty": False,
        "source_dirty_excluded_generated_paths": list(generated),
    }


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--expected-commit", required=True)
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="verify source and the complete locked submission toolchain without dispatching a builder",
    )
    parser.add_argument("command", nargs=argparse.REMAINDER)
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    expected_commit = str(args.expected_commit).lower()
    if _FULL_COMMIT.fullmatch(expected_commit) is None:
        raise SubmissionRunnerError("expected submission commit must be a full SHA-1 object id")
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if args.verify_only and command:
        raise SubmissionRunnerError("submission verify-only mode does not accept a command")
    if not args.verify_only and not command:
        raise SubmissionRunnerError("submission Python command is missing")
    if command:
        _require_canonical_command(command)

    with tempfile.TemporaryDirectory(prefix="walksafe-submission-runtime-") as runtime_raw:
        runtime = Path(runtime_raw)
        private_home = runtime / "home"
        (private_home / "xdg").mkdir(parents=True, mode=0o700)
        git_environment = _configure_process_environment(private_home, expected_commit)

        helper = _load_pinned_source(
            "_walksafe_submission_base_bootstrap",
            _BASE_BOOTSTRAP_PATH,
            _BASE_BOOTSTRAP_SHA256,
        )
        try:
            repo_root = helper._repository_root(args.repo_root)
        except helper.IsolatedPythonError as exc:
            raise SubmissionRunnerError(str(exc)) from exc
        _require_runner_checkout(repo_root)
        policy = _load_pinned_source(
            "_walksafe_submission_manifest_policy",
            _SUBMISSION_POLICY_PATH,
            _SUBMISSION_POLICY_SHA256,
        )
        _verify_source(policy, repo_root, expected_commit, git_environment)
        expected_versions, expected_bundles = _locked_environment(repo_root)
        try:
            before_site, roots, before_site_identity = helper.inspect_site_environment(expected_versions)
        except helper.IsolatedPythonError as exc:
            raise SubmissionRunnerError(str(exc)) from exc
        before_bundles, before_bundle_identity = _locked_library_bundles(
            helper,
            roots,
            expected_versions,
            expected_bundles,
        )

        previous_cache_prefix = sys.pycache_prefix
        previous_sys_path = list(sys.path)
        sys.pycache_prefix = str(runtime / "pycache")
        main_module = sys.modules.get("__main__")
        if main_module is None:
            raise SubmissionRunnerError("submission runner main module is unavailable")
        marker_name = "_walksafe_submission_source_commit"
        root_marker_name = "_walksafe_submission_repo_root"
        generated_marker_name = "_walksafe_submission_generated_paths"
        markers_set = False
        try:
            if args.verify_only:
                sys.path.extend(str(root) for root in roots)
                try:
                    attestation = policy.submission_toolchain_attestation(repo_root)
                except ValueError as exc:
                    raise SubmissionRunnerError(str(exc)) from exc
                print(json.dumps(attestation, ensure_ascii=False, sort_keys=True))
            else:
                setattr(main_module, marker_name, expected_commit)
                setattr(main_module, root_marker_name, str(repo_root))
                setattr(main_module, generated_marker_name, _submission_generated_paths(policy))
                markers_set = True
                helper._dispatch(repo_root, command, roots)
        finally:
            if markers_set:
                for name in (marker_name, root_marker_name, generated_marker_name):
                    if hasattr(main_module, name):
                        delattr(main_module, name)
            sys.path[:] = previous_sys_path
            sys.pycache_prefix = previous_cache_prefix
            try:
                after_site, after_roots, after_site_identity = helper.inspect_site_environment(
                    expected_versions
                )
            except helper.IsolatedPythonError as exc:
                raise SubmissionRunnerError(str(exc)) from exc
            after_bundles, after_bundle_identity = _locked_library_bundles(
                helper,
                after_roots,
                expected_versions,
                expected_bundles,
            )
            _verify_source(policy, repo_root, expected_commit, git_environment)
            if (
                after_site != before_site
                or after_site_identity != before_site_identity
                or after_bundles != before_bundles
                or after_bundle_identity != before_bundle_identity
            ):
                raise SubmissionRunnerError("submission Python or site closure changed during the command")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SubmissionRunnerError as exc:
        print(f"Isolated submission command FAIL: {exc}", file=sys.stderr)
        raise SystemExit(2)
