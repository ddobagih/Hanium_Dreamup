# Android JVM Tests

이 source set은 Android runtime의 순수 정책과 직렬화 계약을 JVM/JUnit으로 검증한다.

주요 범위:

- depth sampling, tracking, TTC와 메시지 정책
- coordinate mapper와 YUV/TFLite output parser
- runtime JSON과 unified/legacy fallback 계약
- 768 unified TFLite SHA-256·float32 input/output FlatBuffer 계약
- GPS 신뢰도, 보폭, route navigator
- 공통 fixture 기반 TMAP 전역 경로/점자블록 local corridor 전환 정책
- feedback/device/report 후보와 multipart writer
- 앱 재시작 생존, JSONL rotation, 개인정보 제외를 포함한 field session 저장 계약
- `MainActivity`의 일부 접근성/report 연결 정적 검사

```bash
cd apps/android
./gradlew test --no-daemon
```

`MainActivity` 정적 검사는 소스 문자열 계약을 확인할 뿐 실제 UI 동작 테스트가 아니다. `src/androidTest`는 unified/legacy TFLite asset load·invoke만 확인하므로 camera permission, ARCore depth, SpeechRecognizer, TTS/진동, TalkBack, HTTP와 outdoor route는 별도 실기기 evidence가 필요하다.

## 정지·품질 저하·자동 회복 사례

2026-10-10 기준 사례 목록은 [센서 39개](resources/navigation/guidance_sensor_replay_v2_20261010.json), [정지 중심 전체 흐름 16개](resources/navigation/guidance_stationary_full_journey_v2_20261010.json)에 있다. 기존 v1 입력과 실행 결과는 보존한다. 현재 재확인 유예는 GPS 18초(3초 간격 6회), 방향 9초, 카메라의 일시적 측정 실패 4.5초다. 기존 위치의 실제 측정 유효시간 10초가 먼저 끝나면 위치 안내는 제한한다. 잘못된 수치·모의 위치·반복된 큰 위치 도약·실제 장착 불량에는 정상 측정을 빌려 주지 않는다.

| 상황 | 확인할 동작 |
| --- | --- |
| 60초 정지, GPS ±1m 흔들림 | 같은 경로·안내를 유지하고 제한을 반복하지 않음 |
| 정지, 방향 오차 변동·359→0도 경계 | 실제 회전 없이 안내를 취소하지 않음 |
| GPS 15m·방향 오차 29↔31도 경계 | 유예의 최초 시작 시각을 유지하고 작은 변동에 제한이 반복되지 않음 |
| GPS 일회성 누락·정확도 저하·단일 도약 | 현재 유효한 측정 범위에서 재확인, 원인 음성 안내 |
| GPS 중복·역순·미래·모의 측정 | 원래 측정 시각을 유지하거나 거절하며 새 정상 위치로 취급하지 않음 |
| GPS 또는 방향 장기 소실 | 해당 안내 제한, 실제 새 정상 측정으로 자동 회복 |
| GPS·방향 동시 소실, 회복 순서 교차 | 각 기능을 독립적으로 복구하고 이전 안내를 재생하지 않음 |
| 카메라 일시 장애·회복, 계속되는 장착 불량 | 일시 장애만 자동 회복, 실제 장착 불량은 교정 필요 |
| TTS 시작 지연·오류·이전 안내의 늦은 완료 | 현재 안내만 유효, 실제 START/DONE 이후에만 전달·완료 인정 |
| 실제 반대 방향·모퉁이·도착 | 오래된 안내 취소, 새 안내와 END 처리 |
| 권한 회수·화면/세션/경로 변경 | 이전 센서·음성 콜백 거절 |
| 위치 출력 제한 중 걸음 입력 | 현재 권한·세션의 움직임 입력은 유지, 위치 이동·안내 출력은 제한 유지 |

관련 실행 예시(저장소의 Android 디렉터리에서):

```bash
./gradlew :app:testDebugUnitTest --no-daemon --max-workers=1 \
  --tests '*GuidanceSensorReplayTest' \
  --tests '*GuidanceStationaryFullJourneyReplayTest' \
  --tests '*GuidanceFullJourneyReplayTest' \
  --tests '*RuntimeEnvironmentWarningContinuityTest' \
  --tests '*GpsAfterDeadlineRecoveryTraceTest'
```

전체 흐름 재현은 HOME부터 경로 시작·제한·회복·도착까지 실제 컨트롤러를 호출한다. Android 시간·센서 입력·일부 카메라 사실·네이티브 TTS 콜백은 시험에서 제어한다. 따라서 이 결과는 실제 스피커로 들은 안내, 실제 장착/카메라, 야외 좌표 정확도 또는 보행 안전의 확인 결과가 아니다. 휴대폰 적용 후에는 정지 60초 → 직진 → 실제 회전 → 일시 품질 저하 → 회복 순서로 화면·음성·측정 시각을 대조해야 한다.

