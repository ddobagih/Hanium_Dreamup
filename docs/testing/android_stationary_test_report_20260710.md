# Android 연결 폰 정지 테스트 결과

> **과거 APK 한정 기록 / SUPERSEDED:** 아래 수치·640 asset 부재·legacy fallback 판정은 2026-07-10 당시 SHA-256 `de75a472...` APK에만 해당한다. 현재 img768 unified primary APK와 자동검증 기준은 `apps/android/README.md`, `docs/status/current_status.md`, `docs/testing/test_layers_20260711.md`를 우선한다. 이 문서의 과거 결과를 현재 빌드의 모델·테스트 수·Device PASS로 재사용하지 않는다.

기준일: 2026-07-10 KST  
대상: Samsung SM-G981N / Android 13 / API 33 / arm64  
APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`  
APK SHA-256: `de75a472b5f6f42a7a9f43aebdcb5d02e9eb0c991b7a995b329c32ce2848525c`

## 판정 요약

| 구분 | 결과 | 확인 범위 |
| --- | --- | --- |
| ADB·기기 기반 | PASS | 연결·인증, OS/ABI, 배터리·저장공간·Wi-Fi |
| 센서·AR 기반 | PARTIAL | 하드웨어와 ARCore 설치는 PASS, 실제 ARCore session/Depth frame은 잠금으로 BLOCKED |
| APK 빌드·설치 | PASS | JVM 108 tests, assemble, 빌드 APK와 설치 APK hash 일치 |
| 권한·프로세스 | PASS | camera/location/activity/audio grant, Activity start, PID, FATAL crash 없음 |
| 폰→backend API 계약 | PASS | USB reverse 뒤 phone `curl` health, fake detect v2, synthetic report, reviewed 상태, filtered CSV |
| PostGIS 회귀 | PASS | backend 112 tests, skipped 0, Alembic head |
| 현장 JSONL session | BLOCKED | 저장 root와 회수 도구는 확인했으나 PIN 잠금으로 시작 버튼을 누르지 못함 |
| 카메라·Depth·bbox·음성 체감 | BLOCKED | PIN 잠금 화면에서 UI 자동화 불가 |

전체 제품 판정은 계속 `PARTIAL`이다. phone-to-backend PASS는 잠긴 폰의 shell에서
`curl`로 수행한 연결·API 계약 검증이다. 설치 앱의 `AndroidReportUploader` UI E2E는
아니며, 실제 객체탐지 정확도, Web/PWA release, ARCore Depth field 성능을 증명하지 않는다.

현장 로그의 GPS·step 센서는 session 시작으로 활성화되지만 주기 JSONL telemetry는
ARCore `onDrawFrame()`에서 기록된다. 현재 폰은 잠금 때문에 render loop를 시작하지
못했으므로 app-private 저장 root PASS를 telemetry 수집 PASS로 확대하지 않는다.

## 세부 결과

| ID | 시험 | 결과 | 근거·주의 |
| --- | --- | --- | --- |
| ST-01 | ADB 연결·인증 | PASS | authorized physical device 1대 |
| ST-02 | 카메라·마이크·GPS·IMU·step 센서 | PASS | package feature와 sensor service에서 존재 확인 |
| ST-03 | Google Play Services for AR | PASS | 1.54 설치·활성화 확인. Depth 지원 판정은 아직 아님 |
| ST-04 | 인터넷·위치 서비스 | PASS | validated Wi-Fi, 외부 ping, location provider enabled. 실제 좌표는 문서에 기록하지 않음 |
| ST-05 | 저장공간·배터리 | PASS | `/data` 여유 약 40 GB, USB 연결 상태 100% |
| ST-06 | Android JVM·APK | PASS | 108 tests, failures/errors/skipped 0, `assembleDebug` 성공 |
| ST-07 | 최신 APK 보존 설치 | PASS | `adb install -r`; 설치된 `base.apk` hash가 빌드본과 일치 |
| ST-08 | runtime permission | PASS | camera, fine/coarse location, activity recognition, record audio granted |
| ST-09 | cold process start | PASS | Activity start ok, process 존재, launch 뒤 FATAL/AndroidRuntime crash 없음 |
| ST-10 | phone→backend health | PASS | `adb reverse tcp:8000` 뒤 phone `curl`로 `/health` 응답 확인 |
| ST-11 | phone→detect v2 API 계약 | PASS | phone `curl`의 fake mode contract가 3개 detection을 반환. 성능 근거에서 제외 |
| ST-12 | phone→PostGIS API 계약 | PASS | phone `curl` multipart로 합성 위치·fake source 이미지의 Android-source v2 신고 생성, `performance_excluded=true` 확인. 설치 앱 UI E2E가 아님 |
| ST-13 | review·CSV API 계약 | PASS | 합성 Android-source 신고를 `reviewed`로 변경하고 source/date filter CSV에서 같은 행 확인 |
| ST-14 | backend DB test | PASS | local PostGIS healthy, backend 112 tests no-skip, Alembic `202605120001 (head)` |
| ST-15 | app-private log root | PASS | debug `run-as`와 `files/field_sessions` 접근 확인 |
| ST-16 | field session start·recovery·pull | BLOCKED | 잠금 때문에 session 0건. 빈 회수 결과는 삭제했고 Device PASS로 올리지 않음 |
| ST-17 | camera preview·ARCore Depth | BLOCKED | 보안 잠금 `Bouncer`; PIN 우회하지 않음 |
| ST-18 | 실제 TFLite·bbox·거리·TTS·진동·음성 | BLOCKED | 화면 잠금 해제 후 `S-01`~`S-11` 필요 |

## 현재 APK 모델 경계

`two_model_runtime.json`은 unified 13-class asset을 우선하도록 되어 있지만
`models/walksafe_unified_yolo26n_640_float32.tflite`가 APK에 없다. 따라서 현재
설치 APK는 legacy custom tactile + COCO allowlist로 fallback한다.

- 이번 폰에서 최신 epoch 270의 13-class 모바일 성능을 검증했다고 말할 수 없다.
- 횡단보도, 보도 단차, 불균일 보도, 전동킥보드 장애물은 이번 APK의 유효 탐지
  시험 대상이 아니다.
- `GET /detect/v2` fake 응답도 실제 PT/TFLite 정확도 근거가 아니다.

## 잠금 해제 후 남은 정지 시험

1. 합성 로그인 ID를 저장한다.
2. `현장 로그 시작`을 누르고 session ID·화면 유지 상태를 확인한다. 아래 재시작 시험은
   이 active session을 선행조건으로 한다.
3. `ARCore Depth 시작` 후 camera preview, `arcore_session_started`, 실제 Depth 지원 판정과
   `legacy_two_model` fallback 표시를 확인한다.
4. 사람·벤치·점자블록을 대상으로 bbox, 회전, stale, depth 거리를 관찰한다.
5. Korean TTS와 `SpeechRecognizer` 사용 가능 여부를 확인한 뒤 음성 신고, TTS·진동,
   GPS trust와 step count를 확인한다.
6. 앱 강제 종료·재실행 뒤 같은 session이 복구되는지 확인한다.
7. `python3 scripts/pull_android_field_sessions_20260710.py --strict`로 회수해
   APK/config hash, JSONL parse와 privacy gate를 검증한다. 출발 가능한 smoke는
   telemetry 1건 이상, `arcore_session_started`, `loaded_model=legacy_two_model`,
   fallback, Depth 지원 결과가 모두 기록된 경우로 제한한다.

실행 체크리스트는 `android_stationary_and_field_test_checklist_20260710.md`, 내일
다른 폰 준비 절차는 `phone_field_test_master_checklist_20260710.md`를 사용한다.
