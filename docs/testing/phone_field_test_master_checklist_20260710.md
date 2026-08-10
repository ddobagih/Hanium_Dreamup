# 휴대폰 현장 테스트 통합 체크리스트

기준일: 2026-07-13  
목적: 오늘 연결 폰의 정지 테스트와 내일 다른 폰의 원거리 테스트를 제품 경로별로 분리한다.

> 2026-07-11 갱신: Web/PWA용 production 서버, epoch270 real detector, TMAP/voice proxy,
> Cloudflare HTTPS, 인증과 자동 JSONL 기록이 준비됐다. 내일 주 테스트는
> `web_remote_field_test_20260711.md`를 따른다. 아래 Android 절차는 ARCore/TFLite 보조 연구용이다.
> 이후 현재 debug/AndroidTest APK를 SM-G981N에 설치하는 instrumentation에서 unified img768 primary와 legacy asset 계약·load/invoke 2/2를 통과했고 primary 결과는 `fallback_used=false`였다. 아래 정지 smoke 기록은 대화형 camera/depth Field PASS로 확대하지 않는다.

## 1. 먼저 구분할 두 경로

| 경로 | 제품 역할 | 원거리 실행 조건 | 기록 위치 | 현재 한계 |
| --- | --- | --- | --- | --- |
| Web/PWA | 주 사용자 앱 | Cloudflare 임시 HTTPS + field session token | 현재 run의 `web-field-logs`, 동의 기반 test-capture | 합성 카메라 원격 E2E PASS. 실제 외출용 폰의 camera/mic/TTS/진동/보행 검증은 내일 수행 |
| Android debug APK | ARCore Depth/TFLite 연구 보조 | 서버 없이 실행 가능, 앱을 foreground로 유지 | 폰의 `files/field_sessions` | unified img768 13-class primary·`fallback_used=false`와 실기기 instrumentation 2/2는 확인. 대화형 camera/depth/FPS·실외 Field는 미검증 |

`127.0.0.1`과 `localhost`는 원거리에서 이 개발 PC를 가리키지 않고 USB가 분리되면
`adb reverse`도 끊어진다. 2026-07-11에는 Cloudflare quick tunnel을 준비했으며 현재
주소는 `artifacts/cloudflare-field-test/current-public-url.txt`에서 확인한다. quick
tunnel 재시작 시 주소가 바뀌므로 출발 직전 Web 전용 실행서를 따라 다시 확인한다.

## 2. 오늘 연결 폰 정지 테스트 상태

전체 명령·판정은 `android_stationary_test_report_20260710.md`에 기록했다. 아래 PASS는
잠긴 폰에서 가능한 ADB·phone `curl` 범위이며 앱 UI E2E와 구분한다.

### ADB로 확인 완료

- [x] Android 13 / arm64 실기기 1대 연결
- [x] 카메라, 마이크, GPS/network location, 가속도계, 자이로, 걸음 감지·계수 센서 존재
- [x] Google Play Services for AR 1.54 설치
- [x] Wi-Fi 인터넷 검증과 위치 서비스 활성 상태 확인
- [x] 당시 debug APK 보존 설치, 당시 빌드본과 설치본 SHA-256 일치
- [x] 현재 debug/AndroidTest APK의 SM-G981N unified/legacy asset 계약·load/invoke instrumentation 2/2, primary `fallback_used=false`
- [x] camera/location/activity/audio 권한 grant, 앱 package 기동 및 crash 부재 확인
- [x] 저장공간과 배터리 상태 확인
- [x] USB reverse 뒤 phone `curl` backend health와 fake detect v2 API 계약 확인
- [x] phone `curl` 합성 Android-source 신고, reviewed 전환, source/date 필터 CSV 확인
- [x] 전용 test DB migration 뒤 functional Python/PostGIS 241개·0 skip, integration Python 109개·0 skip와 필수 canonical PT warm-up 확인
- [ ] 잠금 해제 뒤 camera preview·ARCore Depth·음성·현장 로그 UI 확인

마지막 항목은 기기가 PIN 잠금 화면이어서 BLOCKED다. PIN 우회는 테스트 방법으로
사용하지 않는다. 잠금 해제 후에는 Android 상세 체크리스트의 `S-01`~`S-11`을
순서대로 수행한다.

## 3. 내일 외출용 폰 연결 직후

1. 외출용 폰의 잠금을 해제하고 USB debugging을 허용한다.
2. 프로젝트 루트에서 기기를 확인한다.

