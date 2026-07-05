#!/usr/bin/env bash
set -euo pipefail

# CPU-only wrapper: generates a Stage1/Stage2 report from existing files only.
# Do not add yolo train/val/predict here; this must remain safe to run while GPU work is active.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}"

SUMMARY_SCRIPT="scripts/summarize_walksafe_tactile3_reviewed_yolo26s_run_20260523.py"
OUT_DIR="${OUT_DIR:-runs/reports/walksafe_tactile3_reviewed_yolo26s_20260523}"
INCLUDE_LOG_TAIL="${INCLUDE_LOG_TAIL:-40}"

mkdir -p "${OUT_DIR}"

CUDA_VISIBLE_DEVICES="" python3 "${SUMMARY_SCRIPT}" \
  --include-log-tail "${INCLUDE_LOG_TAIL}" \
  --out-json "${OUT_DIR}/reviewed_yolo26s_stage1_stage2_summary.json" \
  --out-md "${OUT_DIR}/reviewed_yolo26s_stage1_stage2_summary.md"

printf 'Wrote CPU-only reviewed YOLO26s report to %s\n' "${OUT_DIR}"
