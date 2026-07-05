# WalkSafe Android ARCore Depth

Android native ARCore depth/TFLite 구현입니다. 현재 WalkSafe의 주 사용자 앱 경로는 Web/PWA가 아니라 이 Android APK입니다.

## 목표

- ARCore `DepthMode.AUTOMATIC` 기반 metric depth 확보
- Raw Depth + confidence 우선, Full Depth fallback
- 객체 bbox/polygon 내부 robust depth sampling
- 실제 metric source만 사용자 보폭 거리 안내에 사용
- pseudo trend는 접근/멀어짐 추세만 안내

## 현재 상태

`apps/android`는 실기기 설치 가능한 debug APK를 만들 수 있는 Android Gradle 프로젝트입니다. 현재 Gradle wrapper는 `9.3.1`로 포함되어 있습니다.

```bash
cd apps/android
./gradlew test --no-daemon
./gradlew assembleDebug --no-daemon
```

생성 APK:

```text
/home/ddobagi/Code/hanium-dreamup/apps/android/app/build/outputs/apk/debug/app-debug.apk
sha256=1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9
size≈69M
```

실기기 설치:

```bash
adb install -r /home/ddobagi/Code/hanium-dreamup/apps/android/app/build/outputs/apk/debug/app-debug.apk
```

현재 앱 루프는 ARCore camera texture preview를 렌더링하고, 같은 `Frame`에서 camera image / Raw Depth / Full Depth snapshot을 수신합니다. runtime config는 `unified_walksafe`를 primary로 둔다. unified `.tflite` asset이 있으면 단일 모델을 먼저 로드하고, 아직 없으면 기존 custom tactile + COCO 두 asset을 legacy fallback으로 사용합니다. 모든 detector asset이 없으면 no-op detector로 안전하게 fallback합니다.

TTS/haptic 위험 안내와 Android `/reports/v2` upload 후보 생성은 Device Gate 뒤에 연결되어 있습니다. report upload는 `damaged_tactile_block`, fresh metric depth, trusted GPS, 허용된 model key가 모두 맞을 때만 시도합니다. 다만 실기기 bbox/depth 정합과 backend PostGIS no-skip 검증 전에는 제품 완료 evidence가 아니라 static/unit 구현 상태로만 봅니다.

## Runtime source of truth

Android TFLite asset 설정은 아래 JSON이 runtime source of truth입니다.

```text
app/src/main/assets/model-config/two_model_runtime.json
```

이 JSON이 다음 값을 결정합니다.

- `primary_model` / `fallback_model`
- unified/custom/COCO asset path
- input size
- unified 13-class class order
- legacy custom class order와 COCO 80 class order/allowlist
- class/default threshold

Primary asset:

```text
app/src/main/assets/models/walksafe_unified_yolo26n_640_float32.tflite
```

Legacy fallback assets:

```text
app/src/main/assets/models/custom_tactile_yolo26s_float32.tflite
app/src/main/assets/models/coco_yolo26n_float32.tflite
```

현재 로컬 작업트리에는 legacy fallback 두 asset이 배치되어 APK에 포함됩니다. unified asset은 아직 없지만 config가 이미 primary로 켜져 있어, 학습/export 완료 후 primary 경로에 파일을 넣으면 `unified_walksafe`가 우선 로드됩니다. 모바일 본명 학습 후보는 768 입력으로 진행 중이지만, 최종 768 TFLite asset이 생성되기 전에는 이 config를 존재하지 않는 768 파일명으로 바꾸지 않습니다. runtime parser는 `fallback_model=null`인 unified-only config도 받을 수 있으므로, 나중에 fallback asset을 제거하는 전환도 코드 수정 없이 config로 가능하다. root `.gitignore`의 `*.tflite` 정책 때문에 모델 asset은 Git 기본 추적 대상이 아니며, 다른 환경에서는 `scripts/export_android_tflite_models_20260531.py --unified <best.pt>`로 재생성하거나 artifact를 별도 전달해야 합니다.

## Debug bbox overlay

`DebugBboxOverlayView`가 ARCore preview 위에 developer overlay를 표시합니다.

표시 정보:

- detection count
- detector result age
- detector frame timestamp
- top detection class/confidence/source
- top detection bbox center/width/height
- best depth bbox center/width/height
- depth sample count/ratio/median
- raw detection bbox와 best depth bbox rectangle/center point

