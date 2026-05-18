# 모델 개발

이 폴더는 YOLO 기반 모델 개발을 위한 스크립트를 담습니다.

## 데이터셋 검증

```bash
python model/validate_yolo_dataset.py
```

검증 항목:

- `data.yaml` 클래스 정의
- train/val/test 이미지 및 라벨 폴더 존재 여부
- 이미지와 라벨 파일 매칭
- 라벨 행 형식
- 클래스 ID 범위
- bbox 좌표가 0~1 사이인지 여부

## 학습 실행

```bash
python model/train_yolo.py \
  --data datasets/walksafe_kr_v1/data.yaml \
  --model yolo11n.pt \
  --epochs 50 \
  --imgsz 640 \
  --batch 8
```

AI Hub 513 전체 `TL8/TL9/TS8/TS9`로 만든 v2 데이터셋은 명시적으로 경로를 지정한다.

```bash
python model/validate_yolo_dataset.py --data datasets/walksafe_kr_v2/data.yaml
python model/train_yolo.py \
  --data datasets/walksafe_kr_v2/data.yaml \
  --model yolo11n.pt \
  --epochs 50 \
  --imgsz 640 \
  --batch 8 \
  --name walksafe_kr_tactile_v2_full
```

학습 완료 후 test split 별도 검증은 새 run 이름으로 분리한다.

```bash
yolo detect val \
  model=runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  data=datasets/walksafe_kr_v2/data.yaml \
  split=test \
  name=walksafe_kr_tactile_v2_test
```

## 실패 후보 샘플링

full test split 실패 후보를 다시 볼 때는 prediction 이미지를 대량 저장하지 않고 CSV와 checkpoint만 남긴다.

```bash
.venv/bin/python model/sample_yolo_failures.py \
  --model runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  --data datasets/walksafe_kr_v2/data.yaml \
  --split test \
  --output-dir runs/failure_sampling/walksafe_kr_v2_test_stream_smoke_20260519 \
  --max-images 80 \
  --device cpu \
  --overwrite
```

새 학습에 쓰기 전에는 생성 CSV의 `privacy_review_required=yes` 항목과 `small_or_far`/min-box 후보를 수동 검수한다.

`ultralytics`가 설치되어 있지 않으면 `requirements-model.txt`를 먼저 설치합니다.

```bash
python -m pip install -r requirements-model.txt
```

## 주의

데이터가 없는 상태에서는 학습을 실행하지 않습니다. 먼저 `datasets/walksafe_kr_v1` 또는 `datasets/walksafe_kr_v2`의 `images`와 `labels`에 한국 기준 YOLO 형식 데이터를 채웁니다.

`runs/`, `weights/`, `*.pt`, `*.onnx`, `*.engine`, `*.tflite`는 GitHub에 올리지 않습니다.

해외 공개 baseline 데이터셋은 다음처럼 명시적으로 지정할 때만 사용합니다.

```bash
python model/train_yolo.py --data datasets/walksafe_v1/data.yaml --epochs 1 --batch 4 --name walksafe_public_smoke
```
