#!/usr/bin/env python3
"""Create a source-bound SHA-256 manifest for a completed Next.js build."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from pathlib import PurePosixPath
import re
import stat
import tarfile
from typing import BinaryIO


FORBIDDEN_RELEASE_PATH_PREFIXES = (("dev",), (".next", "dev"))
MAXIMUM_TOOLCHAIN_JSON_BYTES = 64 * 1024


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _stat_identity(metadata: os.stat_result) -> tuple[int, int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _stable_regular_bytes(path: Path, label: str) -> bytes:
    candidate = path.expanduser().absolute()
    try:
        before = candidate.lstat()
        resolved = candidate.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        raise ValueError(f"{label} is unavailable") from exc
    if (
        not stat.S_ISREG(before.st_mode)
        or stat.S_ISLNK(before.st_mode)
        or resolved != candidate
        or before.st_size > MAXIMUM_TOOLCHAIN_JSON_BYTES
    ):
        raise ValueError(f"{label} must be a bounded regular non-symlink file")
    nofollow = getattr(os, "O_NOFOLLOW", None)
    if nofollow is None:
        raise ValueError("O_NOFOLLOW support is required")
    flags = os.O_RDONLY | nofollow | getattr(os, "O_CLOEXEC", 0)
    try:
        descriptor = os.open(candidate, flags)
        try:
            opened = os.fstat(descriptor)
            if _stat_identity(opened) != _stat_identity(before):
                raise ValueError(f"{label} changed before it was opened")
            chunks: list[bytes] = []
            total = 0
            while True:
                chunk = os.read(descriptor, 64 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAXIMUM_TOOLCHAIN_JSON_BYTES:
                    raise ValueError(f"{label} exceeds the size limit")
                chunks.append(chunk)
            after = os.fstat(descriptor)
        finally:
            os.close(descriptor)
        current = candidate.lstat()
    except OSError as exc:
        raise ValueError(f"{label} cannot be read safely") from exc
    if (
        _stat_identity(after) != _stat_identity(before)
        or _stat_identity(current) != _stat_identity(before)
        or total != before.st_size
    ):
        raise ValueError(f"{label} changed while being read")
    return b"".join(chunks)


def _strict_json_file(path: Path, label: str) -> dict[str, object]:

    def pairs(values: list[tuple[str, object]]) -> dict[str, object]:
        payload: dict[str, object] = {}
        for key, value in values:
            if key in payload:
                raise ValueError(f"{label} contains a duplicate JSON key")
            payload[key] = value
        return payload

    def constant(value: str) -> object:
        raise ValueError(f"{label} contains a non-finite JSON value: {value}")

    try:
        payload = json.loads(
            _stable_regular_bytes(path, label).decode("utf-8"),
            object_pairs_hook=pairs,
            parse_constant=constant,
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"{label} is not strict JSON") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"{label} must contain a JSON object")
    return payload


def _validate_node_toolchain_attestation(
    *,
    lock_path: Path,
    attestation_path: Path,
    node_version: str,
    npm_version: str,
) -> None:
    lock = _strict_json_file(lock_path, "Node toolchain lock")
    if set(lock) != {"schema_version", "official_archive", "platform", "root", "node", "npm"}:
        raise ValueError("Node toolchain lock has unexpected fields")
    if lock.get("schema_version") != "walksafe.node-toolchain.v2":
        raise ValueError("Node toolchain lock schema is invalid")
    node = lock.get("node")
    npm = lock.get("npm")
    if not isinstance(node, dict) or not isinstance(npm, dict):
        raise ValueError("Node toolchain lock runtime records are invalid")
    if node.get("version") != node_version.strip() or npm.get("version") != npm_version.strip():
        raise ValueError("executed Node/npm versions differ from the Node toolchain lock")
    expected = {
        "schema_version": "walksafe.node-toolchain-attestation.v2",
        "lock_sha256": sha256_file(lock_path),
        "official_archive": lock["official_archive"],
        "platform": lock["platform"],
        "root": lock["root"],
        "node": node,
        "npm": npm,
    }
    if _strict_json_file(attestation_path, "Node toolchain attestation") != expected:
        raise ValueError("Node toolchain attestation differs from the locked toolchain")


def _archive_relative_path(raw_name: str) -> str | None:
    if not raw_name or "\\" in raw_name:
        raise ValueError(f"deployment archive contains an unsafe path: {raw_name!r}")
    path = PurePosixPath(raw_name)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError(f"deployment archive contains an unsafe path: {raw_name!r}")
    parts = tuple(part for part in path.parts if part not in {"", "."})
    return PurePosixPath(*parts).as_posix() if parts else None


def _streams_are_identical(left: BinaryIO, right: BinaryIO) -> bool:
    while True:
        left_chunk = left.read(1024 * 1024)
        right_chunk = right.read(1024 * 1024)
        if left_chunk != right_chunk:
            return False
        if not left_chunk:
            return True


def verify_deployment_archive(deployment_archive: Path, build_root: Path) -> None:
    """Compare a tar.gz to build_root without extracting untrusted members."""
    archive = deployment_archive.expanduser()
    root = build_root.expanduser().resolve()
    if archive.is_symlink() or not archive.is_file() or archive.stat().st_size <= 0:
        raise ValueError("deployment archive is missing or empty")
    expected_files = {
        item.relative_to(root).as_posix(): item
        for item in root.rglob("*")
        if item.is_file() and not item.is_symlink()
    }
    archived_files: set[str] = set()
    seen_paths: set[str] = set()
    try:
        with tarfile.open(archive, mode="r:gz") as tar:
            entry_limit = max(100, len(expected_files) * 4 + 32)
            for entry_count, member in enumerate(tar, start=1):
                if entry_count > entry_limit:
                    raise ValueError("deployment archive contains an unreasonable number of entries")
                relative = _archive_relative_path(member.name)
                if relative is None:
                    if not member.isdir():
                        raise ValueError("deployment archive root entry must be a directory")
                    continue
                if relative in seen_paths:
                    raise ValueError(f"deployment archive contains a duplicate path: {relative}")
                seen_paths.add(relative)
                if member.isdir():
                    if relative in expected_files:
                        raise ValueError(f"deployment archive directory collides with a file: {relative}")
                    continue
                if not member.isreg():
                    raise ValueError(f"deployment archive contains a non-regular entry: {relative}")
                source = expected_files.get(relative)
                if source is None:
                    raise ValueError(f"deployment archive contains an unexpected file: {relative}")
                if member.size != source.stat().st_size:
                    raise ValueError(f"deployment archive file size differs from build_root: {relative}")
                if member.mode != stat.S_IMODE(source.stat().st_mode):
                    raise ValueError(f"deployment archive file mode differs from build_root: {relative}")
                archived = tar.extractfile(member)
                if archived is None:
                    raise ValueError(f"deployment archive file cannot be read: {relative}")
                with archived, source.open("rb") as current:
                    if not _streams_are_identical(archived, current):
                        raise ValueError(f"deployment archive file bytes differ from build_root: {relative}")
                archived_files.add(relative)
    except (tarfile.TarError, OSError) as exc:
        raise ValueError("deployment archive is not a readable gzip tar archive") from exc
    if archived_files != set(expected_files):
        missing = sorted(set(expected_files) - archived_files)
        raise ValueError(f"deployment archive file set differs from build_root; missing={missing[:3]}")


def _artifact_record(path: Path, artifact_root: Path, *, name: str | None = None) -> dict[str, object]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size <= 0:
        raise ValueError(f"release artifact is missing or empty: {path}")
    root = artifact_root.expanduser().resolve()
    resolved = path.expanduser().resolve()
    if root not in resolved.parents:
        raise ValueError(f"release artifact must be stored under artifact_root: {path}")
    record: dict[str, object] = {
        "path": resolved.relative_to(root).as_posix(),
        "sha256": sha256_file(resolved),
        "bytes": resolved.stat().st_size,
    }
    if name is not None:
        record["name"] = name
    return record


def create_manifest(
    build_root: Path,
    source_commit: str,
    *,
    package_json: Path,
    package_lock: Path,
    node_toolchain_lock: Path,
    node_version: str,
    npm_version: str,
    build_environment: dict[str, str],
    quality_receipts: dict[str, Path],
    deployment_archive: Path,
    artifact_root: Path,
) -> dict[str, object]:
    if re.fullmatch(r"[0-9a-fA-F]{40}", source_commit) is None:
        raise ValueError("source_commit must be a full Git commit SHA")
    root = build_root.expanduser()
    if root.is_symlink() or not root.is_dir():
        raise ValueError("build_root must be a real directory")
    root = root.resolve()
    build_id_path = root / "BUILD_ID"
    if build_id_path.is_symlink() or not build_id_path.is_file():
        raise ValueError("Next build BUILD_ID is missing")
    build_id = build_id_path.read_text(encoding="utf-8").strip().lower()
    if build_id != source_commit.lower():
        raise ValueError("Next BUILD_ID must equal source_commit; rebuild with WALKSAFE_SOURCE_COMMIT")
    files: list[dict[str, str]] = []
    for item in sorted(root.rglob("*")):
        relative = item.relative_to(root)
        if any(
            relative.parts[: len(prefix)] == prefix
            for prefix in FORBIDDEN_RELEASE_PATH_PREFIXES
        ):
            raise ValueError(f"build contains development-only output: {relative.as_posix()}")
        if item.is_symlink():
            raise ValueError(f"build contains a symlink: {item}")
        if item.is_file():
            files.append(
                {
                    "path": relative.as_posix(),
                    "sha256": sha256_file(item),
                }
            )
        elif not item.is_dir():
            raise ValueError(f"build contains an unsupported entry: {item}")
    for dependency_file in (package_json, package_lock, node_toolchain_lock):
        if dependency_file.is_symlink() or not dependency_file.is_file():
            raise ValueError(f"dependency input is missing: {dependency_file}")
    if not node_version.strip() or not npm_version.strip():
        raise ValueError("node and npm versions must not be empty")
    expected_environment = {
        "NEXT_PUBLIC_DETECTOR_MODE": "server-v2",
        "NEXT_PUBLIC_WALKSAFE_PWA_ENABLED": "true",
        "NEXT_PUBLIC_WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING": "false",
        "NEXT_PUBLIC_WALKSAFE_TEST_CAPTURE_PANEL": "false",
    }
    if build_environment != expected_environment:
        raise ValueError("release public build environment is incomplete or unexpected")
    expected_receipts = {
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
    if set(quality_receipts) != expected_receipts:
        raise ValueError("release quality receipt set is incomplete or unexpected")
    receipt_records = []
    for name, receipt in sorted(quality_receipts.items()):
        receipt_records.append(_artifact_record(receipt, artifact_root, name=name))
    _validate_node_toolchain_attestation(
        lock_path=node_toolchain_lock,
        attestation_path=quality_receipts["node-toolchain"],
        node_version=node_version,
        npm_version=npm_version,
    )
    _validate_node_toolchain_attestation(
        lock_path=node_toolchain_lock,
        attestation_path=quality_receipts["node-toolchain-post"],
        node_version=node_version,
        npm_version=npm_version,
    )
    verify_deployment_archive(deployment_archive, root)
    return {
        "schema_version": "walksafe.web-build-manifest.v4",
        "source_commit": source_commit.lower(),
        "build_id": build_id,
        "inputs": {
            "package_json": _artifact_record(package_json, artifact_root),
            "package_lock": _artifact_record(package_lock, artifact_root),
            "node_toolchain_lock": _artifact_record(node_toolchain_lock, artifact_root),
        },
        "toolchain": {
            "node": node_version.strip(),
            "npm": npm_version.strip(),
        },
        "build_environment": build_environment,
        "quality_receipts": receipt_records,
        "deployment_archive": {"name": deployment_archive.name, **_artifact_record(deployment_archive, artifact_root)},
        "files": files,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-root", required=True, type=Path)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--package-json", required=True, type=Path)
    parser.add_argument("--package-lock", required=True, type=Path)
    parser.add_argument("--node-toolchain-lock", required=True, type=Path)
    parser.add_argument("--node-version", required=True)
    parser.add_argument("--npm-version", required=True)
    parser.add_argument("--public-build-env", action="append", default=[])
    parser.add_argument("--quality-receipt", action="append", default=[])
    parser.add_argument("--deployment-archive", required=True, type=Path)
    parser.add_argument(
        "--artifact-root",
        type=Path,
        help="Directory containing the preserved inputs, receipts and archive; defaults to output parent.",
    )
    args = parser.parse_args()
    try:
        build_environment = dict(item.split("=", 1) for item in args.public_build_env)
        quality_receipts = {name: Path(path) for name, path in (item.split("=", 1) for item in args.quality_receipt)}
    except ValueError as exc:
        raise SystemExit("build environment and receipt arguments must use name=value") from exc
    payload = create_manifest(
        args.build_root,
        args.source_commit,
        package_json=args.package_json,
        package_lock=args.package_lock,
        node_toolchain_lock=args.node_toolchain_lock,
        node_version=args.node_version,
        npm_version=args.npm_version,
        build_environment=build_environment,
        quality_receipts=quality_receipts,
        deployment_archive=args.deployment_archive,
        artifact_root=args.artifact_root or args.output.parent,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(f"{json.dumps(payload, ensure_ascii=False, indent=2)}\n", encoding="utf-8")
    print(f"manifest={args.output.resolve()}")
    print(f"manifest_sha256={sha256_file(args.output)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