주의: overlay는 `Frame.transformCoordinates2d(IMAGE_PIXELS -> VIEW)` 결과를 GL frame에서 계산한 뒤 UI에 전달합니다. depth sampling도 `IMAGE_PIXELS -> TEXTURE_NORMALIZED` mapper를 우선 사용하지만, 실기기에서 bbox/depth/`N보` 정합은 아직 PASS가 아닙니다.

## Metadata-only capture log와 stale guard

- `MetadataCaptureLog`는 이미지/깊이 파일을 저장하지 않고 최근 frame metadata만 메모리 ring buffer에 보관합니다.
- 기록 필드: frame timestamp, detector frame timestamp, source/completed age, frame delta, detector timing, partial/skipped model, detection count, top bbox, best depth bbox, depth median/p20/risk distance/confidence/sample count, preview/camera/depth size, display rotation, overlay/depth transform path, fallback reason.
- 2026-06-01 latency fix 이후 depth transform path는 ARCore mapper 가능 시 `arcore_image_to_texture_normalized`, 불가 시 `identity` fallback으로 표시됩니다.
- session stop 시 capture log를 clear합니다.
- 오래된 결과는 완료 시각이 아니라 camera image capture/source age와 ARCore frame timestamp delta 기준으로 판단합니다. overlay는 약 1.2초, depth 입력은 약 0.8초를 넘으면 제외합니다.
- ARCore depth mapper가 없으면 MainActivity의 metric depth 입력을 막아 identity fallback sampling을 실사용 경로에서 억제합니다.
- 파일 export, screenshot, raw camera/depth dump는 별도 승인 전 추가하지 않습니다.

## Debug-only server metadata log

- debug build에는 local/dev 서버로 metadata-only depth debug log를 보낼 수 있는 버튼이 있다.
- 기본 상태는 꺼짐이며, 버튼을 눌러 명시적으로 켜야 한다.
- 현재 main manifest에는 `INTERNET` permission이 선언되어 있고, debug manifest에만 local cleartext HTTP가 포함된다. release 빌드의 report/debug upload 구현은 Noop 또는 gate로 막지만, 외부 공개 전 permission/네트워크 정책은 별도 검증한다.
- endpoint 기본값은 `http://127.0.0.1:8000/android/debug/depth-logs`다. 실기기 USB 연결 시 `adb reverse tcp:8000 tcp:8000`를 사용한다.
- 전송 payload에는 이미지, depth raw, confidence image, screenshot, GPS, audio, secret이 없다.
- backend endpoint도 `ANDROID_DEBUG_LOG_ENABLED=true`일 때만 동작한다.

runbook:

```text
docs/android/android_server_debug_log_runbook_20260601.md
```

## 검증 명령

```bash
python scripts/check_android_tflite_contract_20260531.py
python scripts/check_android_depth_scaffold_20260531.py
python scripts/export_android_tflite_models_20260531.py --dry-run
cd apps/android && ./gradlew test --no-daemon
cd apps/android && ./gradlew assembleDebug --no-daemon
```

현재 알려진 결과:

- Android TFLite contract check: PASS, backend threshold 차이는 warning
- Android depth scaffold check: PASS
- Android unit tests: BUILD SUCCESSFUL
- Android debug APK assemble: BUILD SUCCESSFUL
- MetadataCaptureLog unit test: Gradle test에 포함
- Stationary device smoke on `SM-G981N` / Android 13 / `R3CN50F4APH`: install Success, `am start -W` Status ok/COLD/TotalTime 480ms, process PID 16571, `MainActivity` RESUMED/visible/reportedDrawn, AndroidRuntime:E empty

## 남은 gate

- 정적 RGB 이미지는 detector/class/threshold 검증용이다.
- ARCore depth, `N보` 거리, bbox-depth alignment, approach/TTC는 움직이는 실기기 RGB-D 또는 실측 거리 데이터가 필요하다.
- reviewed tactile3 원본 image/GT dataset이 현재 로컬에 없어 full static metric rerun은 blocked다.
- TTS/haptic/report upload 코드는 Device Gate 뒤에 연결되어 있지만, bbox/depth 정합 전에는 제품 완료로 주장하지 않는다.

## 주의

- ARCore 세션 사용 중에는 별도 CameraX/Camera2 preview를 기본 경로로 열지 않습니다.
- SharedCamera는 depth-critical 기본 경로에서 제외합니다.
- Android 실기기 ARCore depth 검증에는 ARCore 지원 Android 기기, Google Play Services for AR, 카메라 권한이 필요합니다.
