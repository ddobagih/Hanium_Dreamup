# Android ARCore Depth Estimation Kotlin Module 설계

## 현재 반영 범위

- 기준일: 2026-07-01 KST
- 현재 저장소에는 `apps/android` Android Gradle 프로젝트와 실기기 설치 가능한 debug APK 산출물이 있다.
- 최신 APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`
- 최신 APK SHA-256: `1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9`
- 첨부 설계 기준 P0/P1 구현은 `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/` 아래 실제 Android 앱 소스에 반영되어 있다.
- `docs/android/kotlin/ArCoreDepthArchitecture.kt`는 초기 reference 산출물이다. 현재 production source-of-truth는 `apps/android` 쪽 구현이다.
- Gradle wrapper가 `apps/android`에 포함되어 있어 `./gradlew test --no-daemon`과 `./gradlew assembleDebug --no-daemon`로 JVM 테스트와 debug APK 빌드를 검증한다.

## 목표

ARCore Depth API에서 얻은 depth image를 탐지 bbox/tracking 결과와 결합해 객체별 거리, 신뢰도, 접근 상태를 만들고, 사용자에게 말할지 여부는 별도 정책으로 제한한다.

```text
ARCore Frame/Image
  -> ArCoreFrameProvider
  -> CoordinateMapper
  -> DepthSampler
  -> RobustDepthStats
  + MotionContext(route bearing alignment, freshness)
  -> TrackedObjectDepth
  -> MessagePolicy
```

현재 앱은 여기에 TFLite detector와 debug bbox overlay가 추가로 연결되어 있다.

```text
ARCore Frame camera image
  -> TfliteAndroidFrameDetector
     -> unified_walksafe primary if asset exists
     -> legacy custom_tactile+coco_general fallback otherwise
  -> ObjectDepthRuntimePipeline
  -> DebugBboxOverlayView
