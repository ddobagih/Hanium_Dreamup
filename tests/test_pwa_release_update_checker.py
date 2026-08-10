from __future__ import annotations

import importlib.util
import errno
import hashlib
import json
import io
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tarfile
import time
import zlib

import pytest

from scripts import create_walksafe_web_build_manifest_20260711 as manifest_creator


ROOT = Path(__file__).resolve().parents[1]
TEST_NODE_BINARY = b"#!/bin/sh\nprintf 'v22.23.1\\n'\n"


def load_checker():
    scripts = ROOT / "scripts"
    sys.path.insert(0, str(scripts))
    path = scripts / "check_pwa_release_update_20260717.py"
    spec = importlib.util.spec_from_file_location("pwa_release_update_checker_under_test", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


checker = load_checker()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fake_release_artifact(
    root: Path,
    label: str,
    commit: str,
) -> checker.ReleaseArtifact:
    return checker.ReleaseArtifact(
        manifest_path=root / f"{label}.json",
        build_root=root / f"{label}-standalone",
        deployment_archive=root / f"{label}.tar.gz",
        source_commit=commit,
        manifest_sha256="1" * 64,
        manifest_bytes=1,
        deployment_archive_sha256="2" * 64,
        deployment_archive_bytes=2,
        build_root_sha256="3" * 64,
        build_root_bytes=3,
        build_root_file_count=1,
        root_shell_sha256="5" * 64,
        node_version="v22.23.1",
        node_sha256="4" * 64,
        node_bytes=4,
    )


def write_canonical_archive(
    archive: Path,
    build_root: Path,
    *,
    reverse_members: bool = False,
    wrong_server_mode: bool = False,
) -> None:
    tar_payload = io.BytesIO()

    def canonical(info: tarfile.TarInfo) -> tarfile.TarInfo:
        info.uid = 0
        info.gid = 0
        info.uname = ""
        info.gname = ""
        info.mtime = 0
        info.mode = 0o755 if info.isdir() else 0o644
        if wrong_server_mode and info.name.endswith("/server.js"):
            info.mode = 0o600
        return info

    with tarfile.open(fileobj=tar_payload, mode="w", format=tarfile.GNU_FORMAT) as tar:
        if not reverse_members:
            tar.add(build_root, arcname=".", recursive=True, filter=canonical)
        else:
            tar.add(build_root, arcname=".", recursive=False, filter=canonical)
            for item in reversed(sorted(build_root.rglob("*"), key=lambda path: path.as_posix())):
                tar.add(
                    item,
                    arcname=f"./{item.relative_to(build_root).as_posix()}",
                    recursive=False,
                    filter=canonical,
                )
    compressed = subprocess.run(
        ["/usr/bin/gzip", "-n", "-6"],
        check=True,
        input=tar_payload.getvalue(),
        stdout=subprocess.PIPE,
    ).stdout
    archive.write_bytes(compressed)


def write_release_artifact(
    root: Path,
    source_commit: str,
    *,
    source_root: Path | None = None,
) -> tuple[Path, Path, Path, Path]:
    source_root = source_root or root / "source"
    (source_root / "apps/web").mkdir(parents=True, exist_ok=True)
    (source_root / "configs").mkdir(parents=True, exist_ok=True)
    package_json = source_root / "apps/web/package.json"
    package_lock = source_root / "apps/web/package-lock.json"
    node_lock = source_root / "configs/walksafe_node_toolchain_lock_20260715.json"
    package_json.write_text('{"name":"walksafe-test"}\n', encoding="utf-8")
    package_lock.write_text('{"lockfileVersion":3}\n', encoding="utf-8")
    node_binary = TEST_NODE_BINARY
    node_lock_payload = {
        "schema_version": "walksafe.node-toolchain.v2",
        "official_archive": {
            "name": "node.tar.xz",
            "url": "https://example.invalid/node.tar.xz",
            "sha256": "1" * 64,
            "shasums_url": "https://example.invalid/SHASUMS256.txt",
        },
        "platform": {
            "system": "Linux",
            "machine": "x86_64",
            "node_platform": "linux",
            "node_arch": "x64",
        },
        "root": {
            "path": ".",
            "closure": {
                "algorithm": "walksafe-node-root-closure-jsonl-sha256.v1",
                "entries": 1,
                "directories": 0,
                "regular_files": 1,
                "symlinks": 0,
                "bytes": len(node_binary),
                "sha256": hashlib.sha256(node_binary).hexdigest(),
            },
        },
        "node": {
            "version": "v22.23.1",
            "path": "bin/node",
            "type": "regular",
            "mode": "0755",
            "bytes": len(node_binary),
            "sha256": hashlib.sha256(node_binary).hexdigest(),
            "target": "",
        },
        "npm": {
            "version": "10.9.8",
            "launcher": {
                "path": "bin/npm",
                "type": "symlink",
                "mode": "0777",
                "bytes": 7,
                "sha256": "2" * 64,
                "target": "npm-cli",
                "resolved_path": "npm-cli",
            },
            "package": {
                "path": "lib/node_modules/npm",
                "closure": {
                    "algorithm": "walksafe-node-npm-closure-jsonl-sha256.v1",
                    "entries": 1,
                    "directories": 0,
                    "regular_files": 1,
                    "symlinks": 0,
                    "bytes": 1,
                    "sha256": "3" * 64,
                },
            },
        },
    }
    node_lock.write_text(json.dumps(node_lock_payload, separators=(",", ":")) + "\n", encoding="utf-8")

    build_root = root / f"web-standalone-{source_commit}"
    (build_root / ".next/static" / source_commit).mkdir(parents=True)
    (build_root / ".next/server/app").mkdir(parents=True)
    (build_root / "BUILD_ID").write_text(source_commit, encoding="utf-8")
    (build_root / ".next/BUILD_ID").write_text(source_commit, encoding="utf-8")
    (build_root / ".next/static" / source_commit / "_buildManifest.js").write_text(
        f"self.__BUILD_ID={json.dumps(source_commit)};\n",
        encoding="utf-8",
    )
    (build_root / ".next/server/app/index.html").write_text(
        '<script src="/_next/static/chunks/app.js"></script>\n',
        encoding="utf-8",
    )
    (build_root / "server.js").write_text("// verified standalone server\n", encoding="utf-8")
    for directory in [build_root, *(item for item in build_root.rglob("*") if item.is_dir())]:
        directory.chmod(0o755)
    for file_path in (item for item in build_root.rglob("*") if item.is_file()):
        file_path.chmod(0o644)

    archive = root / f"web-standalone-{source_commit}.tar.gz"
    write_canonical_archive(archive, build_root)

    provenance = root / f"web-provenance-{source_commit}"
    quality = root / f"web-quality-{source_commit}"
    provenance.mkdir()
    quality.mkdir()
    preserved_package_json = provenance / "package.json"
    preserved_package_lock = provenance / "package-lock.json"
    preserved_node_lock = provenance / "walksafe_node_toolchain_lock_20260715.json"
    for source, destination in (
        (package_json, preserved_package_json),
        (package_lock, preserved_package_lock),
        (node_lock, preserved_node_lock),
    ):
        shutil.copy2(source, destination)
    attestation = {
        "schema_version": "walksafe.node-toolchain-attestation.v2",
        "lock_sha256": sha256(preserved_node_lock),
        "official_archive": node_lock_payload["official_archive"],
        "platform": node_lock_payload["platform"],
        "root": node_lock_payload["root"],
        "node": node_lock_payload["node"],
        "npm": node_lock_payload["npm"],
    }
    quality_receipts: dict[str, Path] = {}
    browser_receipt = {
        "schema_version": "walksafe.pwa_browser_evidence.v2",
        "service_worker_version": f"source-{source_commit}",
        "cache_names": [f"walksafe-assist-source-{source_commit}"],
        "manifest_installable": True,
        "service_worker_active": True,
        "service_worker_controls_page": True,
        "offline_shell_available": True,
        "offline_reload_hydrated": True,
        "offline_authentication_gate_only": True,
        "offline_safety_api_unavailable": True,
    }
    semantic_receipts = {
        "npm-ci": "added 1 package, and audited 1 package\nfound 0 vulnerabilities\n",
        "npm-audit": "found 0 vulnerabilities\n",
        "npm-lint": "eslint app lib types --max-warnings=0\n",
        "npm-typecheck": "tsc --noEmit\n",
        "npm-test": (
            "bash ../../scripts/check_frontend_policy_suite.sh\n"
            "PWA install/update/offline shell policy checks passed\n"
            "walksafe test log policy checks passed\n"
        ),
        "npm-build": "next build\n/api/release-identity\n/sw-version.js\n",
        "runtime-trace": "Web runtime trace scope PASS: 1 traces, 1 unique files\n",
        "browser-lifecycle": (
            "PASS: PWA browser lifecycle, server-v2 privacy controls, and offline safety boundary\n"
            f"{json.dumps(browser_receipt)}\nlogs=/tmp/test\n"
        ),
    }
    for name in checker.WEB_QUALITY_RECEIPT_NAMES:
        receipt = quality / f"{name}.log"
        if name in {"node-toolchain", "node-toolchain-post"}:
            receipt.write_text(json.dumps(attestation, separators=(",", ":")) + "\n", encoding="utf-8")
        else:
            receipt.write_text(semantic_receipts[name], encoding="utf-8")
        quality_receipts[name] = receipt

    payload = manifest_creator.create_manifest(
        build_root,
        source_commit,
        package_json=preserved_package_json,
        package_lock=preserved_package_lock,
        node_toolchain_lock=preserved_node_lock,
        node_version="v22.23.1",
        npm_version="10.9.8",
        build_environment=checker.EXPECTED_WEB_BUILD_ENVIRONMENT,
        quality_receipts=quality_receipts,
        deployment_archive=archive,
        artifact_root=root,
    )
    manifest = root / "web-build-manifest.json"
    manifest.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return manifest, build_root, archive, source_root


def git(repo: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo), *arguments],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env={
            "PATH": "/usr/bin:/bin",
            "HOME": "/nonexistent",
            "LANG": "C",
            "LC_ALL": "C",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_SYSTEM": "/dev/null",
            "GIT_CONFIG_GLOBAL": "/dev/null",
        },
    )
    return result.stdout.strip()


