# WalkSafe Android 사용자 앱

## 2026-10-10 책임과 테스트 경계

`MainActivity`는 Android 화면·수명주기·실제 입력을 연결한다. 작업의 상태와 종료 권한은 다음 구성 요소가 갖는다. 새 동작을 추가할 때 Activity에 병렬 상태나 별도 재시도 타이머를 더 만들지 않고 해당 소유자의 진입점을 사용한다.

| 경계 | 실제 책임 소유자 | 보존하는 계약 |
| --- | --- | --- |
| 시작 준비·방향 보정 | `NativeGuidanceCalibrationSession`, `WalkingCalibrationSession` | 원 측정 시각, 실제 음성 완료, 사용자·보행 세대, 프로필 저장 순서 |
| 경로·방향 안내 | `InitialRouteRetryEpisode`, `RouteFacingContinuityGate`, `RouteRecheckNoticeCoordinator`, `RouteDecisionSpeechCoordinator` | 현재 요청에 속한 재시도·질문·완료만 반영 |
| 음성 입력·출력 | `HandsFreeRuntimeSession`, `GuidanceSpeechPeerAdapter`, `UtteranceLedger` | 닫힌 입력 모델의 늦은 시작 차단, START/DONE/ERROR/STOP 구분 |
| 권한·HTTP | `PermissionFlowController`, `JsonHttpExchange` | 의도별 권한 상태 변경, 호출별 취소·정리; 도메인 오류 해석은 각 클라이언트 |
| 카메라·보조 모델 | `CameraFallbackController`, `UnknownRuntimeServiceOwner`, `UnknownCaptureAttempt` | 실제 해제 확인 전 재시작 차단, 이전 준비·캡처 결과의 새 작업 침범 방지 |
| 거리 검사·추적 이력 | `MetricPreflightAttempt`, `TrackState` | 장치 검사와 저장 프로필 갱신의 권한 구분, 내부에서만 이력 변경 |
| 화면 표시 | `NativePreviewPresentationPolicy`/`NativePreviewPresentationReader`, `AndroidExplicitReportPresentation`, `UserReportPresenter` | 상태 조회 순서·표시 권한·새 화면 이후 오래된 초기화 차단 |
| 진단 앱 화면 | `CameraSessionRelease`, `OutdoorObservationCleanup`, `OutdoorLocationRelease`, `OutdoorRawGnssRegistration` | 한 종료 실패가 나머지 정리를 건너뛰지 않음; 파일 완료와 센서 해제를 별도로 확인 |

기존 native SDK 소유자, 암호화 저장소, 개인정보 삭제·outbox, frozen/current frame 및 Raw/Full 거리의 별도 증거 이력은 유지한다. JNI/vendor·모델 자산·임계값을 패턴 통일 목적으로 바꾸지 않는다. 관리자와 위치 평가 앱은 독립 모듈이며 각각 [관리자 README](adminapp/README.md), [위치 평가 README](positionevalapp/README.md)를 따른다.

호스트 시험은 복제된 앱 로직 대신 실제 controller/session을 호출한다. `NativeGuidanceTestFixture`의 보정 진행·재시도도 실제 `NativeGuidanceCalibrationSession`에 연결한다. [시험 코드 안내](app/src/test/README.md)의 SDK adapter가 필요한 시험은 일반 엄격 JVM 시험과 구분한다. JVM·빌드 결과는 실제 청취·카메라/센서 해제·화면 표시·야외 보행 증거가 아니다. 아래 날짜가 붙은 빌드·기기 결과는 당시의 기록이다.

## 2026-09-30 현재 작업 안내

이 README는 현재 소스의 안내 문서이며, 아래에서 과거 기록으로 표시한 APK 경로·해시·시험 결과는 해당 시점의 이력이다. 새 팀원은 [인수인계 시작 문서](../../docs/handoff/2026-09-30/README.md), [현재 상태·검증 범위](../../docs/handoff/2026-09-30/status.md), [Part A: 보행·센서·카메라·안내 출력](../../docs/handoff/2026-09-30/part-a.md), [Part B: 계정·음성 입력·신고·서버](../../docs/handoff/2026-09-30/part-b.md)를 먼저 확인한다. 최신 원장 집계와 전체 시험의 실패·후속 재검증은 상태 문서에서 확인한다.

현재 진입점은 [`MainActivity.kt`](app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt)이며 센서·카메라·추론·음성·길안내·서버 기능을 조립한다. Part A가 이 파일의 단일 수정 담당이고 Part B의 연결 변경도 Part A에 요청한다. 상세 패키지 책임은 [현재 코드 지도](../../docs/guides/code/android-user.md)를 따른다.

최근 통합에서는 사용자 debug APK 빌드와 기기시험 코드 컴파일, 실제 Android HTTP 클라이언트의 JVM→Gateway→Backend 연동을 확인했다. 실제 Activity 자동 흐름, encrypted prefs를 포함한 프로세스 재시작, 최신 APK 설치·실기기·현장·운영 배포는 확인하지 않았다. 이 문서 갱신은 제품 시험을 새로 실행한 결과가 아니다.

