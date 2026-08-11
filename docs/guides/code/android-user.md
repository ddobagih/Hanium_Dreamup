# Android 사용자 앱 코드 지도

상태: `CURRENT_PRODUCT`

진입점은 [`MainActivity.kt`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt), runtime 정본은 버전 관리되는 Gradle 설정과 asset config입니다. [Android README](../../../apps/android/README.md)는 완료 증거에 결속된 과거 snapshot이므로 그 안의 절대 APK 경로와 dated hash를 현행 명령으로 사용하지 않습니다. 이 문서는 패키지를 찾기 위한 안내이며 기능 정책과 완료 상태를 새로 정하지 않습니다.

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
| [`session/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/) | 최초 실행·교육·권한·보행 생명주기·개인정보 상태 |
| [`security/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/security/) | AndroidKeyStore 기반 민감 설정 암호화 |
| [`fieldlog/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/) | debug 현장 session의 암호화 metadata-only 기록 |
| [`debuglog/`](../../../apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/debuglog/) | debug source set 진단 전송; release는 no-op |

Android가 직접 사용하는 모델 선택·class·tensor·hash는 [`two_model_runtime.json`](../../../apps/android/app/src/main/assets/model-config/two_model_runtime.json), 서버 요청 표면은 [Gateway OpenAPI](../../../apps/android-gateway/openapi.json)를 기준으로 봅니다.

저장소 루트에서 debug APK를 만들고 설치할 때는 현재 checkout 기준 상대경로를 사용합니다.

```bash
(cd apps/android && ./gradlew :app:assembleDebug --no-daemon)
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

## 변경 시 확인

- `network`, `report`, `navigation` 변경은 Gateway route·OpenAPI와 Backend consumer까지 확인합니다.
- `inference`, `depth`, `device` 변경은 모델 config, asset 계약, 안전중지·fallback 테스트를 함께 확인합니다.
- `session`, `security` 변경은 계정 전환·재시작·권한 철회·삭제·민감 저장 거부 경로를 포함합니다.
- `feedback` 변경은 TTS/TalkBack 완료 확인과 진동 정책을 실제 기기 검증과 구분합니다.

자동검사와 빌드 명령은 [테스트 가이드](../testing-guide.md)를 사용합니다. JVM·정적 테스트는 ARCore·카메라·GPS·TTS·진동·야외 보행의 실제 기기 PASS를 뜻하지 않습니다.
