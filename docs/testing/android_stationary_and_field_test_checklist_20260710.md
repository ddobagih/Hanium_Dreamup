# Android 정지·원거리 현장 테스트 체크리스트

기준일: 2026-07-13  
대상: Android ARCore/TFLite 연구 보조 앱  
판정 원칙: 코드·JVM PASS와 실기기 Device PASS를 분리한다.

## 1. 기록 방식

debug APK에 `현장 로그 시작/종료` 버튼이 있다. 시작 후 다음 자료가 앱 전용 `files/field_sessions` 아래에 남고, 네트워크나 개발 PC와의 연결이 끊겨도 유지된다.

GPS·step 센서는 현장 로그 시작으로 활성화되지만 주기 telemetry 쓰기는 ARCore
`onDrawFrame()`에서 수행된다. ARCore session/render loop가 시작되지 않으면 lifecycle이나
상태 event만 남고 GPS·step의 주기 표본은 남지 않을 수 있다.

- 앱·ARCore session lifecycle event
- detector 모델·fallback·class·confidence·추론 시간·stale 사유
- bbox와 ARCore depth의 거리·confidence·sample 요약
- device/report gate 결과, 걸음 수와 보폭
- GPS 보유 여부·정확도·age·heading. 정확한 위·경도는 저장하지 않음
- 개인정보를 제거한 navigation/report 상태 코드
- 위험 TTS·진동 정책이 실제 action을 방출한 event

저장하지 않는 항목: 카메라 이미지, raw depth, 음성, 음성 인식 문장, 사용자 ID, 검색어·목적지, 정확한 위·경도. 이 때문에 시각적 bbox 정합은 현장 관찰 메모 또는 별도 승인된 화면 녹화와 함께 판정해야 한다.

기존 `서버 로그 켜기`는 USB reverse/local backend용 선택적 HTTP debug 기능이다. 원거리 기록에 필요하지 않으며, 성공하더라도 Device PASS로 간주하지 않는다.

## 2. 외출용 폰 준비

- [ ] 개발자 옵션과 USB debugging을 켠다.
- [ ] `adb devices -l`에서 상태가 `device`인지 확인한다.
- [ ] Google Play Services for AR를 설치·업데이트한다. 설치 사실만으로 Depth PASS를
  주지 않는다.
- [ ] 재사용 폰에 기존 active field session이 있으면 먼저 로그를 회수하고 앱에서 종료한다. 새 logger는 APK/model-config 변경 시 세션을 자동 분리하지만 사전 회수가 가장 명확하다.
- [ ] clean source commit에서 field APK를 한 번 빌드한 뒤, 그 고정 파일을 `--apk`로 지정해 기존 데이터 보존 설치(`adb install -r`)·앱 실행을 수행한다. 준비 도구는 설치 전후 로컬 파일과 기기 `base.apk` SHA-256 일치를 확인한다.

```bash
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
test -z "$(git status --porcelain=v1)"
FIELD_SOURCE_COMMIT="$(git rev-parse HEAD)"
(cd apps/android && WALKSAFE_SOURCE_COMMIT="${FIELD_SOURCE_COMMIT}" \
  ./gradlew testDebugUnitTest assembleDebug --no-daemon)
install -d -m 0700 artifacts/android-field-apks
FIELD_APK_DIR="artifacts/android-field-apks/${FIELD_SOURCE_COMMIT}"
mkdir -m 0700 -- "${FIELD_APK_DIR}"
install -m 0400 -T -- apps/android/app/build/outputs/apk/debug/app-debug.apk \
  "${FIELD_APK_DIR}/app-debug.apk"
export FIELD_APK="$(realpath "${FIELD_APK_DIR}/app-debug.apk")"
export FIELD_APK_SHA256="$(sha256sum -- "${FIELD_APK}" | awk '{print $1}')"
bash scripts/prepare_android_field_device_20260710.sh \
  --serial <ADB_SERIAL> --apk "${FIELD_APK}"
printf 'FIELD_APK=%s\nFIELD_APK_SHA256=%s\n' "${FIELD_APK}" "${FIELD_APK_SHA256}"
```

