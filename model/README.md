# 모델 개발

이 폴더는 WalkSafe 모델 검증, 학습, v2 two-model runtime helper를 담습니다.

## 현재 모델 방향

- custom tactile 후보: YOLO26s 3-class
  - `normal_tactile_block`
  - `damaged_tactile_block`
  - `tactile_damage_area`
- COCO helper 후보: YOLO26n COCO pretrained, inference-only
- v2 앱은 두 모델 결과를 한 class-id 공간으로 합치지 않고 `model_key`, `source_model`, `class_name`을 유지한다.
- 2026-05-22 기준 Stage1 `best.pt`가 현재 MVP 후보로 기록되어 있으나, 실제 weight/run 산출물은 GitHub에 올리지 않는다.

## v2 runtime helper

`model/two_model_runtime.py`는 CPU-only helper입니다. Ultralytics/PIL/GPU runtime을 import하지 않고, 이미 생성된 detection payload를 필터링/병합합니다.

```bash
PATH="$PWD/.venv/bin:$PATH" python3 -m pytest model/test_two_model_runtime.py -q
```

관련 설정:

- `configs/walksafe_two_model_runtime_20260522.yaml`
- custom tactile thresholds: 기본 0.25
- COCO allowlist: `person`, `car`, `bus`, `truck`, `bicycle`, `motorcycle`, `traffic light`, `bench`
- cross-model NMS는 적용하지 않는다.

## 데이터셋 검증

```bash
python model/validate_yolo_dataset.py --data datasets/walksafe_kr_v2/data.yaml
```

검증 항목:

- `data.yaml` 클래스 정의
- train/val/test 이미지 및 라벨 폴더 존재 여부
- 이미지와 라벨 파일 매칭
- 라벨 행 형식
- 클래스 ID 범위
- bbox 좌표가 0~1 사이인지 여부

## 학습 실행 예시

```bash
python model/train_yolo.py \
  --data datasets/walksafe_kr_v2/data.yaml \
  --model yolo11n.pt \
  --epochs 50 \
  --imgsz 640 \
  --batch 8
```

`ultralytics`가 설치되어 있지 않으면 먼저 설치합니다.

```bash
python -m pip install -r requirements-model.txt
```

## tactile_damage_area 검수 상태

`tactile_damage_area` 검수 패키지는 `ai_tasks/walksafe_tactile_damage_area_review_20260522/`에 있습니다.

- AI suggestion은 최종 label decision이 아니다.
- 외부 검수 결과 CSV가 있어도 최종 decision 적용과 reviewed dataset build는 별도 단계다.
- bbox 수정/추가 결정은 normalized bbox 좌표 검수 후 적용해야 한다.

## GitHub 업로드 금지

다음은 로컬 전용입니다.

- `datasets/**/images/**`, `datasets/**/labels/**`
- `runs/`, `weights/`
- `*.pt`, `*.onnx`, `*.engine`, `*.tflite`
- 원본 AI Hub zip과 대량 review 중간 산출물
