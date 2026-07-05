#!/usr/bin/env bash
set -euo pipefail

cd /home/ddobagi/Code/hanium-dreamup

RUN_DATE=20260521
DATA=/home/ddobagi/Code/hanium-dreamup/datasets/walksafe_kr_tactile_3class_20260521/data.yaml
PROJECT=/home/ddobagi/Code/hanium-dreamup/runs/detect
VAL_PROJECT=/home/ddobagi/Code/hanium-dreamup/runs/validation

BASE_NAME=walksafe_tactile3_yolo26s_img960_musgd_e200_${RUN_DATE}
FT_NAME=walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_${RUN_DATE}
BASE_LAST=${PROJECT}/${BASE_NAME}/weights/last.pt
BASE_BEST=${PROJECT}/${BASE_NAME}/weights/best.pt
FT_BEST=${PROJECT}/${FT_NAME}/weights/best.pt

trap 'echo "===== TACTILE3 YOLO26S RESUME FAILED line=${LINENO} $(date +"%F %T %Z") ====="' ERR

echo "===== TACTILE3 YOLO26S RESUME PIPELINE START $(date '+%F %T %Z') ====="
echo "DATA=${DATA}"
echo "BASE_NAME=${BASE_NAME}"
echo "FT_NAME=${FT_NAME}"
echo "BASE_LAST=${BASE_LAST}"

if [[ ! -f "${BASE_LAST}" ]]; then
  echo "Missing checkpoint: ${BASE_LAST}" >&2
  exit 1
fi

# Continue the interrupted 960px base training from last.pt.
# workers=4 is used for dataloader stability only; model/training hyperparameters are restored from the checkpoint resume state.
.venv/bin/yolo detect train \
  model="${BASE_LAST}" \
  resume=True \
  workers=4

if [[ ! -f "${BASE_BEST}" ]]; then
  echo "Missing base best checkpoint after resume: ${BASE_BEST}" >&2
  exit 1
fi

echo "===== BASE TEST VAL START $(date '+%F %T %Z') ====="
.venv/bin/yolo detect val \
  model="${BASE_BEST}" \
  data="${DATA}" \
  split=test \
  imgsz=960 \
  batch=8 \
  device=0 \
  workers=4 \
  plots=True \
  project="${VAL_PROJECT}" \
  name="${BASE_NAME}_test"

echo "===== HIGH-RES FINETUNE START $(date '+%F %T %Z') ====="
.venv/bin/yolo detect train \
  model="${BASE_BEST}" \
  data="${DATA}" \
  epochs=80 \
  patience=25 \
  imgsz=1280 \
  batch=4 \
  device=0 \
  workers=4 \
  optimizer=MuSGD \
  lr0=0.00008 \
  lrf=0.2 \
  momentum=0.948 \
  weight_decay=0.00027 \
  warmup_epochs=1.0 \
  cos_lr=True \
  close_mosaic=0 \
  mosaic=0.0 \
  mixup=0.0 \
  copy_paste=0.0 \
  scale=0.2 \
  translate=0.05 \
  fliplr=0.5 \
  erasing=0.0 \
  cache=False \
  plots=True \
  save_period=20 \
  project="${PROJECT}" \
  name="${FT_NAME}"

if [[ ! -f "${FT_BEST}" ]]; then
  echo "Missing finetune best checkpoint: ${FT_BEST}" >&2
  exit 1
fi

echo "===== HIGH-RES TEST VAL START $(date '+%F %T %Z') ====="
.venv/bin/yolo detect val \
  model="${FT_BEST}" \
  data="${DATA}" \
  split=test \
  imgsz=1280 \
  batch=4 \
  device=0 \
  workers=4 \
  plots=True \
  project="${VAL_PROJECT}" \
  name="${FT_NAME}_test"

echo "===== TACTILE3 YOLO26S RESUME PIPELINE DONE $(date '+%F %T %Z') ====="