def release_history(root: Path) -> tuple[Path, Path, str, str]:
    repo = root / "candidate-source"
    repo.mkdir()
    git(repo, "init", "--quiet")
    git(repo, "config", "user.name", "WalkSafe Test")
    git(repo, "config", "user.email", "walksafe@example.invalid")
    tracked = repo / "tracked.txt"
    tracked.write_text("baseline\n", encoding="utf-8")
    git(repo, "add", "tracked.txt")
    git(repo, "commit", "--quiet", "-m", "baseline")
    baseline = git(repo, "rev-parse", "HEAD")
    tracked.write_text("candidate\n", encoding="utf-8")
    git(repo, "add", "tracked.txt")
    git(repo, "commit", "--quiet", "-m", "candidate")
    candidate = git(repo, "rev-parse", "HEAD")
    baseline_repo = root / "baseline-source"
    git(root, "clone", "--quiet", "--no-local", str(repo), str(baseline_repo))
    git(baseline_repo, "checkout", "--quiet", baseline)
    return baseline_repo, repo, baseline, candidate


def test_release_pair_must_use_distinct_full_commits() -> None:
    baseline = "a" * 40
    candidate = "b" * 40
    checker.require_distinct_releases(baseline, candidate)
    with pytest.raises(checker.CheckFailed, match="must differ"):
        checker.require_distinct_releases(baseline, baseline)
    with pytest.raises(checker.CheckFailed, match="full lowercase"):
        checker.require_distinct_releases("not-a-commit", candidate)


def test_worker_url_must_be_canonical_and_queryless() -> None:
    origin = "http://127.0.0.1:3101"
    assert checker.require_canonical_worker_url(f"{origin}/sw.js", origin) == f"{origin}/sw.js"
    for invalid in (
        f"{origin}/sw.js?synthetic=1",
        f"{origin}/other.js",
        "http://127.0.0.1:9999/sw.js",
    ):
        with pytest.raises(checker.CheckFailed, match="canonical"):
            checker.require_canonical_worker_url(invalid, origin)


def test_release_artifact_precheck_binds_v4_manifest_root_and_archive(tmp_path: Path) -> None:
    source_commit = "a" * 40
    manifest, build_root, archive, source_root = write_release_artifact(tmp_path, source_commit)

    artifact = checker.verify_release_artifact(
        manifest,
        build_root,
        archive,
        source_root,
        label="baseline",
    )

    assert artifact.source_commit == source_commit
    assert artifact.build_root == build_root
    assert artifact.deployment_archive_sha256 == sha256(archive)
    assert artifact.build_root_file_count == 5
    assert artifact.root_shell_sha256 == sha256(build_root / ".next/server/app/index.html")
    assert checker.artifact_evidence(artifact)["standalone_root"]["root_shell_sha256"] == (
        artifact.root_shell_sha256
    )


