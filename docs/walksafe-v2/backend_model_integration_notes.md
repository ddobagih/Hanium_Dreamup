# Backend model integration notes

- 기준일: 2026-05-23 KST
- 상태: `/detect/v2`는 fake 기본값을 유지하며, `DETECT_V2_MODE=yolo|real`에서 `LazyYoloDetectV2Runtime`/`YoloDetectV2Provider`로 실제 YOLO provider가 연결되어 있다. 2026-05-23 KST 기준 reviewed YOLO26s Stage1 `best.pt`를 MVP/backend integration 후보로 선택했고, 초기 runtime config를 준비했다.

## 현재 구현된 연결

- Adapter: `backend/app/services/yolo_inference_adapter.py`
- Adapter tests: `backend/tests/test_yolo_inference_adapter.py`
- v2 provider/runtime: `backend/app/services/detect_v2.py`
- Runtime filter/merge: `model/two_model_runtime.py`
- Threshold config: `configs/walksafe_two_model_runtime_20260522.yaml`
- Stage1 MVP candidate config: `configs/walksafe_two_model_runtime_stage1_mvp_20260523.json`

`/detect/v2` 기본값은 fake provider다. `DETECT_V2_MODE=yolo` 또는 `real`이고 custom tactile/COCO model path가 모두 설정되어 있으면 `YoloDetectV2Provider`가 만들어진다. `LazyYoloDetectV2Runtime`은 Ultralytics/Pillow/model weight 로드를 실제 `/detect/v2` 요청 시점까지 지연한다.

`/detect/v2/health`는 configured path 상태와 mode/status/reason만 확인한다. health 호출만으로 실제 모델을 로드하지 않는다.

## Detection field contract

Adapter output:

| field | meaning |
|---|---|
| `model_key` | `custom_tactile` 또는 `coco_general` |
| `source_model` | checkpoint 또는 pretrained 모델 식별자 |
| `model_class_id` | 해당 모델 안에서의 class id |
| `class_name` | v2 class name |
| `category` | `tactile` 또는 `general_obstacle` |
| `confidence` | `[0, 1]` confidence |
| `bbox` | normalized `(x, y, width, height)`; runtime/filter와 `/detect/v2` 응답에 쓰는 값 |
| `bbox_xyxy` | normalized `(x1, y1, x2, y2)`; 디버깅/검수용 보조 값 |

중요:

- `model.two_model_runtime.Detection`은 `bbox`를 `(x, y, width, height)`로 해석한다.
- Ultralytics `xyxyn`을 그대로 `bbox`에 넣으면 안 된다.
- `bbox_xyxy`는 저장/응답 필수 필드가 아니며, 연결 시 필요 없으면 버려도 된다.

## Class mapping

Custom tactile model:

| model_class_id | class_name |
|---:|---|
| 0 | `normal_tactile_block` |
| 1 | `damaged_tactile_block` |
| 2 | `tactile_damage_area` |

COCO helper:

- `result.names` 또는 명시 `class_name_by_id`로 class name을 해석한다.
- allowlist는 `model.two_model_runtime.COCO_GENERAL_ALLOWLIST`와 맞춘다.
- 일반 객체는 시설물 신고 저장 대상이 아니라 위험 경고 대상이다.

## 현재 처리 흐름

1. `/detect/v2`가 image validation 후 provider를 선택한다.
2. fake mode는 fake v2 detection을 반환한다.
3. `yolo`/`real` mode는 설정된 model path가 모두 있어야 ready가 된다.
4. 실제 요청에서 lazy runtime이 image open 및 두 모델 inference를 실행한다.
5. 각 result를 `ultralytics_result_to_raw_detections()`로 변환한다.
6. `filter_and_merge_detections()`로 class allowlist/threshold를 적용한다.
7. 기존 `DetectV2Detection` 응답 생성 경로로 변환한다.
8. `/reports/v2` 저장 정책은 custom tactile의 `damaged_tactile_block`만 허용한다.

## Reports/export 연동 상태

- `/reports`는 v2 metadata 필터 `model_key`, `trigger`, `auto_reported`를 지원한다.
- `/reports`의 `class_name`은 v2 class name 문자열도 필터링할 수 있다.
- `/reports/export`는 CSV 기본, `format=json` 지원이다.
- `/reports/export`는 `/reports`와 같은 필터를 재사용하고 CSV 응답에 `Content-Disposition`을 제공한다.

## 선택된 MVP 후보

2026-05-23 KST 완료된 reviewed YOLO26s pipeline 기준 선택값:

| 항목 | 값 |
|---|---|
| 선택 checkpoint | `runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt` |
| COCO helper | `yolo26n.pt` |
| runtime config | `configs/walksafe_two_model_runtime_stage1_mvp_20260523.json` |
| 선택 근거 | Stage1 best val/test mAP50-95가 Stage2보다 높음 |

Local env 예시:

```bash
export DETECT_V2_MODE=yolo
export DETECT_V2_CUSTOM_TACTILE_MODEL_PATH=/home/ddobagi/Code/hanium-dreamup/runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt
export DETECT_V2_COCO_MODEL_PATH=/home/ddobagi/Code/hanium-dreamup/yolo26n.pt
export DETECT_V2_RUNTIME_CONFIG_PATH=/home/ddobagi/Code/hanium-dreamup/configs/walksafe_two_model_runtime_stage1_mvp_20260523.json
```

Health-only readiness check:

```bash
bash scripts/check_detect_v2_stage1_candidate_health_20260523.sh
```

이 check는 `/detect/v2/health`와 경로/config readiness만 확인하며, YOLO weight를 로드하거나 inference를 실행하지 않는다.

## 남은 결정사항

운영/현장 적용 전 확정할 항목:

1. 실제 `/detect/v2` 이미지 smoke 실행 시점과 GPU/CPU device 정책.
2. threshold sweep 또는 false-positive 샘플링 후 최종 class별 threshold.
3. 배포 환경 변수:
   - `DETECT_V2_MODE=yolo` 또는 `real`
   - `DETECT_V2_CUSTOM_TACTILE_MODEL_PATH`
   - `DETECT_V2_COCO_MODEL_PATH`
   - `DETECT_V2_RUNTIME_CONFIG_PATH`
4. 최종 모델 파일 배포 위치와 권한.

## 최소 테스트 계획

최종 checkpoint/env 확정 PR에서 확인할 테스트:

- `fake` mode는 model path 없이 fake contract로 동작한다.
- `yolo`/`real` mode에서 model path 미설정이면 `/detect/v2/health`가 unavailable reason을 반환하고 `/detect/v2`는 503을 반환한다.
- `/detect/v2/health`는 실제 model load를 하지 않는다.
- custom tactile result가 v2 response의 `model_key=custom_tactile`로 나온다.
- COCO allowlist 밖 class는 response에 없다.
- `normal_tactile_block`은 `/reports/v2`에서 계속 거부된다.
- `damaged_tactile_block`만 `/reports/v2`에 저장된다.
- `tactile_damage_area`는 손상 부위 bbox 보조 정보로만 두고 `/reports/v2` 저장은 거부된다.
- bbox는 response에서 `{x, y, width, height}`로 유지된다.

## 금지 사항

- v1 `ClassId`/`ClassName` validator에 v2 class를 섞지 않는다.
- v2 `model_class_id`를 전역 class id처럼 저장/비교하지 않는다.
- COCO/general object를 시설물 신고 대상으로 저장하지 않는다.
- custom tactile과 COCO 결과를 bbox overlap만으로 cross-model NMS하지 않는다.
