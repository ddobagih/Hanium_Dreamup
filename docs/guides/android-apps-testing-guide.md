# Android 앱 빌드와 테스트 사용 가이드

현재 앱 빌드·JVM·연동 결과와 기기 미검증 범위는 [2026-09-30 현황](../handoff/2026-09-30/status.md)을 확인합니다. 새 clone은 SDK와 [Vosk 모델](#모델-자산-준비)을 준비해야 하며 APK 생성·AndroidTest 컴파일과 실제 기기 실행을 구분합니다.

이 문서는 현재 소스의 앱 구분과 실행 준비를 설명합니다. 빌드 성공, 자동 테스트 통과, 실기기 검증은 서로 다른 결과이며, 이 가이드 자체는 테스트 완료나 보행 안전성·거리 정확도를 증명하지 않습니다. 공통 검사 절차는 [테스트 가이드](testing-guide.md)를 참고합니다.

## 공통 준비

- JDK 21, Android SDK Platform 36, Android SDK Platform-Tools를 준비합니다. 앱의 최소 Android 버전은 API 26입니다.
- 저장소의 Gradle wrapper를 사용합니다. 현재 [루트 빌드 설정](../../apps/android/build.gradle.kts)은 Android Gradle Plugin 9.1.0, [wrapper 설정](../../apps/android/gradle/wrapper/gradle-wrapper.properties)은 Gradle 9.3.1을 지정합니다.
- SDK 위치는 개발 환경의 `ANDROID_HOME` 또는 로컬 `apps/android/local.properties`로 지정합니다. 개인 SDK 경로나 서명·인증 정보를 저장소에 올리지 않습니다.
- `positionevalapp`은 native PPK 엔진을 빌드하므로 Android NDK와 CMake 3.22.1 이상이 추가로 필요합니다. APK의 지원 ABI는 `arm64-v8a`입니다.
- 아래 명령은 별도 표시가 없으면 저장소 루트에서 실행합니다. 모델 준비는 이 문서 마지막 절을 먼저 확인합니다.

## 사용자 앱: debug, release, visualTest

[기본 Android 프로젝트](../../apps/android/settings.gradle.kts)의 `app`은 사용자 앱입니다. `visualTest`는 [별도 overlay](../../tools/visual-test/overlay/apps/android/)를 적용한 작업폴더에서만 빌드합니다.

| 구분 | 용도와 경계 | applicationId | 빌드 위치 |
|---|---|---|---|
| `debug` | 개발·진단용 사용자 앱. debug 전용 진단 코드가 포함됩니다. | `kr.co.hanium.dreamup.walksafe` | 원본 `apps/android` |
| `release` | 배포 구성을 확인하는 사용자 앱. debug 진단은 no-op이며 release 입력 검사를 거칩니다. | `kr.co.hanium.dreamup.walksafe` | 원본 `apps/android` |
| `visualTest` | 화면·객체 인식·거리 표시 등을 관찰하는 별도 테스트 앱. 물리적 거리 정답을 제공하는 도구는 아닙니다. | `kr.co.hanium.dreamup.walksafe.visualtest` | overlay를 준비한 새 작업폴더의 `apps/android` |

`debug`와 `release`는 같은 applicationId를 사용합니다. 서로 다른 서명으로 설치된 앱을 교체할 때 업데이트가 거절될 수 있으므로 기존 앱의 데이터를 지우는 조치를 자동으로 진행하지 않습니다. `visualTest`는 debug 설정을 상속한 디버깅 가능 앱이며 별도 applicationId와 debug 서명을 사용합니다. 다른 PC에서 재빌드하면 debug keystore 차이로 기존 visualTest 앱의 업데이트도 거절될 수 있습니다. Android의 일반적인 빌드·서명·APK 설치 방식은 [공식 명령행 빌드 문서](https://developer.android.com/build/building-cmdline)에 설명되어 있습니다.

사용자 debug APK:

```bash
(cd apps/android && ./gradlew :app:assembleDebug --no-daemon)
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

release에는 40자리 16진수 소스 커밋 ID인 `WALKSAFE_SOURCE_COMMIT`과 루트 HTTPS origin인 `WALKSAFE_GATEWAY_ORIGIN`이 필요합니다. 실제 값을 로컬 환경에 설정한 뒤 빌드합니다. release 서명·배포 승인과 실제 서비스 검증은 별도입니다.

```bash
(cd apps/android && ./gradlew :app:assembleRelease --no-daemon \
  -PWALKSAFE_SOURCE_COMMIT="$WALKSAFE_SOURCE_COMMIT" \
  -PWALKSAFE_GATEWAY_ORIGIN="$WALKSAFE_GATEWAY_ORIGIN")
```

visualTest 준비 스크립트는 커밋된 `HEAD`와 overlay를 사용하므로, 포함할 소스 변경을 먼저 커밋해야 합니다. 대상은 아직 없는 새 절대경로여야 합니다. 다음 예시는 저장소 옆에 작업폴더를 만듭니다.

```bash
VISUAL_TEST_WORKSPACE="$PWD/../walksafe-visual-test"
bash scripts/prepare_visual_test_workspace.sh "$VISUAL_TEST_WORKSPACE"
(cd "$VISUAL_TEST_WORKSPACE" && python3 scripts/prepare_vosk_ko_small_model.py)
(cd "$VISUAL_TEST_WORKSPACE/apps/android" && ./gradlew :app:assembleVisualTest --no-daemon \
  -PWALKSAFE_GATEWAY_ORIGIN="$WALKSAFE_GATEWAY_ORIGIN" \
  -PWALKSAFE_TMAP_MAP_KEY="$WALKSAFE_TMAP_MAP_KEY")
adb install -r "$VISUAL_TEST_WORKSPACE/apps/android/app/build/outputs/apk/visualTest/app-visualTest.apk"
```

`WALKSAFE_GATEWAY_ORIGIN`은 테스트 서버의 루트 HTTPS origin으로 설정합니다. 지도 사용에 필요한 `WALKSAFE_TMAP_MAP_KEY`는 개인 로컬 환경에서 주입합니다. 이 지도키는 APK에 포함되는 클라이언트 설정이므로 서버 전용 비밀값으로 취급할 수 없습니다. 외부 연결 구성은 [야외 테스트 Cloudflare 가이드](outdoor-test-cloudflare-guide.md)를 참고합니다.

설치 후 실제 테스트 계정으로 로그인하고 권한·앱 상태에 따른 안내를 따릅니다. `DEVELOPMENT_QUICK_START`를 켜는 절차는 사용하지 않습니다. 화면에 박스나 거리값이 보인다는 사실만으로 거리 오차나 보행 위험 안내의 정확도를 판단하지 말고, 실측 거리와 촬영 조건을 분리해 비교해야 합니다.

별도 로컬 기능 시험에서 `developmentQuickStart=true`를 사용하는 경우에도 계정 정보는 APK에 넣지 않습니다. 앱의 기존 로그인 화면에서 직접 로그인한 뒤 개발 기능 테스트를 시작합니다. 이전 `developmentQuickStartEmail`과 `WALKSAFE_DEVELOPMENT_TEST_PASSWORD` 빌드 입력은 더 이상 사용하지 않습니다. 이 옵션은 실외 보행 검증용이 아니며, 시험 계정 권한·수명 관리는 별도로 적용해야 합니다.

## 기존 기기점검의 샘플 이미지 성능 확인

- 기존 **기기점검**의 객체 인식 성능 검사는 앱에 포함된 고정 테스트 이미지4장만 사용합니다. 카메라 미리보기·ARCore 추적·실시간 Depth를 켜지 않으며, 물체를 비추거나 휴대폰을 움직이는 절차는 없습니다. 별도의 설정 점검 버튼도 없습니다.
- CPU1 참조 결과와 각 CPU/GPU 후보의 raw 출력·분할 mask를 같은 이미지로 비교합니다. 기본 인식과 FastSAM을 함께 실행한 샘플 전처리·추론·후처리 시간, 완료 수·완료율·처리 간격을 측정합니다. 카메라 촬영 지연·실제 거리 정확도·화면 렌더링 성능을 측정한 것으로 기록하지 않습니다.
- 모델 시험은 비공개 `:fixture_runtime` 보조 프로세스가 소유합니다. 후보별 준비·실행·종료 기한을 전체 기기점검 제한 안에 두고, GPU 생성이 반환하지 않으면 해당 보조 프로세스의 종료를 요청합니다. 실제 Binder death를 확인하기 전에는 다음 후보를 시작하지 않습니다. 앱 본체와 음성 디코더 프로세스를 종료하지 않습니다.
- 샘플 출력 동등성·실제 delegate/스레드·fallback 여부와 공동 성능 기준을 충족한 조합만 한 설정으로 저장합니다. 저장 형식은 `SAMPLE_IMAGES_TWO_MODEL`인 schema2이며 이전 실시간 점검 기록을 재사용하지 않습니다. 온도 관측 API가 없는 경우는 미관측으로 보존하고 정상 온도로 위조하지 않습니다.
- 시험용 모델과 보조 프로세스의 종료 확인 후 선택 설정으로 기본 모델을 다시 불러와 예제 추론을 검증합니다. 홈 카메라와 보행은 같은 기록의 두 모델 및 간격을 복원하며, 실제 영상의 frame·geometry·fresh depth 검사는 계속 별도로 수행합니다. 점검 전 준비는 CPU의 임시 초기 준비이며, 검증된 최종 조합을 뜻하지 않습니다.
- 거리 기능 점검은 기존 `DEPTH_API_SUPPORT_ONLY` 경로로 지원 여부만 확인합니다. 샘플 결과로 Depth 지원·미지원이나 실제 거리의 유효성을 판정하지 않습니다. 실제 사용 시 fresh depth gate는 유지합니다.
- `CameraCompositionCalibrationDeviceTest`의 명시적 입력은 `allowFixtureCompositionCalibration=true`입니다. 내장 이미지 측정·worker 종료·실제 유효 기록 저장을 시험하며 HOME·카메라를 열거나 계정·동의·교육을 만들어내지 않습니다. 이번 작업에서는 사용자 지시에 따라 이 실기기 시험을 실행하지 않습니다.
- `CameraHomeReopenDiagnosticDeviceTest`는 실제 카메라 진입을 따로 확인하는 기존 진단입니다. `restoredHome`과 `directCamera`를 구분하며 샘플 성능 검사의 일부로 자동 실행하지 않습니다.

## 관리자 앱

`adminapp`은 applicationId `kr.co.hanium.dreamup.walksafe.admin`의 별도 앱입니다. [관리자 빌드 설정](../../apps/android/adminapp/build.gradle.kts)은 비밀번호·TOTP 인증과 보안 제어 화면을 사용하며, 신고 검토·수동 기관 전달 기록 같은 운영 워크플로는 기본적으로 잠급니다.

```bash
(cd apps/android && ./gradlew :adminapp:assembleDebug --no-daemon)
adb install -r apps/android/adminapp/build/outputs/apk/debug/adminapp-debug.apk
```

- 서버 origin은 Gradle property 또는 환경변수 `WALKSAFE_ADMIN_API_ORIGIN`으로 지정합니다. debug 기본값은 `http://127.0.0.1:8000`이며 휴대폰의 loopback이 개발 PC를 자동으로 가리키지는 않습니다.
- debug의 HTTP 예외는 `127.0.0.1` 또는 `localhost`에 한정됩니다. 외부 테스트 서버는 루트 HTTPS origin을 사용합니다.
- 내부 운영 동작을 시험할 때만 `WALKSAFE_ADMIN_OPERATIONAL_DEBUG=true`를 명시합니다. 허용 값은 `true` 또는 `false`이고 기본값은 `false`입니다. 서버의 관리자·기기 등록과 인증 준비도 필요합니다.
- release는 정확한 루트 HTTPS `WALKSAFE_ADMIN_API_ORIGIN`이 필수입니다. 경로·query·fragment·사용자정보를 붙이지 않습니다. release에서는 debug 옵션과 관계없이 운영 워크플로가 잠깁니다.

```bash
(cd apps/android && ./gradlew :adminapp:assembleRelease --no-daemon \
  -PWALKSAFE_ADMIN_API_ORIGIN="$WALKSAFE_ADMIN_API_ORIGIN")
```

관리자 테스트는 권한이 있는 테스트 서버와 계정으로 진행합니다. 계정·비밀번호·TOTP·기기 등록 정보는 문서나 커밋에 포함하지 않습니다.

## 위치 평가 앱: positionevalapp

`positionevalapp`은 applicationId `kr.co.hanium.dreamup.walksafe.positioneval`의 후처리 앱입니다. 사용자 앱의 실시간 위치를 개선하거나 GPS를 직접 기록하는 앱과 구분합니다. 구성과 제한은 [위치 평가 앱 README](../../apps/android/positionevalapp/README.md), [빌드 설정](../../apps/android/positionevalapp/build.gradle.kts), [native 빌드 설정](../../apps/android/positionevalapp/src/main/cpp/CMakeLists.txt)을 기준으로 합니다.

```bash
(cd apps/android && ./gradlew :positionevalapp:assembleDebug --no-daemon)
adb install -r apps/android/positionevalapp/build/outputs/apk/debug/positionevalapp-debug.apk
```

1. 같은 테스트 시간대를 포함하는 WalkSafe trace v2와 GnssLogger 원시 TXT 기록을 준비합니다. 파일명만 비슷한 다른 세션의 기록을 섞지 않습니다.
2. 앱에서 개인 NGII 다운로드 키를 입력하고 `키 안전하게 저장`을 선택합니다. 실제 키를 소스나 명령행 예시에 넣지 않습니다.
3. `1. WalkSafe trace v2 선택`, `2. GnssLogger TXT 선택`으로 시스템 파일 선택기에서 입력을 지정합니다.
4. `3. 자동 분석 시작`을 선택합니다. 앱은 GnssLogger Raw를 RINEX3로 변환하고, 가까운 기준국의 해당 시간대 자료를 받아 내장 RTKLIB-EX 엔진으로 사후 PPK를 수행합니다.
5. 결과 상태와 유효 구간을 확인하고 필요하면 `진단·결과 JSON 저장`을 사용합니다. 작업을 마치면 `가져온 기록과 다운로드 키 삭제`로 앱 내부에 가져온 자료와 키를 삭제할 수 있습니다. 외부로 저장한 입력·결과 사본은 별도로 관리합니다.

평가에는 `Q=1` FIX만 1Hz 기준 격자로 사용합니다. 유효한 기준이 확보된 구간에서 raw·filtered·matched의 P50, P95, RMSE, 최대오차, 2m 이내 비율과 availability를 계산합니다. **2m 이내 비율은 측정 항목이며, 2m 정확도 달성 보장이 아닙니다.**

기준국 관측·항법 자료나 충분한 FIX가 없으면 `정답 부족`으로 끝납니다. 이를 오차 0m나 정확도 0점으로 바꾸거나 성공한 평가에 포함하지 않습니다. 변환기·native 엔진의 검증 조건이 충족되지 않아도 정답을 만들지 않습니다. 실제 NGII 다운로드와 사후 처리 성공은 유효한 키·동시간 기록·서버 자료가 있어야 확인할 수 있습니다.

이 기준은 같은 휴대폰의 원시 GNSS를 사후 처리한 개발용 비교값입니다. 독립 측량 장비의 정답이나 법적·측량용 성과가 아니며, ARCore 객체 거리의 정답으로 사용할 수 없습니다.

## 모델 자산 준비

사용자 앱 모델 선택·class·tensor·hash의 기준은 [two_model_runtime.json](../../apps/android/app/src/main/assets/model-config/two_model_runtime.json)입니다. 현재 `unified_walksafe` 키의 primary는 **WalkMate YOLO11n 21클래스 768**이며 `fallback_model`은 `null`입니다. 키 이름만 보고 이전 YOLO26 모델로 해석하지 않습니다. release 모델 allowlist는 실제 [사용자 앱 Gradle](../../apps/android/app/build.gradle.kts)을 함께 확인합니다.

| 설정 이름 | `app/src/main/assets/` 아래 자산 |
|---|---|
| `unified_walksafe` primary | `models/walkmate_yolo11n_21cls_768_float32.tflite` |
| 보조 분할 모델 | `models/fastsam_s_768_float32.tflite` |

기존 모델 자산은 버전 관리되는 파일을 사용합니다. 모델을 교체할 때는 파일명뿐 아니라 runtime config의 `artifact_sha256`, 입력 크기·dtype, 출력 구조·class 대응도 함께 맞춰야 합니다. 미커밋 모델이나 로컬 전용 자산은 `HEAD` 기반 visualTest 작업폴더에 자동으로 포함되지 않으므로 해당 폴더에서 별도로 준비합니다.

한국어 Vosk 모델은 빌드할 저장소 또는 visualTest 작업폴더의 루트에서 준비 스크립트를 실행합니다. 네트워크 다운로드와 로컬 저장 공간이 필요합니다.

```bash
python3 scripts/prepare_vosk_ko_small_model.py
```

[준비 스크립트](../../scripts/prepare_vosk_ko_small_model.py)는 고정 모델과 라이선스 자료를 내려받아 무결성을 확인합니다. 각 사용자 앱 변형의 assets 병합에는 `verifyBundledVoskModelAssets`가 연결되며 `MODEL_FILES.sha256`와 모델 파일을 검사합니다. 소스와 모델 준비가 끝나도 카메라·ARCore·위치·TTS·진동·장시간 보행의 실기기 결과까지 확인된 것은 아닙니다.
