#!/usr/bin/env bash
set -euo pipefail

# Current mobile-first WalkSafe 13-class training runner for the verified AIHub183 PNG dataset.
# Default target is YOLO26n at 768 input for Galaxy S26-class Android deployment.
# This script only starts training when called without --print-only.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA="${DATA:-${REPO_ROOT}/datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml}"
MODEL="${MODEL:-${REPO_ROOT}/model/artifacts/pretrained/yolo26n.pt}"
EPOCHS="${EPOCHS:-100}"
BATCH="${BATCH:-16}"
IMGSZ="${IMGSZ:-768}"
DEVICE="${DEVICE:-0}"
WORKERS="${WORKERS:-8}"
PROJECT="${PROJECT:-${REPO_ROOT}/runs/detect}"
NAME="${NAME:-walksafe_unified_aihub183_png_yolo26n_img768_e100_20260627}"
TMPDIR="${TMPDIR:-${REPO_ROOT}/tmp/train_tmp}"
YOLO_BIN="${YOLO_BIN:-${REPO_ROOT}/.venv/bin/yolo}"
PRINT_ONLY=0

if [[ "${1:-}" == "--print-only" ]]; then
  PRINT_ONLY=1
  shift
fi

if [[ ! -x "${YOLO_BIN}" ]]; then
  YOLO_BIN="$(command -v yolo)"
fi

mkdir -p "${TMPDIR}"

cmd=(
  "${YOLO_BIN}" detect train
  data="${DATA}"
  model="${MODEL}"
  epochs="${EPOCHS}"
  imgsz="${IMGSZ}"
  batch="${BATCH}"
  device="${DEVICE}"
  workers="${WORKERS}"
  project="${PROJECT}"
  name="${NAME}"
  cache=False
  amp=True
  cos_lr=True
  close_mosaic=10
  patience=30
  save_period=-1
  plots=True
  exist_ok=False
)

# Optional smoke/calibration knobs, e.g. FRACTION=0.02 EPOCHS=1 BATCH=16.
if [[ -n "${FRACTION:-}" ]]; then
  cmd+=(fraction="${FRACTION}")
fi

cmd+=("$@")

printf 'WalkSafe unified AIHub183 PNG mobile-first training command:\n'
printf '  TMPDIR=%q' "${TMPDIR}"
printf ' %q' "${cmd[@]}"
printf '\n'

if [[ "${PRINT_ONLY}" == "1" ]]; then
  exit 0
fi

export TMPDIR
exec "${cmd[@]}"
