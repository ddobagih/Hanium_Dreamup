#!/usr/bin/env python3
"""Verify and update the local model control-plane without changing runtime configuration."""

from __future__ import annotations

import argparse
import contextlib
import fcntl
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator


REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))

from scripts.walksafe_dataset_integrity import DatasetIntegrityError, verify_content_hashed_manifest  # noqa: E402

DEFAULT_REGISTRY = REPO_ROOT / "model/registry/walksafe-model-registry.json"
DEFAULT_DEPLOYMENT = REPO_ROOT / "model/deployments/local-deployment.json"
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def repo_path(value: str) -> Path:
    relative = Path(value)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError(f"path must be repository-relative: {value}")
    resolved = (REPO_ROOT / relative).resolve()
    if not resolved.is_relative_to(REPO_ROOT.resolve()):
        raise ValueError(f"path escapes repository: {value}")
    return resolved


def registry_models(registry: dict[str, Any]) -> dict[str, dict[str, Any]]:
    rows = registry.get("models")
    if not isinstance(rows, list):
        raise ValueError("registry models must be a list")
    models: dict[str, dict[str, Any]] = {}
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("model_id"), str):
            raise ValueError("each registry model requires model_id")
        model_id = row["model_id"]
        if model_id in models:
            raise ValueError(f"duplicate model_id: {model_id}")
        models[model_id] = row
    return models


def check_hashed_path(label: str, path_value: Any, expected_hash: Any, issues: list[str]) -> None:
    if not isinstance(path_value, str) or not path_value:
        issues.append(f"{label}: missing path")
        return
    if not isinstance(expected_hash, str) or not SHA256_PATTERN.fullmatch(expected_hash):
        issues.append(f"{label}: invalid sha256")
        return
    try:
        path = repo_path(path_value)
    except ValueError as error:
        issues.append(f"{label}: {error}")
        return
    if not path.is_file():
        issues.append(f"{label}: file missing: {path_value}")
        return
    actual = sha256(path)
    if actual != expected_hash:
        issues.append(f"{label}: sha256 mismatch: expected {expected_hash}, got {actual}")


def verify_state(registry: dict[str, Any], deployment: dict[str, Any]) -> list[str]:
    issues: list[str] = []
    if registry.get("schema_version") != "walksafe.local_model_registry.v1":
        issues.append("unsupported registry schema_version")
    if deployment.get("schema_version") != "walksafe.local_deployment.v1":
        issues.append("unsupported deployment schema_version")

    try:
        models = registry_models(registry)
    except ValueError as error:
        issues.append(str(error))
        return issues

    for model_id, model in models.items():
        artifact = model.get("artifact") if isinstance(model.get("artifact"), dict) else {}
        exports = model.get("exports") if isinstance(model.get("exports"), dict) else {}
        dataset = model.get("dataset") if isinstance(model.get("dataset"), dict) else {}
        training = model.get("training") if isinstance(model.get("training"), dict) else {}
        check_hashed_path(f"{model_id}.artifact", artifact.get("path"), artifact.get("sha256"), issues)
        for export_name, export in exports.items():
            if not isinstance(export, dict):
                issues.append(f"{model_id}.exports.{export_name}: invalid export record")
                continue
            check_hashed_path(
                f"{model_id}.exports.{export_name}",
                export.get("path"),
                export.get("sha256"),
                issues,
            )
        android_export = exports.get("android_tflite")
        if isinstance(android_export, dict):
            if android_export.get("input_shape") != [1, 768, 768, 3]:
                issues.append(f"{model_id}.exports.android_tflite: input shape must be [1, 768, 768, 3]")
            if android_export.get("output_shape") != [1, 300, 6]:
                issues.append(f"{model_id}.exports.android_tflite: output shape must be [1, 300, 6]")
        check_hashed_path(
            f"{model_id}.dataset",
            dataset.get("manifest_path"),
            dataset.get("manifest_sha256"),
            issues,
        )
        check_hashed_path(
            f"{model_id}.training",
            training.get("results_path"),
            training.get("results_sha256"),
            issues,
        )
        if model.get("deployment_eligible") is True:
            if dataset.get("content_hash_policy") != "sha256_per_image_and_label":
                issues.append(f"{model_id}: deployment-eligible dataset requires per-image and per-label SHA-256")
            elif isinstance(dataset.get("manifest_path"), str):
                try:
                    verify_content_hashed_manifest(
                        repo_path(dataset["manifest_path"]),
                        repository_root=REPO_ROOT,
                    )
                except (DatasetIntegrityError, OSError, ValueError) as error:
                    issues.append(f"{model_id}.dataset content verification failed: {error}")
        if model.get("deployment_eligible") is True and model.get("blockers"):
            issues.append(f"{model_id}: deployment_eligible model still has blockers")

    targets = deployment.get("targets")
    if not isinstance(targets, dict):
        issues.append("deployment targets must be an object")
        return issues
    for target_name, target in targets.items():
        if not isinstance(target, dict):
            issues.append(f"deployment target {target_name} must be an object")
            continue
        for key in ("active_model_id", "previous_model_id"):
            model_id = target.get(key)
            if model_id is not None and model_id not in models:
                issues.append(f"deployment target {target_name}.{key} references unknown model {model_id}")
    return issues


