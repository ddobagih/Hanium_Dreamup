# 모델 개발

이 폴더는 WalkSafe 모델 검증, 학습, v2 unified-primary/legacy-fallback runtime helper를 담습니다.

## 현재 모델 방향

- primary 후보: YOLO26n unified 13-class 단일 모델
- legacy fallback 후보: YOLO26s custom tactile 3-class
  - `normal_tactile_block`
  - `damaged_tactile_block`
  - `tactile_damage_area`
- legacy COCO helper 후보: YOLO26n COCO pretrained, inference-only
- v2 앱은 primary에서 `model_key=unified_walksafe`를 사용한다. legacy fallback에서만 custom/COCO 결과를 한 class-id 공간으로 합치지 않고 `model_key`, `source_model`, `class_name`을 유지한다.
- 2026-05-22 기준 Stage1 `best.pt`는 legacy fallback/historical MVP 후보로만 기록한다. 현재 primary 방향은 unified 13-class이며, 실제 weight/run 산출물은 GitHub에 올리지 않는다.

## unified YOLO26n COCO+AIHub source 학습/export 계약

두 모델 순차 실행 지연을 줄이기 위한 현재 기본 경로는 **YOLO26n unified 13-class 단일 모델**입니다. 모바일 본명 후보 입력 크기는 2026-06-27부터 `768`로 갱신했다. 단, Android runtime config는 최종 768 TFLite asset이 생성되기 전까지 기존 640 unified asset path를 유지한다.

현재 13-class class order:

```yaml
0: person
1: bicycle
2: car
3: motorcycle
4: bus
5: truck
6: traffic light
7: normal_tactile_block
8: damaged_tactile_block
9: crosswalk
10: curb_step
11: uneven_sidewalk
12: e_scooter_obstruction
```

관련 스크립트:

```bash
# 데이터셋 생성 초안: COCO allowlist + 현재 구현된 AIHub 513 road-facility adapter + 추가 커스텀 클래스 자리
python data_sources/scripts/build_walksafe_unified_coco_tactile.py --dry-run

# 학습 명령 확인. 실제 학습 전 data.yaml이 있어야 한다.
bash scripts/run_walksafe_unified_yolo26n_20260601.sh --print-only

# 2026-06-27 이후 모바일 본명 후보. 기본 imgsz=768.
bash scripts/run_walksafe_unified_aihub183_png_yolo26n_768_20260627.sh --print-only

# TFLite export 초안. 실제 export는 TensorFlow 가능한 export venv에서 실행한다.
python scripts/export_walksafe_unified_tflite_20260601.py --help

# 데이터셋 materialize 전 계획 정적 점검
python scripts/check_walksafe_unified_training_plan_20260601.py --allow-missing-dataset

# 다운로드된 AIHub zip을 압축 해제 없이 검사
python data_sources/scripts/inspect_aihub_unified_sources.py --root ~/Downloads --max-json-per-zip 2000
```

AIHub source plan은 `docs/model_unified_13class_aihub_sources_20260602.md`와 `docs/execution/2026-06-27_walksafe_13class_aihub183_png_training_plan.md`를 기준으로 봅니다.

- AIHub 186/189: 신규 학습 우선 source. tactile/crosswalk/curb/uneven sidewalk와 보행자 인도 도메인 보강.
- AIHub 513: 현재 builder가 직접 지원하는 local/보조 source.
- AIHub 189/614 또는 직접 촬영/수동 라벨링: `e_scooter_obstruction` source.

`tactile_damage_area`는 1차 단일 모델에서는 제외합니다. 필요하면 데이터셋 빌더의 `--include-tactile-damage-area`로 14-class 비교안을 만들 수 있습니다.

## v2 runtime helper

`model/two_model_runtime.py`는 CPU-only helper입니다. Ultralytics/PIL/GPU runtime을 import하지 않고, 이미 생성된 detection payload를 필터링/병합합니다.

```bash
PATH="$PWD/.venv/bin:$PATH" python3 -m pytest model/test_two_model_runtime.py -q
```

관련 설정:

- Android runtime source of truth: `apps/android/app/src/main/assets/model-config/two_model_runtime.json`
- Backend/current runtime config: `configs/walksafe_two_model_runtime_stage1_mvp_20260523.json`
- Legacy helper/base config: `configs/walksafe_two_model_runtime_20260522.yaml`
- legacy custom tactile thresholds: 기본 0.25
- legacy COCO allowlist: `person`, `car`, `bus`, `truck`, `bicycle`, `motorcycle`, `traffic light`, `bench`
- cross-model NMS는 legacy fallback에서도 적용하지 않는다.

## 2026-07-01 학습 상태

현재 로컬에는 strict 768 e300 학습 run이 진행 중인 산출물이 있다.

```text
runs/detect/walksafe_unified_aihub183_png_yolo26n_img768_e300_open150_strict768_20260701_b8w2
logs/walksafe_unified_aihub183_png_yolo26n_img768_e300_open150_strict768_20260701_b8w2_20260701_120341.log
```

`args.yaml` 기준 `imgsz: 768`, `epochs: 300`, `batch: 8`, `workers: 2`, `resume: false`다. `results.csv`에는 epoch 1 결과만 기록된 상태라 최종 metric 또는 export-ready 모델로 쓰지 않는다. 학습 로그에는 일부 truncated image warning이 있어, 학습 완료 후 데이터 품질 감사와 validation 결과를 별도 기록해야 한다.

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
