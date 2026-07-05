# WalkSafe 13-class AIHub183 PNG 모바일 우선 학습 최신 계획 (2026-06-27)

## 기준 결정

- 최종 목표: Galaxy S26급 Android에서 카메라 preview 30FPS를 유지하면서 13클래스 객체 탐지와 ARCore depth 결합을 실시간으로 돌리는 모바일 모델.
- 1순위 본명 후보: `YOLO26n`, 입력 크기 `768`.
- 이전 `YOLO26s / 960` 계획은 정확도 상한 확인용 비교 후보로 내린다. 모바일 기본 학습/배포 후보로 쓰지 않는다.
- 학습은 항상 13클래스 전체 단일 모델로 진행한다. 일부 클래스만 따로 학습하지 않는다.

## 기준 데이터셋

- 최종 데이터셋: `datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml`
- 전체 이미지: 196,506
- 전체 bbox: 607,814
- `e_scooter_obstruction`: 19,758 boxes
  - train 17,660
  - val 2,098
- 데이터 검증 상태: YOLO validator 통과, audit errors 0, empty labels 0
- AIHub183 추가 이미지는 원본 PNG bytes 그대로 통합됨.

## 현재 학습 장비

- CPU: AMD Ryzen 9 9950X 16-Core / 32 threads
- RAM: 29GiB, 확인 시 available 약 14GiB
- GPU: NVIDIA GeForce RTX 5070 Ti
- VRAM: 16,303MiB, 확인 시 여유 약 15.8GiB
- Driver/CUDA: NVIDIA 580.159.03 / CUDA 13.0
- Python: 3.14.4 in `.venv`
- PyTorch: 2.11.0+cu130
- Ultralytics: 8.4.48
- 최근 디스크 여유: 약 26GiB

## 왜 768인가

WalkSafe는 점자블록 손상, 노면 파손, 보도 단차, 신호등, 원거리 전동킥보드처럼 작은 객체가 많다. `640`은 빠르지만 작은 객체 recall 손실 위험이 있고, `960`은 640 대비 연산량이 약 2.25배라 모바일 실시간/발열에 부담이 크다. `768`은 640 대비 연산량이 약 1.44배이면서 객체 크기를 보강할 수 있어 모바일 1차 후보로 가장 균형이 좋다.

## 최신 실행 스크립트

- 기본 모바일 본명 스크립트: `scripts/run_walksafe_unified_aihub183_png_yolo26n_768_20260627.sh`
- 비교/상한 확인용 기존 스크립트: `scripts/run_walksafe_unified_aihub183_png_yolo26s_20260627.sh`

기본값:

- model: `yolo26n.pt`
- imgsz: 768
- epochs: 100
- batch: 16
- device: 0
- workers: 8
- cache: False
- AMP: True
- cos_lr: True
- close_mosaic: 10
- save_period: -1
- run name: `walksafe_unified_aihub183_png_yolo26n_img768_e100_20260627`

OOM 발생 시 `imgsz=768`은 유지하고 batch만 `12`, 그래도 실패하면 `8`로 낮춘다.

## 실행 전 gate

```bash
python3 data_sources/scripts/validate_yolo_dataset.py \
  datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml

python3 data_sources/scripts/audit_yolo_dataset.py \
  datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml \
  --out-dir runs/review/walksafe_13class_aihub183_png_yolo26n_768_pretrain_audit_20260627_no_overlay \
  --overwrite \
  --no-overlays

df -h . /tmp
nvidia-smi
```

통과 기준:

- validation ok
- audit errors 0
- empty labels 0
- GPU VRAM 여유 확인
- 디스크 여유 최소 20GiB 이상, 권장 40GiB 이상

## 권장 순서

### 로더 smoke

목적은 전체 학습이 아니라 로더, PNG 디코딩, CUDA, batch 설정 확인이다. 최종 성능으로 보지 않는다.

```bash
FRACTION=0.02 \
EPOCHS=1 \
BATCH=16 \
NAME=walksafe_unified_aihub183_png_loader_smoke_yolo26n_img768_e1_20260627 \
bash scripts/run_walksafe_unified_aihub183_png_yolo26n_768_20260627.sh
```

통과 기준:

- train 시작/종료 정상
- OOM 없음
- label/cache 오류 없음
- 결과 폴더 생성

### 본 학습

```bash
bash scripts/run_walksafe_unified_aihub183_png_yolo26n_768_20260627.sh
```

동일 명령 미리보기:

```bash
bash scripts/run_walksafe_unified_aihub183_png_yolo26n_768_20260627.sh --print-only
```

## 학습 후 검증

```bash
.venv/bin/yolo detect val \
  model=runs/detect/walksafe_unified_aihub183_png_yolo26n_img768_e100_20260627/weights/best.pt \
  data=datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml \
  imgsz=768 \
  batch=16 \
  device=0 \
  project=runs/validation \
  name=walksafe_unified_aihub183_png_yolo26n_img768_e100_20260627_val
```

확인할 지표:

- 전체 mAP50-95
- class별 mAP/recall
- 특히 `damaged_tactile_block`, `uneven_sidewalk`, `e_scooter_obstruction`, `traffic light`
- confusion matrix에서 motorcycle/bicycle/e_scooter 혼동
- tactile normal/damaged 혼동

## Android/export 최신 방향

학습 후 Android에 넣을 때는 unified 13-class 단일 모델로 export한다. 모바일 1순위 산출물 이름은 아래처럼 둔다.

```bash
python scripts/export_walksafe_unified_tflite_20260601.py \
  --model runs/detect/walksafe_unified_aihub183_png_yolo26n_img768_e100_20260627/weights/best.pt \
  --data datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml \
  --imgsz 768 \
  --output-name walksafe_unified_aihub183_png_yolo26n_768_float32.tflite \
  --dry-run
```

실제 모바일 배포 후보는 float32만으로 확정하지 않는다. INT8 우선, 실패 시 FP16을 검토한다. 현재 export script는 기본 float32 경로라 quantized export는 별도 export 환경/명령을 확정해야 한다.

Android 쪽 현재 주의점:

- `apps/android/app/src/main/assets/model-config/two_model_runtime.json`의 unified asset은 아직 `walksafe_unified_yolo26n_640_float32.tflite` 기준이다.
- 최종 768 TFLite가 생성되기 전에는 config를 768 파일명으로 바꾸지 않는다.
- 현재 detector 주기는 `DETECTION_INTERVAL_MS = 250L`이라 탐지 상한이 4FPS다. 실기기 성능 검증 단계에서 100~125ms 후보로 낮춰야 한다.
- 현재 runtime은 CPU/NNAPI만 지원하고 기본값은 CPU다. 모바일 합격을 위해서는 NNAPI 또는 GPU/QNN 계열 가속 적용 여부를 별도 실측해야 한다.

## 모바일 합격 기준

- 카메라 preview 30FPS 유지
- detector 실효 8FPS 이상, 권장 10~15FPS
- ARCore depth 결합 후 UI 끊김 없음
- 5분 이상 실기기 구동에서 발열로 FPS 급락 없음
- 주요 위험 클래스 recall이 학습 검증 기준을 만족
- overlay bbox와 depth sample 위치가 실기기에서 정합

## 남은 리스크

- 현재 디스크 여유가 낮다. 학습 전 불필요한 산출물 삭제 또는 외부 백업을 검토한다.
- smoke는 최종 성능 판단이 아니다.
- 모바일 성능은 학습 mAP가 아니라 TFLite export 후 Galaxy S26급 실기기 latency/FPS로 확정한다.
