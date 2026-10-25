from __future__ import annotations

import hashlib
import json
from pathlib import Path

from scripts.manage_local_model_registry import verify_runtime_state


ROOT = Path(__file__).resolve().parents[1]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def test_historical_backend_and_deployment_model_identity_is_preserved() -> None:
    registry = json.loads((ROOT / "model/registry/walksafe-model-registry.json").read_text(encoding="utf-8"))
    deployment = json.loads((ROOT / "model/deployments/local-deployment.json").read_text(encoding="utf-8"))
    backend = json.loads((ROOT / "configs/walksafe_unified_epoch270_field_20260711.json").read_text(encoding="utf-8"))
    active_id = deployment["targets"]["backend"]["active_model_id"]
    model = next(model for model in registry["models"] if model["model_id"] == active_id)
    model_id = model["model_id"]
    pt = ROOT / model["artifact"]["path"]
    exported = model["exports"]["android_tflite"]
    tflite = ROOT / exported["path"]
    backend_model = backend["models"]["unified_walksafe"]

    assert deployment["targets"]["backend"]["active_model_id"] == model_id
    assert deployment["targets"]["android"]["active_model_id"] == model_id
    assert deployment["targets"]["backend"]["resolved_repository_artifact"] == model["artifact"]["path"]
    assert deployment["targets"]["android"]["model_artifact"] == exported["path"]

    assert sha256(pt) == model["artifact"]["sha256"] == backend_model["artifact_sha256"]
    assert sha256(tflite) == exported["sha256"]
    assert exported["input_shape"] == [1, 768, 768, 3]
    assert exported["output_shape"] == [1, 300, 6]
    assert len(backend_model["classes"]) == 13


def test_current_android_model_identity_matches_observed_registry_binding() -> None:
    registry = json.loads((ROOT / "model/registry/walksafe-model-registry.json").read_text())
    deployment = json.loads((ROOT / "model/deployments/local-deployment.json").read_text())
    android = json.loads(
        (ROOT / "apps/android/app/src/main/assets/model-config/two_model_runtime.json").read_text()
    )
    result = verify_runtime_state(registry, deployment)
    assert result["ok"], result["issues"]
    observed = result["observed_runtime"]["android"]
    model = next(model for model in registry["models"] if model["model_id"] == observed["model_id"])
    exported = model["exports"]["android_tflite"]
    android_model = android["models"][android["primary_model"]]
    assert observed["verified"] is True
    assert result["historical_manifest"]["android_matches_observed_runtime"] is False
    assert observed["model_id"] != deployment["targets"]["android"]["active_model_id"]
    assert observed["artifact_path"] == exported["path"]
    assert sha256(ROOT / exported["path"]) == exported["sha256"] == android_model["artifact_sha256"]
    assert sha256(ROOT / model["artifact"]["path"]) == model["artifact"]["sha256"]
    assert android_model["source_model"] == model["artifact"]["path"]
    assert android_model["source_model_sha256"] == model["artifact"]["sha256"]
    assert android_model["input_size"] == 768
    assert android_model["export"]["input_shape"] == exported["input_shape"]
    assert android_model["export"]["output_shape"] == exported["output_shape"]
    assert android_model["classes"] == exported["classes"]
    assert len(android_model["classes"]) == 21
    assert observed["tensors"]["input_shape"] == exported["input_shape"]
    assert observed["tensors"]["output_shape"] == exported["output_shape"]
    assert model["deployment_eligible"] is False
