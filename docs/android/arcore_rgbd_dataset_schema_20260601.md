# Android ARCore RGB-D 검증 dataset schema - 2026-06-01

## 목적

Android native ARCore 앱의 거리 추정과 `N보` 안내를 검증하기 위한 RGB-D dataset schema 초안이다. 기본 전략은 metadata-first이며, RGB/depth 원본 파일 저장은 개인정보·용량·opt-in 정책이 확정된 뒤에만 활성화한다.

## 현재 상태

- Android 앱은 ARCore Raw Depth, Raw Confidence, Full Depth snapshot을 사용할 수 있다.
- 현재 APK는 metadata-only capture log를 갖고 있지만 RGB/depth 원본 저장 dataset capture 기능은 없다.
- 정적 RGB 산출물은 detector threshold 검토에는 쓸 수 있으나 ARCore metric depth 검증에는 쓸 수 없다.
- 실측 거리 기반 `N보` 정확도 검증은 아직 PASS가 아니다.

## dataset 단위

하나의 row는 “특정 frame에서 특정 detection bbox에 대해 depth를 sampling하고, 가능하면 실측 거리 GT를 연결한 sample”이다.

## 필수 metadata field

| group | field | required | 설명 |
|---|---|---|---|
| sample | `sample_id` | yes | sample 고유 ID |
| sample | `session_id` | yes | capture session ID |
| device | `device_model` | yes | 기기 모델 |
| device | `android_version` | yes | Android OS 버전 |
| device | `arcore_version` | recommended | ARCore SDK/서비스 버전 |
| time | `timestamp_frame` | yes | camera/depth frame timestamp |
| time | `timestamp_depth` | yes | depth image timestamp 또는 동일 frame 표시 |
| geometry | `camera_rotation` | yes | display/camera rotation |
| geometry | `image_width`, `image_height` | yes | camera image 기준 크기 |
| geometry | `depth_width`, `depth_height` | yes | depth image 크기 |
| geometry | `preview_width`, `preview_height` | yes | overlay view 크기 |
| camera | `camera_intrinsics.fx` | recommended | focal length x |
| camera | `camera_intrinsics.fy` | recommended | focal length y |
| camera | `camera_intrinsics.cx` | recommended | principal point x |
| camera | `camera_intrinsics.cy` | recommended | principal point y |
| transform | `display_transform` | yes | image/display transform 요약 |
| transform | `transform_path` | yes | identity/ARCore/fallback |
| transform | `fallback_reason` | conditional | fallback이면 이유 |
| detector | `detector_source` | yes | android-tflite/server/fake 등 |
| detector | `model_asset_hash` | yes | model asset hash |
| detector | `runtime_config_hash` | yes | Android runtime JSON hash |
| detector | `class_name` | yes | detection class |
| detector | `confidence` | yes | detection confidence |
| bbox | `bbox_norm` | yes | detector normalized bbox |
| bbox | `bbox_view` | yes | overlay view pixel bbox |
| bbox | `bbox_depth` | yes | depth image pixel bbox |
| depth | `depth_source` | yes | Raw/Full/pseudo/none |
| depth | `depth_median_m` | yes | robust median depth |
| depth | `depth_p20_m`, `depth_p80_m` | recommended | spread 확인 |
| depth | `depth_sample_count` | yes | valid sample count |
| depth | `depth_valid_ratio` | yes | valid/attempt ratio |
| depth | `raw_confidence_summary` | recommended | confidence quality |
| gt | `distance_gt_m` | conditional | 실측 거리 |
| gt | `gt_method` | conditional | 줄자/마커/수동 추정 등 |
| step | `step_length_m` | yes | 보폭 설정값 |
| step | `expected_steps` | conditional | GT 기반 expected step |
| step | `predicted_steps` | yes | 앱 표시 step |
| scene | `scene_position` | yes | center/left/right/top/bottom |
| scene | `lighting` | recommended | 밝음/어두움/역광 |
| scene | `motion` | recommended | 정지/걷기/흔들림 |
| scene | `wearing_angle` | recommended | 목걸이 착용 각도 메모 |
| privacy | `privacy_flags` | yes | 얼굴/차량번호/주소/GPS 포함 여부 |

## 원본 file reference field

아래 필드는 dataset v1에서 기본 필수가 아니다. opt-in, 마스킹, 보존기간, 저장 위치, 삭제 방법이 확정된 뒤에만 사용한다.

| field | 기본값 | 승인 전 상태 |
|---|---|---|
| `rgb_frame_ref` | null | forbidden |
| `raw_depth_ref` | null | forbidden |
| `raw_confidence_ref` | null | forbidden |
| `full_depth_ref` | null | forbidden |
| `overlay_screenshot_ref` | null | forbidden |

## metric 분리

| metric | 목적 | PASS 조건 후보 |
|---|---|---|
| distance MAE/RMSE | meter 단위 거리 오차 | GT distance가 있는 RGB-D dataset 필요 |
| step bucket error | `N보` 표시 오차 | step_length와 GT distance 필요 |
| monotonic trend | 가까워질수록 depth/보 수가 줄어드는지 | 실기기 반복 관찰 가능 |
| Raw fallback rate | Raw depth 사용률과 Full fallback 빈도 | depth source log 필요 |
| sample insufficiency rate | sample 부족으로 신뢰도 낮아지는 비율 | sample count/ratio 필요 |
| coordinate failure rate | overlay/depth bbox가 깨지는 비율 | P0 checklist + metadata 필요 |

## 수집 bucket

| bucket | 값 |
|---|---|
| 위치 | center, left, right, top, bottom, edge-clipped |
| 거리 | 0.5m, 1m, 2m, 3m 후보 |
| 자세 | 손持ち, 목걸이, 걷기, 정지 |
| 조명 | 실내 밝음, 실내 어두움, 실외, 역광 |
| 대상 | person, 큰 물체, normal_tactile_block, damaged_tactile_block 후보 |
| rotation | portrait, landscape 또는 90도 회전 |

## 작업 계획

- [단계] metadata-only sample row부터 만든다.
  -> 검증: RGB/depth 원본 ref 없이도 bbox/depth/GT/manual note를 기록할 수 있다.

- [단계] 거리 metric과 step metric을 분리한다.
  -> 검증: meter 오차와 `N보` bucket 오차가 같은 PASS 항목에 섞이지 않는다.

- [단계] privacy gate를 먼저 확정한다.
  -> 검증: 원본 frame/depth 저장 전 opt-in, 마스킹, 보존기간, 삭제 절차가 문서화된다.

- [단계] 실기기 P0 checklist 통과 후 dataset capture 구현 여부를 결정한다.
  -> 검증: 좌표 정합 실패 상태에서 dataset을 대량 수집하지 않는다.

## PASS로 쓰면 안 되는 항목

- static RGB image-level 성능을 ARCore depth 성능으로 주장하는 것.
- 실측 GT 없이 `N보` 정확도 PASS를 주장하는 것.
- metadata-only log만으로 거리 MAE/RMSE를 계산했다고 주장하는 것.
- 사용자 승인 없이 RGB/depth 원본을 저장하거나 외부 전송하는 것.