WalkSafe의 일반 사용자용 정식 제품 후보입니다. 카메라로 가까운 위험을 찾고, TMAP으로 큰 이동 방향을 안내하며, 손상된 점자블록 신고를 돕습니다. 흰지팡이·안내견·보호자를 대신하거나 보행 안전을 보장하지 않습니다. 관리자 기능은 이 앱에 넣지 않고 별도 Android 관리자 앱에서 처리합니다. 두 앱은 고유 식별자·서명·세션·배포경로를 분리합니다.

이 앱의 ARCore depth/TFLite 구현과 CameraX 제한 모드는 아직 내부 구현·검증 단계입니다. debug APK나 과거 Web/PWA 시험 결과는 정식 출시 완료 근거가 아닙니다. 지원 OS·카메라·위치·음성·거리 기능 검사와 정식 실기기 시험을 통과하기 전까지 출시는 `NOT_ELIGIBLE`입니다.

EPIC-01 1차 시작 gate는 Android 12+, 카메라, GPS, 마이크, 진동, 온디바이스 STT와 오프라인 한국어 TTS를 확인한다. `FULL`은 안정적인 live ARCore 미터 거리와 승인된 지정 기기 프로필·비어 있지 않은 프로필 버전이 모두 있어야 하며 현재는 허용하지 않는다. 시작 제한 고지는 TTS 완료 또는 TalkBack 전달 뒤에만 확인되고, STT/TTS의 구조적 실행 실패는 확인·경로를 무효화해 카메라·위치·걸음·방향 출력을 안전중지한다. 앱 재개와 권한 callback도 확인 없이는 센서를 다시 시작하지 않는다.

FP-004 최초 사용 경계는 전맹·저시력 사용자를 같은 우선순위로 두고 화면읽기·큰 글자·고대비 상태를 표시한다. 교육 상태는 검증된 로그인 계정 ID의 SHA-256으로 분리한 계정별 앱 전용 프로필에 저장한다. 프로필을 바꾸기 전에는 계정별 무효화 표식을 동기식으로 저장하고, 새 스냅샷과 표식 해제를 한 커밋으로 확정한다. 두 번째 커밋이 실패하면 무효화 표식이 남아 다음 실행에서도 과거 완료 상태를 복원하지 않으며, 첫 표식 저장 자체가 실패하면 변경을 수락하지 않고 현재 실행을 차단한다. 소유자를 증명할 수 없는 이전 전역 교육 상태는 어떤 계정에도 이관하지 않는다. 현재 `EMAIL_ACCOUNT_V4` 가입은 사용자가 입력한 생년월일을 서울 날짜 기준으로 계산해 만 14세 미만이면 서버가 이메일 OTP 발급 전에 차단한다. 만 14세 이상은 보호자 확인 없이 이메일 OTP로 가입하고 로그인하며, 이 자기입력 방식은 본인·명의·공적 연령 인증이 아니다. 과거 `LEGACY_PHONE_V3`의 14~17세 보호자 게이트는 현행 가입 경로에서 사용하지 않는다. 만 14세 이상 사용자는 안전 제한을 들은 뒤 실제 도로가 아닌 장소를 확인하고 위험 안내·일시정지·재개·안전정지 네 동작을 순서대로 직접 연습해야 첫 보행을 시작할 수 있다. 위험 안내 확인은 연습 상태를 다음 단계로 전이하되 실제 보행 상태는 `ACTIVE`로 유지하고, 일시정지·재개·안전정지는 센서 없는 연습 상태에서 제품 보행 생명주기 전이를 실행한다. 안내 또는 연습 음성은 TTS 재생 종료가 확인돼야 하며, 연습은 진동 요청이 수락되고 비반복 패턴 시간이 지난 뒤에만 완료로 기록한다. 이는 실제 기기의 물리 진동 전달을 입증하는 정식 시험이 아니다. 교육 완료 기록은 해당 계정에서 앱 재시작 뒤 보존하지만 이전 보행·경로는 복원하지 않으며, 계정 전환·로그아웃·필수 채널 상실 시 진행 중 연습을 폐기하고 필요한 경우 활성 보행을 안전정지한다. 실제 전맹·저시력·초보 사용자 시험은 아직 끝나지 않았으므로 내부 구현 근거가 정식 접근성 PASS를 뜻하지 않는다.

## 사용자 문서 후보

- [사용자 설명서 초안](USER_GUIDE.md): `REL-17 DRAFT`이며 승인된 정식 사용자 설명서가 아닙니다.
- [릴리스 설명 후보](RELEASE_DESCRIPTION.md): `REL-09 PLANNED/NOT_RUN`, `INTERNAL_DRAFT_NOT_PUBLISHED`이며 실제 게시·배포 문안이 아닙니다.

두 문서는 현재 기능과 안전 한계를 쉽게 검토하기 위한 후보입니다. 정식 시험과 승인을 대신하지 않으며 출시 상태는 계속 `NOT_ELIGIBLE`입니다.

## 목표

- ARCore `DepthMode.AUTOMATIC` 기반 metric depth 확보
- Raw Depth + confidence 우선, Full Depth fallback
- 객체 bbox/polygon 내부 robust depth sampling
- 실제 metric source만 사용자 보폭 거리 안내에 사용
- pseudo trend는 접근/멀어짐 추세만 안내

## 현재 상태

