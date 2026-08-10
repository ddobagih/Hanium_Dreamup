# Field Session Log

이 폴더는 Android debug APK의 원거리 현장 테스트를 앱 전용 저장소에 남기는 코드다. 기존 `debuglog` HTTP 업로더와 별개이며 서버 연결이 없어도 동작한다.

## 저장 계약

- 경로: `files/field_sessions/<session-id>/`
- `manifest.json`: 세션 manifest 평문을 담지 않고 정확히 `{envelope_version,key_version,iv,ciphertext}`인 v1 AEAD envelope만 저장
- `records-NNNN.jsonl`: 각 줄에 telemetry/event 평문 대신 같은 v1 AEAD envelope를 저장하며 파일당 약 4 MiB 자동 회전
- `active_session.txt`: active session ID 평문 대신 같은 v1 AEAD envelope를 저장

호스트 회수 도구는 envelope를 열 수 있는 키나 복호화 경로를 제공하지 않는다. 암호화 archive를 감지하면 tar 원문을 `field_sessions.encrypted.tar`(권한 `0600`)로만 보존하고 파일을 추출하지 않으며, 평문 summary/export/cache를 만들지 않고 종료코드 1을 반환한다. 현재 암호화 로그의 호스트 요약은 의도적으로 unavailable이다.

기록에는 detector timing/class/bbox, ARCore depth 요약, stale 사유, gate 상태, 걸음 수, GPS 정확도·age, 익명화된 navigation 상태 코드가 포함된다. 정확한 위·경도, 사용자 ID, 검색어·목적지, 인식 문장, 이미지·depth 원본, 음성은 포함하지 않는다.

`PersistentFieldSessionLog`는 debug build에서만 생성되고 release build는 `NoopFieldSessionLog`를 사용한다. 이 기록은 field 진단 자료이며, 자동으로 제품 PASS나 운영 신고 증거가 되지 않는다.

실제 미지원 기기 gate는 같은 source commit을 주입한 고정 debug field APK와 그 APK의 SHA-256을 사용한다. 서명 release APK는 별도 SHA로 검증되며, debug field 세션은 release APK 자체를 실기기에서 실행했다는 근거가 아니다. 15분 continuity는 정확한 1 Hz를 주장하지 않고 표본 60개 이상, span 900초 이상, 표본 간격 1~30초를 확인한다.

앱 또는 model-config hash가 active session의 manifest와 달라지면 이전 세션을 `app_or_model_provenance_changed`로 종료하고 새 세션을 자동 시작한다. 서로 다른 APK의 record가 한 session에 섞이지 않는다.

활성 field session 동안 `MainActivity`는 `FLAG_KEEP_SCREEN_ON`을 설정하고 명시 종료 시 해제한다. 이는 화면이 켜진 foreground 수집만 보조하며 background ARCore·위치·걸음 수집을 구현하는 foreground service가 아니다.

field session 시작은 Android 위치·신체 활동 권한을 요청하고 허용된 GPS·step service를 시작한다. 저장소에는 정확한 좌표 대신 위치 신뢰 여부·accuracy·age만 기록한다.

주기 telemetry는 ARCore `onDrawFrame()`에서 생성되므로 ARCore session이 시작되지 않으면 GPS·step service가 활성화돼도 주기 표본은 쌓이지 않는다.

암호문 원문 회수(정상적으로 summary unavailable/종료코드 1):

```bash
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL>
```

아래 평문 요약 계약과 fixture는 2026-08 암호화 전 과거 증거의 회귀 확인을 위한 **LEGACY/HISTORY ONLY**다. 새 실기기 로그나 운영 evidence 생성에 사용하지 않는다. 과거 평문에서 기본 `--strict`는 최신 세션 하나의 provenance, telemetry, `arcore_session_started`, loaded-model 근거를 검사하고 `--strict-mode camera-non-metric`은 CameraX 계약을 검사한다.

과거 평문 fixture 요약 예시:

```bash
FIELD_APK=/absolute/path/to/frozen-app-debug.apk

# ARCore metric
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL> --strict \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum "${FIELD_APK}" | awk '{print $1}')"

# CameraX non-metric 시작 + 실제 analyzed frame 계약
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL> --strict \
  --strict-mode camera-non-metric \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum "${FIELD_APK}" | awk '{print $1}')"

# 실제 advisory event 계약도 필수로 검사
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL> --strict \
  --strict-mode camera-non-metric --require-camera-advisory \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum "${FIELD_APK}" | awk '{print $1}')"

# 실제 ARCore 미지원 원인도 필수로 검사
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL> --strict \
  --strict-mode camera-non-metric --require-arcore-unsupported \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum "${FIELD_APK}" | awk '{print $1}')"
```