## 신고 presenter의 권한 교체 경합

`UserReportPresenterAuthorityFenceTest`는 실제 `UserReportPresenter.render`와 예약된 화면 초기화를 실행한다. `TextView.setText` 등 final Android API가 있어 이 suite는 mockable Android SDK의 `returnDefaultValues` adapter를 사용하는 별도 JVM 실행 대상이다. 일반 엄격 SDK 실행 결과와 분리해 보고하며 전역 `returnDefaultValues` 설정이나 조건부 skip으로 숨기지 않는다. 이 adapter 결과는 화면 그리기·접근성·실기기 표시 확인 근거가 아니다. 검증 시 이전 권한의 clear를 큐에 넣고 새 권한을 render한 뒤 큐를 실행해, 이전 clear가 새 화면을 지우지 않는지 확인한다.

`MainActivityGuidanceSemanticStatusBehaviorTest`는 일시적인 밝기 부족(자동 회복)과 실제 장착 각도 오류(명시적 교정 필요)를 구분한다. 구체적인 카메라 원인이 있을 때도 `PhoneMountingPolicy`의 교정 조건과 안내 문구가 일치해야 한다. 이 검사는 클래스 추출로 생긴 회귀가 아니라, 기존 문구가 정책의 자동 회복 조건과 달랐던 불일치를 수정한 범위다.

## Android SDK adapter를 사용하는 분리 JVM 시험

별도 `returnDefaultValues` SDK adapter 실행 대상은 `UserReportPresenterAuthorityFenceTest` 3개, `RouteGuidanceCompassClockRaceRegressionTest` 4개, `AndroidEarthOrientationQueryReceiptRaceRegressionTest` 7개다. 마지막 모음은 엄격 JVM SDK의 `android.util.Log.isLoggable` 미구현 경계 때문에 분리한다. 실제 제품 객체와 경합 단언을 유지하며, 일반 엄격 SDK 시험과 결과를 합쳐 숨기거나 조건부 skip·전역 기본값 설정으로 대체하지 않는다. 이 결과는 실제 센서 입력, 화면 표시, 접근성, 음성 출력 또는 기기 동작의 검증 근거가 아니다.

위 3개 클래스의 14개 시험은 저장소에 정의된 전용 task로 실행한다. 저장소 루트에서 일반 시험과 전용 시험을 각각 호출한다.

```bash
cd apps/android
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:testDebugSdkAdapterUnitTest --no-daemon
```

일반 task는 `returnDefaultValues=false`를 유지하고 위 3개 클래스를 제외한다. 전용 task는 허용된 3개 클래스만 SDK 기본값 대역으로 실행한다. 전용 task는 정확한 이름으로 단독 호출해야 하며 다른 task와 함께 지정하면 거부된다. 외부 init 스크립트는 필요 없다. [현행 runner](../../../../../scripts/run_walksafe_test_layers_current.sh)의 `unit`·`all`은 두 Gradle 호출을 순서대로 수행하므로 CI에도 같은 분리가 적용된다.

`apps/android` 기준 일반 XML은 `app/build/test-results/testDebugUnitTest/`, 전용 XML은 `app/build/test-results/testDebugSdkAdapterUnitTest/`, 전용 HTML은 `app/build/reports/tests/testDebugSdkAdapterUnitTest/index.html`에서 확인한다. 전용 결과의 XML 3개·시험 14개와 일반 결과가 보존됐는지 함께 확인한다. 이 대역은 Android API의 기본값을 반환할 뿐 SDK 동작을 재현하지 않는다.

## 빌드 조건에 따라 실행되는 시험

일반 debug 설정에서는 개발용 안내 우회가 꺼져 있어 `MainActivityNavigationTestModeTest`의 7개가 실행되지 않는다. 신고 대기열도 기본 비활성 상태여서 uploader 6개, postcommit recovery 2개, 활성 용량 계약 1개가 별도 설정을 요구한다. 이 16개는 호출별 빌드 옵션으로 해당 기능을 켠 JVM 실행에서 확인하고, 일반 설정의 통과 수와 구분한다. 신고 시험은 메모리 HTTP 연결 또는 임시 로컬 서버·시험용 암호화 저장소를 사용한다.

`AndroidIntegratedConsentCasLiveHandoffTest`는 별도로 준비한 Android JVM–Gateway–Backend 실행 환경이 필요한 opt-in 통합 시험이다. 일반 실행에서 건너뛴 결과를 실제 연동 통과로 계산하지 않는다. 별도 옵션 시험 후 배포 후보를 빌드할 때는 원래 빌드 설정을 사용한다.