```bash
adb devices -l
```

3. 한 대만 연결한 상태에서 최신 debug APK를 빌드하고 Gradle 출력과 분리된 읽기 전용 파일로 고정한 뒤 설치한다.

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
bash scripts/prepare_android_field_device_20260710.sh --apk "${FIELD_APK}"
printf 'FIELD_APK=%s\nFIELD_APK_SHA256=%s\n' "${FIELD_APK}" "${FIELD_APK_SHA256}"
```

untracked 파일을 포함해 worktree 변경이 있거나 commit을 주입하지 못하면 Field evidence 설치를 중단한다.
출력된 `FIELD_APK` 절대 경로와 SHA-256을 현장 메모에 보존하고 이후 다시 빌드하지 않는다. 아래 회수 명령은 같은 shell의 변수를 계속 사용하며, 새 shell에서는 기록한 두 값을 먼저 다시 설정한다.

4. Google Play Services for AR를 설치·업데이트한다. 설치 사실만으로 Depth PASS를
   주지 않는다.
5. 폰에서 카메라·정확한 위치·신체 활동 권한을 허용한다. 음성 시험을 할 때만
   마이크 권한을 허용한다.
6. 앱 상단의 로그인 user id를 테스트용 비식별 ID로 저장한다.
7. `현장 로그 시작`을 누른 뒤 `ARCore Depth 시작`을 누른다.
8. 버튼이 `현장 로그 종료`로 바뀌고 화면이 자동으로 꺼지지 않는지 확인한다.
9. camera preview와 ARCore session 시작을 확인하고 실제 Depth 지원 여부를 판정한다.
10. 화면 상태가 `loaded_model=unified_walksafe`, `fallback_used=false`, input 768과 13-class인지 확인한다. legacy가 표시되면 primary load failure로 기록한다.
11. Korean TTS가 발화 가능한지 확인하고 `SpeechRecognizer`로 “신고해줘”가 인식되는지 정지 상태에서 시험한다. 목적지 검색에서는 상위 3개 번호·이름·주소·거리를 읽는지, 검색 실패·범위 밖 번호에서 기존 경로를 유지하는지도 확인한다. TalkBack 시험 대상이면 해당 폰의 TalkBack도 미리 확인한다.
12. 30초 정지 smoke를 수행한 뒤 앱을 강제 종료·재실행해 동일 세션이 복구되는지
    확인한다.
13. 출발 전에 아래 회수 명령을 한 번 실행한다. 이 명령은 폰 원본을 삭제하지 않는다.

```bash
python3 scripts/pull_android_field_sessions_20260710.py --strict \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum -- "${FIELD_APK}" | awk '{print $1}')"
```

### 출발 Gate

`--strict`는 telemetry 0건, ARCore 시작 event 누락, loaded-model 근거 누락도 실패시킨다.
다만 Depth 지원 여부와 현재 APK의 정확한 fallback·hash·지속 기록은 자동으로 모두
판정하지 않으므로 다음을 추가 확인한다.

- [ ] `field_session_summary.json`의 `records.telemetry`가 1 이상이다.
- [ ] raw JSONL에 `arcore_session_started`가 있고 실제 `depth_supported` 값이 기록됐다.
- [ ] Depth 시나리오를 수행할 폰은 `depth_supported=true`다. false면 Depth 시험을
  `BLOCKED`로 남기고 대체 폰 여부를 결정한다.
- [ ] 현재 APK 기준 `loaded_model=unified_walksafe`, `fallback_used=false` 기록이 있다.
- [ ] session manifest의 APK SHA-256과 model-config SHA-256이 설치에 사용한 파일과 일치한다.
- [ ] telemetry에 GPS·step runtime 필드가 있고 30초 구간의 record가 지속해서 증가한다.

GPS·step 센서는 `현장 로그 시작`으로 활성화되지만 주기 JSONL telemetry는 ARCore
render loop에서 기록된다. ARCore가 시작되지 않으면 센서 event 일부만 남을 수 있으므로
telemetry 0건을 로그 준비 완료로 판정하지 않는다.

CameraX 기능 트랙은 위 ARCore gate가 아니라 아래 mode를 사용한다.
시작 event의 loaded model·fallback·`metric=false`·`reports_allowed=false`와 실제 detector 성공
`camera_non_metric_frame_analyzed(state=detector_succeeded)`를 검사하며,
ARCore telemetry나 ARCore 시작 event를 요구하지 않는다.

```bash
python3 scripts/pull_android_field_sessions_20260710.py --strict --strict-mode camera-non-metric \
  --require-arcore-unsupported \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum -- "${FIELD_APK}" | awk '{print $1}')"