```

## 모듈 책임

| 모듈 | 책임 | Android 의존성 여부 |
|---|---|---|
| `ArCoreFrameProvider` | 최신 ARCore depth frame을 `DepthFrameSnapshot`으로 노출 | interface는 순수 Kotlin, 구현체만 Android/ARCore 의존 |
| `CoordinateMapper` | detector normalized bbox/point를 depth image pixel로 변환 | 순수 Kotlin |
| `DepthSampler` | polygon/mask 내부 sample을 만들고 depth frame에서 meter 값을 수집 | 순수 Kotlin |
| `RobustDepthStats` | invalid depth, outlier를 제거하고 median/IQR/confidence 계산 | 순수 Kotlin |
| `ObjectTracker` | track id 안정성, ID switch, 접근/TTC 신호 생성 | 순수 Kotlin |
| `TrackedObjectDepth` | track id, class, 거리, sample 품질, 접근 상태를 담는 data class | 순수 Kotlin |
| `MessagePolicy` | confidence/distance/approach/cooldown으로 TTS·haptic 여부 결정 | 순수 Kotlin |
| `TwoModelRuntimeConfig` | Android JSON config에서 asset/input/class/allowlist/threshold 로드 | Android asset 로드만 Android 의존 |
| `TfliteAndroidFrameDetector` | camera image를 unified TFLite primary 또는 legacy two-model fallback 입력으로 변환하고 detection 생성 | Android/TFLite 의존 |
| `DebugBboxOverlayView` | preview 위에 detection/depth bbox와 debug HUD 표시 | Android View 의존 |

## 구현 메모

1. Android adapter는 ARCore `Frame`과 depth `Image` lifecycle을 소유한다.
   - ARCore `Image`를 장기 보관하지 말고, 필요한 depth plane을 copy한 immutable snapshot을 만들거나 sampling을 image lifecycle 안에서 끝낸 뒤 닫는다.
   - `DepthFrameSnapshot`은 raw millimeter/format을 `DepthImage16`/`ConfidenceImage8` immutable snapshot으로 복사해 Image lifecycle 밖에서 안전하게 사용한다.
2. 좌표계는 실기기에서 반드시 검증한다.
   - 현재 overlay와 sampler는 detector bbox가 camera/depth stream과 같은 normalized 기준이라는 보수적 가정이 남아 있다.
   - preview crop, display rotation, camera image, depth image 좌표가 어긋나면 ARCore `Frame.transformCoordinates2d(...)` 기반 mapper를 adapter에서 적용한다.
3. depth 통계는 polygon 내부 robust sampling과 median/p20/IQR 중심으로 둔다.
   - bbox center 하나만 쓰지 않고 polygon을 erosion한 뒤 내부 sample을 수집한다.
   - 0m, NaN, 범위 밖 값, low confidence, median에서 크게 벗어난 outlier는 제외한다.
4. 메시지는 신고와 분리한다.
   - depth는 거리/접근 경고에만 사용한다.
   - 자동 신고 완료/실패 안내나 공공기관 제출 여부는 `MessagePolicy` 밖에서 다룬다.
   - 일반 객체는 가까움/접근 등 위험 context가 있을 때만 말한다.

## 현재 Android 구현 파일 배치

```text
apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/
  MainActivity.kt                 # ARCore session/render loop, depth snapshot, detector/background executor, overlay 연결
  CameraBackgroundRenderer.kt     # ARCore camera texture renderer
  DebugBboxOverlayView.kt         # developer bbox overlay/HUD
  MetadataCaptureLog.kt          # image/depth 저장 없는 최근 frame metadata ring buffer, transform/depth size debug summary
  debuglog/*                     # debug build 전용 metadata-only server log uploader

apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/depth/
  ArCoreFrameProvider.kt
  ArCoreImageConverters.kt
  DepthContracts.kt
  DepthFrameSnapshot.kt
  DepthGeometry.kt
  CoordinateMapper.kt
  MaskPolygonExtractor.kt
  DepthSampler.kt
  DepthSmokePipeline.kt
  ObjectTracker.kt
  ObjectDepthRuntimePipeline.kt
  DepthEstimator.kt
  MessagePolicy.kt
  MotionContext.kt
  TrackerRiskPolicy.kt
  TactilePathPolicy.kt

apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/
  AndroidDetectorContracts.kt
  TfliteAndroidFrameDetector.kt
  TwoModelRuntimeConfig.kt
  TwoModelClassMap.kt
  YoloEndToEndOutputParser.kt
  YuvImagePreprocessor.kt

apps/android/app/src/main/assets/model-config/two_model_runtime.json
apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/depth/*Test.kt
apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/inference/*Test.kt
scripts/check_android_depth_scaffold_20260531.py
scripts/check_android_tflite_contract_20260531.py
scripts/export_android_tflite_models_20260531.py
docs/android/arcore_coordinate_mapping_plan.md
docs/android/android_backend_threshold_decision_20260531.md
docs/android/android_server_debug_log_runbook_20260601.md
```

## 테스트/검증 기준

- `RobustDepthStats`
  - invalid sample(`NaN`, `0m`, 너무 먼 값)을 버린다.
  - outlier가 있어도 median이 안정적으로 유지된다.
  - valid sample 수가 부족하면 `null`을 반환한다.
- `CoordinateMapper`
  - 0/90/180/270도 rotation과 mirror 옵션이 expected pixel로 매핑된다.
  - bbox inset 후 sample point가 depth image 범위를 벗어나지 않는다.
- `DepthSampler`
  - confidence threshold와 sparse sample 조건을 만족하지 못하면 metric stats를 비운다.
  - outlier가 있어도 median/p20/IQR이 안정적으로 유지된다.
- `ObjectTracker` / `DepthEstimator`
  - stable track 전에는 approach speed/TTC를 만들지 않는다.
  - Raw Depth sample이 충분하면 Raw source를 쓰고, 부족하거나 confidence가 없으면 Full Depth로 fallback한다.
- `TwoModelRuntimeConfig`
  - JSON primary/fallback model key, asset path/input size/class order/allowlist/threshold를 로드한다.
  - 현재 기본값은 `primary_model=unified_walksafe`, `fallback_model=legacy_two_model`이다.
  - Kotlin detector 상수로 config가 분산되지 않는다.
- `MessagePolicy`
  - 낮은 confidence 또는 먼 객체는 말하지 않는다.
  - trusted metric source에서만 사용자 보폭 안내를 만든다.
  - normal tactile block은 장애물 경고가 아닌 path guidance로 처리한다.

## 남은 통합 리스크

- 현재는 normalized bbox → view/depth mapping을 같은 좌표계로 보는 가정이 남아 있다.
- overlay가 실제 객체와 밀리거나 회전되면 ARCore image/display/depth transform mapper를 구현해야 한다.
- ARCore depth availability는 기기/조명/표면에 영향을 받으므로 `UNAVAILABLE` 경로를 정상 UX로 취급해야 한다.
- Android 앱은 ARCore session/render loop, camera background renderer, Raw/Full Depth snapshot, unified-primary TFLite detector 계약, JSON runtime config, bbox overlay까지 포함한다.
- 남은 핵심 검증은 실기기에서 camera preview / detector bbox / depth sample 위치 정합성과 성능을 확인하는 것이다.
