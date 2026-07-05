# Android/backend threshold decision note (2026-06-02)

## 목적

Android native next-step plan의 P3(Android/backend threshold 정리)를 위해, 현재 Android 실기기 설정과 backend stage1 운영/report 설정의 threshold 차이를 어떻게 다룰지 기록한다.

## 참조 config

- Android config: `apps/android/app/src/main/assets/model-config/two_model_runtime.json`
- backend config: `configs/walksafe_two_model_runtime_stage1_mvp_20260523.json`

## 현재 차이

Android와 backend의 threshold는 서로 다르다.

- Android `unified_walksafe` primary
  - 일반 객체 대부분: 0.35
  - `traffic light`: 0.40
  - `normal_tactile_block`: 0.25
  - `damaged_tactile_block`: 0.35
  - `crosswalk`: 0.30
  - `curb_step`, `uneven_sidewalk`, `e_scooter_obstruction`: 0.35
- backend `unified_walksafe`
  - Stage1 config 기준 `person`: 0.20
  - `car`/`bus`/`truck`: 0.45
  - `traffic light`: 0.50
  - `normal_tactile_block`: 0.30
  - `damaged_tactile_block`, `crosswalk`: 0.35
  - `curb_step`, `uneven_sidewalk`, `e_scooter_obstruction`: 0.40
- Android legacy `custom_tactile`
  - `normal_tactile_block`: 0.25
  - `damaged_tactile_block`: 0.35
  - `tactile_damage_area`: 0.45
- backend legacy `custom_tactile`
  - `normal_tactile_block`: 0.45
  - `damaged_tactile_block`: 0.45
  - `tactile_damage_area`: 0.80
- Android legacy `coco_general`
  - allowlist 대부분 0.35, `traffic light`/`bench` 0.40
- backend legacy `coco_general`
  - `person` 0.20, `car`/`bus`/`truck` 0.45, `traffic light`/`bench` 0.50 등

이 차이를 같은 평가조건으로 섞으면 안 된다. Android 결과와 backend report 결과를 비교하거나 합산할 때는 어떤 config와 threshold를 적용했는지 분리해서 표기해야 한다.

## `scripts/check_android_tflite_contract_20260531.py` warning 의미

`check_android_tflite_contract_20260531.py`는 Android TFLite 정적 계약을 확인하면서 Android config와 backend config의 threshold 차이를 비교한다.

- 기본 모드에서 차이가 있으면 `warning`으로 보고한다.
- 이 `warning`은 Android 설정이 깨졌다는 뜻이 아니다.
- 의미는 “Android TFLite threshold와 backend stage1 runtime config가 다르며, 이 차이는 의도적일 수 있지만 report에서 섞으면 안 된다”이다.
- `--strict-backend-config`를 켜면 같은 차이를 오류로 취급할 수 있지만, 현재 P3 결정에서는 기본 warning을 허용한다.

## 임시 결정: 분리 유지

현재 결정은 임시로 **분리 유지**한다.

이유:

- Android threshold는 실기기 관찰, 화면 overlay/depth 확인, on-device recall 확보 목적에 가깝다.
- backend threshold는 report 운영에서 false positive 부담을 줄이고 precision을 관리하는 목적에 가깝다.
- 두 목적이 다르므로 아직 하나의 threshold로 통합하지 않는다.

## blocked 사항

full static rerun은 현재 `blocked` 상태다.

- reviewed tactile3 원본 image/GT dataset이 현재 작업 기준으로 부재하다.
- 따라서 Android/backend threshold를 같은 데이터셋에서 완전 재평가해 통합 결론을 내릴 수 없다.
- 지금 문서는 full rerun 결과가 아니라, 현 설정 차이와 임시 운영 결정을 기록하는 decision note다.

## 다음 변경 조건

다음 조건을 확인한 뒤 Android/backend threshold 통합 또는 재조정을 다시 판단한다.

1. Device overlay/depth 정합 후
   - Android 실기기에서 detection 위치, depth 결합, 사용자에게 보이는 overlay가 기대와 맞는지 확인한다.
2. reviewed dataset 복구 후
   - reviewed tactile3 원본 image/GT dataset을 복구해 동일 평가조건으로 재평가한다.
3. FP burden 관측 후
   - backend report 운영에서 false positive 부담이 어느 정도인지 관측한다.

## PASS로 쓰면 안 되는 항목

다음 항목은 PASS 근거로 쓰면 안 된다.

- Android/backend threshold 차이에 대한 `warning`이 있다는 사실 자체
- 기본 모드에서 `warning`만 발생하고 오류가 없다는 사실
- full static rerun 없이 현재 threshold가 통합 검증됐다는 주장
- Android 실기기 recall 목적 threshold를 backend 운영 precision 목적 report PASS로 사용하는 것
- backend report threshold를 Android 실기기 overlay/depth 체감 품질 PASS로 사용하는 것
- reviewed tactile3 원본 image/GT dataset 부재 상태에서 “동일 평가조건 검증 완료”라고 쓰는 것