def test_release_artifact_precheck_rejects_root_or_archive_byte_drift(tmp_path: Path) -> None:
    source_commit = "a" * 40
    manifest, build_root, archive, source_root = write_release_artifact(tmp_path, source_commit)
    (build_root / "server.js").write_text("// changed after manifest\n", encoding="utf-8")
    with pytest.raises(checker.CheckFailed, match="deployment archive file size differs"):
        checker.verify_release_artifact(manifest, build_root, archive, source_root, label="baseline")

    manifest, build_root, archive, source_root = write_release_artifact(
        tmp_path / "archive-drift", source_commit
    )
    with archive.open("ab") as output:
        output.write(b"changed")
    with pytest.raises(checker.CheckFailed, match="archive.*(bytes|SHA-256)"):
        checker.verify_release_artifact(manifest, build_root, archive, source_root, label="baseline")


def test_release_artifact_precheck_rejects_normalized_top_level_build_id(tmp_path: Path) -> None:
    source_commit = "a" * 40
    manifest, build_root, archive, source_root = write_release_artifact(tmp_path, source_commit)
    root_build_id = build_root / "BUILD_ID"
    root_build_id.write_text(source_commit.upper(), encoding="utf-8")
    root_build_id.chmod(0o644)
    write_canonical_archive(archive, build_root)
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    next(item for item in payload["files"] if item["path"] == "BUILD_ID")["sha256"] = sha256(
        root_build_id
    )
    payload["deployment_archive"]["sha256"] = sha256(archive)
    payload["deployment_archive"]["bytes"] = archive.stat().st_size
    manifest.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    with pytest.raises(checker.CheckFailed, match="top-level BUILD_ID"):
        checker.verify_release_artifact(manifest, build_root, archive, source_root, label="candidate")


def test_release_artifact_precheck_rejects_archive_from_another_root(tmp_path: Path) -> None:
    source_commit = "a" * 40
    manifest, build_root, archive, source_root = write_release_artifact(tmp_path / "release", source_commit)
    other_root = tmp_path / "other-root"
    shutil.copytree(build_root, other_root)
    (other_root / "server.js").write_text("// different byte content\n", encoding="utf-8")
    (other_root / "server.js").chmod(0o644)
    write_canonical_archive(archive, other_root)
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    payload["deployment_archive"]["sha256"] = sha256(archive)
    payload["deployment_archive"]["bytes"] = archive.stat().st_size
    manifest.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    with pytest.raises(checker.CheckFailed, match="deployment archive file size differs"):
        checker.verify_release_artifact(manifest, build_root, archive, source_root, label="candidate")


@pytest.mark.parametrize(
    ("archive_mutation", "message"),
    (("wrong-mode", "mode differs"), ("reverse-order", "order or set")),
)
def test_release_artifact_precheck_rejects_archive_mode_or_order_mutation(
    tmp_path: Path,
    archive_mutation: str,
    message: str,
) -> None:
    manifest, build_root, archive, source_root = write_release_artifact(tmp_path, "a" * 40)
    write_canonical_archive(
        archive,
        build_root,
        reverse_members=archive_mutation == "reverse-order",
        wrong_server_mode=archive_mutation == "wrong-mode",
    )
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    payload["deployment_archive"]["sha256"] = sha256(archive)
    payload["deployment_archive"]["bytes"] = archive.stat().st_size
    manifest.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    with pytest.raises(checker.CheckFailed, match=message):
        checker.verify_release_artifact(manifest, build_root, archive, source_root, label="baseline")


def test_release_artifact_rejects_equivalent_noncanonical_deflate_stream(tmp_path: Path) -> None:
    manifest, build_root, archive, source_root = write_release_artifact(tmp_path, "a" * 40)
    raw_tar = zlib.decompress(archive.read_bytes(), wbits=31)
    compressor = zlib.compressobj(level=6, wbits=31, strategy=zlib.Z_FILTERED)
    recompressed = compressor.compress(raw_tar) + compressor.flush()
    assert recompressed[:10] == archive.read_bytes()[:10]
    assert recompressed != archive.read_bytes()
    archive.write_bytes(recompressed)
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    payload["deployment_archive"]["sha256"] = sha256(archive)
    payload["deployment_archive"]["bytes"] = archive.stat().st_size
    manifest.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    with pytest.raises(checker.CheckFailed, match="exact canonical tar.gz bytes"):
        checker.verify_release_artifact(manifest, build_root, archive, source_root, label="baseline")


def test_release_artifact_precheck_rejects_forged_receipt_semantics(tmp_path: Path) -> None:
    manifest, build_root, archive, source_root = write_release_artifact(tmp_path, "a" * 40)
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    receipt = next(item for item in payload["quality_receipts"] if item["name"] == "npm-audit")
    receipt_path = tmp_path / receipt["path"]
    receipt_path.write_text("npm-audit PASS\n", encoding="utf-8")
    receipt["sha256"] = sha256(receipt_path)
    receipt["bytes"] = receipt_path.stat().st_size
    manifest.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    with pytest.raises(checker.CheckFailed, match="zero vulnerabilities"):
        checker.verify_release_artifact(manifest, build_root, archive, source_root, label="baseline")


def test_minimal_fake_v4_manifest_is_rejected(tmp_path: Path) -> None:
    manifest = tmp_path / "web-build-manifest.json"
    manifest.write_text(
        json.dumps(
            {
                "schema_version": "walksafe.web-build-manifest.v4",
                "source_commit": "a" * 40,
                "build_id": "a" * 40,
                "files": [],
            }
        ),
        encoding="utf-8",
    )
    with pytest.raises(checker.CheckFailed, match="field set"):
        checker.release_manifest_commit(manifest, "baseline")


def test_node_runtime_bytes_are_bound_to_both_manifest_attestations(tmp_path: Path) -> None:
    baseline_paths = write_release_artifact(tmp_path / "baseline", "a" * 40)
    candidate_paths = write_release_artifact(tmp_path / "candidate", "b" * 40)
    baseline = checker.verify_release_artifact(*baseline_paths, label="baseline")
    candidate = checker.verify_release_artifact(*candidate_paths, label="candidate")
    node = tmp_path / "node"
    node.write_bytes(TEST_NODE_BINARY)
    node.chmod(0o755)
    runtime = checker.verify_node_runtime(node, (baseline, candidate))
    assert runtime.path == node
    assert runtime.executable == f"/proc/self/fd/{runtime.descriptor}"
    runtime.close()

    node.write_bytes(TEST_NODE_BINARY + b"# drift\n")
    node.chmod(0o755)
    with pytest.raises(checker.CheckFailed, match="bytes differ"):
        checker.verify_node_runtime(node, (baseline, candidate))


