#!/usr/bin/env bash
set -euo pipefail

# CPU-only health/readiness smoke for the selected Stage1 reviewed YOLO26s candidate.
# This checks environment path wiring and /detect/v2 health readiness only.
# It must not POST to /detect/v2, load YOLO weights, or run train/val/predict.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

export DETECT_V2_MODE="${DETECT_V2_MODE:-yolo}"
export DETECT_V2_CUSTOM_TACTILE_MODEL_PATH="${DETECT_V2_CUSTOM_TACTILE_MODEL_PATH:-${REPO_ROOT}/runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt}"
export DETECT_V2_COCO_MODEL_PATH="${DETECT_V2_COCO_MODEL_PATH:-${REPO_ROOT}/model/artifacts/pretrained/yolo26n.pt}"
export DETECT_V2_RUNTIME_CONFIG_PATH="${DETECT_V2_RUNTIME_CONFIG_PATH:-${REPO_ROOT}/configs/walksafe_two_model_runtime_stage1_mvp_20260523.json}"

CUDA_VISIBLE_DEVICES="" PYTHONPATH="${REPO_ROOT}" python3 - <<'PY'
import json
from pathlib import Path

from backend.app.config import Settings
from backend.app.services.detect_v2 import detect_v2_health, load_detect_v2_runtime_config

settings = Settings()
health = detect_v2_health(settings)
print(json.dumps(health, ensure_ascii=False, indent=2))

expected_paths = [
    settings.detect_v2_custom_tactile_model_path,
    settings.detect_v2_coco_model_path,
    settings.detect_v2_runtime_config_path,
]
missing = [str(path) for path in expected_paths if path is None or not Path(path).is_file()]
if missing:
    raise SystemExit(f"missing configured files: {missing}")

config = load_detect_v2_runtime_config(settings.detect_v2_runtime_config_path)
if health["mode"] not in {"yolo", "real"}:
    raise SystemExit(f"unexpected mode: {health['mode']}")
if health["status"] != "ready" or health["reason"] is not None:
    raise SystemExit(f"detect.v2 health is not ready: {health}")

thresholds = config["models"]["custom_tactile"]["thresholds"]
print(
    "stage1 candidate health ready; thresholds="
    + json.dumps(thresholds, ensure_ascii=False, sort_keys=True)
)
PY
