#!/usr/bin/env python3
"""Run one reviewed Python quality command without Python site startup hooks."""

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
            "isolated quality bootstrap requires Python -I -S -B before any quality code runs"
        )

import argparse
import base64
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path, PurePosixPath
import re
import runpy
import stat
import sysconfig
import tempfile
from typing import Iterable, Mapping, Sequence


POLICY_SHA256 = "c4a9dccedcee730f6542ed2a5b627780bb9419c13a10ce7fd011ecdc8e73a6f5"
POLICY_RELATIVE = "configs/walksafe_product_quality_policy_20260713.json"
PRODUCTS = frozenset({"web", "android", "backend", "voice"})
SHA256 = re.compile(r"[0-9a-f]{64}")
MODULE_NAME = re.compile(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*")


class IsolatedPythonError(RuntimeError):
    """A fail-closed isolated-startup or Python environment error."""


def require_isolated_python(context: str) -> None:
    required = {
        "isolated": sys.flags.isolated,
        "no_site": sys.flags.no_site,
        "ignore_environment": sys.flags.ignore_environment,
        "safe_path": int(sys.flags.safe_path),
        "dont_write_bytecode": sys.flags.dont_write_bytecode,
    }
    if any(value != 1 for value in required.values()) or "site" in sys.modules:
        raise IsolatedPythonError(
            f"{context} requires Python -I -S -B before any quality code runs"
        )


def _strict_json(path: Path, context: str) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise IsolatedPythonError(f"{context} must be a regular non-symlink file")

    def pairs(values: list[tuple[str, object]]) -> dict[str, object]:
        payload: dict[str, object] = {}
        for key, value in values:
            if key in payload:
                raise IsolatedPythonError(f"{context} contains a duplicate JSON key")
            payload[key] = value
        return payload

    def constant(_value: str) -> object:
        raise IsolatedPythonError(f"{context} contains a non-finite JSON number")

    try:
        payload = json.loads(
            path.read_bytes(),
            object_pairs_hook=pairs,
            parse_constant=constant,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise IsolatedPythonError(f"{context} is not strict JSON") from exc
    if not isinstance(payload, dict):
        raise IsolatedPythonError(f"{context} must contain a JSON object")
    return payload


def _safe_relative(raw: object, context: str) -> PurePosixPath:
    if not isinstance(raw, str) or not raw or "\\" in raw:
        raise IsolatedPythonError(f"{context} is not a canonical repository-relative path")
    relative = PurePosixPath(raw)
    if relative.is_absolute() or not relative.parts or any(part in {"", ".", ".."} for part in relative.parts):
        raise IsolatedPythonError(f"{context} is not a canonical repository-relative path")
    return relative


def _normalize_distribution_name(raw: object) -> str:
    if not isinstance(raw, str) or re.fullmatch(r"[A-Za-z0-9_.-]+", raw) is None:
        raise IsolatedPythonError("tested Python environment has invalid distribution metadata")
    return re.sub(r"[-_.]+", "-", raw).lower()


def _valid_version(raw: object) -> str:
    if (
        not isinstance(raw, str)
        or not raw
        or any(character in raw for character in "\0\r\n")
    ):
        raise IsolatedPythonError("tested Python environment has invalid distribution metadata")
    return raw


def _tested_site_packages_policy(raw: object, *, product: str) -> dict[str, object] | None:
    if product == "android":
        if raw is not None:
            raise IsolatedPythonError("Android tested site-packages policy must be null")
        return None
    if not isinstance(raw, dict) or set(raw) != {"format", "closure"}:
        raise IsolatedPythonError("tested site-packages policy is invalid")
    closure = raw.get("closure")
    if (
        raw.get("format") != "walksafe.record-claimed-site-closure.v2"
        or not isinstance(closure, dict)
        or set(closure) != {"count", "sha256"}
        or not isinstance(closure.get("count"), int)
        or isinstance(closure.get("count"), bool)
        or closure["count"] <= 0
        or not isinstance(closure.get("sha256"), str)
        or SHA256.fullmatch(closure["sha256"]) is None
    ):
        raise IsolatedPythonError("tested site-packages policy is invalid")
    return {
        "format": raw["format"],
        "closure": {"count": closure["count"], "sha256": closure["sha256"]},
    }


def _locked_versions(path: Path) -> dict[str, str]:
    if path.is_symlink() or not path.is_file():
        raise IsolatedPythonError("tested environment lock is unavailable")
    logical: list[str] = []
    pending = ""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        raise IsolatedPythonError("tested environment lock is unreadable") from exc
    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.endswith("\\"):
            pending += line[:-1].strip() + " "
            continue
        logical.append((pending + line).strip())
        pending = ""
    if pending:
        raise IsolatedPythonError("tested environment lock has an incomplete continuation")
    pattern = re.compile(
        r"^([A-Za-z0-9_.-]+)(?:\[[A-Za-z0-9_,.-]+\])?==([^\s;]+)"
        r"((?:\s+--hash=sha256:[0-9a-f]{64})+)$"
    )
    versions: dict[str, str] = {}
    for line in logical:
        matched = pattern.fullmatch(line)
        if matched is None:
            raise IsolatedPythonError(
                "tested environment lock is not exactly pinned with SHA-256 hashes"
            )
        name = _normalize_distribution_name(matched.group(1))
        if name in versions:
            raise IsolatedPythonError("tested environment lock contains a duplicate distribution")
        versions[name] = _valid_version(matched.group(2))
    if not versions:
        raise IsolatedPythonError("tested environment lock contains no distributions")
    return versions


def expected_environment(
    repo_root: Path,
    product: str,
) -> tuple[dict[str, str], dict[str, str], dict[str, object] | None]:
    if product not in PRODUCTS:
        raise IsolatedPythonError("isolated Python product is unsupported")
    policy_path = repo_root / POLICY_RELATIVE
    try:
        policy_bytes = policy_path.read_bytes()
    except OSError as exc:
        raise IsolatedPythonError("quality policy is unavailable") from exc
    if policy_path.is_symlink() or hashlib.sha256(policy_bytes).hexdigest() != POLICY_SHA256:
        raise IsolatedPythonError("quality policy differs from the bootstrap-pinned policy")
    policy = _strict_json(policy_path, "quality policy")
    products = policy.get("products")
    if not isinstance(products, dict) or set(products) != PRODUCTS:
        raise IsolatedPythonError("quality policy product set is invalid")
    definition = products.get(product)
    if not isinstance(definition, dict):
        raise IsolatedPythonError("quality policy product definition is invalid")
    lock_relatives = [
        relative
        for relative in (
            definition.get("tested_environment_lock"),
            definition.get("tested_tool_lock"),
        )
        if relative is not None
    ]
    locked: dict[str, str] = {}
    for raw_relative in lock_relatives:
        relative = _safe_relative(raw_relative, "tested environment lock")
        for name, version in _locked_versions(repo_root.joinpath(*relative.parts)).items():
            if name in locked and locked[name] != version:
                raise IsolatedPythonError("tested environment locks disagree")
            locked[name] = version
    trusted = definition.get("trusted_tool_distributions")
    if not isinstance(trusted, dict):
        raise IsolatedPythonError("trusted tool distribution policy is invalid")
    trusted_versions: dict[str, str] = {}
    for raw_name, raw_version in trusted.items():
        name = _normalize_distribution_name(raw_name)
        if name != raw_name or name in trusted_versions:
            raise IsolatedPythonError("trusted tool distribution name is not canonical")
        trusted_versions[name] = _valid_version(raw_version)
    if set(locked) & set(trusted_versions):
        raise IsolatedPythonError("trusted tools collide with fixed locks")
    return (
        locked,
        {**locked, **trusted_versions},
        _tested_site_packages_policy(
            definition.get("tested_site_packages"),
            product=product,
        ),
    )


def _distribution_identity(versions: Mapping[str, str]) -> dict[str, object]:
    canonical = "".join(
        f"{name}=={versions[name]}\n" for name in sorted(versions)
    ).encode("utf-8")
    return {"count": len(versions), "sha256": hashlib.sha256(canonical).hexdigest()}


def _exact_distributions(
    expected: Mapping[str, str],
    distribution_items: Iterable[tuple[object, object]],
) -> dict[str, str]:
    installed: dict[str, str] = {}
    for raw_name, raw_version in distribution_items:
        name = _normalize_distribution_name(raw_name)
        version = _valid_version(raw_version)
        if name in installed:
            raise IsolatedPythonError(
                f"tested Python environment contains a duplicate normalized distribution: {name}"
            )
        installed[name] = version
    missing = sorted(set(expected) - set(installed))
    unexpected = sorted(set(installed) - set(expected))
    mismatched = sorted(
        name for name in set(expected) & set(installed) if expected[name] != installed[name]
    )
    if missing or unexpected or mismatched:
        raise IsolatedPythonError(
            "tested Python environment differs from its exact fixed distribution set: "
            f"missing={missing[:3]}, unexpected={unexpected[:3]}, "
            f"version_mismatch={mismatched[:3]}"
        )
    return installed


def _site_roots() -> tuple[Path, ...]:
    prefix = Path(sys.prefix).absolute()
    base_prefix = Path(sys.base_prefix).absolute()
    marker = prefix / "pyvenv.cfg"
    executable = Path(sys.executable).absolute()
    executable_directory = prefix / "bin"
    if (
        sys.prefix == sys.base_prefix
        or prefix.resolve() != prefix
        or prefix.is_symlink()
        or not prefix.is_dir()
        or marker.resolve() != marker
        or marker.is_symlink()
        or not marker.is_file()
        or not stat.S_ISREG(marker.lstat().st_mode)
        or executable.parent != executable_directory
        or executable_directory.resolve() != executable_directory
        or executable_directory.is_symlink()
        or not executable_directory.is_dir()
        or prefix.resolve() == base_prefix.resolve()
    ):
        raise IsolatedPythonError("tested Python environment is not a real dedicated virtualenv")
    roots: list[Path] = []
    for key in ("purelib", "platlib"):
        raw = sysconfig.get_path(key)
        if not isinstance(raw, str) or not raw:
            raise IsolatedPythonError("tested Python environment has no site-packages path")
        root = Path(raw).absolute()
        if root.resolve() != root or root.is_symlink() or not root.is_dir():
            raise IsolatedPythonError("tested site-packages root is not a real directory")
        try:
            relative = root.relative_to(prefix)
        except ValueError as exc:
            raise IsolatedPythonError("tested site-packages root is outside its virtualenv") from exc
        if not relative.parts:
            raise IsolatedPythonError("tested site-packages root is outside its virtualenv")
        if root not in roots:
            roots.append(root)
    return tuple(roots)


def _canonical_site_name(path: Path, root: Path) -> str:
    relative = path.relative_to(root).as_posix()
    if (
        not relative
        or "\\" in relative
        or any(ord(character) < 32 or ord(character) == 127 for character in relative)
    ):
        raise IsolatedPythonError("tested site-packages path is not canonical")
    return relative


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _walk_site_closure(
    roots: Sequence[Path],
) -> tuple[
    dict[tuple[int, str], tuple[int, int, int]],
    dict[tuple[int, str], tuple[int, int, int, int, str]],
]:
    directories: dict[tuple[int, str], tuple[int, int, int]] = {}
    files: dict[tuple[int, str], tuple[int, int, int, int, str]] = {}
    for root_index, root in enumerate(roots):
        for directory, directory_names, file_names in os.walk(root, topdown=True, followlinks=False):
            directory_path = Path(directory)
            for name in sorted(directory_names):
                path = directory_path / name
                mode = path.lstat().st_mode
                _canonical_site_name(path, root)
                if not stat.S_ISDIR(mode) or stat.S_ISLNK(mode):
                    raise IsolatedPythonError("tested site-packages contains a symlink or special directory")
                relative = _canonical_site_name(path, root)
                key = (root_index, relative)
                metadata = path.lstat()
                if key in directories:
                    raise IsolatedPythonError("tested site-packages contains a duplicate directory")
                directories[key] = (
                    metadata.st_dev,
                    metadata.st_ino,
                    stat.S_IMODE(metadata.st_mode),
                )
            for name in sorted(file_names):
                path = directory_path / name
                relative = _canonical_site_name(path, root)
                metadata = path.lstat()
                if not stat.S_ISREG(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
                    raise IsolatedPythonError("tested site-packages contains a symlink or special file")
                if path.suffix.lower() in {".pyc", ".pyo"}:
                    raise IsolatedPythonError("tested site-packages contains executable bytecode")
                key = (root_index, relative)
                if key in files:
                    raise IsolatedPythonError("tested site-packages contains a duplicate path")
                files[key] = (
                    metadata.st_dev,
                    metadata.st_ino,
                    stat.S_IMODE(metadata.st_mode),
                    metadata.st_size,
                    _file_sha256(path),
                )
    return directories, files


def _claimed_site_directories(
    claimed_files: Iterable[tuple[int, str]],
) -> set[tuple[int, str]]:
    claimed: set[tuple[int, str]] = set()
    for root_index, relative in claimed_files:
        for parent in PurePosixPath(relative).parents:
            if parent == PurePosixPath("."):
                continue
            claimed.add((root_index, parent.as_posix()))
    return claimed


def _site_closure_record(
    roots: Sequence[Path],
    directories: Mapping[tuple[int, str], tuple[int, int, int]],
    files: Mapping[tuple[int, str], tuple[int, int, int, int, str]],
    normalized_record_files: Mapping[tuple[int, str], tuple[int, int, str]],
) -> dict[str, object]:
    canonical_rows = [
        f"root\0{root_index}\0{stat.S_IMODE(root.lstat().st_mode):04o}\n"
        for root_index, root in enumerate(roots)
    ]
    canonical_rows.extend(
        f"directory\0{key[0]}/{key[1]}\0{directories[key][2]:04o}\n"
        for key in sorted(directories)
    )
    for key in sorted(files):
        mode, size, digest = normalized_record_files.get(key, files[key][2:])
        canonical_rows.append(
            f"file\0{key[0]}/{key[1]}\0{mode:04o}\0{size}\0{digest}\n"
        )
    canonical = "".join(canonical_rows).encode("utf-8")
    return {
        "format": "walksafe.record-claimed-site-closure.v2",
        "roots": [str(root) for root in roots],
        "closure": {
            "count": len(roots) + len(directories) + len(files),
            "sha256": hashlib.sha256(canonical).hexdigest(),
        },
    }


def _canonical_record_claim(raw: str) -> PurePosixPath:
    relative = PurePosixPath(raw)
    if (
        not raw
        or "\\" in raw
        or relative.is_absolute()
        or relative.as_posix() != raw
        or any(ord(character) < 32 or ord(character) == 127 for character in raw)
    ):
        raise IsolatedPythonError("distribution RECORD uses a non-canonical file claim")
    saw_name = False
    for part in relative.parts:
        if part == "..":
            if saw_name:
                raise IsolatedPythonError("distribution RECORD uses a non-canonical file claim")
        else:
            saw_name = True
    if not saw_name:
        raise IsolatedPythonError("distribution RECORD uses a non-canonical file claim")
    return relative


def _external_record_claim(
    candidate: Path,
    raw: str,
    hash_mode: str,
    hash_value: str,
    recorded_size: int | None,
) -> tuple[str, int | None, tuple[str, int, int] | None]:
    prefix = Path(sys.prefix).absolute()
    try:
        relative = candidate.relative_to(prefix)
    except ValueError as exc:
        raise IsolatedPythonError("distribution RECORD claim escapes its virtualenv") from exc
    if not relative.parts or candidate.resolve() != candidate:
        raise IsolatedPythonError("distribution RECORD claim is not a real virtualenv file")
    try:
        before = candidate.lstat()
    except FileNotFoundError:
        if candidate.suffix.lower() in {".pyc", ".pyo"} and not hash_mode and recorded_size is None:
            return hash_value, recorded_size, None
        raise IsolatedPythonError("distribution RECORD claims a missing virtualenv file")
    except OSError as exc:
        raise IsolatedPythonError("distribution RECORD virtualenv file is unreadable") from exc
    if (
        not stat.S_ISREG(before.st_mode)
        or stat.S_ISLNK(before.st_mode)
        or candidate.suffix.lower() in {".pyc", ".pyo"}
    ):
        raise IsolatedPythonError("distribution RECORD claims an unsafe virtualenv file")
    try:
        payload = candidate.read_bytes()
        after = candidate.lstat()
    except OSError as exc:
        raise IsolatedPythonError("distribution RECORD virtualenv file is unreadable") from exc
    identities = {
        (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
        for item in (before, after)
    }
    if len(identities) != 1 or len(payload) != before.st_size:
        raise IsolatedPythonError("distribution RECORD virtualenv file changed during inspection")
    digest = hashlib.sha256(payload).digest()
    if recorded_size != len(payload):
        raise IsolatedPythonError("virtualenv file size differs from distribution RECORD")
    if hash_mode != "sha256":
        raise IsolatedPythonError("distribution RECORD uses a non-SHA-256 file hash")
    encoded = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    if encoded != hash_value:
        raise IsolatedPythonError("virtualenv file differs from distribution RECORD")
    launcher_shebang = b"#!" + os.fsencode(prefix / "bin/python") + b"\n"
    normalized = payload
    if candidate.parent == prefix / "bin":
        if not payload.startswith(launcher_shebang):
            raise IsolatedPythonError(
                "distribution RECORD virtualenv bin claim lacks the exact Python launcher shebang"
            )
        normalized = b"#!/__walksafe_venv__/bin/python\n" + payload[len(launcher_shebang) :]
    normalized_hash = base64.urlsafe_b64encode(hashlib.sha256(normalized).digest()).rstrip(b"=").decode("ascii")
    return normalized_hash, len(normalized), (raw, before.st_dev, before.st_ino)


def _claimed_site_files(
    distributions: Sequence[importlib.metadata.Distribution],
    roots: Sequence[Path],
    actual: Mapping[tuple[int, str], tuple[int, int, int, int, str]],
) -> tuple[
    set[tuple[int, str]],
    dict[tuple[int, str], tuple[int, int, str]],
    tuple[tuple[str, int, int], ...],
]:
    claimed: set[tuple[int, str]] = set()
    seen_claims: set[tuple[int, str]] = set()
    seen_external_claims: set[Path] = set()
    normalized_record_files: dict[tuple[int, str], tuple[int, int, str]] = {}
    external_identities: list[tuple[str, int, int]] = []
    for distribution in distributions:
        package_files = distribution.files
        if package_files is None:
            raise IsolatedPythonError("installed distribution has no RECORD file closure")
        seen_record_claims: set[str] = set()
        record_key: tuple[int, str] | None = None
        normalized_rows: list[str] = []
        for package_file in package_files:
            raw = str(package_file)
            _canonical_record_claim(raw)
            if raw in seen_record_claims:
                raise IsolatedPythonError("distribution RECORD contains a duplicate file claim")
            seen_record_claims.add(raw)
            hash_mode = package_file.hash.mode if package_file.hash is not None else ""
            hash_value = package_file.hash.value if package_file.hash is not None else ""
            recorded_size = package_file.size
            if recorded_size is not None and recorded_size < 0:
                raise IsolatedPythonError("distribution RECORD contains an invalid file size")
            candidate = Path(os.path.abspath(distribution.locate_file(package_file)))
            match: tuple[int, str] | None = None
            for root_index, root in enumerate(roots):
                try:
                    relative_path = candidate.relative_to(root)
                except ValueError:
                    continue
                if ".." in PurePosixPath(raw).parts:
                    raise IsolatedPythonError("distribution RECORD uses an escaping site-packages claim")
                key = (root_index, _canonical_site_name(root / relative_path, root))
                if match is not None:
                    raise IsolatedPythonError("distribution RECORD claim matches multiple site roots")
                match = key
            if match is None:
                if candidate in seen_external_claims:
                    raise IsolatedPythonError("virtualenv path is claimed by multiple distributions")
                seen_external_claims.add(candidate)
                hash_value, recorded_size, identity = _external_record_claim(
                    candidate,
                    raw,
                    hash_mode,
                    hash_value,
                    recorded_size,
                )
                if identity is not None:
                    external_identities.append(identity)
                normalized_rows.append(
                    f"claim\0{raw}\0{hash_mode}\0{hash_value}\0"
                    f"{'' if recorded_size is None else recorded_size}\n"
                )
                continue
            if match in seen_claims:
                raise IsolatedPythonError("site-packages path is claimed by multiple distributions")
            seen_claims.add(match)
            record = actual.get(match)
            if record is None:
                if (
                    Path(match[1]).suffix.lower() in {".pyc", ".pyo"}
                    and package_file.hash is None
                    and package_file.size is None
                ):
                    normalized_rows.append(f"claim\0{raw}\0\0\0\n")
                    continue
                raise IsolatedPythonError("distribution RECORD claims a missing site-packages file")
            claimed.add(match)
            _device, _inode, _mode, size, digest = record
            if package_file.size is not None and package_file.size != size:
                raise IsolatedPythonError("site-packages file size differs from distribution RECORD")
            if package_file.hash is None:
                if not match[1].endswith(".dist-info/RECORD"):
                    raise IsolatedPythonError("site-packages file is not hashed by distribution RECORD")
                if record_key is not None:
                    raise IsolatedPythonError("distribution has multiple unhashed RECORD files")
                record_key = match
            elif package_file.hash.mode != "sha256":
                raise IsolatedPythonError("distribution RECORD uses a non-SHA-256 file hash")
            else:
                encoded = base64.urlsafe_b64encode(bytes.fromhex(digest)).rstrip(b"=").decode("ascii")
                if encoded != package_file.hash.value:
                    raise IsolatedPythonError("site-packages file differs from distribution RECORD")
            normalized_rows.append(
                f"claim\0{raw}\0{hash_mode}\0{hash_value}\0"
                f"{'' if recorded_size is None else recorded_size}\n"
            )
        if record_key is None:
            raise IsolatedPythonError("installed distribution has no unhashed RECORD file")
        canonical_record = "".join(sorted(normalized_rows)).encode("utf-8")
        normalized_record_files[record_key] = (
            actual[record_key][2],
            len(canonical_record),
            hashlib.sha256(canonical_record).hexdigest(),
        )
    return claimed, normalized_record_files, tuple(sorted(external_identities))


def inspect_site_environment(
    expected: Mapping[str, str],
    *,
    installed_distributions: Mapping[str, str] | None = None,
) -> tuple[dict[str, object], tuple[Path, ...], object]:
    if installed_distributions is not None:
        installed = _exact_distributions(expected, installed_distributions.items())
        roots = _site_roots() if expected else ()
        canonical_rows = [
            f"root\0{root_index}\0{stat.S_IMODE(root.lstat().st_mode):04o}\n"
            for root_index, root in enumerate(roots)
        ]
        canonical_rows.extend(
            f"test-injected-distribution\0{name}=={installed[name]}\n"
            for name in sorted(installed)
        )
        synthetic = "".join(canonical_rows).encode("utf-8")
        return (
            {
                "installed_distributions": _distribution_identity(installed),
                "site_packages": {
                    "format": "walksafe.record-claimed-site-closure.v2",
                    "roots": [str(root) for root in roots],
                    "closure": {
                        "count": len(roots) + len(installed),
                        "sha256": hashlib.sha256(synthetic).hexdigest(),
                    },
                },
            },
            roots,
            (
                tuple((root.stat().st_dev, root.stat().st_ino) for root in roots),
                tuple(sorted(installed.items())),
            ),
        )
    if not expected:
        return (
            {
                "installed_distributions": _distribution_identity({}),
                "site_packages": {
                    "format": "walksafe.record-claimed-site-closure.v2",
                    "roots": [],
                    "closure": {"count": 0, "sha256": hashlib.sha256(b"").hexdigest()},
                },
            },
            (),
            ((), ()),
        )
    roots = _site_roots()
    distributions = list(importlib.metadata.distributions(path=[str(root) for root in roots]))
    installed = _exact_distributions(
        expected,
        ((distribution.metadata.get("Name"), distribution.version) for distribution in distributions),
    )
    directories, files = _walk_site_closure(roots)
    claimed_files, normalized_record_files, external_identities = _claimed_site_files(
        distributions,
        roots,
        files,
    )
    unexpected_files = sorted(set(files) - claimed_files)
    if unexpected_files:
        raise IsolatedPythonError(
            f"tested site-packages contains an unowned file: {unexpected_files[:3]}"
        )
    claimed_directories = _claimed_site_directories(claimed_files)
    unexpected_directories = sorted(set(directories) - claimed_directories)
    if unexpected_directories:
        raise IsolatedPythonError(
            f"tested site-packages contains an unowned directory: {unexpected_directories[:3]}"
        )
    return (
        {
            "installed_distributions": _distribution_identity(installed),
            "site_packages": _site_closure_record(
                roots,
                directories,
                files,
                normalized_record_files,
            ),
        },
        roots,
        (
            tuple((root.stat().st_dev, root.stat().st_ino) for root in roots),
            tuple(
                (key, directories[key][0], directories[key][1])
                for key in sorted(directories)
            ),
            tuple((key, files[key][0], files[key][1]) for key in sorted(files)),
            external_identities,
        ),
    )


def require_expected_site_packages(
    environment: Mapping[str, object],
    expected: Mapping[str, object] | None,
) -> None:
    if expected is None:
        return
    site_packages = environment.get("site_packages")
    if not isinstance(site_packages, dict):
        raise IsolatedPythonError("tested Python site closure record is missing")
    observed = {
        "format": site_packages.get("format"),
        "closure": site_packages.get("closure"),
    }
    if observed != expected:
        raise IsolatedPythonError(
            "tested Python site closure differs from the source-pinned policy"
        )


def _repository_root(raw: Path) -> Path:
    root = raw.expanduser().absolute()
    if root.resolve() != root or root.is_symlink() or not root.is_dir() or not (root / ".git").exists():
        raise IsolatedPythonError("repo root must be a real Git worktree")
    for directory, directory_names, file_names in os.walk(root, topdown=True, followlinks=False):
        if Path(directory) == root:
            directory_names[:] = [name for name in directory_names if name != ".git"]
        if any(Path(name).suffix.lower() in {".pyc", ".pyo"} for name in file_names):
            raise IsolatedPythonError("repo root contains executable Python bytecode")
    return root


def _stable_source_bytes(path: Path) -> bytes:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
        with os.fdopen(descriptor, "rb") as source:
            before = os.fstat(source.fileno())
            payload = source.read()
            after = os.fstat(source.fileno())
        current = os.stat(path, follow_symlinks=False)
    except OSError as exc:
        raise IsolatedPythonError("isolated Python source cannot be snapshotted") from exc
    identities = {
        (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
        for item in (before, after, current)
    }
    if not stat.S_ISREG(current.st_mode) or len(identities) != 1 or not payload:
        raise IsolatedPythonError("isolated Python source changed while being snapshotted")
    return payload


def _dispatch(repo_root: Path, command: Sequence[str], site_roots: Sequence[Path]) -> None:
    if not command:
        raise IsolatedPythonError("isolated Python command is missing")
    if command[0] == "-m":
        if len(command) < 2 or MODULE_NAME.fullmatch(command[1]) is None:
            raise IsolatedPythonError("isolated Python module name is invalid")
        sys.path.extend([*(str(root) for root in site_roots), str(repo_root)])
        sys.argv = [command[1], *command[2:]]
        runpy.run_module(command[1], run_name="__main__", alter_sys=True)
        return
    target = Path(command[0])
    target = target.absolute() if target.is_absolute() else (repo_root / target).absolute()
    if target.resolve() != target or target.is_symlink() or not target.is_file() or target.suffix != ".py":
        raise IsolatedPythonError("isolated Python script must be a real repository .py file")
    try:
        target.relative_to(repo_root)
    except ValueError as exc:
        raise IsolatedPythonError("isolated Python script escaped the repository") from exc
    sys.path.extend(
        [*(str(root) for root in site_roots), str(target.parent), str(repo_root)]
    )
    sys.argv = [str(target), *command[1:]]
    namespace = {
        "__name__": "__main__",
        "__file__": str(target),
        "__cached__": None,
        "__loader__": None,
        "__package__": None,
        "__spec__": None,
    }
    payload = _stable_source_bytes(target)
    exec(compile(payload, str(target), "exec", dont_inherit=True), namespace)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", required=True, type=Path)
    parser.add_argument("--product", required=True, choices=sorted(PRODUCTS))
    parser.add_argument("command", nargs=argparse.REMAINDER)
    return parser.parse_args()


def main() -> int:
    require_isolated_python("isolated quality bootstrap")
    args = parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    repo_root = _repository_root(args.repo_root)
    _locked, expected, expected_site_packages = expected_environment(repo_root, args.product)
    before, roots, before_identity = inspect_site_environment(expected)
    require_expected_site_packages(before, expected_site_packages)
    previous_cache_prefix = sys.pycache_prefix
    with tempfile.TemporaryDirectory(prefix="walksafe-isolated-pycache-") as cache_prefix:
        sys.pycache_prefix = cache_prefix
        try:
            _dispatch(repo_root, command, roots)
        finally:
            sys.pycache_prefix = previous_cache_prefix
            after, _, after_identity = inspect_site_environment(expected)
            if after != before or after_identity != before_identity:
                raise IsolatedPythonError("tested Python site closure changed during the quality command")
            require_expected_site_packages(after, expected_site_packages)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except IsolatedPythonError as exc:
        print(f"Isolated Python quality command FAIL: {exc}", file=sys.stderr)
        raise SystemExit(2)
