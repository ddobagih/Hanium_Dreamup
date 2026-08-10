from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
def test_768_model_identity_is_shared_by_backend_android_and_deployment_state() -> None:
    registry = json.loads((ROOT / "model/registry/walksafe-model-registry.json").read_text(encoding="utf-8"))
    deployment = json.loads((ROOT / "model/deployments/local-deployment.json").read_text(encoding="utf-8"))
    backend = json.loads((ROOT / "configs/walksafe_unified_epoch270_field_20260711.json").read_text(encoding="utf-8"))
    android = json.loads(
        (ROOT / "apps/android/app/src/main/assets/model-config/two_model_runtime.json").read_text(encoding="utf-8")
    )

    model = registry["models"][0]
    model_id = model["model_id"]
    pt = ROOT / model["artifact"]["path"]
    exported = model["exports"]["android_tflite"]
    tflite = ROOT / exported["path"]
    backend_model = backend["models"]["unified_walksafe"]
    android_model = android["models"]["unified_walksafe"]

    assert deployment["targets"]["backend"]["active_model_id"] == model_id
    assert deployment["targets"]["android"]["active_model_id"] == model_id
    assert deployment["targets"]["backend"]["resolved_repository_artifact"] == model["artifact"]["path"]
    assert deployment["targets"]["android"]["model_artifact"] == exported["path"]

    assert sha256(pt) == model["artifact"]["sha256"] == backend_model["artifact_sha256"]
    assert sha256(tflite) == exported["sha256"] == android_model["artifact_sha256"]
    assert android_model["source_model"] == model["artifact"]["path"]
    assert android_model["source_model_sha256"] == model["artifact"]["sha256"]
    assert android_model["input_size"] == 768
    assert android_model["export"]["input_shape"] == exported["input_shape"]
    assert android_model["export"]["output_shape"] == exported["output_shape"]
    assert android_model["classes"] == backend_model["classes"]
