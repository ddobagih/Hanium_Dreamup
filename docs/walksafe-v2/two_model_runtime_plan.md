# Unified-primary runtime transition plan

- 기준일: 2026-06-02 KST
- 구현 상태: backend CPU-only filtering/merge helper 구현 완료, `/detect/v2` lazy YOLO provider 연결 완료, Android TFLite JSON runtime config 연결 완료, Android runtime은 `unified_walksafe` primary + legacy two-model fallback으로 전환 완료

## 1. 모델 구성

| model_key | source_model | 역할 | class/allowlist |
|---|---|---|---|
| `custom_tactile` | YOLO26s custom / Android TFLite | 점자블록/타일 상태와 손상 영역 | `normal_tactile_block`, `damaged_tactile_block`, `tactile_damage_area` |
| `coco_general` | YOLO26n COCO pretrained / Android TFLite | 일반 객체 후보 | Android JSON의 COCO allowlist 기준 |
| `unified_walksafe` | YOLO26n COCO+WalkSafe 13-class | primary 단일 모델 경로 | `person`, `bicycle`, `car`, `motorcycle`, `bus`, `truck`, `traffic light`, `normal_tactile_block`, `damaged_tactile_block`, `crosswalk`, `curb_step`, `uneven_sidewalk`, `e_scooter_obstruction` |

`bench`는 unified 단일 모델에서 제거한다. legacy `coco_general` fallback의 `bench`는 과거 COCO allowlist/테스트 호환 때문에 유지 가능하다.

## 2. Runtime source-of-truth 분리

| 런타임 | source-of-truth | 용도 |
|---|---|---|
| Android native | `apps/android/app/src/main/assets/model-config/two_model_runtime.json` | APK 내부 primary/fallback model key, TFLite asset path, input size, class order, allowlist, threshold |
| Backend `/detect/v2` | `configs/walksafe_two_model_runtime_stage1_mvp_20260523.json` + env model path | unified YOLO path 우선, legacy pair fallback, API smoke, report pipeline |
| CPU helper/test | `model/two_model_runtime.py` | class filtering/threshold/merge contract 테스트 |

Android config와 backend config를 같은 파일처럼 취급하지 않는다. 둘 다 unified primary 계약을 갖지만 threshold는 다르며, `scripts/check_android_tflite_contract_20260531.py`는 이 차이를 warning으로 보고한다.

## 3. 현재 구현

Android:

- Config: `apps/android/app/src/main/assets/model-config/two_model_runtime.json` (`primary_model=unified_walksafe`, `fallback_model=legacy_two_model`)
- Loader: `TwoModelRuntimeConfig.kt`
- Detector: `TfliteAndroidFrameDetector.kt`
- Export helper: `scripts/export_android_tflite_models_20260531.py`
- Contract check: `scripts/check_android_tflite_contract_20260531.py`

Backend/helper:

- Helper: `model/two_model_runtime.py`
- Test: `model/test_two_model_runtime.py`
- Stage1 MVP config: `configs/walksafe_two_model_runtime_stage1_mvp_20260523.json`
- Adapter: `backend/app/services/yolo_inference_adapter.py`
- Unified source plan: `../model_unified_13class_aihub_sources_20260602.md`

Helper 기능:

- runtime config validation
- custom tactile class filtering
- COCO allowlist filtering
- class별 threshold filtering
- detection payload normalization
- legacy custom + COCO 결과 concatenate
- unified 13-class filtering/category contract
- cross-model NMS 미적용

## 4. 현재 threshold 주의

Backend Stage1 MVP config는 unified `damaged_tactile_block=0.35`, legacy custom `damaged_tactile_block=0.45`를 쓴다. Android JSON은 실기기 관찰성을 위해 일부 class threshold가 다르다. 이 차이는 의도적으로 유지할지, backend와 맞출지 별도 decision이 필요하다.

| 항목 | 현재 기준 | 주의 |
|---|---|---|
| Android `unified_walksafe`/legacy threshold | `two_model_runtime.json` | APK runtime 실제 기준 |
| Backend unified/legacy threshold | `configs/walksafe_two_model_runtime_stage1_mvp_20260523.json` | `/detect/v2` server smoke 기준 |
| full static metric rerun | blocked | reviewed tactile3 원본 image/GT dataset이 현재 로컬에 없음 |
| saved prediction sweep | 가능 | fresh inference/full metric이 아니라 saved artifact 재분석 |

## 5. 결과 처리 원칙

- unified 경로에서는 `unified_walksafe`의 13-class class id가 그대로 전역 class id다. legacy fallback에서만 model별 class id 공간을 분리한다.
- 각 detection은 `model_key`, `source_model`, `model_class_id`, `class_name`, `category`, `threshold_used`를 유지한다.
- legacy fallback에서는 bbox가 겹쳐도 cross-model NMS를 하지 않는다.
- downstream은 `class_name`과 `model_key`를 함께 보되, 새 학습 산출물은 `model_key=unified_walksafe`가 정상 경로다.

## 6. Backend 연결 상태

- `/detect/v2`는 기본 `fake` mode를 유지한다.
- `DETECT_V2_MODE=yolo|real`이고 `DETECT_V2_UNIFIED_MODEL_PATH`가 있으면 unified 단일 모델을 우선 사용한다. 없으면 custom tactile/COCO model path pair로 legacy fallback을 사용한다.
- fake contract도 이제 `unified_walksafe` payload를 기본으로 반환하므로 13-class class id/threshold 정책을 테스트한다.
- Android native 주경로에서는 backend detector가 primary runtime이 아니며, report/admin/export를 위한 운영 API로 본다.

## 7. 다음 구현 후보

1. unified TFLite asset을 `models/walksafe_unified_yolo26n_640_float32.tflite`로 넣고 Android가 `unified_walksafe`를 completed model로 기록하는지 확인한다.
2. Android overlay/depth 좌표 정합을 확인한다.
3. Android/backend threshold 차이를 decision note로 문서화한다.
4. AIHub 186/189 source zip을 우선 확보하고, 513은 보존/보완, 614/직접 촬영 라벨은 `e_scooter_obstruction` 재라벨용으로 확보해 unified 13-class dataset을 materialize한다.
5. 일반 객체는 tracking/path/depth context가 위험을 표시할 때만 경고한다.
6. server-v2 latency 측정은 Android core gate 이후 후순위로 둔다.