첫 `test`가 실패하면 증거용 APK를 만들지 말고 untracked 파일을 포함한 변경을 먼저 확정한다. commit을 주입하지
않은 debug APK는 manifest에 `source_commit=unverified`를 남기며 strict에서 실패한다.
출력된 절대 경로와 SHA-256을 현장 메모에 보존하고 이후 다시 빌드하지 않는다. 새 shell에서는 기록한 `FIELD_APK`를 먼저 다시 설정한다. 이 field APK와 서명 release APK는 같은 source commit에 결속되지만 SHA-256이 다른 별도 artifact다. 아래 현장 기록은 debug field APK 실행 근거이며 release APK 자체의 실행 근거가 아니다.

- [ ] 앱 상단 `로그인 user id`에 실제 개인정보가 아닌 합성 ID(예: `field-tester-01`)를 입력하고 `로그인 ID 저장`을 누른다. 이 단계가 없으면 ARCore Depth·신고·경로 시작이 차단된다.
- [ ] 카메라·위치·신체 활동·마이크 권한은 각 테스트 직전에 필요한 것만 허용한다.
- [ ] 앱에서 `현장 로그 시작`을 누르고 버튼이 `현장 로그 종료`로 바뀌는지 확인한다. 시작 시 위치·신체 활동 권한을 요청하고, 허용된 GPS·step 수집을 field session 사유로 시작한다.
- [ ] `ARCore Depth 시작`을 누르고 camera preview, ARCore session 시작과 실제 Depth
  지원 여부를 확인한다.
- [ ] 현재 APK에서 `loaded_model=unified_walksafe`, `fallback_used=false`, 입력 768과 13-class가 표시되는지 확인한다. legacy 표시가 나오면 정상 결과로 넘기지 말고 primary load 실패 사유를 기록한다.
- [ ] Korean TTS 발화와 `SpeechRecognizer`의 “신고해줘” 인식을 정지 상태에서 확인한다.
- [ ] 제어 화면을 위로 스크롤해 하단 목적지·경로·진행음 버튼까지 접근되는지 확인한다.
- [ ] 외출 전에 앱을 백그라운드로 보냈다가 다시 열어 로그가 계속 `기록 중`인지 확인한다.
- [ ] active field session이 화면 켜짐을 유지하는지 확인한다. 이 플래그는 background 수집을 제공하지 않는다.
- [ ] 주요 현장 수집 중에는 앱을 foreground로 유지하고 화면을 잠그거나 다른 앱으로 전환하지 않는다.
- [ ] 배터리를 충분히 충전하고 발열을 확인한다. 화면·카메라·ARCore 지속 실행으로 과열 또는 급격한 방전이 발생하면 즉시 중단한다.
- [ ] 배터리 절전 예외가 필요하면 해당 테스트 시간에만 적용한다.
- [ ] `pm clear`, 앱 저장공간 삭제, 앱 제거는 로그가 모두 회수될 때까지 하지 않는다.

### 출발 Gate

30초 smoke 후 출발 전에 다음 명령으로 한 번 회수한다.

```bash
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL> --strict \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum "${FIELD_APK}" | awk '{print $1}')"
```

`--strict`는 가장 최신 세션 하나만 판정하며 telemetry 0건, ARCore 시작 event 누락,
loaded-model 근거 누락, 잘못된 provenance나 기대 commit/APK 불일치도 실패시킨다.
Depth 지원 여부와 현재 APK의 정확한 fallback·hash·지속 기록은 별도로 확인해 다음을
모두 만족해야 한다.

- [ ] `field_session_summary.json`의 `records.telemetry`가 1 이상이다.
- [ ] raw JSONL에 `arcore_session_started`와 실제 `depth_supported` 값이 있다.
- [ ] Depth 시험 대상 폰은 `depth_supported=true`다. false면 해당 시험을 `BLOCKED`로 남긴다.
- [ ] 현재 APK 기준 `loaded_model=unified_walksafe`, `fallback_used=false` 기록이 있다.
- [ ] manifest의 APK SHA-256·model-config SHA-256이 설치 파일과 일치한다.
- [ ] GPS·step runtime 필드가 있고 30초 동안 record가 계속 증가한다.