def test_node_execution_uses_pinned_fd_and_rejects_path_swap(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    baseline_paths = write_release_artifact(tmp_path / "baseline", "a" * 40)
    candidate_paths = write_release_artifact(tmp_path / "candidate", "b" * 40)
    baseline = checker.verify_release_artifact(*baseline_paths, label="baseline")
    candidate = checker.verify_release_artifact(*candidate_paths, label="candidate")
    node = tmp_path / "node"
    node.write_bytes(TEST_NODE_BINARY)
    node.chmod(0o755)
    original = tmp_path / "node.original"
    malicious = b"#!/bin/sh\nprintf 'malicious\\n'\n"

    def swap_during_execution(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
        descriptor = kwargs["pass_fds"][0]
        assert command[0] == f"/proc/self/fd/{descriptor}"
        assert kwargs["env"] == checker.clean_child_environment()
        node.rename(original)
        node.write_bytes(malicious)
        node.chmod(0o755)
        assert Path(command[0]).read_bytes() == TEST_NODE_BINARY
        node.unlink()
        original.rename(node)
        return subprocess.CompletedProcess(command, 0, stdout="v22.23.1\n", stderr="")

    monkeypatch.setattr(checker.subprocess, "run", swap_during_execution)

    with pytest.raises(checker.CheckFailed, match="identity or bytes changed"):
        checker.verify_node_runtime(node, (baseline, candidate))


def test_release_child_environment_drops_ambient_loader_injection(tmp_path: Path) -> None:
    environment = checker.release_environment(
        {
            "NODE_OPTIONS": "--require=/tmp/hook.js",
            "NODE_PATH": "/tmp/node-modules",
            "LD_PRELOAD": "/tmp/inject.so",
            "LD_LIBRARY_PATH": "/tmp/lib",
            "PYTHONPATH": "/tmp/python",
            "WALKSAFE_ENVIRONMENT": "test",
            "NODE_ENV": "production",
        },
        build_id="a" * 40,
        process_lock_path=tmp_path / "web.lock",
    )

    assert not ({"NODE_OPTIONS", "NODE_PATH", "LD_PRELOAD", "LD_LIBRARY_PATH", "PYTHONPATH"} & environment.keys())
    assert environment["WALKSAFE_ENVIRONMENT"] == "test"
    assert environment["NODE_ENV"] == "production"


def test_candidate_source_is_clean_exact_head_and_baseline_is_strict_ancestor(tmp_path: Path) -> None:
    baseline_repo, candidate_repo, baseline, candidate = release_history(tmp_path)
    source = checker.verify_release_history(baseline_repo, candidate_repo, baseline, candidate)
    assert source["head"] == candidate
    assert source["baseline"] == baseline

    with pytest.raises(checker.CheckFailed, match="strict ancestor"):
        checker.verify_release_history(baseline_repo, candidate_repo, candidate, candidate)
    (candidate_repo / "tracked.txt").write_text("dirty\n", encoding="utf-8")
    with pytest.raises(checker.CheckFailed, match="clean HEAD|dirty|differs"):
        checker.verify_release_history(baseline_repo, candidate_repo, baseline, candidate)


def test_release_source_rejects_local_clone_hardlink_boundary(tmp_path: Path) -> None:
    baseline_repo, candidate_repo, baseline, candidate = release_history(tmp_path)
    object_file = next(path for path in (baseline_repo / ".git/objects").rglob("*") if path.is_file())
    os.link(object_file, tmp_path / "shared-git-object")

    with pytest.raises(checker.CheckFailed, match="clone --no-local"):
        checker.verify_release_history(baseline_repo, candidate_repo, baseline, candidate)


def test_release_source_rejects_symlinked_object_store_root(tmp_path: Path) -> None:
    baseline_repo, candidate_repo, baseline, candidate = release_history(tmp_path)
    baseline_objects = baseline_repo / ".git/objects"
    shutil.rmtree(baseline_objects)
    baseline_objects.symlink_to(candidate_repo / ".git/objects", target_is_directory=True)

    with pytest.raises(checker.CheckFailed, match="object root|symlink ancestry"):
        checker.verify_release_history(baseline_repo, candidate_repo, baseline, candidate)


def test_release_source_rejects_shared_git_or_object_directory_inode(tmp_path: Path) -> None:
    baseline_git = tmp_path / "baseline/.git"
    candidate_git = tmp_path / "candidate/.git"
    baseline_objects = baseline_git / "objects"
    candidate_objects = candidate_git / "objects"
    baseline_objects.mkdir(parents=True)
    candidate_objects.mkdir(parents=True)
    baseline = checker.GitDirectoryBoundary(
        baseline_git,
        (1, 10),
        baseline_objects,
        (1, 20),
    )
    candidate = checker.GitDirectoryBoundary(
        candidate_git,
        (1, 11),
        candidate_objects,
        (1, 20),
    )
    with pytest.raises(checker.CheckFailed, match="independent Git"):
        checker.require_distinct_git_boundaries(baseline, candidate)


def test_build_directory_only_cli_is_not_release_evidence() -> None:
    with pytest.raises(SystemExit):
        checker.parse_args(
            [
                "--baseline-build-dir",
                ".next-a",
                "--candidate-build-dir",
                ".next-b",
            ]
        )


def test_evidence_publish_is_private_and_no_replace(tmp_path: Path) -> None:
    evidence = {"schema_version": "walksafe.pwa_release_update_evidence.v2", "result": "passed"}
    output = tmp_path / "evidence.json"
    checker.publish_evidence(output, evidence)
    assert output.stat().st_mode & 0o777 == 0o600
    assert json.loads(output.read_text(encoding="utf-8")) == evidence

    with pytest.raises(checker.CheckFailed, match="already exists|publish"):
        checker.publish_evidence(output, {"result": "replacement"})
    assert json.loads(output.read_text(encoding="utf-8")) == evidence


def test_evidence_publish_rejects_symlink_output(tmp_path: Path) -> None:
    target = tmp_path / "target.json"
    target.write_text("preserve\n", encoding="utf-8")
    output = tmp_path / "evidence.json"
    output.symlink_to(target)

    with pytest.raises(checker.CheckFailed, match="already exists|publish"):
        checker.publish_evidence(output, {"result": "passed"})
    assert target.read_text(encoding="utf-8") == "preserve\n"


def test_evidence_publish_wraps_parent_resolution_failure(tmp_path: Path) -> None:
    looping_parent = tmp_path / "loop"
    looping_parent.symlink_to(looping_parent, target_is_directory=True)

    with pytest.raises(checker.CheckFailed, match="publish"):
        checker.publish_evidence(looping_parent / "evidence.json", {"result": "passed"})


def test_evidence_publish_rolls_back_post_link_fsync_failure(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    output = tmp_path / "evidence.json"
    real_fsync = checker._integrity.os.fsync
    calls = 0

    def fail_directory_fsync(descriptor: int) -> None:
        nonlocal calls
        calls += 1
        if calls == 2:
            raise OSError("injected post-link fsync failure")
        real_fsync(descriptor)

    monkeypatch.setattr(checker._integrity.os, "fsync", fail_directory_fsync)

    with pytest.raises(checker.CheckFailed, match="publish"):
        checker.publish_evidence(output, {"result": "passed"})

    assert not output.exists()
    assert not list(tmp_path.glob(".evidence.json.*"))


def test_evidence_publish_rolls_back_post_link_stat_failure(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    output = tmp_path / "evidence.json"
    real_link = checker._integrity.os.link
    real_stat = checker._integrity.os.stat
    linked = False

    def mark_linked(*args: object, **kwargs: object) -> None:
        nonlocal linked
        real_link(*args, **kwargs)
        linked = True

    def fail_published_stat(path: object, *args: object, **kwargs: object):
        nonlocal linked
        if linked and path == output.name and kwargs.get("dir_fd") is not None:
            linked = False
            raise OSError("injected post-link stat failure")
        return real_stat(path, *args, **kwargs)

    monkeypatch.setattr(checker._integrity.os, "link", mark_linked)
    monkeypatch.setattr(checker._integrity.os, "stat", fail_published_stat)

    with pytest.raises(checker.CheckFailed, match="publish"):
        checker.publish_evidence(output, {"result": "passed"})

    assert not output.exists()
    assert not list(tmp_path.glob(".evidence.json.*"))


def test_evidence_publish_fails_closed_without_anonymous_temporary_support(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    output = tmp_path / "evidence.json"
    real_open = checker._integrity.os.open
    temporary_flag = getattr(checker._integrity.os, "O_TMPFILE", 0)
    assert temporary_flag

    def reject_anonymous_temporary(path: object, flags: int, *args: object, **kwargs: object) -> int:
        if path == "." and flags & temporary_flag:
            raise OSError(errno.EOPNOTSUPP, "anonymous temporary files unsupported")
        return real_open(path, flags, *args, **kwargs)

    monkeypatch.setattr(checker._integrity.os, "open", reject_anonymous_temporary)

    with pytest.raises(checker.CheckFailed, match="publish"):
        checker.publish_evidence(output, {"result": "passed"})

    assert not output.exists()
    assert list(tmp_path.iterdir()) == []


def test_evidence_publish_rolls_back_if_parent_path_is_swapped_after_link(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    parent = tmp_path / "evidence"
    displaced_parent = tmp_path / "displaced-evidence"
    parent.mkdir()
    output = parent / "result.json"
    real_link = checker._integrity.os.link

    def swap_parent_after_link(*args: object, **kwargs: object) -> None:
        real_link(*args, **kwargs)
        parent.rename(displaced_parent)
        parent.mkdir()

    monkeypatch.setattr(checker._integrity.os, "link", swap_parent_after_link)

    with pytest.raises(checker.CheckFailed, match="publish"):
        checker.publish_evidence(output, {"result": "passed"})

    assert not output.exists()
    assert not (displaced_parent / output.name).exists()
    assert list(parent.iterdir()) == []
    assert list(displaced_parent.iterdir()) == []


def test_http_capture_binds_worker_graph_to_release_identity(monkeypatch: pytest.MonkeyPatch) -> None:
    build_id = "a" * 40
    worker = b'importScripts("/sw-version.js");\n'
    version = f'self.WALKSAFE_SW_SOURCE_COMMIT = "{build_id}";\n'.encode()
    identity = json.dumps({"status": "ready", "source_commit": build_id}, separators=(",", ":")).encode()

    def fake_fetch(url: str, *, timeout: float):
        assert timeout == 2.0
        if url.endswith("/api/release-identity"):
            return identity, {"cache-control": "no-store", "content-type": "application/json"}
        if url.endswith("/sw-version.js"):
            return version, {"cache-control": "no-store", "content-type": "application/javascript"}
        if url.endswith("/sw.js"):
            return worker, {"cache-control": "public, max-age=0", "content-type": "application/javascript"}
        raise AssertionError(url)

    monkeypatch.setattr(checker, "fetch_bytes", fake_fetch)
    captured = checker.capture_release_http("http://127.0.0.1:3101", ".next-a", build_id, timeout=2.0)
    assert captured["build_id"] == build_id
    assert captured["sw_graph_sha256"] == checker.sha256_bytes(worker + b"\0" + version)


def test_identical_or_missing_worker_graph_hash_is_rejected() -> None:
    checker.require_distinct_worker_graphs({"sw_graph_sha256": "a"}, {"sw_graph_sha256": "b"})
    for baseline, candidate in (
        ({"sw_graph_sha256": "same"}, {"sw_graph_sha256": "same"}),
        ({}, {"sw_graph_sha256": "candidate"}),
    ):
        with pytest.raises(checker.CheckFailed, match="must differ"):
            checker.require_distinct_worker_graphs(baseline, candidate)


def test_candidate_root_must_match_online_artifact_bound_shell() -> None:
    digest = "a" * 64
    expression = checker.candidate_root_probe_expression("b" * 40)
    assert "/_next/static/" not in expression
    assert ".arrayBuffer()" in expression
    assert ".text()" not in expression
    probe = {
        "cached": True,
        "cachedSha256": digest,
        "onlineOk": True,
        "onlineStatus": 200,
        "onlineSha256": digest,
    }
    assert checker.require_candidate_root_probe(probe, "b" * 40, digest) == digest

    probe["onlineSha256"] = "c" * 64
    with pytest.raises(checker.CheckFailed, match="verified artifact shell"):
        checker.require_candidate_root_probe(probe, "b" * 40, digest)

    probe["onlineSha256"] = digest
    probe["cachedSha256"] = "c" * 64
    with pytest.raises(checker.CheckFailed, match="verified artifact shell"):
        checker.require_candidate_root_probe(probe, "b" * 40, digest)

    probe["cachedSha256"] = digest
    with pytest.raises(checker.CheckFailed, match="verified artifact shell"):
        checker.require_candidate_root_probe(probe, "b" * 40, "c" * 64)


def test_offline_api_requires_transport_rejection_not_http_failure() -> None:
    digest = "a" * 64
    shell = {"ok": True, "status": 200, "sha256": digest}
    with pytest.raises(checker.CheckFailed, match="transport-rejected"):
        checker.require_offline_probe(
            {
                "shell": shell,
                "api": {"rejected": False, "ok": False, "status": 503},
            },
            digest,
        )

    _shell, api = checker.require_offline_probe(
        {
            "shell": shell,
            "api": {"rejected": True, "ok": False, "status": None},
        },
        digest,
    )
    assert api["rejected"] is True


def test_release_server_start_failure_verifies_process_and_port_cleanup(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    process = object()
    calls: list[tuple[object, int, str]] = []
    node_runtime = checker.types.SimpleNamespace(
        executable="/proc/self/fd/9",
        descriptor=9,
        verify_identity=lambda: None,
    )
    args = checker.argparse.Namespace(
        web_port=3101,
        node_runtime=node_runtime,
        signal_guard=object(),
    )
    monkeypatch.setattr(checker, "start_child_process", lambda *_args, **_kwargs: process)

    def fail_wait(_url: str, *, timeout: float) -> None:
        assert timeout == 60.0
        raise RuntimeError("startup probe failed")

    monkeypatch.setattr(checker, "wait_for_http", fail_wait)
    monkeypatch.setattr(
        checker,
        "stop_owned_process",
        lambda owned, port, label: calls.append((owned, port, label)),
    )
    owned: list[object] = []

    with pytest.raises(RuntimeError, match="startup probe failed"):
        checker.start_release_server(args, tmp_path, {}, tmp_path / "web.log", owned)

    assert calls == [(process, 3101, "the release Web process after startup failure")]
    assert owned == []


def test_process_registration_baseexception_cleans_the_unregistered_child(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    process = object()
    cleanup: list[tuple[object, int]] = []

    class FailingRegistry(list[object]):
        def append(self, _value: object) -> None:
            raise KeyboardInterrupt("registration interrupted")

    monkeypatch.setattr(checker, "start_child_process", lambda *_args, **_kwargs: process)
    monkeypatch.setattr(
        checker,
        "stop_owned_process",
        lambda owned, port, _label: cleanup.append((owned, port)),
    )

    with pytest.raises(KeyboardInterrupt, match="registration interrupted"):
        checker.start_registered_process(
            ["/test/child"],
            cwd=tmp_path,
            env=checker.clean_child_environment(),
            log_path=tmp_path / "child.log",
            pass_fds=(),
            registry=FailingRegistry(),
            port=3101,
            label="test child",
            signal_guard=object(),
        )

    assert cleanup == [(process, 3101)]


def test_child_log_close_baseexception_cleans_the_registered_child(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    process = object()
    cleanup: list[tuple[object, int]] = []

    class FailingLog:
        def __init__(self) -> None:
            self.closed = False
            self.close_calls = 0

        def close(self) -> None:
            self.close_calls += 1
            if self.close_calls == 1:
                raise KeyboardInterrupt("log close interrupted")
            self.closed = True

    log = FailingLog()
    monkeypatch.setattr(checker.Path, "open", lambda *_args, **_kwargs: log)
    monkeypatch.setattr(checker.subprocess, "Popen", lambda *_args, **_kwargs: process)
    monkeypatch.setattr(
        checker,
        "stop_owned_process",
        lambda owned, port, _label: cleanup.append((owned, port)),
    )
    owned: list[object] = []
    signal_guard = checker.OwnedProcessSignalGuard()
    signal_guard.install()

    try:
        with pytest.raises(KeyboardInterrupt, match="log close interrupted"):
            checker.start_child_process(
                ["/test/child"],
                cwd=tmp_path,
                env=checker.clean_child_environment(),
                log_path=tmp_path / "child.log",
                registry=owned,
                port=3101,
                label="test child",
                signal_guard=signal_guard,
            )
    finally:
        signal_guard.close()

    assert cleanup == [(process, 3101)]
    assert owned == []
    assert log.closed is True


def test_pending_termination_during_spawn_cleans_registered_child_and_restores_child_mask(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    process = object()
    owned: list[object] = []
    cleanup: list[tuple[object, int]] = []
    handlers: dict[signal.Signals, object] = {}
    mask_calls: list[tuple[int, set[signal.Signals]]] = []
    captured_preexec: list[object] = []
    delivered = False
    process_created = False
    inherited_mask = {signal.SIGUSR1}

    def fake_signal(signal_number: signal.Signals, handler: object) -> object:
        previous = handlers.get(signal_number, signal.SIG_DFL)
        handlers[signal_number] = handler
        return previous

    def fake_pthread_sigmask(
        how: int,
        signals: set[signal.Signals],
    ) -> set[signal.Signals]:
        nonlocal delivered
        mask_calls.append((how, set(signals)))
        if how == signal.SIG_SETMASK and process_created and not delivered:
            delivered = True
            handler = handlers[signal.SIGTERM]
            assert callable(handler)
            handler(signal.SIGTERM, None)
        return set(inherited_mask)

    def fake_popen(*_args: object, **kwargs: object) -> object:
        nonlocal process_created
        process_created = True
        captured_preexec.append(kwargs["preexec_fn"])
        return process

    monkeypatch.setattr(checker.signal, "signal", fake_signal)
    monkeypatch.setattr(checker.signal, "pthread_sigmask", fake_pthread_sigmask)
    monkeypatch.setattr(checker.subprocess, "Popen", fake_popen)
    monkeypatch.setattr(
        checker,
        "stop_owned_process",
        lambda child, port, _label: cleanup.append((child, port)),
    )

    signal_guard = checker.OwnedProcessSignalGuard()
    signal_guard.install()
    try:
        with pytest.raises(checker.DeferredProcessAcquisitionSignal) as captured:
            checker.start_child_process(
                ["/test/child"],
                cwd=tmp_path,
                env=checker.clean_child_environment(),
                log_path=tmp_path / "child.log",
                registry=owned,
                port=3101,
                label="test child",
                signal_guard=signal_guard,
            )
    finally:
        signal_guard.close()

    assert captured.value.code == 143
    assert cleanup == [(process, 3101)]
    assert owned == []
    assert len(captured_preexec) == 1
    assert callable(captured_preexec[0])
    captured_preexec[0]()
    assert mask_calls[-1] == (signal.SIG_SETMASK, inherited_mask)


def test_spawned_child_does_not_inherit_temporary_termination_mask(tmp_path: Path) -> None:
    current_mask = signal.pthread_sigmask(signal.SIG_BLOCK, set())
    assert signal.SIGTERM not in current_mask
    owned: list[subprocess.Popen[str]] = []
    signal_guard = checker.OwnedProcessSignalGuard()
    signal_guard.install()
    try:
        process = checker.start_child_process(
            [
                "/usr/bin/python3",
                "-I",
                "-S",
                "-B",
                "-c",
                (
                    "import signal,sys; "
                    "mask=signal.pthread_sigmask(signal.SIG_BLOCK,set()); "
                    "sys.exit(1 if signal.SIGTERM in mask else 0)"
                ),
            ],
            cwd=tmp_path,
            env=checker.clean_child_environment(),
            log_path=tmp_path / "child.log",
            registry=owned,
            label="signal-mask probe",
            signal_guard=signal_guard,
        )

        assert process.wait(timeout=5) == 0
        assert owned == [process]
        owned.remove(process)
    finally:
        signal_guard.close()


def test_signal_guard_restores_handlers_only_after_cleanup_with_signals_blocked(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    handlers: dict[signal.Signals, object] = {}
    events: list[str] = []
    second_signal_pending = False

    def fake_signal(signal_number: signal.Signals, handler: object) -> object:
        nonlocal second_signal_pending
        previous = handlers.get(signal_number, signal.SIG_DFL)
        handlers[signal_number] = handler
        if handler == signal.SIG_DFL:
            events.append(f"restore:{signal_number}")
            second_signal_pending = True
        return previous

    def fake_pthread_sigmask(
        how: int,
        _signals: set[signal.Signals],
    ) -> set[signal.Signals]:
        if how == signal.SIG_SETMASK and second_signal_pending:
            events.append("second-signal-delivered")
            raise SystemExit(143)
        return set()

    monkeypatch.setattr(checker.signal, "signal", fake_signal)
    monkeypatch.setattr(checker.signal, "pthread_sigmask", fake_pthread_sigmask)
    signal_guard = checker.OwnedProcessSignalGuard()
    signal_guard.install()

    events.append("owned-process-cleanup")
    with pytest.raises(SystemExit) as captured:
        signal_guard.close()

    assert captured.value.code == 143
    assert signal_guard.installed is False
    assert all(
        handlers[signal_number] == signal.SIG_DFL
        for signal_number in checker.PROCESS_ACQUISITION_SIGNALS
    )
    cleanup_index = events.index("owned-process-cleanup")
    delivery_index = events.index("second-signal-delivered")
    restore_indexes = [
        index for index, event in enumerate(events) if event.startswith("restore:")
    ]
    assert restore_indexes
    assert cleanup_index < min(restore_indexes)
    assert max(restore_indexes) < delivery_index


def test_owned_process_cleanup_attempts_every_process_and_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    chrome = object()
    candidate_web = object()
    baseline_web = object()
    calls: list[tuple[object, int]] = []
    args = checker.argparse.Namespace(web_port=3101, chrome_debug_port=9323)

    def fake_stop(process: object, port: int, _label: str) -> None:
        calls.append((process, port))
        if process is chrome or process is baseline_web:
            raise checker.CheckFailed(f"port {port} remained open")

    monkeypatch.setattr(checker, "stop_owned_process", fake_stop)

    with pytest.raises(checker.CheckFailed, match=r"Chromium.*Web\[2\]"):
        checker.cleanup_owned_processes(
            args,
            [chrome],
            [baseline_web, candidate_web],
        )

    assert calls == [
        (chrome, 9323),
        (candidate_web, 3101),
        (baseline_web, 3101),
    ]


def test_stop_helper_rejects_a_surviving_owned_process_group(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    process = subprocess.Popen(
        [
            "/usr/bin/python3",
            "-c",
            "import os, time; pid=os.fork(); os._exit(0) if pid else time.sleep(30)",
        ],
        start_new_session=True,
    )
    process.wait(timeout=5)
    deadline = time.monotonic() + 2
    while True:
        try:
            os.killpg(process.pid, 0)
            break
        except ProcessLookupError:
            if time.monotonic() >= deadline:
                pytest.fail("orphaned owned process group was not created")
            time.sleep(0.01)
    monkeypatch.setattr(checker, "_stop_owned_process", lambda *_args: None)
    monkeypatch.setattr(checker, "PROCESS_GROUP_RELEASE_TIMEOUT_SECONDS", 0.05)
    try:
        with pytest.raises(checker.CheckFailed, match="process group remained"):
            checker.stop_owned_process(process, 3101, "orphaned test group")
    finally:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def test_cleanup_failure_is_reported_together_with_the_original_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    primary = RuntimeError("browser transition failed")
    args = checker.argparse.Namespace(web_port=3101, chrome_debug_port=9323)

    def fail_cleanup(*_args: object, **_kwargs: object) -> None:
        raise checker.CheckFailed("loopback port remained open")

    monkeypatch.setattr(checker, "cleanup_owned_processes", fail_cleanup)

    with pytest.raises(
        checker.CheckFailed,
        match="browser transition failed.*loopback port remained open",
    ):
        checker.cleanup_after_failure(primary, args, [], [])


def test_final_gate_reruns_all_exact_verifiers_and_rejects_persistent_mutation(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    baseline_commit = "a" * 40
    candidate_commit = "b" * 40
    baseline = fake_release_artifact(tmp_path, "baseline", baseline_commit)
    candidate = fake_release_artifact(tmp_path, "candidate", candidate_commit)
    mutated_candidate = candidate._replace(root_shell_sha256="9" * 64)
    history = {"baseline": baseline_commit, "head": candidate_commit}
    node = tmp_path / "node"
    runtime = checker.types.SimpleNamespace(path=node)
    calls = {"history": 0, "baseline": 0, "candidate": 0, "node": 0}
    args = checker.argparse.Namespace(
        baseline_source_root=tmp_path / "baseline-source",
        candidate_source_root=tmp_path / "candidate-source",
        baseline_manifest=baseline.manifest_path,
        baseline_standalone_root=baseline.build_root,
        baseline_deployment_archive=baseline.deployment_archive,
        candidate_manifest=candidate.manifest_path,
        candidate_standalone_root=candidate.build_root,
        candidate_deployment_archive=candidate.deployment_archive,
    )

    def verify_history(*_args: object) -> dict[str, str]:
        calls["history"] += 1
        return history

    def verify_artifact(*_args: object, label: str) -> checker.ReleaseArtifact:
        calls[label] += 1
        return baseline if label == "baseline" else mutated_candidate

    def verify_node(
        _path: Path,
        releases: tuple[checker.ReleaseArtifact, checker.ReleaseArtifact],
        *,
        existing: object,
    ) -> object:
        calls["node"] += 1
        assert releases == (baseline, mutated_candidate)
        assert existing is runtime
        return runtime

    monkeypatch.setattr(checker, "verify_release_history", verify_history)
    monkeypatch.setattr(checker, "verify_release_artifact", verify_artifact)
    monkeypatch.setattr(checker, "verify_node_runtime", verify_node)

    with pytest.raises(checker.CheckFailed, match="candidate artifact"):
        checker.verify_unchanged_release_inputs(args, history, baseline, candidate, runtime)

    assert calls == {"history": 1, "baseline": 1, "candidate": 1, "node": 1}
    source = (ROOT / "scripts/check_pwa_release_update_20260717.py").read_text(encoding="utf-8")
    main_source = source[source.index("def main()") :]
    assert main_source.index("verify_unchanged_release_inputs(") < main_source.index(
        "publish_evidence(args.evidence_out, evidence)"
    )


def test_pass_evidence_is_published_only_after_final_gate_and_verified_cleanup(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    baseline_commit = "a" * 40
    candidate_commit = "b" * 40
    baseline = fake_release_artifact(tmp_path, "baseline", baseline_commit)
    candidate = fake_release_artifact(tmp_path, "candidate", candidate_commit)
    node = tmp_path / "node"
    evidence_out = tmp_path / "evidence.json"
    runtime = tmp_path / "runtime"
    runtime.mkdir()
    events: list[str] = []

    class FakeProcess:
        def __init__(self, label: str) -> None:
            self.label = label
            self.stopped = False

        def poll(self) -> int | None:
            return 0 if self.stopped else None

    class FakeLock:
        def close(self) -> None:
            events.append("lock-close")

    class FakeSignalGuard:
        def install(self) -> None:
            events.append("signal-guard-install")

        def close(self) -> None:
            events.append("signal-guard-close")

    web_process = FakeProcess("web")
    chrome_process = FakeProcess("chrome")
    args = checker.argparse.Namespace(
        baseline_source_root=tmp_path / "baseline-source",
        baseline_manifest=baseline.manifest_path,
        baseline_standalone_root=baseline.build_root,
        baseline_deployment_archive=baseline.deployment_archive,
        candidate_source_root=tmp_path / "candidate-source",
        candidate_manifest=candidate.manifest_path,
        candidate_standalone_root=candidate.build_root,
        candidate_deployment_archive=candidate.deployment_archive,
        node_bin=node,
        web_port=3101,
        chrome_debug_port=9323,
        chrome_bin="/test/chromium",
        browser_timeout=1.0,
        evidence_out=evidence_out,
    )

    monkeypatch.setattr(checker, "parse_args", lambda: args)
    monkeypatch.setattr(checker, "require_isolated_loopback_ports", lambda _ports: None)
    monkeypatch.setattr(
        checker,
        "release_manifest_commit",
        lambda _path, label: baseline_commit if label == "baseline" else candidate_commit,
    )
    monkeypatch.setattr(
        checker,
        "verify_release_history",
        lambda *_args: {"baseline": baseline_commit, "head": candidate_commit},
    )
    monkeypatch.setattr(
        checker,
        "verify_release_artifact",
        lambda *_args, label: baseline if label == "baseline" else candidate,
    )
    fake_node_runtime = checker.types.SimpleNamespace(path=node, close=lambda: None)
    monkeypatch.setattr(checker, "verify_node_runtime", lambda *_args, **_kwargs: fake_node_runtime)
    monkeypatch.setattr(checker, "OwnedProcessSignalGuard", FakeSignalGuard)
    monkeypatch.setattr(checker, "acquire_lifecycle_lock", lambda: FakeLock())
    monkeypatch.setattr(checker.tempfile, "mkdtemp", lambda **_kwargs: str(runtime))
    monkeypatch.setattr(
        checker,
        "new_lifecycle_credentials",
        lambda: ("actor", "account-token", "backend-token", "session-secret"),
    )
    def start_web(
        _args: object,
        _root: object,
        _environment: object,
        _log: object,
        registry: list[FakeProcess],
    ) -> FakeProcess:
        registry.append(web_process)
        return web_process

    monkeypatch.setattr(checker, "start_release_server", start_web)
    monkeypatch.setattr(checker, "capture_release_http", lambda *_args, **_kwargs: {"release": "A"})
    monkeypatch.setattr(checker, "chrome_binary", lambda _path: "/test/chromium")
    monkeypatch.setattr(checker, "start_child_process", lambda *_args, **_kwargs: chrome_process)
    monkeypatch.setattr(checker, "wait_for_http", lambda *_args, **_kwargs: None)

    def finish_transition(coroutine: object) -> tuple[dict[str, str], dict[str, bool]]:
        coroutine.close()
        return {"release": "B"}, {"applied": True}

    monkeypatch.setattr(checker.asyncio, "run", finish_transition)

    def stop_owned(process: FakeProcess, _port: int, _label: str) -> None:
        process.stopped = True
        events.append(f"cleanup:{process.label}")

    monkeypatch.setattr(checker, "stop_owned_process", stop_owned)
    monkeypatch.setattr(
        checker,
        "verify_unchanged_release_inputs",
        lambda *_args: events.append("final-input-gate"),
    )
    monkeypatch.setattr(
        checker,
        "publish_evidence",
        lambda path, _evidence: events.append(f"publish:{path.name}"),
    )

    assert checker.main() == 0
    assert events == [
        "signal-guard-install",
        "cleanup:chrome",
        "final-input-gate",
        "cleanup:web",
        "lock-close",
        "signal-guard-close",
        "publish:evidence.json",
    ]
