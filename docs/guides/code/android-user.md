# Android 사용자 앱 코드 지도

상태: `CURRENT_PRODUCT`

기준일: 2026-09-30. [인수인계 시작 문서](../../handoff/2026-09-30/README.md), [현재 상태·검증 범위](../../handoff/2026-09-30/status.md), [Part A](../../handoff/2026-09-30/part-a.md), [Part B](../../handoff/2026-09-30/part-b.md)를 먼저 확인합니다.

진입점은 [`MainActivity.kt`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt), runtime 정본은 버전 관리되는 Gradle 설정과 asset config입니다. [Android README](../../../apps/android/README.md)는 현재 소스 안내와 표시된 과거 기록을 함께 담습니다. 과거 절대 APK 경로·dated hash·smoke 결과는 해당 버전의 이력으로만 사용합니다. 이 문서는 패키지를 찾기 위한 안내이며 기능 정책과 완료 상태를 새로 정하지 않습니다.

`MainActivity`는 로그인·동의·보행 상태와 센서·카메라·추론·음성·길안내·출력 연결을 조립합니다. Part A가 이 파일의 단일 수정 담당이며 Part B가 연결 변경을 필요로 하면 Part A에 요청합니다. 세부 담당 항목과 인수 조건은 위 분담 문서를 따릅니다.

## 패키지 책임

| 경로 | 책임 |
|---|---|
| [패키지 루트](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/) | 화면 조립, 권한·카메라·탐지·길안내·신고 연결 |
| [`depth/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/depth/) | ARCore depth, 좌표 변환, 거리·추적·TTC·위험 정책 |
| [`inference/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/) | runtime config, 이미지 전처리, TFLite 추론과 출력 파싱 |
| [`device/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/) | 지원기기·센서·카메라·배터리·시작 안전 gate |
| [`feedback/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/) | TTS·TalkBack·진동 우선순위와 전달 확인 |
| [`navigation/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/) | GPS·방향·걸음, TMAP 경로, 이탈·도착·점자블록 안내 |
| [`network/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/) | Gateway 로그인·세션·동의·계정 삭제와 전송 정책 |
| [`report/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/) | 신고 후보·동의·cooldown·업로드 |
| [`session/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/) | 최초 실행·교육·권한·보행 생명주기, 통합 동의·Backend 권한 재확인·CAS journal |
| [`security/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/security/) | AndroidKeyStore 기반 민감 설정 암호화 |
| [`fieldlog/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/) | debug 현장 session의 암호화 metadata-only 기록 |
| [`debuglog/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/debuglog/) | debug source set 진단 전송; release는 no-op |

Android가 직접 사용하는 모델 선택·class·tensor·hash는 [`two_model_runtime.json`](../../../apps/android/app/src/main/assets/model-config/two_model_runtime.json), 서버 요청 표면은 [Gateway OpenAPI](../../../apps/android-gateway/openapi.json)를 기준으로 봅니다.

새 clone에서는 [Vosk 모델 준비 스크립트](../../../scripts/prepare_vosk_ko_small_model.py)를 먼저 실행합니다. JDK·SDK·로컬 설정은 [개발 환경 가이드](../development-environment-guide.md#android-사용자관리자-앱), 앱별 실행·기기시험은 [Android 앱별 테스트 가이드](../android-apps-testing-guide.md)를 따릅니다. 저장소 루트에서 debug APK를 만들고 시험 전용 단말에 설치할 때는 현재 checkout 기준 상대경로를 사용합니다.

```bash
python3 scripts/prepare_vosk_ko_small_model.py
(cd apps/android && ./gradlew :app:assembleDebug --no-daemon)
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

통합 동의의 저장된 확인서 복원은 [`IntegratedConsentPolicy.kt`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/IntegratedConsentPolicy.kt)의 현재 Backend 권한 확인과 분리되며, 서버 재확인 전에는 전송을 허용하지 않습니다. CAS 충돌의 거부 선택 보존과 production V2 압축 journal은 [`RejectedConsentCasJournalCodec.kt`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/RejectedConsentCasJournalCodec.kt), 실제 prefs 저장·HTTP 연결은 `MainActivity`에서 확인합니다. 현재 연동 근거는 JVM 클라이언트→Gateway→Backend이며 실제 Activity·encrypted prefs 프로세스 재시작·최신 APK 설치는 미검증입니다.

## 샘플 이미지 성능 점검 연결 (2026-11-01)

- 진입은 `MainActivity.startDeviceCompositeCameraCheck` → `inference/composite/AndroidJointRuntimeCheck`입니다. 기존 기기점검의 probe/controller가 진행과 최종 상태를 소유합니다. 점검에는 카메라·ARCore preview 또는 사용자 물체 촬영 단계가 없습니다.
- `FixtureRuntimeService`는 비공개 `:fixture_runtime` 프로세스의 수명을 소유하고, `RemoteFixtureRuntime`/`FixtureRuntimeProtocol`은 시도·nonce에 결속한 IPC와 실제 process death 확인을 담당합니다. `FixtureRuntimeWorker`는 내장4장 검증과 두 모델의 공동 샘플 측정만 수행합니다. 큰 영상/tensor는 자식 내부에서 처리하며 결과는 제한된 수치 데이터입니다.
- `JointRuntimeChoice`의 샘플 전용 evidence와 `AndroidJointRuntimeChoiceStore`의 schema2/모델·fixture·기기 binding이 선택 기록을 검증합니다. 카메라 mode·ARCore scope·live source age를 샘플 측정값으로 위조하지 않습니다.
- 기본 모델은 `AndroidPrimaryLifecyclePlatform`/`AndroidJointRuntimeLoader`, 추가 모델은 `UnknownRuntimeServiceOwner`의 기존 소유자에 복원됩니다. CameraTest와 실제 보행의 원본 frame·geometry·세션·거리 freshness 검사는 이 저장 기록과 별개입니다. 기존 화면 간 `CameraRuntimeHandoff`도 유지합니다.
- 샘플 검사 실패는 실행 실패로 유지하며 기본 모델을 잠시 해제했다는 이유로 성공한 제한 결과로 낮추지 않습니다. 실제 worker 해제가 미확인되면 실패 안내와 재할당 차단을 함께 유지합니다. 거리 지원은 기존 `runtime/metric/AndroidRuntimeMetricPreflight`의 API support-only 검사에 맡깁니다.
- 관련 자동 회귀는 `inference/composite`의 policy/store/IPC·감독 시험, `AndroidPrimaryInitialPreparationTest`, `AndroidPostLoginDeviceProbesTest`, `PostLoginDeviceCheckControllerTest`, `CameraRuntimePreparationContractTest`입니다. 실제 기기 시험과 구분합니다.

## 변경 시 확인

- `network`, `report`, `navigation` 변경은 Gateway route·OpenAPI와 Backend consumer까지 확인합니다.
- `inference`, `depth`, `device` 변경은 모델 config, asset 계약, 안전중지·fallback 테스트를 함께 확인합니다.
- `session`, `security` 변경은 계정 전환·재시작·권한 철회·삭제·민감 저장 거부 경로를 포함합니다.
- `feedback` 변경은 TTS/TalkBack 완료 확인과 진동 정책을 실제 기기 검증과 구분합니다.

자동검사와 빌드 명령은 [테스트 가이드](../testing-guide.md)를 사용합니다. JVM·정적 테스트는 ARCore·카메라·GPS·TTS·진동·야외 보행의 실제 기기 PASS를 뜻하지 않습니다.