CameraX 비계측 기능 트랙은 위 ARCore gate 대신 다음 명령을 사용한다. 지원 기기의 debug 강제
fallback과 ARCore Depth 미지원도 이 기능 gate는 통과할 수 있지만 실제 ARCore 미지원 근거는 아니다.

```bash
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL> --strict \
  --strict-mode camera-non-metric \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum "${FIELD_APK}" | awk '{print $1}')"
```

이 모드는 ARCore render-loop telemetry와 `arcore_session_started`를 요구하지 않는다. 대신
다음을 모두 fail-closed로 검사하거나 직접 확인한다.

- [ ] raw JSONL에 `camera_non_metric_session_started`가 있다.
- [ ] 시작 event에 비어 있지 않은 `loaded_model`, boolean `model_fallback_used`,
  `metric=false`, `reports_allowed=false`가 있다.
- [ ] raw JSONL에 detector가 실제 성공한 `camera_non_metric_frame_analyzed`가 있고, 시작 event와
  동일한 모델·비계측·신고 차단 계약 및 `state=detector_succeeded`가 있다.
- [ ] `camera_non_metric_inference_sample`은 최대 1 Hz로 기록되며 추론 시간·탐지 수·현재 capability/gate 상태만 포함한다(좌표·이미지·인식 원문 없음). 실제 미지원 기기 continuity 판정은 정확한 1 Hz가 아니라 15분 동안 60개 이상, 표본 간격 1~30초를 요구한다.
- [ ] 앱 화면에서 TMAP 경로·GPS·fresh IMU가 준비되지 않으면 advisory가 나오지 않는다.
- [ ] 거리·걸음 수·STOP/high·local steering·경로 변경·자동 신고가 나오지 않는다.
- [ ] 같은 심각도의 안전한 고정 fixture를 동시에 보여도 경고가 겹치지 않고 bounded FIFO 순서로 전달된다(전달 중 포함 최대 3개).
- [ ] TTS 사용 불가 또는 TalkBack 우선순위 hold로 전달이 거절되면 cooldown을 소모하지 않고, 조건 복구 뒤 현재 후보를 다시 시도한다.
- [ ] `CAMERA_IMU_NON_METRIC` low 경고는 TTS/TalkBack만 사용하며 진동하지 않는다(P-09).

실제 ARCore 미지원 기기 근거로 판정할 때만 다음 옵션을 추가한다.

```bash
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL> --strict \
  --strict-mode camera-non-metric --require-arcore-unsupported \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum "${FIELD_APK}" | awk '{print $1}')"
```

이 gate는 시작 원인이 `arcore_availability_unsupported`이고 상태가
`UNSUPPORTED_DEVICE_NOT_CAPABLE`인 경우, 또는 지원 상태 확인 뒤
`arcore_session_incompatible`이 발생한 경우만 인정한다. debug 강제·Depth 미지원·UNKNOWN·원인 누락은
실패한다. `field_session_summary.json`의 `evidence_scope=ARCORE_UNSUPPORTED_FIELD`와
`arcore_unsupported_verified=true`도 함께 확인한다. 또한 선택된 최신 세션의
`camera_non_metric.strict_selected_session_samples`가 sample 60개 이상, span 900초 이상,
max gap 30초 이하여야 한다. 단조 `elapsed_realtime_ms`는 record 순서대로 1~30초 간격으로 증가하고,
epoch 시각은 정수·비감소이며 완료 manifest 시간 범위 안이어야 한다. inference p50/p95는 성능 관찰값이며 이 gate의 합격 임계값은 아니다.
debug 강제 또는 Depth 미지원 기능 스모크에는 이 15분 gate를 적용하지 않는다.

안전한 고정 fixture로 실제 advisory까지 발생시켰다면 다음 옵션을 추가한다.

```bash
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL> --strict \
  --strict-mode camera-non-metric --require-camera-advisory \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum "${FIELD_APK}" | awk '{print $1}')"
```