`apps/android`는 실기기 설치 가능한 debug APK를 만들 수 있는 Android Gradle 프로젝트입니다. 현재 Gradle wrapper는 `9.3.1`로 포함되어 있습니다.

새 clone에는 Git에서 제외한 Vosk 모델이 없으므로 저장소 루트에서 먼저 준비한다. 준비 스크립트는 고정된 다운로드·파일 해시를 확인한다. JDK 21·Android SDK 36과 로컬 설정은 [개발 환경 가이드](../../docs/guides/development-environment-guide.md#android-사용자관리자-앱), 앱별 시험 범위는 [Android 앱별 테스트 가이드](../../docs/guides/android-apps-testing-guide.md)를 따른다.

```bash
python3 scripts/prepare_vosk_ko_small_model.py
cd apps/android
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:testDebugSdkAdapterUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

일반 unit 시험은 엄격한 SDK stub(`returnDefaultValues=false`)을 유지한다. [3개 클래스·14개 SDK 대역 정책 시험](app/src/test/README.md#android-sdk-adapter를-사용하는-분리-jvm-시험)은 위 전용 task를 별도 Gradle 호출로 실행하며, 다른 task와 함께 지정하면 거부된다. 외부 init 스크립트는 필요 없고 [현행 runner](../../scripts/run_walksafe_test_layers_current.sh)의 `unit`·`all`과 CI가 두 호출을 자동으로 수행한다. 전용 XML은 `app/build/test-results/testDebugSdkAdapterUnitTest/`, HTML은 `app/build/reports/tests/testDebugSdkAdapterUnitTest/index.html`에서 일반 결과와 구분해 확인한다. 이 SDK 기본값 대역 결과는 실기기 동작 증거가 아니다.

debug에서 commit을 주입하지 않으면 provenance는 명시적으로 `unverified`다. release APK는 정확한 40자리 commit과 승인된 단일 HTTPS gateway origin을 함께 전달하지 않으면 build가 실패한다.

```bash
WALKSAFE_SOURCE_COMMIT=<40_HEX_COMMIT> \
WALKSAFE_GATEWAY_ORIGIN=https://<APPROVED_GATEWAY_HOST> \
./gradlew assembleRelease --no-daemon
```

현재 Gradle project에는 운영 signingConfig를 저장하지 않으므로 `assembleRelease` 결과는 `app-release-unsigned.apk`다. RC 검사는 native multidex 위치와 무관하게 유일한 WalkSafe `BuildConfig` 정의의 static field에서 source commit과 release marker를 읽는다. 이는 DEX source-commit marker 검증용 산출물일 뿐 배포 APK가 아니다. 실제 `android-research`/`full` evidence는 승인된 외부 keystore로 서명한 뒤 `WALKSAFE_ANDROID_RELEASE_SIGNER_SHA256` trust anchor와 `apksigner`/`apkanalyzer` gate를 통과해야 한다.

과거 APK 기록 (2026-07-15 RC2 작업 경로, 현재 산출물의 해시가 아님):

```text
/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715/apps/android/app/build/outputs/apk/debug/app-debug.apk
sha256=410fb694f832c1f235b3682424147d0257ffc72232598aaeee501d47da603ac1
size=86,385,040 bytes
```

같은 과거 기록의 AndroidTest APK는 369,615 bytes, SHA-256 `deaaa81863a4c981431ada91ec2f5ee5756bd2fdb493ccfdced9cc5e210d0824`다. 당시 정확한 source commit과 모델 해시는 이 기록만으로 확인할 수 없으며 현재 모델의 검증 근거로 사용하지 않는다.

실기기 설치:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

debug와 release APK는 같은 applicationId(`kr.co.hanium.dreamup.walksafe`; 관리자 앱은 `kr.co.hanium.dreamup.walksafe.admin`)를 쓰므로 한 단말에 함께 설치되지 않는다. 정식 식별자는 FP-007-DETAIL-01로 고정되어 있고 공존 설치 요구가 없으므로 개발용 suffix는 두지 않는다. 서명이 다른 APK(debug↔release, 다른 debug keystore)로 바꿀 때는 다음 절차를 따른다.

1. 개인 사용 단말이 아닌 시험 전용 단말만 쓴다.
2. `adb uninstall kr.co.hanium.dreamup.walksafe`로 기존 앱을 지운다. 앱 전용 저장소와 앱의 Android Keystore 키가 함께 지워지므로 Gateway 세션, 앱 안 동의 기록, 전송 전 신고 대기열도 남지 않는다. 필요한 기록은 지우기 전에 확인한다.
3. 새 APK를 설치하고 로그인·동의를 처음부터 다시 진행한다.

같은 서명끼리의 갱신만 `adb install -r`로 앱 자료를 유지한다. 서명이 다르면 설치가 거부되며, 이를 우회하려고 식별자를 바꾸지 않는다.

현재 앱 루프는 ARCore camera texture preview를 렌더링하고, 같은 `Frame`에서 camera image / Raw Depth / Full Depth snapshot을 수신합니다. 현재 primary는 21개 출력 ID를 가진 WalkMate YOLO26n `unified_walksafe`입니다. float32 입력 `[1,768,768,3]`, 출력 `[1,300,6]`은 입력 영상 픽셀 단위 xyxy, 점수, 클래스 ID입니다. 9·10번 ID는 모두 `e_scooter`이며 “킥보드”로 안내합니다. 방치·이동 상태는 분류 이름으로 판단하지 않습니다. `fallback_model=null`이므로 주 모델 실패 시 legacy 모델로 자동 전환하도록 설정되어 있지 않습니다. GPU 실패 시 CPU delegate 대체는 모델 fallback과 별개입니다. 현재 모델의 실기기 실행 성공은 과거 smoke 기록으로 대체하지 않습니다.

ARCore 미지원 단말은 설치 대상에서 제외하지 않는다. 이 경우 `CAMERA_IMU_NON_METRIC` 제한 모드가 CameraX 후면 카메라와 TFLite 탐지를 사용하며, camera permission·fallback session·detector·fresh IMU가 모두 맞으면 목적지와 활성 TMAP 경로 없이도 동작한다. 같은 후보의 서로 다른 연속 3프레임·700ms 안정화 뒤 좌·가운데·우 low 보조 경고만 만들고, 전달 직전에 tier·lifecycle·freshness를 다시 확인한다. 안정 후보는 전달 중 항목을 포함해 최대 3개의 bounded FIFO에서 같은 심각도끼리 순서대로 처리한다. TTS 완료 또는 TalkBack 전달 확인 뒤에만 cooldown을 시작하며 전달 실패는 cooldown을 소모하지 않는다. gate 실패·전달 시작 전 TTL 만료·후보 소실 시 대기 경고를 버리고 카메라 보조 경고를 중단한다.

이 제한 모드는 거리·보폭·STOP/high·local steering·안전 경로·경로 변경·report 후보를 만들지 않는다. P-09에 따라 low 보조 경고는 TTS/TalkBack만 사용하고 진동하지 않는다. `normal_tactile_block`은 low 안내가 가능하지만 `damaged_tactile_block`과 `tactile_damage_area`는 제외한다. 기존 ARCore metric 위험·route-bound tactile 연구 경로와 별도 damage-only report 경로는 그대로 유지한다.

TTS/haptic 위험 안내와 Android `/api/reports/v2` upload 후보 생성은 Device Gate 뒤에 연결되어 있습니다. 위험 안내와 자동 신고는 같은 detector 결과를 AR frame마다 중복 계산하지 않고, 서로 다른 완료 추론 3개와 700ms 안정성을 모두 만족할 때만 허용합니다. 자동 신고는 원본 탐지 confidence 0.70 이상도 요구합니다. report upload는 `damaged_tactile_block`, fresh metric depth, 15m 이내·10초 이내 trusted GPS, 허용된 model key가 모두 맞을 때만 시도합니다. 자동 신고 성공 후 같은 named actor/class의 25m 반경은 10분간 다시 자동 전송하지 않으며, app-private cooldown 기록은 Activity/process 재생성 후에도 복원하고 명시적 음성 신고만 이 cooldown을 우회합니다. Android는 production backend service token이나 actor header를 직접 보내지 않습니다. 독립 Android API Gateway의 `/api/field-session`에 named actor/account token으로 로그인한 뒤 actor가 일치하는 status 응답을 확인하고, 최대 12시간의 Gateway session cookie는 Android Keystore AES-GCM으로 암호화해 앱 전용 저장소에 보관합니다. process 재생성 뒤 같은 actor·gateway·유효기간인 경우에만 복원하고 실제 서버 기능 직전에 다시 검증합니다. release APK의 gateway는 build-time `WALKSAFE_GATEWAY_ORIGIN`과 정확히 일치하도록 고정하고 URL 편집과 cleartext `localhost`/`127.0.0.1` adb reverse는 debug APK에서만 허용합니다. 명시적 로그아웃·actor 변경·인증 만료는 local cookie를 제거하지만 Android 권한, 원본 신고 동의, 별도 자동신고 동의와 이동통신망 선택은 바꾸지 않습니다. `/privacy/rights`는 앱 설치와 로그인 없이 서버 자료 열람·동의 철회·삭제 접수 경로를 안내합니다. 연결 실기기 instrumentation의 load/invoke 2/2 PASS는 과거 unified 768 모델 기록이며(아래 검증 이력), 현재 21클래스 primary의 실기기 실행 증거가 아니다. camera→inference→bbox/depth·TTS/haptic·gateway의 대화형 전체 흐름도 아직 Device Field 근거가 아니다.

## 통합 동의 복원과 충돌 처리

[`IntegratedConsentPolicy.kt`](app/src/main/java/kr/co/hanium/dreamup/walksafe/session/IntegratedConsentPolicy.kt)는 저장된 확인서와 현재 Backend 권한을 분리한다. 캐시 복원은 `backendAuthorityVerified=false`로 시작하고 서버 재확인 전에는 전송 권한을 주지 않는다. 실제 소비 시에도 현재 세대의 guard 안에서 확인서·철회·pending 선택을 다시 검사한다.

정확한 `privacy_consent_previous_receipt_conflict` 409에서만 원 요청 미저장을 확인하고, 첫 불변 요청과 최신 predecessor·기존 `false` 선택을 보존한 채 새 request ID·Backend revision floor+1로 재조정한다. 자동 재조정은 실행당 한 번이며 다른 오류는 같은 ID로 재시도한다. production 저장은 [`RejectedConsentCasJournalCodec.kt`](app/src/main/java/kr/co/hanium/dreamup/walksafe/session/RejectedConsentCasJournalCodec.kt)의 V2 압축 journal을 사용하며 Backend·Gateway 감사 이력을 삭제하지 않는다. 이 경로의 JVM 검증과 실제 Activity·프로세스 재시작 미검증은 [현재 상태 문서](../../docs/handoff/2026-09-30/status.md)에서 구분한다.

## Runtime source of truth

Android TFLite asset 설정은 아래 JSON이 runtime source of truth입니다.

```text
app/src/main/assets/model-config/two_model_runtime.json
```

이 JSON이 다음 값을 결정합니다.

- `primary_model` / `fallback_model`
- unified/custom/COCO asset path
- input size
- unified 21-class class order
- legacy custom class order와 COCO 80 class order/allowlist
- class/default threshold

Primary asset:

```text
app/src/main/assets/models/walkmate_yolo26n_21cls_768_float32.tflite
```

Legacy 호환성 검사 자산 (현재 자동 fallback으로 선택하지 않음):

```text
app/src/main/assets/models/custom_tactile_yolo26s_float32.tflite
app/src/main/assets/models/coco_yolo26n_float32.tflite
```

현재 설정은 primary와 legacy 호환 자산을 선언하지만 활성 모델은 primary 하나입니다. primary asset은 `artifact_sha256`, 원본 PT는 `source_model_sha256`으로 고정합니다. 아래 요약은 활성 문서 검사에서 현재 JSON과 대조합니다. 이 일치는 실제 빌드나 실기기 추론 PASS를 뜻하지 않습니다.

| 항목 | 현재 설정 |
| --- | --- |
| primary_model | `unified_walksafe` |
| asset | `models/walkmate_yolo26n_21cls_768_float32.tflite` |
| class_count | `21` |
| input_shape | `[1,768,768,3]` |
| output_shape | `[1,300,6]` |
| output_format | `yolo_end_to_end_xyxy_pixels` |
| fallback_model | `null` |
| artifact_sha256 | `41ec417342f6aea7f4735de009e257ffe92071a055647b9a981f451089d3e79c` |

9·10번 이름은 같지만 기존 출력 ID별 검출 임계값(9번 0.25, 10번 0.15)은 유지합니다. 거리·위험 판단과 정지 음성 우선순위는 변경하지 않았습니다. [변환 및 제한된 호스트 검증 결과](../../model/artifacts/candidates/walkmate_21cls_yolo26n_img768_20261020/README.md)를 참고하세요. 기존 YOLO11n 파일·시험 결과는 과거 검증용으로 보존하며 release APK에는 포함하지 않습니다.

## TMAP과 점자블록 로컬 경로

- 목적지까지의 전역 경로와 재탐색·도착 판정은 항상 TMAP 보행 경로를 사용합니다.
- production `AndroidTactileRouteObservationSupplier`는 ARCore physical camera pose, 같은 capture frame ID의 metric depth, trusted GPS, Earth orientation·자기 편차, 현재 TMAP route projection을 결합해 route-bound 정상 점자블록 관측을 생성합니다. `MainActivity` 조립 경로가 이 supplier에 동일 capture frame detection/depth/context를 전달합니다.
- frame ID, route ID, depth mapper, camera orientation, GPS 정확도·시각 또는 tactile policy gate 중 하나라도 맞지 않으면 observation을 버리고 현재 TMAP 안내로 즉시 복귀합니다. 화면 중앙 bbox와 GPS movement heading만을 projection 대체 근거로 쓰지 않습니다.
- `damaged_tactile_block`은 위험·신고 gate의 입력일 수 있지만 이동 가능한 점자 경로로 선택하지 않습니다.
- 현재 근거는 CODE·JVM AUTO이다. camera calibration/EIS 후 정합, GPS drift, 실외 점자블록 alignment·이탈·복귀 품질과 사용자 안전성은 Device Field로 검증하지 않았습니다.
- capability tier는 ARCore·Depth 지원 시 기존 `ARCORE_METRIC`, ARCore 미지원이지만 camera permission·CameraX fallback session·detector·fresh IMU gate를 만족하면 `CAMERA_IMU_NON_METRIC`, 그 밖에는 `TMAP_ONLY`다. 목적지와 경로 상태는 이 위험 안내 gate가 아니라 별도 길안내 gate에서 판단한다. ARCore 미지원 단말의 실제 camera/IMU/TTS/TalkBack 동작은 아직 Device Field 미검증이며, low 보조 경고는 진동하지 않는 것이 계약이다.
- confirmed off-route에서는 재탐색 진행·cooldown·최대 횟수 도달 여부와 관계없이 기존 경로의 회전 안내를 반환하지 않습니다. rate-limit된 `off_route_waiting` 안전 문구만 사용하고 `currentInstruction`도 `null`로 닫힙니다.
- turn guide와 안내 cooldown은 app navigation TTS의 `onDone` 뒤에만 acknowledge한다. TTS 초기화 중에는 길안내를 queue에 넣지 않고 상태기가 다음 위치 갱신에서 재시도하며, 위험/상호작용이 길안내를 끊으면 callback을 취소한다. 회전 지점은 안내 완료 뒤에도 지점 이후 progress margin 또는 접근 후 거리 증가가 확인돼야 소비한다. TalkBack 상태 중에도 navigation은 중복 accessibility announcement 대신 이 completion-aware app TTS를 사용한다.
- Android route client는 TMAP provider/schema/`STAIR_AVOID`, 양수 distance·duration, 요청 origin/destination과 polyline 양끝 100m 이내 정합, polyline geometry 대비 summary 비율, guide index·route 투영·거리의 단조 순서를 모두 확인합니다. 하나라도 어긋나면 부분 route를 쓰지 않고 응답 전체를 거부합니다.
- POI 응답도 정규화한 요청 query와 일치하고 요청 limit 이내이며 ID가 고유하고 `poi/address/alias` type·좌표·필드가 모두 유효해야 합니다. malformed 후보 하나만 버리고 나머지를 쓰지 않습니다.

axis-aligned bbox만으로 점자블록의 지리 좌표나 실제 연결망을 복원할 수는 없습니다. 현재 Android projection은 camera에 보이는 짧은 구간의 연구형 local steering 관측일 뿐이며 전체 점자블록 연결망·보행 가능성·안전을 보장하지 않습니다.

## Android native 음성 길안내

- 전경 `ACTIVE` 보행에서는 Vosk Android `0.3.75`와 hash-pinned `vosk-model-small-ko-0.22` 하나가 16kHz mono PCM16 스트림에서 `길라잡이` 호출어와 이어지는 명령을 모두 처리합니다. `길라잡이, 서울역으로 안내해줘` 한 문장과 호출어 뒤 6초 command window를 지원합니다.
- final word confidence가 없거나 비유한 값·0.60 미만이면 호출어와 명령을 모두 실행하지 않습니다. PCM은 메모리에서만 처리하고 pre-roll·음성 파일·서버 전송·원문 상태 로그를 만들지 않습니다.
- microphone foreground service는 로그인·기기점검·안전교육, 버전된 호출어 고지 확인, 마이크와 Android 13 이상 알림 권한을 통과한 전경 ACTIVE 보행에서만 시작하며, ACTIVE 이탈·background·권한 철회에서 중지합니다. 화면 꺼짐/background 상시 청취는 현재 지원하지 않습니다.
- Vosk 명령은 아래 기존 parser와 action을 그대로 사용합니다. 기존 Android `SpeechRecognizer` 확인 흐름 및 선택적 Gateway 녹음과 호출어 마이크는 직렬 전환하고, 앱 TTS·TalkBack 발화 중 decoder를 억제해 자기 음성 재인식을 막습니다.
- 플랫폼 `SpeechRecognizer` 결과는 `AndroidVoiceCommand`의 순수 parser를 거쳐 실행하며 지원 명령의 부정문은 action 없이 fail-closed 처리합니다.
- recognizer의 최상위 가설 하나만 실행 후보로 사용한다. confidence가 제공되면 0.55 미만·비유한 값은 실행하지 않으며, 낮은 순위의 positive 가설로 대체하지 않는다.
- 명시적 신고, 목적지 설정·변경, 목적지 후보 번호 선택, 목적지 취소, “다음 경로 뭐야?”, 길안내 중지를 실제 Activity action에 연결했습니다.
- 목적지 설정·변경은 TMAP POI 검색 결과를 보여 주고 후보를 임의로 자동 선택하지 않습니다. 상위 3개 후보의 번호·이름·주소·거리를 bounded 음성으로 먼저 읽고, “1번 선택”, “목적지 2번 선택해”, “첫 번째 선택”처럼 유효한 번호를 말해야 `onDestinationSelected`와 TMAP route 요청이 실행됩니다. 검색 중·범위 밖 번호는 fail-closed 처리하며 검색 실패나 후보 선택 전에는 기존 목적지·경로를 보존합니다.
- 다음 안내 질의는 `RouteNavigator.currentInstruction`이 현재 경로 진행 상태에서 반환한 안내만 읽습니다.
- TTS 중재는 `risk > interaction > navigation` 순서다. 위험 발화 전에 진행 중 STT를 취소하고 일반 발화를 정리하며, 위험 발화 중에는 새 STT를 시작하지 않는다.
- TTS 초기화 중에는 위험·상호작용·길안내를 actuator 큐에 보관하지 않고 `UNAVAILABLE`로 거절하며, 거절한 문장을 초기화 뒤 다시 재생하지 않는다. 초기화 성공 뒤 위험은 다음 camera frame의 현재 관측으로 다시 평가하고, 길안내는 `scheduleNavigationSpeechRetry`로 현재 경로에서 다시 만들며, 상호작용은 호출자가 화면·요청이 아직 유효할 때만 다시 요청한다. 초기화 실패와 한국어 미지원은 `tts=init_failed`, `tts=korean_unsupported`로 구분한다.
- 500ms 상태 TextView는 TalkBack live region에서 제외하고 explicit announcement만 사용한다. 동일 문구 cooldown과 `risk > interaction > navigation` 억제 창으로 중복 발화를 줄인다.
- 이는 JVM action 연결 근거이며 실폰 microphone 인식률·TTS 청취·보행 중 조작 안전성의 Device Field PASS는 아닙니다.

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
- 이미지 export, screenshot, raw camera/depth dump는 별도 승인 전 추가하지 않습니다. 별도 field session logger는 allowlist metadata만 파일로 저장합니다.

## Debug field session log

- debug APK의 `현장 로그 시작/종료` 버튼은 서버 없이 앱 전용 `files/field_sessions`에 metadata-only 기록을 남긴다.
- manifest와 JSONL을 세션별로 저장하며 주기 telemetry와 CameraX 표본은 각각 최대 1 Hz, event는 발생 시 기록한다. JSONL은 약 4 MiB마다 자동 회전한다. manifest의 설치 APK/model-config SHA-256으로 회수 데이터의 실행 provenance를 확인한다. 실제 미지원 기기 continuity gate는 정확한 1 Hz가 아니라 CameraX 표본을 15분 동안 60개 이상, 표본 간격 1~30초로 확인한다.
- 명시적으로 종료하기 전에는 active session pointer가 유지되어 앱 process 재시작 뒤에도 같은 세션을 이어가되, 시작 후 14일이 지나면 active session과 pointer도 자동 삭제한다.
- foreground에서 ARCore 위험 감지, CameraX 제한 위험 감지, active TMAP 경로 또는 debug field session 중 하나가 실행될 때만 화면 켜짐 flag를 유지한다. `onPause`에서는 즉시 해제하고 ARCore·CameraX·위치·걸음 수집도 중단하며 foreground service는 없다.
- field session 시작은 위치·신체 활동 권한을 요청하고 허용된 GPS·step service를 시작한다. 따라서 목적지 경로 없이도 현장 sensor metadata를 수집한다.
- 긴 개발용 제어부는 camera preview 위 `ScrollView`에 있어 하단 route/진행음 control까지 접근할 수 있다.
- 저장 필드에는 detector/Depth/gate/step/GPS accuracy·age/navigation 상태가 있고, 정확한 좌표·사용자 ID·목적지·검색어·인식 문장·이미지·음성은 없다.
- `adb run-as`로 회수하므로 debug APK와 USB debugging이 필요하다. 회수 도구는 폰 데이터를 삭제하지 않는다.
- Field evidence용 debug APK는 untracked 파일까지 포함해 worktree가 깨끗한 상태에서
  `WALKSAFE_SOURCE_COMMIT=$(git rev-parse HEAD)`를 주입해 빌드한다. 주입하지 않아
  `source_commit=unverified`인 세션은 strict에서 실패한다.
- 공식 field 영수증에는 위 고정 debug APK 파일과 SHA-256을 별도 첨부한다. 서명 release APK는 같은 source commit의 별도 artifact/SHA로 검증하며, debug field 결과를 release APK 자체의 실기기 실행 근거로 사용하지 않는다.

```bash
FIELD_APK=/absolute/path/to/frozen-app-debug.apk

# ARCore metric 세션(기존 기본값)
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL> --strict \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum "${FIELD_APK}" | awk '{print $1}')"