def verify_runtime_state(registry: dict[str, Any], deployment: dict[str, Any]) -> list[str]:
    """Verify only artifacts referenced by active runtime targets.

    Training results and dataset manifests remain mandatory for the full ``verify``
    command, but they are intentionally not release-runtime dependencies.
    """
    issues: list[str] = []
    if registry.get("schema_version") != "walksafe.local_model_registry.v1":
        issues.append("unsupported registry schema_version")
    if deployment.get("schema_version") != "walksafe.local_deployment.v1":
        issues.append("unsupported deployment schema_version")
    try:
        models = registry_models(registry)
    except ValueError as error:
        return [str(error)]
    referenced = referenced_model_ids(deployment)
    if not referenced:
        issues.append("deployment does not reference an active or previous runtime model")
        return issues
    for model_id in sorted(referenced):
        model = models.get(model_id)
        if model is None:
            issues.append(f"deployment references unknown model {model_id}")
            continue
        artifact = model.get("artifact") if isinstance(model.get("artifact"), dict) else {}
        exports = model.get("exports") if isinstance(model.get("exports"), dict) else {}
        check_hashed_path(f"{model_id}.artifact", artifact.get("path"), artifact.get("sha256"), issues)
        for export_name, export in exports.items():
            if not isinstance(export, dict):
                issues.append(f"{model_id}.exports.{export_name}: invalid export record")
                continue
            check_hashed_path(
                f"{model_id}.exports.{export_name}",
                export.get("path"),
                export.get("sha256"),
                issues,
            )
        android_export = exports.get("android_tflite")
        if isinstance(android_export, dict):
            if android_export.get("input_shape") != [1, 768, 768, 3]:
                issues.append(f"{model_id}.exports.android_tflite: input shape must be [1, 768, 768, 3]")
            if android_export.get("output_shape") != [1, 300, 6]:
                issues.append(f"{model_id}.exports.android_tflite: output shape must be [1, 300, 6]")
    return issues


@contextlib.contextmanager
def state_lock(registry_path: Path, deployment_path: Path) -> Iterator[None]:
    lock_path = deployment_path.parent / ".local-model-state.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a+", encoding="utf-8") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def atomic_write_json(path: Path, payload: dict[str, Any]) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    backup = path.with_name(f"{path.name}.bak.{datetime.now().strftime('%Y%m%d%H%M%S')}")
    if path.exists():
        shutil.copy2(path, backup)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=True, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, path)
    except Exception:
        Path(temporary_name).unlink(missing_ok=True)
        raise
    return backup


def referenced_model_ids(deployment: dict[str, Any]) -> set[str]:
    references: set[str] = set()
    targets = deployment.get("targets")
    if not isinstance(targets, dict):
        return references
    for target in targets.values():
        if not isinstance(target, dict):
            continue
        for key in ("active_model_id", "previous_model_id"):
            value = target.get(key)
            if isinstance(value, str):
                references.add(value)
    return references


def retention_candidates(registry: dict[str, Any], deployment: dict[str, Any]) -> list[dict[str, str]]:
    references = referenced_model_ids(deployment)
    candidates: list[dict[str, str]] = []
    for model_id, model in registry_models(registry).items():
        retention = model.get("retention") if isinstance(model.get("retention"), dict) else {}
        if model_id in references or retention.get("retain") is not False or model.get("stage") != "archived":
            continue
        artifact = model.get("artifact") if isinstance(model.get("artifact"), dict) else {}
        path = artifact.get("path")
        if isinstance(path, str):
            candidates.append({"model_id": model_id, "artifact_path": path})
    return candidates


def command_verify(args: argparse.Namespace) -> int:
    registry = load_json(args.registry)
    deployment = load_json(args.deployment)
    issues = verify_state(registry, deployment)
    print(json.dumps({"ok": not issues, "issues": issues}, ensure_ascii=False, indent=2))
    return 0 if not issues else 1


def command_verify_runtime(args: argparse.Namespace) -> int:
    registry = load_json(args.registry)
    deployment = load_json(args.deployment)
    issues = verify_runtime_state(registry, deployment)
    print(json.dumps({"ok": not issues, "scope": "runtime", "issues": issues}, ensure_ascii=False, indent=2))
    return 0 if not issues else 1


