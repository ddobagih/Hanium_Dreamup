from __future__ import annotations

import json
import hashlib
import shutil
import subprocess
import sys
from copy import deepcopy
from pathlib import Path

import pytest

from scripts import manage_local_model_registry as model_registry


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/manage_local_model_registry.py"
REGISTRY = ROOT / "model/registry/walksafe-model-registry.json"
DEPLOYMENT = ROOT / "model/deployments/local-deployment.json"
CONTENT_MANIFEST = ROOT / "tests/fixtures/model_registry_dataset/manifest.csv"
TRAINING_RESULTS = ROOT / "tests/fixtures/model_registry_dataset/training-results.csv"
PERSON_SUPPLEMENT = ROOT / "apps/android/app/src/test/resources/inference/person-supplement.json"


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
    payload = json.loads(result.stdout)
    assert payload["ok"] is True
    assert payload["observed_runtime"]["android"]["verified"] is True
    assert payload["historical_manifest"]["android_matches_observed_runtime"] is False
    assert payload["observed_runtime"]["backend"]["verified"] is False


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
    result = model_registry.verify_runtime_state(registry, json.loads(DEPLOYMENT.read_text()))
    assert result["ok"], result["issues"]
    observed = result["observed_runtime"]["android"]
    model = next(model for model in registry["models"] if model["model_id"] == observed["model_id"])
    exported = model["exports"]["android_tflite"]
    runtime = json.loads(
        (ROOT / "apps/android/app/src/main/assets/model-config/two_model_runtime.json").read_text(encoding="utf-8")
    )["models"]["unified_walksafe"]

    assert exported["input_shape"] == [1, 768, 768, 3]
    assert exported["output_shape"] == [1, 25, 12096]
    assert observed["tensors"]["output_shape"] == exported["output_shape"]
    assert runtime["input_size"] == 768
    assert runtime["artifact_sha256"] == exported["sha256"]
    assert runtime["source_model_sha256"] == model["artifact"]["sha256"]
    assert runtime["classes"] == exported["classes"]
    assert model["stage"] == "candidate"
    assert model["deployment_eligible"] is False


def test_manifest_promote_and_rollback_do_not_change_runtime(tmp_path: Path) -> None:
    registry_data = json.loads(REGISTRY.read_text(encoding="utf-8"))
    # A self-contained promotion fixture; observed candidates have no training evidence.
    registry_data["models"] = registry_data["models"][:1]
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
    registry_data["models"] = registry_data["models"][:1]
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


def test_historical_manifest_alone_cannot_verify_current_android(tmp_path: Path) -> None:
    registry = json.loads(REGISTRY.read_text())
    registry["models"] = registry["models"][:1]
    path = tmp_path / "historical-registry.json"
    path.write_text(json.dumps(registry))
    result = run_tool(path, DEPLOYMENT, "verify-runtime")
    payload = json.loads(result.stdout)
    assert result.returncode == 1
    assert payload["historical_manifest"]["artifacts_verified"] is True
    assert payload["observed_runtime"]["android"]["verified"] is False
    assert "must match exactly one registered model" in result.stdout


def test_observed_candidate_cannot_be_promoted(tmp_path: Path) -> None:
    registry = json.loads(REGISTRY.read_text())
    candidate = registry["models"][-1]
    assert candidate["deployment_eligible"] is False
    path = tmp_path / "deployment.json"
    original = DEPLOYMENT.read_bytes()
    path.write_bytes(original)
    result = run_tool(REGISTRY, path, "promote", "--target", "android", "--model-id",
                      candidate["model_id"], "--approve", candidate["model_id"])
    assert result.returncode == 2
    assert "not deployment eligible" in result.stdout
    assert path.read_bytes() == original


@pytest.fixture
def android_runtime_copy(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    registry = json.loads(REGISTRY.read_text())
    runtime_path = tmp_path / model_registry.ANDROID_RUNTIME_CONFIG
    runtime_path.parent.mkdir(parents=True)
    runtime = json.loads((ROOT / model_registry.ANDROID_RUNTIME_CONFIG).read_text())
    selected = runtime["models"]["unified_walksafe"]
    asset_relative = f"{model_registry.ANDROID_ASSETS}/{selected['asset']}"
    model = next(row for row in registry["models"] if row["exports"]["android_tflite"]["path"] == asset_relative)
    registry["models"] = [model]
    for relative in (asset_relative, model["artifact"]["path"]):
        destination = tmp_path / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / relative, destination)
    runtime_path.write_text(json.dumps(runtime))
    monkeypatch.setattr(model_registry, "REPO_ROOT", tmp_path)
    deployment = {"schema_version": "walksafe.local_deployment.v1", "targets": {
        "android": {"active_model_id": model["model_id"], "previous_model_id": None}}}
    return registry, deployment, runtime, runtime_path


