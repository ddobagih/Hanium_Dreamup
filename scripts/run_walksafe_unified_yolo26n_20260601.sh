#!/usr/bin/env bash
set -euo pipefail

# Train the first-pass unified detector:
# - YOLO26n 640
# - existing WalkSafe COCO general-object allowlist, not person-only
# - AIHub tactile classes appended
#
# This script does not download data. Build/validate the dataset first:
#   python data_sources/scripts/build_walksafe_unified_coco_tactile.py ...
#   python data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_unified_coco_aihub513_13cls_20260602/data.yaml

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA="${DATA:-${REPO_ROOT}/datasets/walksafe_unified_coco_aihub513_13cls_20260602/data.yaml}"
MODEL="${MODEL:-yolo26n.pt}"
EPOCHS="${EPOCHS:-100}"
BATCH="${BATCH:-8}"
IMGSZ="${IMGSZ:-640}"
NAME="${NAME:-walksafe_unified_yolo26n_640_13cls_20260602}"
DEVICE="${DEVICE:-}"
PRINT_ONLY=0
if [[ "${1:-}" == "--print-only" ]]; then
  PRINT_ONLY=1
  shift
fi

cmd=(
  python "${REPO_ROOT}/model/train_yolo.py"
  --data "${DATA}"
  --model "${MODEL}"
  --epochs "${EPOCHS}"
  --imgsz "${IMGSZ}"
  --batch "${BATCH}"
  --project "runs/detect"
  --name "${NAME}"
)

if [[ -n "${DEVICE}" ]]; then
  cmd+=(--device "${DEVICE}")
fi

cmd+=("$@")

printf 'Unified YOLO26n training command:\n'
printf '  %q' "${cmd[@]}"
printf '\n'
if [[ "${PRINT_ONLY}" == "1" ]]; then
  exit 0
fi
exec "${cmd[@]}"