def command_promote(args: argparse.Namespace) -> int:
    if args.approve != args.model_id:
        raise ValueError("--approve must exactly match --model-id")
    with state_lock(args.registry, args.deployment):
        registry = load_json(args.registry)
        deployment = load_json(args.deployment)
        models = registry_models(registry)
        model = models.get(args.model_id)
        if not model:
            raise ValueError(f"unknown model_id: {args.model_id}")
        if model.get("deployment_eligible") is not True or model.get("blockers"):
            raise ValueError("model is not deployment eligible")
        issues = verify_state(registry, deployment)
        if issues:
            raise ValueError("state verification failed: " + "; ".join(issues))
        targets = deployment.get("targets", {})
        target = targets.get(args.target)
        if not isinstance(target, dict):
            raise ValueError(f"unknown deployment target: {args.target}")
        previous = target.get("active_model_id")
        if previous == args.model_id:
            print("already active")
            return 0
        target["previous_model_id"] = previous
        target["active_model_id"] = args.model_id
        target["runtime_state"] = "manifest_promoted_runtime_not_changed"
        deployment["generation"] = int(deployment.get("generation", 0)) + 1
        deployment["updated_at"] = now_iso()
        deployment.setdefault("history", []).append(
            {"action": "promote", "target": args.target, "from": previous, "to": args.model_id, "at": deployment["updated_at"]}
        )
        backup = atomic_write_json(args.deployment, deployment)
        print(f"promoted manifest only; runtime unchanged; backup={backup}")
    return 0


def command_rollback(args: argparse.Namespace) -> int:
    if args.approve != f"rollback:{args.target}":
        raise ValueError(f"--approve must be rollback:{args.target}")
    with state_lock(args.registry, args.deployment):
        registry = load_json(args.registry)
        deployment = load_json(args.deployment)
        issues = verify_state(registry, deployment)
        if issues:
            raise ValueError("state verification failed: " + "; ".join(issues))
        target = deployment.get("targets", {}).get(args.target)
        if not isinstance(target, dict):
            raise ValueError(f"unknown deployment target: {args.target}")
        active = target.get("active_model_id")
        previous = target.get("previous_model_id")
        if not isinstance(previous, str):
            raise ValueError("no previous model is recorded")
        target["active_model_id"] = previous
        target["previous_model_id"] = active
        target["runtime_state"] = "manifest_rolled_back_runtime_not_changed"
        deployment["generation"] = int(deployment.get("generation", 0)) + 1
        deployment["updated_at"] = now_iso()
        deployment.setdefault("history", []).append(
            {"action": "rollback", "target": args.target, "from": active, "to": previous, "at": deployment["updated_at"]}
        )
        backup = atomic_write_json(args.deployment, deployment)
        print(f"rolled back manifest only; runtime unchanged; backup={backup}")
    return 0


def command_retention_plan(args: argparse.Namespace) -> int:
    registry = load_json(args.registry)
    deployment = load_json(args.deployment)
    issues = verify_state(registry, deployment)
    if issues:
        raise ValueError("state verification failed: " + "; ".join(issues))
    candidates = retention_candidates(registry, deployment)
    print(json.dumps({"destructive_action": False, "candidates": candidates}, ensure_ascii=False, indent=2))
    return 0


def command_quarantine(args: argparse.Namespace) -> int:
    if args.approve != f"quarantine:{args.model_id}":
        raise ValueError(f"--approve must be quarantine:{args.model_id}")
    with state_lock(args.registry, args.deployment):
        registry = load_json(args.registry)
        deployment = load_json(args.deployment)
        candidates = {item["model_id"]: item for item in retention_candidates(registry, deployment)}
        if args.model_id not in candidates:
            raise ValueError("model is retained, deployed, already quarantined or not archived")
        model = registry_models(registry)[args.model_id]
        artifact = model["artifact"]
        source = repo_path(artifact["path"])
        quarantine_relative = Path("model/artifacts/quarantine") / args.model_id / source.name
        destination = repo_path(quarantine_relative.as_posix())
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            raise ValueError(f"quarantine destination already exists: {quarantine_relative}")
        original_relative = artifact["path"]
        source.replace(destination)
        try:
            artifact["original_path"] = original_relative
            artifact["path"] = quarantine_relative.as_posix()
            model["stage"] = "quarantined"
            model["quarantined_at"] = now_iso()
            registry["updated_at"] = model["quarantined_at"]
            backup = atomic_write_json(args.registry, registry)
        except Exception:
            destination.replace(source)
            raise
        print(f"quarantined {args.model_id}; backup={backup}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY)
    parser.add_argument("--deployment", type=Path, default=DEFAULT_DEPLOYMENT)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("verify").set_defaults(func=command_verify)
    subparsers.add_parser("verify-runtime").set_defaults(func=command_verify_runtime)
    subparsers.add_parser("retention-plan").set_defaults(func=command_retention_plan)

    promote = subparsers.add_parser("promote")
    promote.add_argument("--target", required=True)
    promote.add_argument("--model-id", required=True)
    promote.add_argument("--approve", required=True)
    promote.set_defaults(func=command_promote)

    rollback = subparsers.add_parser("rollback")
    rollback.add_argument("--target", required=True)
    rollback.add_argument("--approve", required=True)
    rollback.set_defaults(func=command_rollback)

    quarantine = subparsers.add_parser("quarantine")
    quarantine.add_argument("--model-id", required=True)
    quarantine.add_argument("--approve", required=True)
    quarantine.set_defaults(func=command_quarantine)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        return int(args.func(args))
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
