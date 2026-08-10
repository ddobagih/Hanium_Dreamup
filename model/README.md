# 모델 개발

이 폴더는 WalkSafe 모델 검증, 학습, unified-primary/legacy-fallback runtime helper를 담습니다.

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

두 모델 순차 실행 지연을 줄이기 위한 현재 기본 경로는 **YOLO26n unified 13-class 단일 모델**입니다. 모바일 후보 입력 크기는 2026-06-27부터 `768`로 갱신했습니다. 다만 최종 13-class TFLite asset은 아직 생성되지 않았고 Android는 legacy 두 모델 asset으로 fallback하는 상태입니다.

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
# 현재 13-class builder 인자와 안전장치 확인
python data_sources/scripts/build_walksafe_unified_13class_dataset.py --help

# 2026-06-02 first-pass 설계 snapshot. 현재 데이터셋 builder가 아니다.
python data_sources/scripts/build_walksafe_unified_coco_tactile.py --dry-run

# 2026-06-02 first-pass 640 학습 명령 확인. 현재 실행 경로가 아니다.
bash scripts/run_walksafe_unified_yolo26n_20260601.sh --print-only

# 현재 13-class img768 학습 명령 확인
bash scripts/run_walksafe_unified_aihub183_png_yolo26n_768_20260627.sh --print-only

# TFLite export 도구 확인. 현재 후보 경로와 imgsz는 반드시 명시한다.
python scripts/export_walksafe_unified_tflite_20260601.py --help

# 2026-06-02 계획의 정적 점검
python scripts/check_walksafe_unified_training_plan_20260601.py --allow-missing-dataset

# 다운로드된 AIHub zip을 압축 해제 없이 검사
python data_sources/scripts/inspect_aihub_unified_sources.py --root ~/Downloads --max-json-per-zip 2000
```

현재 반영 source는 `docs/model-data/walksafe_13class_dataset_source_contract_20260619.md`와 `data_sources/manifests/walksafe_aihub_source_usage_20260628.md`를 기준으로 봅니다.

- AIHub 186/513/189 Surface: tactile/crosswalk/curb/uneven sidewalk 학습 source에 반영.
- AIHub 572 이륜자동차 안전 위험 시설물: 사람이 승인한 `e_scooter_obstruction` 16,005장 반영. 기존 경로의 `aihub183`은 레거시 내부 별칭이다.
- AIHub 189 수동 relabel: `e_scooter_obstruction` 7장 반영.

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

## 검증된 최신 학습 상태

2026-07-08 기준 strict 768 학습은 300 epoch까지 완료됐습니다.

```text
runs/detect/walksafe_unified_aihub183_png_yolo26n_img768_e300_open150_strict768_20260701_b8w2
model/artifacts/candidates/walksafe_13cls_yolo26n_img768_20260708/walksafe_13cls_yolo26n_img768_best_epoch270.pt
reports/walksafe_best_eval_20260708/final_evaluation_report.md
```

- best epoch: 270
- mAP50: 0.55403
- mAP50-95: 0.41651
- precision: 0.62087
- recall: 0.56716
- epoch 300 mAP50-95: 0.41044

이 weight는 **후속 개선용 후보**이며 배포 확정 모델이 아닙니다. `curb_step` recall은 19.5%, `uneven_sidewalk` recall은 7.6%로 낮고, validation에서 corrupt/truncated 이미지 2,331장이 제외됐습니다. 현재 통합 데이터셋은 `train/val`만 제공하므로 독립 라벨 test 성능도 아직 없습니다.

로컬 artifact hash와 배포 가능 여부는 `registry/walksafe-model-registry.json`, manifest-only 승격·rollback 상태는
`deployments/local-deployment.json`에서 관리합니다. `python scripts/manage_local_model_registry.py verify`는 runtime 설정을
바꾸지 않고 model/dataset/results hash와 참조 무결성만 확인합니다.

또한 `apps/android/app/src/main/assets/model-config/two_model_runtime.json`이 가리키는 unified 640 TFLite asset은 존재하지 않습니다. `model/artifacts/exports/yolo26n_saved_model/`의 TFLite는 80-class COCO pretrained export이며 위 13-class 후보의 최종 export가 아닙니다.

## 데이터셋 검증

현재 13-class 통합본은 `train/val` 구성입니다. 이 구성은 다음 검증기를 사용합니다.

```bash
python data_sources/scripts/validate_yolo_dataset.py \
  datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml
```

검증 항목:

- `data.yaml` 클래스 정의
- data.yaml에 선언된 train/val 및 선택적 test 이미지·라벨 폴더 존재 여부
- 이미지와 라벨 파일 매칭
- 라벨 행 형식
- 클래스 ID 범위
- bbox 좌표가 0~1 사이인지 여부

`model/validate_yolo_dataset.py`는 과거 v1/v2 형식처럼 `train/val/test`가 모두 있는 데이터셋을 엄격히 확인하는 legacy 검증기입니다. 두 검증기 모두 데이터 경로를 생략할 수 없습니다.

## 학습 실행 예시

```bash
python model/train_yolo.py \
  --data datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml \
  --model model/artifacts/pretrained/yolo26n.pt \
  --epochs 300 \
  --imgsz 768 \
  --batch 8 \
  --name walksafe_unified_13cls_experiment \
  --dry-run
```

`--dry-run`으로 경로와 설정을 먼저 확인한 뒤 실제 학습 실행에서만 제거합니다. `--data`는 항상 명시해야 합니다.

`ultralytics`가 설치되어 있지 않으면 먼저 설치합니다.

```bash
python -m pip install -r model/requirements.txt
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