# CameraX 비계측 기능 세션(debug 강제·Depth 미지원 포함)
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL> --strict \
  --strict-mode camera-non-metric \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum "${FIELD_APK}" | awk '{print $1}')"

# 실제 ARCore 미지원 기기에서 안전한 fixture로 advisory까지 발생시킨 시험
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL> --strict \
  --strict-mode camera-non-metric --require-arcore-unsupported --require-camera-advisory \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum "${FIELD_APK}" | awk '{print $1}')"
```

CameraX strict는 ARCore render-loop telemetry나 `arcore_session_started`를 요구하지 않는다. 대신
`camera_non_metric_session_started`의 loaded model·fallback 여부와 `metric=false`,
`reports_allowed=false` 계약, 그리고 실제 detector가 성공한
`camera_non_metric_frame_analyzed(state=detector_succeeded)`를 요구한다. advisory 옵션은
방향(LEFT/CENTER/RIGHT), 당시 loaded model·fallback 여부, 비계측·신고 차단 필드까지 fail-closed로 검사한다.
`tmap_route_active`는 당시 길안내 상태를 보여 주는 Boolean 관측값으로 보존하지만 CameraX 위험 안내의 유효성 gate로 사용하지 않는다.
`--require-arcore-unsupported`는 `arcore_availability_unsupported` +
`UNSUPPORTED_DEVICE_NOT_CAPABLE` 또는 지원 상태에서 발생한 `arcore_session_incompatible`만 인정한다.
debug 강제·Depth 미지원·UNKNOWN·원인 누락은 기능 시험으로만 분류하며, 요약의 `evidence_scope`와
`arcore_unsupported_verified=false`에 명시한다.
여러 로그가 남아 있어도 strict는 시작 시각이 가장 최신인 세션 하나만 판정하므로 서로 다른
세션의 시작·advisory·telemetry를 합쳐 PASS하지 않는다.

상세 체크리스트: `docs/testing/android_stationary_and_field_test_checklist_20260710.md`

## Activity 회전 경계

- `MainActivity`는 portrait로 고정해 회전 중 목적지·route·navigation active 상태를 잃는 재생성을 피한다.
- process 종료 뒤 이전 보행·경로는 복원하지 않는다. 유효한 Gateway 로그인만 암호화 저장소에서 복원하며 보호된 서버 기능 직전에 재검증한다.

## Debug-only server metadata log

- debug build에는 local/dev 서버로 metadata-only depth debug log를 보낼 수 있는 버튼이 있다.
- 기본 상태는 꺼짐이며, 버튼을 눌러 명시적으로 켜야 한다.
- 현재 main manifest에는 `INTERNET` permission이 선언되어 있고, debug manifest에만 local cleartext HTTP가 포함된다. release의 metadata/frame debug uploader factory는 Noop이고 일반 신고·경로 요청은 main source set의 독립 Android API Gateway session 경로를 사용한다. 외부 공개 전에는 승인된 release signer, source commit, 실기기 gateway 2xx를 release evidence gate로 검증한다.
- `서버 로그 켜기` 버튼은 debug build에서만 보인다. release에서는 uploader가 Noop이고 버튼도 `GONE`/disabled 처리한다.
- endpoint 기본값은 `http://127.0.0.1:8000/android/debug/depth-logs`다. 실기기 USB 연결 시 `adb reverse tcp:8000 tcp:8000`를 사용한다.
- 전송 payload에는 이미지, depth raw, confidence image, screenshot, GPS, audio, secret이 없다.
- backend endpoint도 `ANDROID_DEBUG_LOG_ENABLED=true`일 때만 동작한다.