`camera_non_metric_advisory_emitted`는 TTS `onDone` 또는 TalkBack accessibility 전달이 확인된 뒤에만 기록한다.
전달 거절·gate·전달 시작 전 TTL 만료·후보 소실로 폐기한 예약에는 이 event와 cooldown이 없어야 한다. 이 옵션은 event의 당시 loaded model·fallback 여부,
`direction=LEFT|CENTER|RIGHT`, `metric=false`, `tmap_authoritative=true`, `reports_allowed=false`를 요구한다. advisory를
안전하게 발생시키지 못한 시험에서는 이 옵션을 빼고 해당 항목을 `미실행`으로 남긴다.

## 3. 연결된 폰으로 가능한 정지 테스트

| ID | 테스트 | 실행·관찰 | 로그 판정 근거 | 결과 |
|---|---|---|---|---|
| S-01 | 설치·cold start | 섹션 2에서 현장 로그·ARCore를 시작한 뒤 앱을 강제 종료하고 다시 실행. crash와 검은 preview 여부 확인 | `session_resumed_after_app_restart`, `app_resumed` | [ ] |
| S-02 | 권한 gate | 합성 로그인 ID 저장 후 카메라/위치/신체 활동/마이크를 차례로 거부·허용 | `field_log_enabled` 권한 값, navigation 상태 | [ ] |
| S-03 | ARCore/Depth | 폰을 고정하고 0.5m·1m·2m의 안전한 물체를 비춤 | `arcore_session_started`, `best_depth_source`, 거리/sample | [ ] |
| S-04 | detector | 사람·차량·신호등·점자블록 등 현재 unified 13-class 대상을 안전하게 표시 | class/confidence/count와 inference p50/p95 | [ ] |
| S-05 | bbox 정합 | 화면 bbox가 실제 물체에 맞는지 회전 0/90/180/270에서 눈으로 확인 | transform path와 회전별 현장 메모. JSONL만으로 화면 정합 PASS 금지 | [ ] |
| S-06 | stale guard | 카메라를 가렸다가 다시 열고 오래된 bbox/음성이 남는지 관찰 | `stale_reason`, `detections_used_for_depth=false` | [ ] |
| S-07 | 위험 피드백 | 안전한 고정 물체에 천천히 접근해 TTS/진동 여부 확인 | `risk_feedback_emitted`, alert gate | [ ] |
| S-08 | GPS 정지 | 창가/실외 정지 위치에서 trusted 전환과 정확도 확인 | `trusted_location_available`, accuracy, age | [ ] |
| S-09 | 음성 신고 명령 | 마이크 허용 후 "신고해줘"를 말함. 원문은 저장되지 않음 | `voice=report_command_recognized` 또는 실패 상태 | [ ] |
| S-10 | 세션 재시작 생존 | 기록 중 앱 process를 종료 후 재실행 | 동일 session ID와 resume event | [ ] |
| S-11 | 목적지 음성 | 기존 경로가 있는 상태에서 새 목적지를 검색해 상위 3개 번호·이름·주소·거리가 읽히는지 확인. 범위 밖 번호와 검색 실패도 시험 | 기존 route 유지, interaction speech와 search/select 상태 | [ ] |
| S-12 | 음성 우선순위 | 길안내 발화·음성 인식 중 안전한 위험 fixture를 발생시켜 위험이 먼저 발화하고 STT가 취소되는지 확인 | risk/interaction/navigation 및 STT 상태 event | [ ] |
| S-13 | 종료 | `현장 로그 종료`를 누르고 버튼이 시작 상태로 복귀 | manifest `completed`, `session_stopped` | [ ] |

정지 테스트에서 Depth 거리 오차를 판정할 때는 줄자로 잰 실제 거리와 화면 표시값을 별도 메모한다. 현재 JSONL은 ground truth 거리를 자동 수집하지 않는다.

## 4. 원거리 실외 테스트

안전한 보행로에서 보조 관찰자와 수행한다. 차도 진입, 실제 충돌, 시야를 가린 상태의 단독 보행은 테스트 방법으로 사용하지 않는다. 앱 안내에 의존하지 않고 주변 안전을 우선한다.