```

`--require-arcore-unsupported`는 availability가 실제 `UNSUPPORTED_DEVICE_NOT_CAPABLE`이거나
지원 상태 확인 뒤 Session incompatibility가 발생한 경우만 인정한다. debug 강제·Depth 미지원·UNKNOWN·원인 누락은
기능 검증과 구분해 실패시키며, 요약은 `evidence_scope`와 `arcore_unsupported_verified`를 항상 표시한다.
실제 미지원 기기 판정은 선택된 최신 세션의 privacy-safe CameraX sample이 60개 이상, 900초 이상 이어지고
단조 시각 기준 간격이 1~30초여야 한다. epoch 시각도 정수·비감소·완료 manifest 범위 안인지 대조한다.
추론 p50/p95는 표시만 하며 합격 임계값으로 쓰지 않는다.
지원폰 debug 강제 fallback 기능만 시험할 때는 이 옵션을 빼고
`evidence_scope=FORCED_SUPPORTED_FUNCTIONAL`, `arcore_unsupported_verified=false`를 확인한다. 이 기능 스모크와
Depth 미지원 기능 스모크에는 15분 연속성 gate를 적용하지 않는다.

실제 advisory까지 안전하게 발생시킨 경우에만 `--require-camera-advisory`를 추가한다. 이때
event 당시 loaded model·fallback provenance도 함께 검사한다. CameraX low 경고는 전달 중 항목을
포함한 최대 3개 bounded FIFO로 순차 처리하고 TTS/TalkBack만 사용하며 진동하지 않아야 한다(P-09).
`camera_non_metric_advisory_emitted`는 TTS 완료 또는 TalkBack 전달 확인 뒤에만 있어야 하며,
전달 실패·gate·전달 시작 전 TTL·후보 소실로 폐기한 경고에는 event와 cooldown이 없어야 한다.
strict는 최신 세션 하나만 사용하며 여러 세션의 시작·advisory·telemetry를 교차 결합하지 않는다.

## 4. Android에서 실제로 시험 가능한 클래스

현재 APK는 `models/walksafe_unified_yolo26n_768_float32.tflite`를 primary로 포함하고,
hash·`[1,768,768,3]`→`[1,300,6]` tensor와 13-class 계약을 확인한 뒤 로드한다.

| 시험 가능 | 이번 APK 계약 밖/별도 검증 |
| --- | --- |
| 사람·자동차·버스·트럭·자전거·오토바이·신호등, 정상/손상 점자블록, 횡단보도, 보도 단차, 불균일 보도, 전동킥보드 장애물 | `bench`, legacy `tactile_damage_area` |
| ARCore metric depth, bbox-depth 연결, 거리·stale gate, TTS/진동 event | PyTorch와 TFLite box·class 동등성, FPS, 최신 모델 모바일 정확도 |
| 목적지 상위 3개 번호·이름·주소·거리 발화, 명시 번호 선택, 기존 경로 보존, `risk > interaction > navigation` | 보행 중 mic 인식률과 TalkBack 충돌의 자동 PASS |

instrumentation의 2/2 PASS는 asset 계약과 1회 invoke만 증명한다. 보도 단차·불균일 보도·전동킥보드를 포함한 실제 class별 성능, camera pipeline FPS와 PyTorch 대비 동등성은 현장·비교 검증 없이 PASS로 기록하지 않는다. 현장에서 legacy fallback이 관찰되면 unified 13-class 성공이 아니라 primary load failure다.

Android production에는 same-frame detection·metric depth·ARCore physical-camera pose, Earth orientation, trusted GPS와 현재 TMAP route segment를 결합하는 local tactile supplier가 연결돼 있다. CODE/JVM은 캡처 frame·route identity·캡처/현재 corridor gate와 fallback을 증명하지만 camera calibration/EIS·GPS drift·실외 안내 품질은 증명하지 않는다. Track B 현장 PASS는 정상 점자블록에서 이 근거가 모두 fresh일 때만 `TACTILE_LOCAL:stable_aligned_tactile`이 나오고, 손상·stale·불일치·route 밖에서는 즉시 TMAP reason으로 복귀해야 한다. 화면 중앙 bbox나 GPS movement heading만으로 local route가 나오면 FAIL이다.

## 5. 외출 중

- [ ] 앱을 foreground로 유지하고 직접 잠그거나 다른 앱으로 전환하지 않는다.
- [ ] 화면 밝기를 필요한 최소 수준으로 두고 발열·배터리를 5분마다 확인한다.
- [ ] 15분 연속 실행, 정지 물체 거리, 접근 추세, GPS 정확도, 걸음 수를 기록한다.
- [ ] 정상·손상 점자블록과 일반 객체를 안전하게 관찰한다.
- [ ] 오탐·미탐과 화면 bbox 정합은 JSONL만으로 판단할 수 없으므로 시간과 짧은 메모를 남긴다.
- [ ] 음성·TalkBack 시험은 반드시 멈춘 상태에서 수행한다.
- [ ] 차도 진입, 충돌 유도, 시야를 가린 단독 보행은 하지 않는다.
- [ ] 종료 시 `현장 로그 종료`를 누른다. 누르지 못해도 앱 데이터는 삭제하지 않는다.

원격 HTTPS backend를 쓰는 Track B에서는 Activity/process 재시작 뒤 Backend URL을 다시
입력한다. 현재 앱은 이 값을 저장하지 않고 재생성 시 `127.0.0.1:8000`으로 초기화한다.

세부 시나리오와 판정 필드는
`android_stationary_and_field_test_checklist_20260710.md`의 `F-01`~`F-10`을
사용한다.

## 6. Web/PWA 현장 시험을 별도로 하려면

2026-07-11에 다음 출발 조건을 준비했다.

- [x] 휴대폰 브라우저용 Cloudflare 임시 HTTPS URL
- [x] same-origin backend/voice proxy와 환경 기반 origin 허용
- [x] epoch270 SHA-256 고정, real/img768, fallback 없음
- [x] field/admin HttpOnly session과 login rate limit
- [x] metadata 1 Hz 자동 로그, 7일 보존
- [x] production에서도 field session + 명시 동의가 있을 때만 수동 맞음/틀림/스냅샷 저장
- [x] 합성 모바일 카메라의 damaged 3-frame 자동 신고 E2E. person STOP 표시는 안전정책 수정 전 historical artifact이며 현행 STOP 회귀 근거가 아님
- [ ] 실제 외출용 폰 camera/GPS/mic/TTS/진동/길안내 field PASS

실제 수행 순서와 현재 URL 확인 방법은 `web_remote_field_test_20260711.md`를 따른다.
합성 E2E PASS를 실제 보행 안전이나 class별 출시 품질 PASS로 바꾸어 기록하지 않는다.
실폰에서는 서버 탐지 처리 동의를 먼저 읽고 누른 뒤 카메라를 요청하며, 신고 저장과
metadata JSONL은 각각 별도 동의가 필요하다. `check_walksafe_remote_field_browser_20260711.py`의
fixture camera·가짜 GPS·headless 모바일 viewport 결과는 동의/API 회귀 근거일 뿐 실폰
camera/GPS/TTS/진동 Field 근거가 아니다.

## 7. 귀가 후

1. 외출용 폰을 USB로 연결하고 잠금을 해제한다.
2. 다음 명령으로 모든 session을 복사·검증·요약한다.

```bash
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
: "${FIELD_APK:?recorded frozen FIELD_APK path is required}"
test -f "${FIELD_APK}" && test ! -L "${FIELD_APK}"
python3 scripts/pull_android_field_sessions_20260710.py --strict \
  --expected-source-commit "$(git rev-parse HEAD)" \
  --expected-apk-sha256 "$(sha256sum -- "${FIELD_APK}" | awk '{print $1}')"
# 실제 ARCore 미지원 CameraX 세션은 위 명령에:
# --strict-mode camera-non-metric --require-arcore-unsupported
```

3. 결과는 `artifacts/android-field-sessions/<회수시각>_<serial>/`에 생성된다.
4. “테스트하고 왔어”라고 알리면 최신 `field_session_summary.json/.md`와 raw
   JSONL을 기준으로 중단 구간, inference 지연, fallback, depth, GPS 품질,
   걸음 수, gate와 feedback event를 분석한다.
5. 오탐·미탐률과 거리 MAE는 각각 현장 관찰/영상 정답과 줄자 실측 메모가 있을
   때만 계산한다.
6. 로컬 복사본의 무결성을 확인하기 전에는 앱 제거, `pm clear`, 앱 저장공간
   삭제를 하지 않는다.
