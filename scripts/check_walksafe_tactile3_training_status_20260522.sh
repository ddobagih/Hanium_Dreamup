#!/usr/bin/env bash
set -euo pipefail

cd /home/ddobagi/Code/hanium-dreamup

RUN_DIR="runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521"
RESUME_LOG="logs/walksafe_tactile3_yolo26s_resume_pipeline_20260522.log"
ORIGINAL_LOG="logs/walksafe_tactile3_yolo26s_pipeline_20260521.log"
RESULTS_CSV="${RUN_DIR}/results.csv"

echo "NOW_KST=$(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S %Z')"
echo

echo "== process =="
pgrep -af "resume_walksafe_tactile3_yolo26s_20260522|run_walksafe_tactile3_yolo26s_20260521|\.venv/bin/yolo detect (train|val)" | grep -v pgrep || true
echo

echo "== gpu =="
if command -v nvidia-smi >/dev/null 2>&1; then
  nvidia-smi --query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu --format=csv,noheader,nounits || true
else
  echo "nvidia-smi not found"
fi
echo

echo "== disk =="
df -h /home/ddobagi/Code/hanium-dreamup | tail -1
echo

echo "== results =="
python3 - <<'PY'
from pathlib import Path
import csv
import subprocess

results = Path('runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/results.csv')
if not results.exists():
    print('results.csv missing')
    raise SystemExit(0)

rows = list(csv.DictReader(results.open()))
print('completed_epochs', len(rows))
print('results_mtime', subprocess.getoutput("TZ=Asia/Seoul stat -c '%y' " + str(results)))
if not rows:
    raise SystemExit(0)

def get(row, name):
    for key, value in row.items():
        if key.strip() == name:
            return value
    return ''

def number(row, name):
    try:
        return float(get(row, name))
    except ValueError:
        return float('-inf')

last = rows[-1]
print('last_epoch', get(last, 'epoch'))
if get(last, 'metrics/mAP50-95(B)'):
    best = max(rows, key=lambda row: number(row, 'metrics/mAP50-95(B)'))
    print('last_metrics',
          'P', f"{number(last, 'metrics/precision(B)'):.4f}",
          'R', f"{number(last, 'metrics/recall(B)'):.4f}",
          'mAP50', f"{number(last, 'metrics/mAP50(B)'):.4f}",
          'mAP50-95', f"{number(last, 'metrics/mAP50-95(B)'):.4f}")
    print('best_mAP50-95',
          'epoch', get(best, 'epoch'),
          'mAP50', f"{number(best, 'metrics/mAP50(B)'):.4f}",
          'mAP50-95', f"{number(best, 'metrics/mAP50-95(B)'):.4f}")
PY
echo

echo "== latest log =="
LOG="${RESUME_LOG}"
if [[ ! -f "${LOG}" ]]; then
  LOG="${ORIGINAL_LOG}"
fi
if [[ -f "${LOG}" ]]; then
  echo "log=${LOG}"
  TZ=Asia/Seoul stat -c 'log_mtime=%y' "${LOG}"
  python3 - "${LOG}" <<'PY'
from pathlib import Path
import re
import sys

log = Path(sys.argv[1])
data = log.read_bytes()[-300000:].decode('utf-8', 'ignore')
clean = re.sub(r'\x1b\[[0-9;?]*[A-Za-z]', '', data).replace('\r', '\n')
lines = [line for line in clean.splitlines() if line.strip()]
for line in lines[-8:]:
    print(line[:240])
PY
else
  echo "no log file"
fi
