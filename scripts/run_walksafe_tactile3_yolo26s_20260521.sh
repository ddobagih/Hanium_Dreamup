#!/usr/bin/env bash
set -euo pipefail

cd /home/ddobagi/Code/hanium-dreamup

RUN_DATE=20260521
DATA=/home/ddobagi/Code/hanium-dreamup/datasets/walksafe_kr_tactile_3class_20260521/data.yaml
PROJECT=/home/ddobagi/Code/hanium-dreamup/runs/detect
VAL_PROJECT=/home/ddobagi/Code/hanium-dreamup/runs/validation

BASE_NAME=walksafe_tactile3_yolo26s_img960_musgd_e200_${RUN_DATE}
FT_NAME=walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_${RUN_DATE}

echo "===== TACTILE3 YOLO26S PIPELINE START $(date '+%F %T %Z') ====="
echo "DATA=${DATA}"
echo "BASE_NAME=${BASE_NAME}"
echo "FT_NAME=${FT_NAME}"

.venv/bin/yolo detect train \
  model=/home/ddobagi/Code/hanium-dreamup/yolo26s.pt \
  data="${DATA}" \
  epochs=200 \
  patience=50 \
  imgsz=960 \
  batch=8 \
  device=0 \
  workers=8 \
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

echo "===== BASE TEST VAL START $(date '+%F %T %Z') ====="
.venv/bin/yolo detect val \
  model="${PROJECT}/${BASE_NAME}/weights/best.pt" \
  data="${DATA}" \
  split=test \
  imgsz=960 \
  batch=8 \
  device=0 \
  workers=8 \
  plots=True \
  project="${VAL_PROJECT}" \
  name="${BASE_NAME}_test"

echo "===== HIGH-RES FINETUNE START $(date '+%F %T %Z') ====="
.venv/bin/yolo detect train \
  model="${PROJECT}/${BASE_NAME}/weights/best.pt" \
  data="${DATA}" \
  epochs=80 \
  patience=25 \
  imgsz=1280 \
  batch=4 \
  device=0 \
  workers=8 \
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

echo "===== HIGH-RES TEST VAL START $(date '+%F %T %Z') ====="
.venv/bin/yolo detect val \
  model="${PROJECT}/${FT_NAME}/weights/best.pt" \
  data="${DATA}" \
  split=test \
  imgsz=1280 \
  batch=4 \
  device=0 \
  workers=8 \
  plots=True \
  project="${VAL_PROJECT}" \
  name="${FT_NAME}_test"

echo "===== TACTILE3 YOLO26S PIPELINE DONE $(date '+%F %T %Z') ====="
