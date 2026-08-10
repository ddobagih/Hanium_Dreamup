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