runbook:

```text
docs/android/android_server_debug_log_runbook_20260601.md
```

## 검증 명령

현재 검사 범위와 전체 시험의 알려진 실패는 [테스트 가이드](../../docs/guides/testing-guide.md)와 [현재 상태 문서](../../docs/handoff/2026-09-30/status.md)를 먼저 확인한다. Vosk 모델 준비 뒤 저장소 루트에서 사용자 앱만 검사·빌드하는 진입점은 다음과 같다.

```bash
(cd apps/android && ./gradlew :app:testDebugUnitTest --no-daemon)
(cd apps/android && ./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-daemon)
```

기기시험 코드 컴파일은 실행 증거가 아니다. 시험 전용 단말·권한·한국어 TTS·모델·서버 준비를 확인한 뒤 [Android 앱별 테스트 가이드](../../docs/guides/android-apps-testing-guide.md)에 따라 설치와 instrumentation을 별도로 수행한다. 날짜형 `check_android_tflite_contract_20260531.py`, `check_android_depth_scaffold_20260531.py`, `export_android_tflite_models_20260531.py`는 과거 검증 경로이며 현행 제품 전체 PASS 근거로 사용하지 않는다.

과거 검증 기록 (원 기록의 실행 날짜·commit·모델 해시가 이 목록에 결속되지 않아 현재 버전 PASS로 사용하지 않음):

