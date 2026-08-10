from __future__ import annotations

import json
import subprocess
import sys
from copy import deepcopy
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/manage_local_model_registry.py"
REGISTRY = ROOT / "model/registry/walksafe-model-registry.json"
DEPLOYMENT = ROOT / "model/deployments/local-deployment.json"
CONTENT_MANIFEST = ROOT / "tests/fixtures/model_registry_dataset/manifest.csv"
TRAINING_RESULTS = ROOT / "tests/fixtures/model_registry_dataset/training-results.csv"


def run_tool(registry: Path, deployment: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--registry",
            str(registry),
            "--deployment",
            str(deployment),
            *args,
        ],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
    )


def test_current_runtime_registry_hashes_verify() -> None:
    result = run_tool(REGISTRY, DEPLOYMENT, "verify-runtime")
    assert result.returncode == 0, result.stdout + result.stderr
    assert json.loads(result.stdout)["ok"] is True


def test_runtime_verify_does_not_require_ignored_training_inputs(tmp_path: Path) -> None:
    registry_data = json.loads(REGISTRY.read_text(encoding="utf-8"))
    registry_data["models"][0]["dataset"]["manifest_path"] = "ignored/not-packaged-dataset.csv"
    registry_data["models"][0]["training"]["results_path"] = "ignored/not-packaged-training.csv"
    registry = tmp_path / "registry.json"
    deployment = tmp_path / "deployment.json"
    registry.write_text(json.dumps(registry_data), encoding="utf-8")
    deployment.write_text(DEPLOYMENT.read_text(encoding="utf-8"), encoding="utf-8")

    result = run_tool(registry, deployment, "verify-runtime")

    assert result.returncode == 0, result.stdout + result.stderr
    assert json.loads(result.stdout)["scope"] == "runtime"


def test_android_768_export_is_bound_to_registry_and_runtime_config() -> None:
    registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    model = registry["models"][0]
    exported = model["exports"]["android_tflite"]
    runtime = json.loads(
        (ROOT / "apps/android/app/src/main/assets/model-config/two_model_runtime.json").read_text(encoding="utf-8")
    )["models"]["unified_walksafe"]

    assert exported["input_shape"] == [1, 768, 768, 3]
    assert exported["output_shape"] == [1, 300, 6]
    assert runtime["input_size"] == 768
    assert runtime["artifact_sha256"] == exported["sha256"]
    assert runtime["source_model_sha256"] == model["artifact"]["sha256"]


def test_manifest_promote_and_rollback_do_not_change_runtime(tmp_path: Path) -> None:
    registry_data = json.loads(REGISTRY.read_text(encoding="utf-8"))
    first = registry_data["models"][0]
    first["deployment_eligible"] = True
    first["blockers"] = []
    first["dataset"] = {
        "manifest_path": str(CONTENT_MANIFEST.relative_to(ROOT)),
        "manifest_sha256": __import__("hashlib").sha256(CONTENT_MANIFEST.read_bytes()).hexdigest(),
        "content_hash_policy": "sha256_per_image_and_label",
    }
    first["training"]["results_path"] = str(TRAINING_RESULTS.relative_to(ROOT))
    first["training"]["results_sha256"] = __import__("hashlib").sha256(
        TRAINING_RESULTS.read_bytes()
    ).hexdigest()
    second = deepcopy(first)
    second["model_id"] = "walksafe-test-second"
    registry_data["models"].append(second)

    registry = tmp_path / "registry.json"
    deployment = tmp_path / "deployment.json"
    registry.write_text(json.dumps(registry_data), encoding="utf-8")
    deployment.write_text(DEPLOYMENT.read_text(encoding="utf-8"), encoding="utf-8")

    first_id = first["model_id"]
    promote_first = run_tool(registry, deployment, "promote", "--target", "backend", "--model-id", first_id, "--approve", first_id)
    assert promote_first.returncode == 0, promote_first.stdout + promote_first.stderr
    promote_second = run_tool(
        registry,
        deployment,
        "promote",
        "--target",
        "backend",
        "--model-id",
        second["model_id"],
        "--approve",
        second["model_id"],
    )
    assert promote_second.returncode == 0, promote_second.stdout + promote_second.stderr
    promoted = json.loads(deployment.read_text(encoding="utf-8"))
    assert promoted["targets"]["backend"]["active_model_id"] == second["model_id"]
    assert promoted["targets"]["backend"]["previous_model_id"] == first_id
    assert promoted["targets"]["backend"]["runtime_state"] == "manifest_promoted_runtime_not_changed"

    rollback = run_tool(registry, deployment, "rollback", "--target", "backend", "--approve", "rollback:backend")
    assert rollback.returncode == 0, rollback.stdout + rollback.stderr
    rolled_back = json.loads(deployment.read_text(encoding="utf-8"))
    assert rolled_back["targets"]["backend"]["active_model_id"] == first_id
    assert rolled_back["targets"]["backend"]["previous_model_id"] == second["model_id"]
    assert rolled_back["targets"]["backend"]["runtime_state"] == "manifest_rolled_back_runtime_not_changed"


def test_candidate_cannot_be_promoted_and_retention_defaults_to_no_action(tmp_path: Path) -> None:
    registry = tmp_path / "registry.json"
    deployment = tmp_path / "deployment.json"
    registry_data = json.loads(REGISTRY.read_text(encoding="utf-8"))
    model = registry_data["models"][0]
    model["dataset"]["manifest_path"] = str(CONTENT_MANIFEST.relative_to(ROOT))
    model["dataset"]["manifest_sha256"] = __import__("hashlib").sha256(
        CONTENT_MANIFEST.read_bytes()
    ).hexdigest()
    model["training"]["results_path"] = str(TRAINING_RESULTS.relative_to(ROOT))
    model["training"]["results_sha256"] = __import__("hashlib").sha256(
        TRAINING_RESULTS.read_bytes()
    ).hexdigest()
    registry.write_text(json.dumps(registry_data), encoding="utf-8")
    deployment.write_text(DEPLOYMENT.read_text(encoding="utf-8"), encoding="utf-8")
    model_id = json.loads(registry.read_text(encoding="utf-8"))["models"][0]["model_id"]

    blocked = run_tool(registry, deployment, "promote", "--target", "backend", "--model-id", model_id, "--approve", model_id)
    assert blocked.returncode == 2
    assert "not deployment eligible" in blocked.stdout

    retention = run_tool(registry, deployment, "retention-plan")
    assert retention.returncode == 0, retention.stdout + retention.stderr
    payload = json.loads(retention.stdout)
    assert payload["destructive_action"] is False
    assert payload["candidates"] == []


def test_manifest_only_dataset_cannot_be_marked_deployment_eligible(tmp_path: Path) -> None:
    registry_data = json.loads(REGISTRY.read_text(encoding="utf-8"))
    registry_data["models"][0]["deployment_eligible"] = True
    registry_data["models"][0]["blockers"] = []
    registry = tmp_path / "registry.json"
    deployment = tmp_path / "deployment.json"
    registry.write_text(json.dumps(registry_data), encoding="utf-8")
    deployment.write_text(DEPLOYMENT.read_text(encoding="utf-8"), encoding="utf-8")

    result = run_tool(registry, deployment, "verify")
    assert result.returncode == 1
    assert "per-image and per-label SHA-256" in result.stdout
