#!/usr/bin/env bash
set -euo pipefail

cd /home/ddobagi/Code/hanium-dreamup

RUN_DATE=20260522
DATA=/home/ddobagi/Code/hanium-dreamup/datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/data.yaml
PROJECT=/home/ddobagi/Code/hanium-dreamup/runs/detect
VAL_PROJECT=/home/ddobagi/Code/hanium-dreamup/runs/validation

BASE_NAME=walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_${RUN_DATE}
FT_NAME=walksafe_tactile3_reviewed_yolo26s_img1280_ft80_nomosaic_${RUN_DATE}
BASE_BEST=${PROJECT}/${BASE_NAME}/weights/best.pt
FT_BEST=${PROJECT}/${FT_NAME}/weights/best.pt

trap 'echo "===== REVIEWED TACTILE3 YOLO26S PIPELINE FAILED line=${LINENO} $(date +"%F %T %Z") ====="' ERR

echo "===== REVIEWED TACTILE3 YOLO26S PIPELINE START $(date '+%F %T %Z') ====="
echo "DATA=${DATA}"
echo "BASE_NAME=${BASE_NAME}"
echo "FT_NAME=${FT_NAME}"
echo "CUDA_VISIBLE_DEVICES=${CUDA_VISIBLE_DEVICES:-unset}"

if [[ ! -f "${DATA}" ]]; then
  echo "Missing data.yaml: ${DATA}" >&2
  exit 1
fi
if [[ ! -f /home/ddobagi/Code/hanium-dreamup/model/artifacts/pretrained/yolo26s.pt ]]; then
  echo "Missing base model: /home/ddobagi/Code/hanium-dreamup/model/artifacts/pretrained/yolo26s.pt" >&2
  exit 1
fi
if [[ -e "${PROJECT}/${BASE_NAME}" || -e "${PROJECT}/${FT_NAME}" ]]; then
  echo "Run directory already exists. Refusing to overwrite:" >&2
  echo "  ${PROJECT}/${BASE_NAME}" >&2
  echo "  ${PROJECT}/${FT_NAME}" >&2
  exit 1
fi

echo "===== BASE TRAIN START $(date '+%F %T %Z') ====="
# Same Stage1 hyperparameters as the previous YOLO26s tactile3 run, except workers=4
# for dataloader stability observed during the earlier resumed training session.
.venv/bin/yolo detect train \
  model=/home/ddobagi/Code/hanium-dreamup/model/artifacts/pretrained/yolo26s.pt \
  data="${DATA}" \
  epochs=200 \
  patience=50 \
  imgsz=960 \
  batch=8 \
  device=0 \
  workers=4 \
  optimizer=MuSGD \
  lr0=0.00038 \
  lrf=0.882 \
  momentum=0.948 \
  weight_decay=0.00027 \
  warmup_epochs=1.0 \
  cos_lr=True \
  close_mosaic=20 \
  mosaic=0.8 \
  mixup=0.0 \
  copy_paste=0.0 \
  scale=0.6 \
  translate=0.1 \
  fliplr=0.5 \
  erasing=0.0 \
  cache=False \
  plots=True \
  save_period=25 \
  project="${PROJECT}" \
  name="${BASE_NAME}"

if [[ ! -f "${BASE_BEST}" ]]; then
  echo "Missing base best checkpoint after training: ${BASE_BEST}" >&2
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

echo "===== REVIEWED TACTILE3 YOLO26S PIPELINE DONE $(date '+%F %T %Z') ====="
