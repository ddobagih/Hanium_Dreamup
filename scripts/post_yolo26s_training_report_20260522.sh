#!/usr/bin/env bash
set -euo pipefail

# CPU-only wrapper: calls the results.csv summary parser only.
# It does not run YOLO train/val/predict, import torch, or load model weights.

cd /home/ddobagi/Code/hanium-dreamup

SUMMARY_SCRIPT="scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py"
OUT_DIR="${OUT_DIR:-runs/reports/walksafe_tactile3_yolo26s_20260522}"
INCLUDE_LOG_TAIL="${INCLUDE_LOG_TAIL:-20}"

STAGE1_RESULTS="runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/results.csv"
STAGE2_RESULTS="runs/detect/walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_20260521/results.csv"

mkdir -p "${OUT_DIR}"

missing=0

write_summary() {
  local label="$1"
  local results_csv="$2"

  if [[ ! -f "${results_csv}" ]]; then
    echo "Missing ${label} results.csv: ${results_csv}" >&2
    missing=1
    return
  fi

  python3 "${SUMMARY_SCRIPT}" \
    --results-csv "${results_csv}" \
    --include-log-tail "${INCLUDE_LOG_TAIL}" \
    --out-json "${OUT_DIR}/${label}_summary.json" \
    --out-md "${OUT_DIR}/${label}_summary.md"
}

write_summary "stage1" "${STAGE1_RESULTS}"
write_summary "stage2" "${STAGE2_RESULTS}"

if [[ "${missing}" -ne 0 ]]; then
  echo "One or more expected results.csv files are missing; pipeline may be incomplete." >&2
  exit 1
fi

echo "Wrote CPU-only summaries to ${OUT_DIR}"