### 4.1 트랙 A: 오프라인 현장 로그

공개 backend가 없는 현재 기본 트랙이다. `서버 로그 켜기`는 끈 채 detector·ARCore Depth·GPS 품질·걸음 수·feedback·재시작 생존만 검사한다. `현장 로그 시작` 자체가 권한이 허용된 GPS·step 서비스를 시작하지만, 두 센서의 주기 metadata는 ARCore render loop의 telemetry에 함께 기록된다. 목적지 검색, 경로 API, 실제 신고 upload 성공은 실행하지 않고 `미실행`으로 남긴다. 현장에서는 앱을 foreground로 유지하고 다른 앱 전환·화면 잠금을 하지 않는다.

### 4.2 트랙 B: 원격 HTTPS backend

외출 장소의 모바일 네트워크에서 접근 가능한 HTTPS backend가 별도로 준비된 경우에만 추가한다. 출발 전에 폰에서 backend health를 확인하고 앱 Backend URL을 해당 주소로 바꾼다. 앱 process가 재시작되면 URL이 `127.0.0.1:8000`으로 초기화되므로 다시 입력한다. 이 트랙에서만 목적지 검색·경로 요청·신고 upload를 판정한다. 개발 PC의 `127.0.0.1`, USB `adb reverse`, 집 내부 LAN 주소는 멀리 떨어진 현장의 원격 backend가 아니다.

### 4.3 공통 체크

| ID | 테스트 | 실행·관찰 | 로그 판정 근거 | 결과 |
|---|---|---|---|---|
| F-01 | 15분 연속 실행 | 앱을 foreground로 유지하고 일반 보행, 발열·방전·중단·crash 관찰 | record 시각 간격, session 상태, parse 오류 | [ ] |
| F-02 | GPS·걸음 수 | 직선 구간을 평소 속도로 왕복 | trusted location 비율, accuracy p95, step min/max | [ ] |
| F-03 | 거리 구간 | 안전한 정적 물체를 0.5m·1m·2m에서 각각 10초 관찰 | metric depth 비율, risk distance 분포 | [ ] |
| F-04 | 접근 추세 | 보조자 감독 아래 안전한 물체로 천천히 접근 후 멈춤 | track/depth 변화와 피드백 event | [ ] |
| F-05 | 실제 보행 객체 | 보행자·차량·자전거·오토바이·신호등 등 현재 unified allowlist 대상을 멀리서 관찰 | class별 count/confidence. 오탐·미탐은 수동 메모 병행 | [ ] |
| F-06 | 점자블록 | 정상·손상 점자블록을 안전한 위치에서 관찰. Track A는 route 부재 fallback, Track B는 정상 same-frame·fresh·corridor evidence의 local 성공과 손상/stale/밖 fallback을 각각 확인 | tactile class, confidence, depth, report gate와 `localRoute=TACTILE_LOCAL:stable_aligned_tactile` 또는 정확한 TMAP reason | [ ] |
| F-07 | 경로 안내 | 트랙 B의 원격 HTTPS backend가 준비된 경우에만 수행 | route/navigation 상태, off-route/arrived 상태 | [ ] |
| F-08 | 백그라운드 복귀 | F-01~F-07 완료 후 별도 회복 시험으로만 1분간 background 후 재개. 중간 센서 공백은 정상 한계로 기록 | pause/resume event, session ID 유지, telemetry 공백 | [ ] |
| F-09 | 음성·TalkBack | 안전하게 정지한 뒤 음성 신고와 TalkBack 중복 발화 확인 | voice 상태와 feedback event, 수동 관찰 | [ ] |
| F-10 | 종료 보존 | 귀가 전 `현장 로그 종료`; 누르지 못했다면 재연결 후 앱에서 종료 | completed manifest 또는 active 상태 명시 | [ ] |