@pytest.mark.parametrize("mutation, expected", [
    ("unregistered", "exactly one registered model"),
    ("asset_bytes", "sha256 mismatch"),
    ("source_bytes", "sha256 mismatch"),
    ("configured_hash", "artifact_sha256"),
    ("configured_source_hash", "source_model_sha256"),
    ("configured_source_path", "source_model"),
    ("output_shape", "output_shape"),
    ("joint_shape", "actual asset tensor"),
    ("class_order", "classes"),
    ("joint_class_order", "artifact metadata order"),
    ("input_dtype", "input_dtype"),
    ("malformed_tensor", "TFL3"),
    ("missing_fallback", "unregistered fallback"),
    ("legacy_fallback", "unregistered fallback"),
    ("person_supplement", "unregistered person_supplement"),
    ("default_person_supplement", "unregistered person_supplement"),
])
def test_runtime_binding_rejects_tampering(android_runtime_copy, mutation: str, expected: str) -> None:
    registry, deployment, runtime, runtime_path = android_runtime_copy
    assert model_registry.verify_runtime_state(registry, deployment)["ok"]
    selected = runtime["models"]["unified_walksafe"]
    model = registry["models"][0]
    exported = model["exports"]["android_tflite"]
    if mutation == "unregistered":
        exported["path"] = exported["path"].replace(".tflite", "-different.tflite")
    elif mutation in ("asset_bytes", "source_bytes"):
        path = model_registry.repo_path(exported["path"] if mutation == "asset_bytes" else model["artifact"]["path"])
        with path.open("ab") as stream:
            stream.write(b"tampered")
    elif mutation == "configured_hash":
        selected["artifact_sha256"] = "0" * 64
    elif mutation == "configured_source_hash":
        selected["source_model_sha256"] = "0" * 64
    elif mutation == "configured_source_path":
        selected["source_model"] = "model/different.pt"
    elif mutation in ("output_shape", "joint_shape"):
        selected["export"]["output_shape"] = [1, 25, 100]
        if mutation == "joint_shape":
            exported["output_shape"] = [1, 25, 100]
    elif mutation in ("class_order", "joint_class_order"):
        selected["classes"] = list(reversed(selected["classes"]))
        if mutation == "joint_class_order":
            exported["classes"] = selected["classes"]
    elif mutation == "input_dtype":
        selected["export"]["input_dtype"] = "int8"
    elif mutation == "malformed_tensor":
        path = model_registry.repo_path(exported["path"])
        path.write_bytes(b"not-a-flatbuffer")
        exported["sha256"] = selected["artifact_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    elif mutation == "missing_fallback":
        runtime.pop("fallback_model")
    elif mutation == "legacy_fallback":
        runtime["fallback_model"] = "legacy_two_model"
    elif mutation in ("person_supplement", "default_person_supplement"):
        supplement = json.loads(PERSON_SUPPLEMENT.read_text())
        if mutation == "person_supplement":
            supplement["enabled"] = True
        else:
            supplement.pop("enabled", None)
        runtime["models"]["person_supplement"] = supplement
    runtime_path.write_text(json.dumps(runtime))
    result = model_registry.verify_runtime_state(registry, deployment)
    assert result["ok"] is False
    assert result["observed_runtime"]["android"]["verified"] is False
    assert expected in " ".join(result["issues"])


def test_disabled_person_supplement_is_not_selected(android_runtime_copy) -> None:
    registry, deployment, runtime, runtime_path = android_runtime_copy
    supplement = json.loads(PERSON_SUPPLEMENT.read_text())
    supplement["enabled"] = False
    runtime["models"]["person_supplement"] = supplement
    runtime_path.write_text(json.dumps(runtime))
    assert model_registry.verify_runtime_state(registry, deployment)["ok"]