- Android TFLite contract check: PASS, backend threshold 차이는 warning
- Android depth scaffold check: PASS
- Android unit tests: JVM 1,313 PASS, 0 failures/errors/skips
- Android debug APK/lint/AndroidTest APK assemble: 당시 변경본 BUILD SUCCESSFUL·SHA 확인 기록
- `SM-G981N` Android 13 연결 실기기 instrumentation: 당시 unified 768 TFLite와 legacy asset hash·load/invoke 2/2 PASS 기록. 현재 21클래스 모델의 실행 증거가 아니며 대화형 앱 launch·camera pipeline·FPS·실외 동작은 별도 검증 필요
- MetadataCaptureLog unit test: Gradle test에 포함
- Stationary device smoke on `SM-G981N` / Android 13: 사용자·관리자 debug APK 설치와 cold start, 사용자 앱 가입·권한·기기점검 진입을 확인했다. 오프라인 한국어 TTS가 없어 기기점검이 안전하게 실패했으며 TalkBack·실외 보행·카메라 지속 frame은 별도 검증이 필요하다.

## 남은 gate

- 정적 RGB 이미지는 detector/class/threshold 검증용이다.
- ARCore depth, `N보` 거리, bbox-depth alignment, approach/TTC는 움직이는 실기기 RGB-D 또는 실측 거리 데이터가 필요하다.
- ARCore 미지원 Android의 CameraX 비계량 보조 경고도 실제 지원 대상 단말에서 오탐·미탐·지연·회전/안정화·TTS/TalkBack 전달을 검증하지 않아 `FIELD UNVERIFIED/OPEN`이다. 이 low 경고는 진동이 없어야 한다.
- reviewed tactile3 원본 image/GT dataset이 현재 로컬에 없어 full static metric rerun은 blocked다.
- TTS/haptic/report upload 코드는 Device Gate 뒤에 연결되어 있지만, bbox/depth 정합 전에는 제품 완료로 주장하지 않는다.
- backend route/search client와 route UI는 연결되어 있지만 outdoor walking/route PASS, 목적지 음성 UX, 운영 auth/reporter 신뢰는 아직 별도 gate다.

## 주의

- ARCore 세션 사용 중에는 별도 CameraX/Camera2 preview를 동시에 열지 않습니다. CameraX는 ARCore 미지원 제한 모드에서만 카메라를 소유합니다.
- SharedCamera는 depth-critical 기본 경로에서 제외합니다.
- Android 실기기 ARCore depth 검증에는 ARCore 지원 Android 기기, Google Play Services for AR, 카메라 권한이 필요합니다.
