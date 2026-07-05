# Android native ARCore gate 실행 계획서 - 2026-06-01

> **문서 상태(2026-06-02): 과거 실행 계획 snapshot.** 현재 기준은 `docs/current_status.md`, `apps/android/README.md`, `docs/README.md`를 우선한다. APK hash와 unified-primary 전환 이후의 모델/좌표 상태는 최신 문서를 따른다.


## 기준

- 작성일: 2026-06-01 KST
- 이전 기준 계획: `plans/features/2026-05-31_android_native_next_step_plan.md`
- 기준 APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`
- 기준 APK sha256: `17a1b0b4efe2b4fd9f85a619292d2fceb40f11c7530187201095fc1a36c71c8f`
- 주 경로: Web/PWA가 아니라 Android native ARCore/TFLite 앱이다.
- 이번 계획의 목적: 기능을 더 얹기 전에 **overlay bbox, preview, ARCore depth, `N보` 표시가 같은 객체를 가리키는지 검증 가능한 gate**를 만드는 것이다.

## 팀 구성

| lane | 이번 계획 담당 | 범위 | 산출물 |
|---|---|---|---|
| 총괄/merge | Codex parent | 하위 조사 결과 통합, 최종 실행 순서 결정, daylog 기록 | 이 계획서, 문서 링크 최신화, 검증 결과 |
| Android Coordinate lane | Cicero | overlay/view/depth 좌표계, ARCore mapper risk | P0/P1 좌표 정합 작업안 |
| Model/Data/Evidence lane | Socrates | static RGB 평가 한계, RGB-D dataset, threshold evidence | P2/P3/P4 작업안 |
| Backend/Ops/Report lane | Heisenberg | `/reports/v2`, source/metadata, admin/export | P5 report 연결 전 결정안 |

하위 lane은 이번 턴에서 읽기 전용 조사만 수행했다. 실제 코드 변경은 이 계획서에 따라 충돌 없는 파일 범위로 다시 나눠 진행한다.

## 현재 확정 사실

- Android debug APK는 빌드 가능하고 실기기 설치 가능한 상태다.
- `MetadataCaptureLog`는 RGB/YUV 이미지나 depth 파일을 저장하지 않고 최근 frame metadata만 메모리 ring buffer로 보관한다.
- `MAX_DETECTION_AGE_MS=2500`을 넘은 detector 결과는 depth pipeline 입력에서 제외된다.
- `DebugBboxOverlayView`는 현재 `RectNorm.toScreenRect(width, height)` 기반 단순 view scaling 경로다.
- `ObjectDepthRuntimePipeline`은 depth sampling에서 아직 `identityMapper(depthWidth, depthHeight)` 가정을 사용한다.
- 사용자가 실기기에서 관찰한 사실은 “프레임 드랍 없음”, “track4 person 감지로 보임”, “`1보` 표시가 뜸”이다.
- bbox overlay가 실제 객체 위에 맞는지, depth sample이 같은 객체를 읽는지는 아직 PASS가 아니다.
- reviewed tactile3 원본 image/GT dataset은 현재 로컬에 없어 full static metric rerun은 blocked다.
- backend `/reports/v2`는 `custom_tactile:damaged_tactile_block`만 저장 허용하고 GPS가 없으면 거부한다.
- backend `DetectorSource`는 현재 `fake | onnx | server`이고, v2 report source는 `source_model`이 fake로 시작하면 `fake`, 그 외에는 `server`로 저장된다.

## 안전 원칙

- 사용자 확인 없이 배포, release signing, 외부 공개, 운영 DB 변경, secret 설정, 비용 발생 API 호출, 장시간 live API 반복 호출은 하지 않는다.
- 이미지, depth raw, confidence map, screenshot, GPS 원본을 기본 저장/export/upload하지 않는다.
- Device evidence 전까지 Android build 성공, static RGB 결과, fake/demo 결과를 실기기 PASS로 쓰지 않는다.
- STT/TTS/길안내/report upload는 Android bbox/depth gate 뒤로 둔다. STT 서버 외부 URL 연결 여부는 음성 기능 단계의 별도 점검 항목이지 현재 P0 blocker가 아니다.

## 최종 gate 정의

| gate | 통과 조건 | 통과 전 금지 |
|---|---|---|
| G0 Build/contract | Android unit/static/build가 통과하고 기준 APK path/hash가 명확하다 | build만으로 Device PASS 주장 금지 |
| G1 Overlay | 중앙/좌/우/상/하/회전 관찰에서 bbox가 실제 객체 위에 대략 정렬된다 | TTS/haptic 제품 경고 연결 금지 |
| G2 Depth | 같은 객체를 0.5m/1m/2m 수준으로 움직일 때 depth median/`N보`가 일관되게 변한다 | `N보` 정확도 PASS 주장 금지 |
| G3 Stale | detector age 초과 시 오래된 bbox가 depth/prompt에 남지 않는다 | 오래된 detection으로 사용자 경고 금지 |
| G4 Evidence | metadata-only log/checklist로 transform path, bbox, depth source, sample count를 재현 가능하게 남긴다 | 이미지/depth 무단 저장으로 evidence 생성 금지 |
| G5 Report prep | Android source/metadata/privacy/cooldown 계약이 문서화되고 test 기준이 있다 | `/reports/v2` 자동 업로드 연결 금지 |

## 1~2일 실행 순서

1. P0 실기기 관찰 체크리스트를 먼저 고정한다.
2. P0 체크리스트로 overlay와 depth가 identity 가정에서 임시로 버틸 수 있는지 판단한다.
3. overlay 또는 depth가 밀리면 P1 ARCore mapper 구현으로 바로 전환한다.
4. P0이 임시 통과해도 현재 경로는 `identity` 임시 통과로만 기록하고, product PASS로 확대하지 않는다.
5. P2 metadata-only log 필드를 좌표 정합 판단에 필요한 수준으로 보강한다.
6. P3/P4/P5는 병렬로 문서와 테스트 기준을 정리하되, report/TTS 기능 연결은 gate 뒤로 둔다.

## P0. 실기기 overlay/depth 1차 판정

### 목표

bbox overlay, preview, depth sampling, `N보` 표시가 같은 객체를 기준으로 움직이는지 확인한다.

### Codex가 바로 할 일

- [단계] 실기기 관찰 checklist 문서를 만든다.
  -> 검증: 중앙/좌/우/상/하, portrait/landscape, 0.5m/1m/2m, stale 상태, metadata log 확인 항목이 모두 포함된다.

- [단계] 현재 overlay가 단순 `RectNorm.toScreenRect(width,height)` scaling이고 depth path는 `identityMapper`라는 사실을 checklist 상단에 명시한다.
  -> 검증: checklist에 “P0 통과는 ARCore transform 구현 PASS가 아니라 identity 임시 관찰 통과” 문구가 있다.

- [단계] 관찰 결과를 PASS/PARTIAL/FAIL/BLOCKED로 기록할 표를 만든다.
  -> 검증: 실기기 미수행 항목을 PASS로 적을 수 없게 `evidence_required`와 `observed_by` 칸이 있다.

### 사용자/실기기 필요 항목

- [단계] 기준 APK를 설치하고 사람 또는 큰 물체를 화면 중앙/좌/우/상/하에 둔다.
  -> 검증: bbox가 실제 객체 위에 있는지, 좌우 반전/상하 반전/비율 밀림/크롭 밀림을 위치별로 기록한다.

- [단계] 같은 장면에서 portrait와 landscape 또는 90도 회전 상태를 본다.
  -> 검증: 회전 후 bbox가 화면 밖으로 튀거나 축이 바뀌는지 기록한다.

- [단계] 가까운 단일 물체를 0.5m/1m/2m 수준으로 움직인다.
  -> 검증: best depth median, sample count, `N보` 표시가 대체로 단조롭게 변하는지 기록한다.

- [단계] detector 결과가 끊기거나 오래될 때 stale 표시와 depth 제외가 보이는지 확인한다.
  -> 검증: `age>2500ms` 상태에서 오래된 bbox가 depth 안내에 계속 쓰이지 않는지 기록한다.

### 판정

| 결과 | 다음 행동 |
|---|---|
| overlay와 depth가 모두 안정 | identity 경로를 `temporary_identity_observed`로 기록하고 P2/P3/P4 문서 보강 진행 |
| overlay만 안정, depth가 이상 | P1 depth mapper 우선 구현 |
| overlay부터 밀림 | P1 overlay/depth mapper 동시 구현 |
| frame drop 재발 | detector interval/in-flight/overlay draw cost 재점검 |
| 관찰 불가 | Device gate는 BLOCKED로 기록하고 Codex-only 작업만 진행 |

## P1. ARCore coordinate mapper 구현 준비 및 분기

### 목표

현재 identity 가정을 제거할 수 있도록 overlay mapper와 depth mapper를 분리하고, ARCore `Frame.transformCoordinates2d(...)` 기반 변환 가능 여부를 실제 의존 버전 기준으로 확인한다.

### 선행 조사

- [단계] 현재 Android ARCore 의존 버전과 `Coordinates2d` enum/source-target 조합을 로컬 dependency 또는 공식 문서 기준으로 확인한다.
  -> 검증: 정확한 enum 이름, camera image/display/depth 변환 가능 여부, depth target 직접 지원 여부가 문서에 적힌다.

- [단계] `Frame.transformCoordinates2d(...)`가 frame update timing과 display geometry update 순서에 어떤 제약이 있는지 확인한다.
  -> 검증: mapper 호출 위치 후보가 `ArCoreFrameProvider`/`MainActivity`/pipeline 중 어디인지 결정된다.

### 구현 분기

- [단계] 현재 identity 경로를 명명한다.
  -> 검증: log 또는 metadata에 `transform_path=identity`와 fallback reason이 남는다.

- [단계] overlay mapper와 depth mapper interface를 분리한다.
  -> 검증: overlay 출력 타입은 view pixel bbox이고, depth 출력 타입은 depth image pixel bbox/sample polygon이다.

- [단계] depth sampling 경로에서 `identityMapper(depthWidth, depthHeight)`를 직접 쓰는 위치를 mapper 주입 구조로 바꾼다.
  -> 검증: `ObjectDepthRuntimePipelineTest` 또는 static check에서 mapper 주입 케이스가 확인된다.

- [단계] ARCore transform adapter를 구현한다.
  -> 검증: 0/90/180/270 rotation fixture, 좌/우/상/하 bbox fixture, bounds clipping 테스트가 통과한다.

- [단계] 실기기에서 보정 전/후를 같은 checklist로 비교한다.
  -> 검증: bbox 위치 오차 또는 depth median 이상 현상이 줄어든 증거가 기록된다.

### PASS 금지

- 정확한 ARCore API 이름을 확인하지 않은 구현 완료 주장 금지.
- overlay만 맞고 depth mapper를 확인하지 않은 상태에서 coordinate gate PASS 금지.
- JVM 테스트만 통과하고 실기기 회전을 보지 않은 상태에서 Device PASS 금지.

## P2. metadata-only capture log/evidence 보강

### 목표

이미지/깊이 파일 없이 좌표와 depth 정합을 판단할 수 있는 최소 metadata를 남긴다.

### 추가 후보 필드

| 필드 | 목적 | 기본 저장 |
|---|---|---|
| `frame_timestamp_ms` | frame/depth 동기 확인 | yes, memory only |
| `detector_timestamp_ms` | detection age 계산 | yes, memory only |
| `detection_age_ms` | stale guard 확인 | yes, memory only |
| `class_name`, `confidence` | detector 결과 확인 | yes, memory only |
| `bbox_norm` | detector normalized bbox | yes, memory only |
| `bbox_view` | overlay 좌표 확인 | yes, memory only |
| `bbox_depth` | depth sampler 좌표 확인 | yes, memory only |
| `preview_size` | view scaling 판단 | yes, memory only |
| `camera_image_size` | camera coordinate 판단 | yes, memory only |
| `depth_size` | depth coordinate 판단 | yes, memory only |
| `display_rotation` | rotation issue 확인 | yes, memory only |
| `transform_path` | identity/ARCore/fallback 구분 | yes, memory only |
| `fallback_reason` | 왜 identity/fallback인지 설명 | yes, memory only |
| `depth_source` | Raw/Full/pseudo 구분 | yes, memory only |
| `depth_median_m` | 거리 추세 확인 | yes, memory only |
| `depth_sample_count` | sample 충분성 확인 | yes, memory only |
| RGB/depth file path | 원본 저장 | no, 별도 승인 전 금지 |

### 작업

- [단계] metadata schema를 문서화한다.
  -> 검증: 위 필드가 포함되고 RGB/depth 원본 저장이 기본 필수가 아님이 명시된다.

- [단계] Android UI/debug detail 또는 log summary에 transform path, depth size, sample count를 표시한다.
  -> 검증: 실기기 관찰자가 screenshot 저장 없이 텍스트로 값을 읽어 기록할 수 있다.

- [단계] ring buffer 보존 범위를 최근 N개 frame으로 유지하고 session stop/app restart 시 clear 정책을 확인한다.
  -> 검증: unit/static check에서 cap/clear/stale summary가 계속 통과한다.

- [단계] debug export 버튼은 별도 승인 전 만들지 않는다.
  -> 검증: 기본 APK 경로에서 파일 생성, 외부 전송, 원본 frame dump가 없다.

## P3. static RGB/model threshold 정리

### 목표

정적 RGB 산출물로 가능한 검증과 ARCore depth/`N보` 검증을 분리한다.

### 할 수 있는 것

- Android/backend threshold 차이의 의도와 risk 문서화.
- saved prediction labels/presence summary 기반 threshold tradeoff 표 작성.
- TFLite/PT artifact 존재 여부, class order, allowlist, threshold contract 확인.
- image-level presence 수준의 FP/FN 후보 분석.

### 할 수 없는 것

- ARCore metric depth 정확도 검증.
- bbox와 depth sample이 같은 객체를 가리키는지 검증.
- 실측 거리 기반 `N보` 정확도 검증.
- full static metric rerun. 현재 reviewed tactile3 원본 image/GT dataset이 없다.

### 작업

- [단계] `android_backend_threshold_decision_20260531.md`의 분리 유지 결정을 evidence index와 연결한다.
  -> 검증: Android recall 목적 threshold와 backend report precision 목적 threshold를 섞지 말라는 문구가 evidence에 남는다.

- [단계] saved reports 기반 threshold tradeoff 표를 만든다.
  -> 검증: `runs/reports/stage1_yolo26s_reviewed_test_presence_error_candidates_20260531/`와 `runs/reports/predictions_manifest_presence_20260531/` 출처와 한계가 표 아래에 적힌다.

- [단계] reviewed tactile3 dataset readiness check를 유지한다.
  -> 검증: dataset 부재 시 결과를 `blocked_for_full_static_eval_dataset_missing`으로 기록하고 PASS로 쓰지 않는다.

- [단계] Android TFLite config와 backend runtime config의 threshold diff를 계속 warning으로 다룬다.
  -> 검증: `check_android_tflite_contract_20260531.py` PASS는 “threshold 통합 완료”가 아니라 “차이 감지 및 warning 유지”로 기록된다.

## P4. ARCore RGB-D 검증 dataset 설계

### 목표

실측 거리와 ARCore depth를 비교할 수 있는 dataset schema를 먼저 정의한다. 기본은 metadata-first이며 원본 저장은 opt-in 이후다.

### 최소 schema

- `sample_id`, `session_id`
- `device_model`, `android_version`, `arcore_version`
- `timestamp_frame`, `timestamp_depth`
- `camera_rotation`
- `image_width`, `image_height`, `depth_width`, `depth_height`
- `camera_intrinsics`: `fx`, `fy`, `cx`, `cy`
- `display_transform` 또는 coordinate transform metadata
- `detector_source`, `model_asset_hash`, `runtime_config_hash`
- `class_name`, `confidence`, `bbox_norm`, `bbox_view`, `bbox_depth`
- `depth_median_m`, `depth_sample_count`, `depth_valid_ratio`, `depth_source`
- `distance_gt_m`, `gt_method`
- `step_length_m`, `expected_steps`, `predicted_steps`
- `scene_notes`: 중앙/좌/우/상/하, 조명, 흔들림, 착용 각도
- `privacy_flags`: 얼굴, 차량번호, 주소, GPS 정밀도, 마스킹 여부
- `rgb_frame_ref`, `raw_depth_ref`, `raw_confidence_ref`, `full_depth_ref`: 별도 opt-in 후에만 필수화

### metric

- [단계] 거리 metric과 step metric을 분리한다.
  -> 검증: MAE/RMSE, step bucket error, Raw/Full fallback rate, sample count 부족률이 별도 항목으로 정의된다.

- [단계] scene bucket을 나눈다.
  -> 검증: 중앙/좌/우/상/하, 실내/실외, 밝음/어두움, 정지/흔들림, 목걸이 착용 각도 항목이 있다.

- [단계] privacy gate를 먼저 만든다.
  -> 검증: 얼굴/차량번호/주소/GPS/원본 이미지 보존기간/마스킹/opt-in 규칙이 checklist와 일치한다.

## P5. backend/report 연결 전 계약 정리

### 목표

Android가 report upload를 붙이기 전에 source, metadata, privacy, cooldown, admin/export 표시 기준을 결정한다.

### 현재 backend 기준

- `/reports/v2`는 기존 `reports` 테이블을 재사용한다.
- 저장 허용 대상은 `custom_tactile:damaged_tactile_block`만이다.
- GPS 없으면 저장하지 않는다.
- `trigger`: `auto | voice`, `auto_reported`: boolean 계약이 있다.
- metadata는 allowlist 기반으로 저장하고 unknown extra는 삭제된다.
- admin/export는 CSV/JSON/GeoJSON, redacted/manifest/grid 옵션과 `Cache-Control: no-store`를 유지한다.

### 결정해야 할 항목

- [단계] Android report source를 정한다: `source=server` 유지 vs backend schema에 `android` 또는 `android-tflite` 추가.
  -> 검증: `backend/app/schemas.py`, report filter, export CSV/JSON field가 같은 결정을 반영한다.

- [단계] `source_model` 표준 포맷을 정한다.
  -> 검증: fake/demo/server/android 후보가 admin/export에서 구분 가능하다.

- [단계] Android metadata allowlist를 정한다.
  -> 검증: `apk_sha256`, `model_config_sha256`, `android_model_version`, `bbox_coordinate_space`, `depth_coordinate_space`, `depth_median_m`, `depth_sample_count`, `depth_source`, `detection_age_ms`, `coordinate_gate_status`의 저장/비저장 여부가 표로 정리된다.

- [단계] Android 초기 field data의 `data_origin`과 `performance_excluded` 정책을 정한다.
  -> 검증: coordinate gate 전 데이터가 admin/export와 metric 집계에서 섞이지 않는다.

- [단계] `coordinate_gate_pending` review flag 처리 위치를 정한다.
  -> 검증: client metadata 또는 server 계산 중 하나로 통일되고 export에서 식별 가능하다.

- [단계] Android 자동 신고 cooldown과 backend duplicate 기준을 맞춘다.
  -> 검증: 동일 class/GPS 반경/시간창에서 client 미전송과 server duplicate 후보가 충돌하지 않는다.

- [단계] 업로드 실패/거부 UX 계약을 정한다.
  -> 검증: GPS 없음, 422 non-reportable, 413 metadata too large, image content-type 오류별 TTS/무음/재시도 정책이 문서화된다.

### 승인 필요

- 운영 DB schema/migration, 운영 데이터 수정/삭제/초기화.
- backend/admin 운영 배포.
- Android release signing, 배포, 외부 공개.
- 공공기관 자동 제출/API 연동.
- TMAP/Kakao/Cloud STT/TTS secret 설정 또는 비용 발생 live API 호출.
- Android 이미지/depth 파일 저장 확대, 정밀 위치 보존 정책 변경.

## P6. 제품 기능 연결 순서

G1~G5가 통과되기 전에는 아래를 구현하지 않는다. 통과 후 순서는 다음과 같다.

1. TTS/haptic 최소 연결
   - [단계] `UserFacingDepth.message`를 Android `TextToSpeech`와 haptic으로 연결하고 rate limit을 둔다.
     -> 검증: 같은 객체에 대해 중복 발화가 과도하게 반복되지 않는다.

2. `damaged_tactile_block` report upload
   - [단계] `custom_tactile:damaged_tactile_block`만 `/reports/v2` 후보로 만든다.
     -> 검증: normal tactile, tactile damage area, COCO/general 객체는 저장 요청을 만들지 않는다.

3. GPS/heading 연결
   - [단계] report upload에는 GPS를 필수로 하고 heading은 가능할 때만 포함한다.
     -> 검증: GPS 없을 때 저장하지 않고 사용자/운영 문구가 정확하다.

4. Navigation/TMAP
   - [단계] Android client에 API key를 넣지 않고 backend navigation proxy 재사용을 검토한다.
     -> 검증: secret이 APK asset/source에 포함되지 않는다.

5. Voice/STT/TTS 확장
   - [단계] STT 서버 외부 URL, local/server 음성 경로, 비용/secret 여부를 별도 점검한다.
     -> 검증: 외부 URL·secret·비용 발생 가능성이 있으면 사용자 확인 후에만 연결한다.

## Codex가 사용자 도움 없이 바로 진행 가능한 작업

1. `docs/android/android_device_overlay_depth_checklist_20260601.md` 작성.
   - [단계] P0 실기기 관찰 표와 PASS 금지 문구 작성.
     -> 검증: `evidence_required`, `observed_by`, `result` 칸이 있다.

2. metadata-only log schema 문서화.
   - [단계] 좌표 정합 필드와 금지 원본 저장 항목을 표로 정리.
     -> 검증: RGB/depth 원본 저장이 기본 필수가 아니다.

3. ARCore mapper API 확인 작업.
   - [단계] 현재 Gradle dependency 기준으로 `Coordinates2d` enum/source-target 조합 조사.
     -> 검증: 정확한 API 이름이 확인되기 전 구현 완료로 표시하지 않는다.

4. threshold tradeoff/evidence 표 작성.
   - [단계] saved reports 출처와 한계를 표로 정리.
     -> 검증: static RGB 결과가 ARCore depth PASS 근거가 아님이 표에 포함된다.

5. backend Android source/metadata decision draft 작성.
   - [단계] `source=server` 유지안과 `source=android` 확장안을 비교.
     -> 검증: schema/filter/export 영향과 승인 필요 여부가 분리된다.

## 사용자/실기기 없이는 완료할 수 없는 작업

- 기준 APK 설치 후 overlay 위치 관찰.
- 실제 거리 0.5m/1m/2m에서 depth median과 `N보` 변화 측정.
- 목걸이 착용 각도, 흔들림, 조명 변화 field 확인.
- TalkBack/TTS/haptic 청취 품질 확인.
- 실제 GPS가 있는 report upload smoke. 단, upload 자체는 gate와 승인 전까지 하지 않는다.

## PASS로 쓰면 안 되는 항목

- Android build 성공만으로 Device PASS 주장.
- bbox overlay가 화면에 나온다는 사실만으로 좌표 정합 PASS 주장.
- depth sample 값이 나온다는 이유만으로 같은 객체를 읽었다고 주장.
- `1보` 표시가 뜬다는 이유만으로 실측 거리 정확도 PASS 주장.
- static RGB 결과를 ARCore depth, bbox-depth 정합, field `N보` 정확도 근거로 사용.
- `check_android_tflite_contract_20260531.py` warning이 error가 아니라는 사실을 threshold 통합 PASS로 사용.
- reviewed tactile3 원본 dataset 부재 상태에서 full static metric rerun 완료 주장.
- fake/demo/headless/PWA 결과를 Android 실기기 PASS로 확대.
- 이미지/depth/export/upload를 사용자 승인 없이 evidence로 생성.
- 운영 DB, 배포, secret, 비용 발생 API, 외부 제출을 승인 없이 진행.

## 다음 실제 실행 후보

| 순서 | 작업 | 파일/영역 | 완료 기준 |
|---|---|---|---|
| 1 | P0 체크리스트 작성 | `docs/android/android_device_overlay_depth_checklist_20260601.md` | 완료: 실기기 관찰 항목과 PASS 금지 기준 포함 |
| 2 | metadata schema 문서 작성 | `docs/android/android_metadata_capture_schema_20260601.md` | 완료: 원본 저장 금지와 좌표/depth 필드 포함 |
| 3 | ARCore mapper API 조사 | `docs/android/arcore_coordinate_mapping_plan.md` | 완료: ARCore 1.54.0 enum/source-target 확인 결과 기록 |
| 4 | threshold tradeoff 표 | `docs/evidence/android_static_threshold_evidence_20260601.md` | 완료: saved report 출처/한계 포함 |
| 5 | Android report source decision draft | `docs/walksafe-v2/android_report_source_metadata_decision_20260601.md` | 완료: source/schema/export 영향과 승인 필요 분리 |

## 2026-06-01 Codex 진행 상황

- 완료: P0 실기기 overlay/depth 체크리스트 작성.
- 완료: P2 metadata-only capture schema 문서 작성.
- 완료: P2 일부 구현. metadata-only log에 preview size, depth size, `transform=identity`, `fallback=arcore_mapper_not_connected` 표시를 추가.
- 완료: debug-only server metadata log upload 경로 추가. backend `/android/debug/depth-logs`는 기본 비활성이고 Android debug build에서 버튼으로만 켠다.
- 완료: P4 ARCore RGB-D 검증 dataset schema 문서 작성.
- 완료: P3 static RGB threshold evidence 문서 작성. full static rerun은 dataset 부재로 blocked 유지.
- 완료: P5 Android report source/metadata decision draft 작성. report upload 구현은 gate 전 보류.
- 완료: ARCore 1.54.0 `Frame.transformCoordinates2d(...)`와 `Coordinates2d` enum을 로컬 AAR API 기준으로 확인해 coordinate mapping plan에 반영.

남은 즉시 작업:

1. 실기기에서 `docs/android/android_device_overlay_depth_checklist_20260601.md`를 따라 P0 관찰을 수행한다.
2. 관찰 결과가 FAIL/PARTIAL이면 P1 mapper 구현으로 전환한다.
3. 관찰 결과가 좋아도 `temporary_identity_observed`로만 기록하고, product PASS로 확대하지 않는다.
