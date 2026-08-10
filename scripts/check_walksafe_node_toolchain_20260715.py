#!/usr/bin/env python3
"""Verify the exact official Node/npm tree used by the WalkSafe Web build."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import stat
import subprocess
import sys
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


SCHEMA_VERSION = "walksafe.node-toolchain.v2"
ATTESTATION_SCHEMA_VERSION = "walksafe.node-toolchain-attestation.v2"
DEFAULT_LOCK = Path(__file__).resolve().parents[1] / "configs/walksafe_node_toolchain_lock_20260715.json"
NODE_PATH = "bin/node"
NPM_LAUNCHER_PATH = "bin/npm"
NPM_LAUNCHER_TARGET = "../lib/node_modules/npm/bin/npm-cli.js"
NPM_LAUNCHER_RESOLVED_PATH = "lib/node_modules/npm/bin/npm-cli.js"
NPM_PACKAGE_PATH = "lib/node_modules/npm"
CLOSURE_ALGORITHM = "walksafe-node-npm-closure-jsonl-sha256.v1"
ROOT_CLOSURE_ALGORITHM = "walksafe-node-root-closure-jsonl-sha256.v1"
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
MODE_PATTERN = re.compile(r"[0-7]{4}")
NODE_VERSION_PATTERN = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+")
NPM_VERSION_PATTERN = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")


class VerificationError(ValueError):
    """The configured Node/npm toolchain does not match the lock."""


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise VerificationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise VerificationError(f"non-finite JSON value is forbidden: {value}")


def _stat_identity(metadata: os.stat_result) -> tuple[int, int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _require_real_directory(path: Path, label: str) -> Path:
    candidate = path.expanduser()
    if not candidate.is_absolute():
        raise VerificationError(f"{label} must be absolute")
    try:
        metadata = candidate.lstat()
        resolved = candidate.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise VerificationError(f"{label} is unavailable: {candidate}") from exc
    if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
        raise VerificationError(f"{label} must be a real directory")
    if resolved != candidate:
        raise VerificationError(f"{label} must not contain symlink or non-canonical components")
    return candidate


def _consume_regular_file(
    path: Path,
    label: str,
    *,
    capture: bool,
    maximum: int | None = None,
) -> tuple[bytes | None, os.stat_result, int, str]:
    try:
        before = path.lstat()
    except OSError as exc:
        raise VerificationError(f"{label} is unavailable: {path}") from exc
    if not stat.S_ISREG(before.st_mode) or stat.S_ISLNK(before.st_mode):
        raise VerificationError(f"{label} must be a regular non-symlink file")
    if maximum is not None and before.st_size > maximum:
        raise VerificationError(f"{label} exceeds {maximum} bytes")
    nofollow = getattr(os, "O_NOFOLLOW", None)
    if nofollow is None:
        raise VerificationError("O_NOFOLLOW support is required")
    flags = os.O_RDONLY | nofollow
    if hasattr(os, "O_CLOEXEC"):
        flags |= os.O_CLOEXEC
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise VerificationError(f"{label} cannot be opened safely") from exc
    try:
        opened = os.fstat(descriptor)
        if _stat_identity(opened) != _stat_identity(before):
            raise VerificationError(f"{label} changed before it was opened")
        payload = bytearray() if capture else None
        digest = hashlib.sha256()
        total = 0
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            digest.update(chunk)
            if payload is not None:
                payload.extend(chunk)
        after = os.fstat(descriptor)
    finally:
        os.close(descriptor)
    try:
        current = path.lstat()
    except OSError as exc:
        raise VerificationError(f"{label} disappeared while being read") from exc
    if _stat_identity(after) != _stat_identity(before) or _stat_identity(current) != _stat_identity(before):
        raise VerificationError(f"{label} changed while being read")
    if total != before.st_size:
        raise VerificationError(f"{label} byte count changed while being read")
    return bytes(payload) if payload is not None else None, before, total, digest.hexdigest()


def _read_regular_bytes(path: Path, label: str, *, maximum: int | None = None) -> bytes:
    payload, _, _, _ = _consume_regular_file(path, label, capture=True, maximum=maximum)
    if payload is None:
        raise VerificationError(f"{label} could not be captured")
    return payload


def _regular_file_record(path: Path, relative_path: str) -> dict[str, Any]:
    _, metadata, size, digest = _consume_regular_file(
        path,
        f"regular file {relative_path}",
        capture=False,
    )
    return {
        "path": relative_path,
        "type": "regular",
        "mode": f"{stat.S_IMODE(metadata.st_mode):04o}",
        "bytes": size,
        "sha256": digest,
        "target": "",
    }


def _canonical_symlink_target(path: Path, boundary: Path, label: str) -> tuple[str, Path]:
    try:
        raw_target = os.readlink(path)
    except OSError as exc:
        raise VerificationError(f"{label} target cannot be read") from exc
    try:
        raw_target.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise VerificationError(f"{label} target is not UTF-8") from exc
    target_path = PurePosixPath(raw_target)
    if (
        not raw_target
        or target_path.is_absolute()
        or "\\" in raw_target
        or target_path.as_posix() != raw_target
    ):
        raise VerificationError(f"{label} target is not a canonical relative POSIX path")
    try:
        resolved = path.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise VerificationError(f"{label} target is unresolved") from exc
    if resolved != boundary and boundary not in resolved.parents:
        raise VerificationError(f"{label} target escapes its trusted root")
    canonical = os.path.relpath(resolved, start=path.parent).replace(os.sep, "/")
    if raw_target != canonical:
        raise VerificationError(f"{label} target is not canonical")
    return raw_target, resolved


def _symlink_record(path: Path, relative_path: str, boundary: Path) -> tuple[dict[str, Any], Path]:
    try:
        before = path.lstat()
    except OSError as exc:
        raise VerificationError(f"symlink {relative_path} is unavailable") from exc
    if not stat.S_ISLNK(before.st_mode):
        raise VerificationError(f"{relative_path} must be a symlink")
    target, resolved = _canonical_symlink_target(path, boundary, f"symlink {relative_path}")
    try:
        after = path.lstat()
    except OSError as exc:
        raise VerificationError(f"symlink {relative_path} disappeared") from exc
    if _stat_identity(after) != _stat_identity(before):
        raise VerificationError(f"symlink {relative_path} changed while being read")
    encoded = target.encode("utf-8")
    return (
        {
            "path": relative_path,
            "type": "symlink",
            "mode": f"{stat.S_IMODE(before.st_mode):04o}",
            "bytes": len(encoded),
            "sha256": hashlib.sha256(encoded).hexdigest(),
            "target": target,
        },
        resolved,
    )


def _validate_relative_path(value: str, *, allow_root: bool = False) -> None:
    if allow_root and value == ".":
        return
    path = PurePosixPath(value)
    if not value or path.is_absolute() or "\\" in value or path.as_posix() != value:
        raise VerificationError(f"non-canonical closure path: {value!r}")
    if any(part in {"", ".", ".."} for part in path.parts):
        raise VerificationError(f"unsafe closure path: {value!r}")
    try:
        value.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise VerificationError(f"closure path is not UTF-8: {value!r}") from exc


def _directory_records(root: Path) -> list[dict[str, Any]]:
    boundary = _require_real_directory(root, "npm package root")
    records: list[dict[str, Any]] = []

    def visit(path: Path, relative_path: str) -> None:
        try:
            metadata = path.lstat()
        except OSError as exc:
            raise VerificationError(f"npm package entry is unavailable: {relative_path}") from exc
        if stat.S_ISDIR(metadata.st_mode):
            records.append(
                {
                    "path": relative_path,
                    "type": "directory",
                    "mode": f"{stat.S_IMODE(metadata.st_mode):04o}",
                    "bytes": 0,
                    "sha256": "",
                    "target": "",
                }
            )
            try:
                with os.scandir(path) as iterator:
                    children = sorted(iterator, key=lambda item: item.name.encode("utf-8"))
            except (OSError, UnicodeEncodeError) as exc:
                raise VerificationError(f"npm package directory cannot be enumerated: {relative_path}") from exc
            for child in children:
                name = child.name
                if not name or name in {".", ".."} or "/" in name or "\\" in name:
                    raise VerificationError(f"npm package contains an unsafe entry name: {name!r}")
                child_relative = name if relative_path == "." else f"{relative_path}/{name}"
                visit(Path(child.path), child_relative)
            return
        if stat.S_ISREG(metadata.st_mode):
            records.append(_regular_file_record(path, relative_path))
            return
        if stat.S_ISLNK(metadata.st_mode):
            record, _ = _symlink_record(path, relative_path, boundary)
            records.append(record)
            return
        raise VerificationError(f"npm package contains a special file: {relative_path}")

    visit(boundary, ".")
    records.sort(key=lambda record: record["path"].encode("utf-8"))
    return records


def _closure_summary(
    records: Iterable[dict[str, Any]],
    *,
    algorithm: str = CLOSURE_ALGORITHM,
) -> dict[str, Any]:
    rows = list(records)
    paths: list[str] = []
    counts = {"directory": 0, "regular": 0, "symlink": 0}
    content_bytes = 0
    canonical_rows: list[str] = []
    for record in rows:
        if set(record) != {"path", "type", "mode", "bytes", "sha256", "target"}:
            raise VerificationError("closure record has unexpected fields")
        relative_path = record["path"]
        kind = record["type"]
        mode = record["mode"]
        size = record["bytes"]
        digest = record["sha256"]
        target = record["target"]
        if not isinstance(relative_path, str):
            raise VerificationError("closure record path must be a string")
        _validate_relative_path(relative_path, allow_root=True)
        if kind not in counts:
            raise VerificationError(f"unexpected closure entry type: {kind!r}")
        if not isinstance(mode, str) or MODE_PATTERN.fullmatch(mode) is None:
            raise VerificationError(f"invalid closure mode for {relative_path}")
        if isinstance(size, bool) or not isinstance(size, int) or size < 0:
            raise VerificationError(f"invalid closure byte count for {relative_path}")
        if not isinstance(digest, str) or not isinstance(target, str):
            raise VerificationError(f"invalid closure hash or target for {relative_path}")
        if kind == "directory":
            if size != 0 or digest or target:
                raise VerificationError(f"directory record has content fields: {relative_path}")
        elif kind == "regular":
            if SHA256_PATTERN.fullmatch(digest) is None or target:
                raise VerificationError(f"regular record has invalid content fields: {relative_path}")
        else:
            if SHA256_PATTERN.fullmatch(digest) is None or not target:
                raise VerificationError(f"symlink record has invalid content fields: {relative_path}")
            encoded_target = target.encode("utf-8")
            if size != len(encoded_target) or digest != hashlib.sha256(encoded_target).hexdigest():
                raise VerificationError(f"symlink record does not bind its target: {relative_path}")
        paths.append(relative_path)
        counts[kind] += 1
        content_bytes += size
        canonical_rows.append(
            json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
        )
    expected_order = sorted(paths, key=lambda value: value.encode("utf-8"))
    if paths != expected_order or len(paths) != len(set(paths)):
        raise VerificationError("closure records are not in unique canonical path order")
    if not paths or paths[0] != "." or rows[0]["type"] != "directory":
        raise VerificationError("closure must begin with the npm package root directory")
    return {
        "algorithm": algorithm,
        "entries": len(rows),
        "directories": counts["directory"],
        "regular_files": counts["regular"],
        "symlinks": counts["symlink"],
        "bytes": content_bytes,
        "sha256": hashlib.sha256("".join(canonical_rows).encode("utf-8")).hexdigest(),
    }


def _directory_closure(
    root: Path,
    *,
    algorithm: str = CLOSURE_ALGORITHM,
) -> dict[str, Any]:
    return _closure_summary(_directory_records(root), algorithm=algorithm)


def _expect_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise VerificationError(f"{label} has unexpected fields")
    return value


def _require_string(value: Any, label: str, pattern: re.Pattern[str] | None = None) -> str:
    if not isinstance(value, str) or not value or (pattern is not None and pattern.fullmatch(value) is None):
        raise VerificationError(f"{label} is invalid")
    return value


def _require_nonnegative_integer(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise VerificationError(f"{label} is invalid")
    return value


def _load_lock(path: Path) -> tuple[dict[str, Any], bytes]:
    payload = _read_regular_bytes(path.expanduser().absolute(), "Node toolchain lock", maximum=64 * 1024)
    try:
        decoded = payload.decode("utf-8")
        value = json.loads(decoded, object_pairs_hook=_strict_object, parse_constant=_reject_constant)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise VerificationError("Node toolchain lock is not strict UTF-8 JSON") from exc
    return _expect_keys(
        value,
        {"schema_version", "official_archive", "platform", "root", "node", "npm"},
        "Node toolchain lock",
    ), payload


def _validate_lock(lock: dict[str, Any]) -> None:
    if lock["schema_version"] != SCHEMA_VERSION:
        raise VerificationError("unexpected Node toolchain lock schema")
    archive = _expect_keys(
        lock["official_archive"],
        {"name", "url", "sha256", "shasums_url"},
        "official archive",
    )
    target = _expect_keys(
        lock["platform"],
        {"system", "machine", "node_platform", "node_arch"},
        "platform",
    )
    if target != {
        "system": "Linux",
        "machine": "x86_64",
        "node_platform": "linux",
        "node_arch": "x64",
    }:
        raise VerificationError("platform must be the locked Linux x86_64 / Node linux x64 target")
    node = _expect_keys(
        lock["node"],
        {"version", "path", "type", "mode", "bytes", "sha256", "target"},
        "node",
    )
    node_version = _require_string(node["version"], "Node version", NODE_VERSION_PATTERN)
    if node["path"] != NODE_PATH or node["type"] != "regular" or node["target"] != "":
        raise VerificationError("Node binary layout is not canonical")
    _require_string(node["mode"], "Node mode", MODE_PATTERN)
    _require_nonnegative_integer(node["bytes"], "Node bytes")
    _require_string(node["sha256"], "Node SHA-256", SHA256_PATTERN)
    expected_name = f"node-{node_version}-linux-x64.tar.xz"
    expected_base = f"https://nodejs.org/dist/{node_version}"
    if archive["name"] != expected_name:
        raise VerificationError("official archive name does not match the locked Node target")
    if archive["url"] != f"{expected_base}/{expected_name}":
        raise VerificationError("official archive URL is not canonical")
    if archive["shasums_url"] != f"{expected_base}/SHASUMS256.txt":
        raise VerificationError("official SHASUMS URL is not canonical")
    _require_string(archive["sha256"], "official archive SHA-256", SHA256_PATTERN)
    root = _expect_keys(lock["root"], {"path", "closure"}, "Node root")
    if root["path"] != ".":
        raise VerificationError("Node root path is not canonical")
    root_closure = _expect_keys(
        root["closure"],
        {"algorithm", "entries", "directories", "regular_files", "symlinks", "bytes", "sha256"},
        "Node root closure",
    )
    if root_closure["algorithm"] != ROOT_CLOSURE_ALGORITHM:
        raise VerificationError("unexpected Node root closure algorithm")
    for field in ("entries", "directories", "regular_files", "symlinks", "bytes"):
        _require_nonnegative_integer(root_closure[field], f"Node root closure {field}")
    if (
        root_closure["entries"]
        != root_closure["directories"]
        + root_closure["regular_files"]
        + root_closure["symlinks"]
    ):
        raise VerificationError("Node root closure counts are inconsistent")
    _require_string(root_closure["sha256"], "Node root closure SHA-256", SHA256_PATTERN)
    npm = _expect_keys(lock["npm"], {"version", "launcher", "package"}, "npm")
    _require_string(npm["version"], "npm version", NPM_VERSION_PATTERN)
    launcher = _expect_keys(
        npm["launcher"],
        {"path", "type", "mode", "bytes", "sha256", "target", "resolved_path"},
        "npm launcher",
    )
    if (
        launcher["path"] != NPM_LAUNCHER_PATH
        or launcher["type"] != "symlink"
        or launcher["target"] != NPM_LAUNCHER_TARGET
        or launcher["resolved_path"] != NPM_LAUNCHER_RESOLVED_PATH
    ):
        raise VerificationError("npm launcher layout is not canonical")
    _require_string(launcher["mode"], "npm launcher mode", MODE_PATTERN)
    _require_nonnegative_integer(launcher["bytes"], "npm launcher bytes")
    _require_string(launcher["sha256"], "npm launcher SHA-256", SHA256_PATTERN)
    package = _expect_keys(npm["package"], {"path", "closure"}, "npm package")
    if package["path"] != NPM_PACKAGE_PATH:
        raise VerificationError("npm package path is not canonical")
    closure = _expect_keys(
        package["closure"],
        {"algorithm", "entries", "directories", "regular_files", "symlinks", "bytes", "sha256"},
        "npm closure",
    )
    if closure["algorithm"] != CLOSURE_ALGORITHM:
        raise VerificationError("unexpected npm closure algorithm")
    for field in ("entries", "directories", "regular_files", "symlinks", "bytes"):
        _require_nonnegative_integer(closure[field], f"npm closure {field}")
    if closure["entries"] != closure["directories"] + closure["regular_files"] + closure["symlinks"]:
        raise VerificationError("npm closure counts are inconsistent")
    _require_string(closure["sha256"], "npm closure SHA-256", SHA256_PATTERN)


def _run(node: Path, arguments: list[str], root: Path, label: str) -> str:
    environment = {
        "HOME": "/nonexistent",
        "LANG": "C",
        "LC_ALL": "C",
        "NODE_OPTIONS": "",
        "NPM_CONFIG_USERCONFIG": "/dev/null",
        "PATH": f"{root / 'bin'}:/usr/bin:/bin",
    }
    try:
        completed = subprocess.run(
            [str(node), *arguments],
            cwd=root,
            env=environment,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="strict",
            timeout=15,
        )
    except (OSError, subprocess.SubprocessError, UnicodeError) as exc:
        raise VerificationError(f"{label} could not be executed") from exc
    if completed.returncode != 0 or completed.stderr or len(completed.stdout.encode("utf-8")) > 4096:
        raise VerificationError(f"{label} returned non-canonical output")
    output = completed.stdout.rstrip("\n")
    if not output or "\n" in output or "\r" in output:
        raise VerificationError(f"{label} returned non-canonical output")
    return output


def verify_node_toolchain(node_root: Path, lock_path: Path = DEFAULT_LOCK) -> dict[str, Any]:
    lock, lock_bytes = _load_lock(lock_path)
    _validate_lock(lock)
    root = _require_real_directory(node_root, "Node root")
    if platform.system() != lock["platform"]["system"] or platform.machine() != lock["platform"]["machine"]:
        raise VerificationError("host platform does not match the Node toolchain lock")

    actual_root_closure = _directory_closure(root, algorithm=ROOT_CLOSURE_ALGORITHM)
    if actual_root_closure != lock["root"]["closure"]:
        raise VerificationError("Node root closure does not match the lock")

    _require_real_directory(root / "bin", "Node bin directory")
    node_path = root / NODE_PATH
    actual_node = _regular_file_record(node_path, NODE_PATH)
    expected_node = {key: lock["node"][key] for key in ("path", "type", "mode", "bytes", "sha256", "target")}
    if actual_node != expected_node:
        raise VerificationError("Node binary does not match the lock")

    actual_launcher, resolved_launcher = _symlink_record(root / NPM_LAUNCHER_PATH, NPM_LAUNCHER_PATH, root)
    expected_launcher = {
        key: lock["npm"]["launcher"][key]
        for key in ("path", "type", "mode", "bytes", "sha256", "target")
    }
    if actual_launcher != expected_launcher:
        raise VerificationError("npm launcher does not match the lock")
    if resolved_launcher.relative_to(root).as_posix() != lock["npm"]["launcher"]["resolved_path"]:
        raise VerificationError("npm launcher resolves to an unexpected path")
    try:
        resolved_metadata = resolved_launcher.lstat()
    except OSError as exc:
        raise VerificationError("npm launcher target is unavailable") from exc
    if not stat.S_ISREG(resolved_metadata.st_mode) or stat.S_ISLNK(resolved_metadata.st_mode):
        raise VerificationError("npm launcher target must be a regular file")

    npm_root = _require_real_directory(root / NPM_PACKAGE_PATH, "npm package root")
    actual_closure = _directory_closure(npm_root)
    if actual_closure != lock["npm"]["package"]["closure"]:
        raise VerificationError("npm package closure does not match the lock")
    package_payload = _read_regular_bytes(npm_root / "package.json", "npm package.json", maximum=1024 * 1024)
    try:
        package = json.loads(
            package_payload.decode("utf-8"),
            object_pairs_hook=_strict_object,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise VerificationError("npm package.json is not strict UTF-8 JSON") from exc
    if (
        not isinstance(package, dict)
        or package.get("name") != "npm"
        or package.get("version") != lock["npm"]["version"]
    ):
        raise VerificationError("npm package metadata does not match the lock")

    if _run(node_path, ["--version"], root, "Node version") != lock["node"]["version"]:
        raise VerificationError("executed Node version does not match the lock")
    runtime_platform = _run(
        node_path,
        ["-p", "JSON.stringify([process.platform,process.arch])"],
        root,
        "Node platform",
    )
    expected_runtime_platform = json.dumps(
        [lock["platform"]["node_platform"], lock["platform"]["node_arch"]],
        separators=(",", ":"),
    )
    if runtime_platform != expected_runtime_platform:
        raise VerificationError("executed Node platform/architecture does not match the lock")
    if _run(node_path, [str(resolved_launcher), "--version"], root, "npm version") != lock["npm"]["version"]:
        raise VerificationError("executed npm version does not match the lock")

    if _regular_file_record(node_path, NODE_PATH) != actual_node:
        raise VerificationError("Node binary changed during attestation")
    final_launcher, final_resolved = _symlink_record(root / NPM_LAUNCHER_PATH, NPM_LAUNCHER_PATH, root)
    if final_launcher != actual_launcher or final_resolved != resolved_launcher:
        raise VerificationError("npm launcher changed during attestation")
    if _directory_closure(npm_root) != actual_closure:
        raise VerificationError("npm package closure changed during attestation")
    if _directory_closure(root, algorithm=ROOT_CLOSURE_ALGORITHM) != actual_root_closure:
        raise VerificationError("Node root closure changed during attestation")

    launcher_attestation = dict(actual_launcher)
    launcher_attestation["resolved_path"] = resolved_launcher.relative_to(root).as_posix()
    return {
        "schema_version": ATTESTATION_SCHEMA_VERSION,
        "lock_sha256": hashlib.sha256(lock_bytes).hexdigest(),
        "official_archive": lock["official_archive"],
        "platform": lock["platform"],
        "root": {"path": ".", "closure": actual_root_closure},
        "node": {"version": lock["node"]["version"], **actual_node},
        "npm": {
            "version": lock["npm"]["version"],
            "launcher": launcher_attestation,
            "package": {"path": NPM_PACKAGE_PATH, "closure": actual_closure},
        },
    }


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node-root", required=True, type=Path)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    arguments = parser.parse_args(argv)
    try:
        attestation = verify_node_toolchain(arguments.node_root, arguments.lock)
    except (OSError, VerificationError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(canonical_json(attestation))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
