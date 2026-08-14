#!/usr/bin/env python3
"""Build the add-only a3ad7ee commit/path/blob witness from two bundles."""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_goal_graph_v2_4 as graph
from scripts import check_walksafe_project_continuation_v2_4 as continuation


SOURCE_CONTRACT = {
    "PRIVATE_BACKUP_20260802T0125KST": {
        "bundle_sha256": (
            "56a95dd2b43612d27b5140dddb2857830cd2ce82571cad3748f7886dade10751"
        ),
        "bundle_byte_count": 430056267,
        "commit_ref": "refs/heads/codex/walksafe-rc2-hardening-20260715",
    },
    "PRE_STANDALONE_RECOVERY_20260810": {
        "bundle_sha256": (
            "2c966e841f5be2a195c1690d11c29f21104c284f75ec5276a36ecc38abb7bf9c"
        ),
        "bundle_byte_count": 430057800,
        "commit_ref": "refs/heads/codex/walksafe-rc2-hardening-20260715",
    },
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git(git_dir: Path, *args: str, check: bool = True) -> bytes:
    completed = subprocess.run(
        ["git", f"--git-dir={git_dir}", *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check and completed.returncode != 0:
        raise RuntimeError(
            f"git {' '.join(args)} failed: "
            + completed.stderr.decode("utf-8", "replace")
        )
    return completed.stdout


def bundle_heads(path: Path) -> dict[str, str]:
    completed = subprocess.run(
        ["git", "bundle", "list-heads", str(path)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
        text=True,
    )
    return {
        ref: object_id
        for line in completed.stdout.splitlines()
        for object_id, ref in [line.split(" ", 1)]
    }


def isolated_bundle_repo(path: Path, destination: Path) -> Path:
    completed = subprocess.run(
        ["git", "clone", "--bare", "--no-hardlinks", str(path), str(destination)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            "isolated bundle clone failed: "
            + completed.stderr.decode("utf-8", "replace")
        )
    return destination


def object_payload(git_dir: Path, kind: str, object_id: str) -> bytes:
    actual_kind = git(git_dir, "cat-file", "-t", object_id).strip().decode("ascii")
    if actual_kind != kind:
        raise RuntimeError(f"object type differs: {object_id}: {actual_kind}")
    return git(git_dir, "cat-file", kind, object_id)


def path_entry(git_dir: Path, commit: str, relative: str) -> tuple[str, str] | None:
    raw = git(git_dir, "ls-tree", "-z", commit, "--", relative)
    if not raw:
        return None
    if not raw.endswith(b"\0") or raw.count(b"\0") != 1:
        raise RuntimeError(f"path tree record differs: {relative}")
    metadata, observed_path = raw[:-1].split(b"\t", 1)
    mode, kind, object_id = metadata.decode("ascii").split(" ")
    if observed_path.decode("utf-8") != relative or kind != "blob":
        raise RuntimeError(f"path binding differs: {relative}")
    return mode, object_id


def build_manifest(repo_dirs: list[Path]) -> tuple[dict, dict[str, tuple[str, bytes]]]:
    commit = graph.HISTORICAL_GIT_WITNESS_COMMIT
    commit_payloads = [object_payload(repo, "commit", commit) for repo in repo_dirs]
    if len(set(commit_payloads)) != 1:
        raise RuntimeError("source bundle commit bytes differ")
    commit_payload = commit_payloads[0]
    headers = commit_payload.split(b"\n\n", 1)[0].splitlines()
    trees = [line[5:].decode("ascii") for line in headers if line.startswith(b"tree ")]
    parents = [line[7:].decode("ascii") for line in headers if line.startswith(b"parent ")]
    if len(trees) != 1:
        raise RuntimeError("source commit tree header differs")
    root_tree = trees[0]

    archive = continuation.load_json(ROOT / graph.V23_ARCHIVE_RELATIVE)
    historical_refs = []
    historical_paths = set()
    for goal_id, indexes in graph.HISTORICAL_GIT_WITNESS_ARTIFACT_INDEXES.items():
        for index in indexes:
            artifact = graph._historical_changed_artifact(
                ROOT,
                archive,
                goal_id=goal_id,
                artifact_index=index,
            )
            if artifact is None:
                raise RuntimeError(f"historical artifact is missing: {goal_id}[{index}]")
            relative, after_sha256 = artifact
            historical_paths.add(relative)
            historical_refs.append(
                {
                    "goal_id": goal_id,
                    "artifact_index": index,
                    "path": relative,
                    "after_sha256": after_sha256,
                }
            )

    start_paths = set(graph.HISTORICAL_GIT_WITNESS_START_HEAD_PATHS)
    witness_paths = sorted(start_paths | historical_paths)
    objects: dict[str, tuple[str, bytes]] = {commit: ("commit", commit_payload)}
    path_rows = []
    for relative in witness_paths:
        entries = [path_entry(repo, commit, relative) for repo in repo_dirs]
        if len(set(entries)) != 1:
            raise RuntimeError(f"source bundle path binding differs: {relative}")
        consumers = []
        if relative in start_paths:
            consumers.append("START_HEAD")
        if relative in historical_paths:
            consumers.append("FROZEN_CHANGED_ARTIFACT")
        entry = entries[0]
        if entry is None:
            path_rows.append(
                {"path": relative, "state": "ABSENT", "consumers": consumers}
            )
        else:
            mode, blob_id = entry
            payloads = [object_payload(repo, "blob", blob_id) for repo in repo_dirs]
            if len(set(payloads)) != 1:
                raise RuntimeError(f"source bundle blob bytes differ: {relative}")
            payload = payloads[0]
            objects[blob_id] = "blob", payload
            path_rows.append(
                {
                    "path": relative,
                    "state": "PRESENT",
                    "consumers": consumers,
                    "mode": mode,
                    "blob_object_id": blob_id,
                    "blob_byte_count": len(payload),
                    "blob_sha256": hashlib.sha256(payload).hexdigest(),
                }
            )

        parts = relative.split("/")[:-1]
        prefixes = [""] + ["/".join(parts[:index]) for index in range(1, len(parts) + 1)]
        for prefix in prefixes:
            expressions = [commit if not prefix else f"{commit}:{prefix}"]
            object_ids = []
            for repo in repo_dirs:
                completed = subprocess.run(
                    ["git", f"--git-dir={repo}", "rev-parse", expressions[0]],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    check=False,
                )
                object_ids.append(
                    completed.stdout.strip().decode("ascii")
                    if completed.returncode == 0
                    else None
                )
            if len(set(object_ids)) != 1:
                raise RuntimeError(f"source bundle tree proof differs: {prefix}")
            object_id = object_ids[0]
            if object_id is None:
                continue
            if not prefix:
                object_id = root_tree
            payloads = [object_payload(repo, "tree", object_id) for repo in repo_dirs]
            if len(set(payloads)) != 1:
                raise RuntimeError(f"source bundle tree bytes differ: {prefix}")
            objects[object_id] = "tree", payloads[0]

    object_rows = []
    for object_id, (kind, payload) in sorted(objects.items()):
        encoded = base64.b64encode(gzip.compress(payload, mtime=0)) + b"\n"
        fixture_path = (
            graph.HISTORICAL_GIT_WITNESS_ROOT_RELATIVE
            / "objects"
            / f"{object_id}.gz.b64"
        ).as_posix()
        object_rows.append(
            {
                "object_type": kind,
                "object_id": object_id,
                "raw_byte_count": len(payload),
                "raw_sha256": hashlib.sha256(payload).hexdigest(),
                "encoding": "GZIP_BASE64_RFC4648_MTIME_0",
                "fixture_path": fixture_path,
                "fixture_byte_count": len(encoded),
                "fixture_sha256": hashlib.sha256(encoded).hexdigest(),
            }
        )

    sources = [
        {"source_id": source_id, **contract}
        for source_id, contract in SOURCE_CONTRACT.items()
    ]
    manifest = {
        "schema_version": "walksafe.git-history-witness.v1",
        "witness_id": "WS-GIT-HISTORY-WITNESS-A3AD7EE-20260812-001",
        "status": "BYTE_PINNED_ADD_ONLY_FIXTURE",
        "generated_on": "2026-08-12",
        "source_bundle_provenance": sources,
        "source_agreement": {
            "independent_source_count": 2,
            "commit_tree_path_blob_bytes_equal": True,
        },
        "commit": {
            "object_format": "sha1",
            "object_id": commit,
            "tree_object_id": root_tree,
            "parent_object_ids": parents,
        },
        "start_head_paths": list(graph.HISTORICAL_GIT_WITNESS_START_HEAD_PATHS),
        "historical_artifact_refs": historical_refs,
        "paths": path_rows,
        "objects": object_rows,
        "integrity": {
            "path_count": len(path_rows),
            "present_path_count": sum(row["state"] == "PRESENT" for row in path_rows),
            "absent_path_count": sum(row["state"] == "ABSENT" for row in path_rows),
            "start_head_path_count": len(start_paths),
            "historical_artifact_ref_count": len(historical_refs),
            "object_count": len(object_rows),
            "commit_object_count": 1,
            "tree_object_count": sum(row["object_type"] == "tree" for row in object_rows),
            "blob_object_count": sum(row["object_type"] == "blob" for row in object_rows),
        },
        "claim_boundary": {
            "external_bundle_required_at_runtime": False,
            "git_history_fetched_or_grafted": False,
            "historical_control_modified": False,
            "goal_completion_credit_added": 0,
            "formal_test_credit_added": 0,
            "release_credit_added": 0,
            "release_status": "NOT_ELIGIBLE",
            "use": "READ_ONLY_HISTORICAL_COMMIT_PATH_BLOB_WITNESS",
        },
    }
    return manifest, objects


def write_fixture(output: Path, manifest: dict, objects: dict[str, tuple[str, bytes]]) -> None:
    if output.exists():
        raise RuntimeError(f"refusing to overwrite existing fixture: {output}")
    (output / "objects").mkdir(parents=True)
    for object_id, (_, payload) in objects.items():
        encoded = base64.b64encode(gzip.compress(payload, mtime=0)) + b"\n"
        (output / "objects" / f"{object_id}.gz.b64").write_bytes(encoded)
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", action="append", nargs=2, metavar=("SOURCE_ID", "BUNDLE"), required=True)
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / graph.HISTORICAL_GIT_WITNESS_ROOT_RELATIVE,
    )
    args = parser.parse_args()
    sources = {source_id: Path(path).resolve() for source_id, path in args.source}
    if set(sources) != set(SOURCE_CONTRACT):
        raise SystemExit("the exact two source IDs are required")
    for source_id, path in sources.items():
        contract = SOURCE_CONTRACT[source_id]
        if (
            not path.is_file()
            or path.stat().st_size != contract["bundle_byte_count"]
            or sha256_file(path) != contract["bundle_sha256"]
            or bundle_heads(path).get(contract["commit_ref"])
            != graph.HISTORICAL_GIT_WITNESS_COMMIT
        ):
            raise SystemExit(f"source bundle differs: {source_id}")

    with tempfile.TemporaryDirectory(
        prefix="walksafe-git-witness-",
        dir=ROOT / ".git",
    ) as temporary:
        temporary_root = Path(temporary)
        repos = [
            isolated_bundle_repo(sources[source_id], temporary_root / f"source-{index}.git")
            for index, source_id in enumerate(SOURCE_CONTRACT, start=1)
        ]
        manifest, objects = build_manifest(repos)
        write_fixture(args.output.resolve(), manifest, objects)
    print(
        "WalkSafe historical Git witness built: "
        f"{args.output} ({len(manifest['paths'])} paths, {len(objects)} objects)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