외부 공개 backend가 없으면 원거리에서 목적지 검색·경로 요청·신고 업로드 성공은 시험할 수 없다. `127.0.0.1`은 폰 자체를 가리키며, USB가 없는 장소에서는 `adb reverse`도 동작하지 않는다. 트랙 A에서는 F-07과 서버 신고 성공을 `미실행`으로 남긴다.

현재 APK에는 img768 unified TFLite가 primary로 포함되며 최근 instrumentation은 `fallback_used=false`였다. 시험 대상은 13종(`person`, `bicycle`, `car`, `motorcycle`, `bus`, `truck`, `traffic light`, `normal_tactile_block`, `damaged_tactile_block`, `crosswalk`, `curb_step`, `uneven_sidewalk`, `e_scooter_obstruction`)이다. `bench`와 legacy `tactile_damage_area`는 unified 실행의 시험 대상으로 기록하지 않는다. 현장에서 legacy fallback이 관찰되면 13-class PASS가 아니라 primary load failure로 판정한다.

정상 점자블록 탐지만으로 Android 경로 선택 PASS를 기록하지 않는다. production supplier는 같은 capture frame의 detection·metric depth·ARCore physical-camera pose, Earth orientation, trusted GPS와 현재 TMAP route segment를 결합한다. CODE/JVM 성공 경로는 연결돼 있지만 camera calibration/EIS·GPS drift·실외 안내 품질은 아직 Field 근거가 아니다. Track B에서는 모든 근거가 fresh이고 캡처 시점과 현재 시점 모두 corridor 안일 때만 `TACTILE_LOCAL:stable_aligned_tactile`을 허용하고, Track A·손상·stale·불일치·route 밖에서는 TMAP reason으로 복귀해야 한다. 화면 중앙 bbox나 GPS movement heading만으로 local 안내가 나오면 FAIL이다.

`FLAG_KEEP_SCREEN_ON`은 active field session의 화면 자동 꺼짐만 억제한다. 사용자가 다른 앱으로 전환하거나 화면을 직접 잠그면 `onPause`에서 ARCore·위치·걸음 수집이 멈추므로 해당 구간은 연속 현장 데이터가 아니다.

## 5. 귀가 후 로그 회수

외출용 폰을 USB로 다시 연결한 뒤 실행한다.

```bash
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
: "${FIELD_APK:?recorded frozen FIELD_APK path is required}"
test -f "${FIELD_APK}" && test ! -L "${FIELD_APK}"
# ARCore metric 세션
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL> --strict \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum "${FIELD_APK}" | awk '{print $1}')"

# CameraX 기능 세션은 아래 mode를 대신 사용. 실제 미지원 판정에는
# --require-arcore-unsupported를 추가한다.
python3 scripts/pull_android_field_sessions_20260710.py --serial <ADB_SERIAL> --strict \
  --strict-mode camera-non-metric \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum "${FIELD_APK}" | awk '{print $1}')"
```

저장 위치:

```text
artifacts/android-field-sessions/<회수시각>_<serial>/
├── field_sessions/<session-id>/manifest.json
├── field_sessions/<session-id>/records-NNNN.jsonl
├── device_pull_info.json
├── field_session_summary.json
└── field_session_summary.md
```

회수 도구는 폰의 원본을 삭제하지 않는다. `field_session_summary.md`와 raw JSONL을 확인하고 백업한 뒤에만 앱 데이터 삭제나 제거를 결정한다. `artifacts/`는 Git 제외 경로이므로 공개 저장소에 자동 포함되지 않는다.

회수 후 “테스트하고 왔어”라고 알려주면 위 최신 artifact를 기준으로 다음을 판정할 수 있다.

- 세션 무결성·중단 구간·JSONL parse 오류
- 설치 APK SHA-256·model-config SHA-256과 세션 provenance 일치 여부
- detector class·confidence·추론 지연·fallback·stale 비율
- ARCore metric depth source·거리·confidence 분포
- alert/report gate 통과율과 피드백 event
- GPS 정확도·freshness, step count, navigation 상태

정확한 오탐·미탐률과 거리 MAE는 각각 영상/GT annotation, 실측 거리 메모가 추가로 있어야 계산할 수 있다.
