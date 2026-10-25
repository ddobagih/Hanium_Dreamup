# WalkSafe GPS·방향·음성 콜백 재생

합성 위치·방향 값, 측정 시각, 콜백 도착 시각, 음성 START/DONE/ERROR를 현재 앱 코드에 입력하는 반복 시험이다. 휴대폰·실제 계정·사용자 API·Cloudflare·앱 데이터·서비스를 변경하지 않는다. 현재 구현·직접 시험 결과와 과거 기준선을 구분해 기록한다.

## 실행

실제 저장소 `/home/ddobagi/Hanium_Dreamup`에서 실행한다.

```bash
python3 scripts/replay_guidance_sensors.py --list
python3 scripts/replay_guidance_sensors.py
python3 scripts/replay_guidance_sensors.py --phase sensors --case 'gps_transient_.*'
python3 scripts/replay_guidance_sensors.py --phase sensors --case 'direction_persistent_auto_recovery'
python3 scripts/replay_guidance_sensors.py --phase ready-start
python3 scripts/replay_guidance_sensors.py --phase raw-sensors
python3 scripts/replay_guidance_sensors.py --phase full-journey
python3 scripts/replay_guidance_supplemental_20261009.py --phase all
```

`--case`는 ID 전체에 적용하는 정규식이다. `--cases /absolute/path/cases.json`으로 같은 스키마의 추가 입력을 재생할 수 있다. `--output /absolute/fresh/directory`는 새 결과 폴더만 허용한다. 기본 결과는 `/home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009/run-.../`에 생성된다. Python 표준 라이브러리, Java 21, 저장소의 Gradle wrapper와 기존 Android SDK/의존성을 사용한다. 추가 계정이나 새 라이브러리는 필요하지 않다.

한 실행은 공유 `work/part-a-four-sessions-20261001/gradle.lock`을 소유한다. 다른 실행이 빌드 중이면 exit 3을 반환하며 두 번째 빌드를 시작하지 않는다. 입력을 바꿨을 때 캐시된 시험 결과가 재사용되지 않도록 해당 Test task의 up-to-date/cache를 끄고 입력 파일과 환경을 명시한다. 프로젝트 build.gradle을 수정하지 않고 실행별 init script를 사용한다. Gradle 문서의 [시험 결과 위치 설정](https://docs.gradle.org/current/userguide/java_testing.html), [환경 변수와 캐시](https://docs.gradle.org/current/userguide/common_caching_problems.html), [초기화 스크립트](https://docs.gradle.org/current/userguide/init_scripts.html)를 참고했다.

## 입력 계약

입력은 `apps/android/app/src/test/resources/navigation/guidance_sensor_replay_v1.json`이다. GPS 좌표는 실제 좌표를 저장하지 않는 합성 local ENU 미터이다. 시각은 처음 경로 음성이 REQUEST된 순간을 0ms로 삼는다. 최초 부트스트랩은 이미 승인된 보행, 정상 GPS와 방향, 통제된 소개 음성 콜백을 전제하며 로그에 표시한다.

- `events[].at_ms`: 이벤트 도착·스케줄러 시각. 오름차순이고 같은 시각 이벤트는 파일 순서를 유지한다.
- GPS `north_m/east_m/accuracy_m`, `age_ms`, `mock`: 측정 시각은 도착 시각에서 age를 뺀 값이다. 음수 age는 의도한 미래 시각 입력이다. `accuracy_kind: missing/nan`은 값이 없는 것과 NaN을 구별한다. refused·중복·과거 입력을 현재 정상 위치로 만들지 않는다.
- heading `degrees/accuracy_deg/age_ms`, `heading_unavailable.reason`: 몸 방향 변환 이후의 관측이다. 시계만 진행했다고 측정 시각을 갱신하지 않는다. `accuracy_kind`는 미보고/NaN도 지원한다.
- `tts`: 실제 등록된 utterance ID에 대응하는 START/DONE/ERROR/STOP 콜백이다. ROUTE, INTRO, 상태·회복 발화를 구별한다. `tts_start_delay_ms/tts_done_delay_ms`는 ROUTE용이고 INTRO 기본은 60/200ms이다. REQUEST를 완료로 보지 않는다.
- `owner`, `permission`, `gps_unavailable`: 현재 owner/epoch/계정/수집 세대/권한·GPS 가용성 입력이다. 실제 SDK 어댑터가 연결되지 않은 경우에는 성공 대신 어댑터 공백으로 남긴다.
- `expectations`: 해당 시각에서 실제 출력·유예·회복·원 시각·도착을 검사한다. 미지원 이벤트/단언과 빈 case 선택은 즉시 실패한다.

현재 39개 데이터 사례는 정상·8/10/15m 초기 정확도·좌우 흔들림·359/0/1도 순환·GPS 오차/누락/NaN/점프·중복/역순/미래/mock·방향 누락/오차·GPS 및 방향 지속 문제·동시 회복·콜백 없음·지연 START·ERROR·오래된 DONE·권한 및 소유권 변화 등을 포함한다. 새 사례는 복구 음성 DONE 직전에 측정된 방향이 요청 시점에도 적격하면 추가 측정 대기 없이 재개되는지 확인한다. 임시 값과 유예가 없는 경우를 무조건 정상으로 해석하지 않는다.

## 결과 읽기

- `summary.json`: 현재 제품 소스 ID, 동결 입력 SHA, 선택 사례, JUnit 건수, 실패, 실행 명령, 범위·미검증 요구사항.
- `traces/*.json`: 실제 클래스 출처, 원 측정 시각, 유예 onset/deadline, REQUEST/START/STOP/DONE, 현재 owner/epoch, 실제 안내 상태. 정밀 좌표·토큰·목적지 주소는 기록하지 않는다.
- `junit/*.xml`, `gradle.log`: 실패 원문과 빌드·실행 결과. assert 실패뿐 아니라 모든 예외를 보존한다.
- `product-source.json`, `cases.json`: 이번 실행의 대상 소스 식별과 동결 입력. APK나 현장 성공의 증거가 아니다.

종료 코드는 0=검사 통과(공백은 남음), 1=실행된 사례 실패·분석 필요, 3=빌드 충돌/환경/실행 또는 검증 공백, 4=입력·출력 폴더 오류이다. `PASS_WITH_SCOPE_GAPS`는 골 완료가 아니다. 실패를 제품·fixture/어댑터·환경으로 근거에 따라 분류한다. `UNTRIAGED_EXECUTION` 예외도 제품 문제일 수 있으므로 전부 환경 탓으로 돌리지 않는다.

## 검증 범위

실행 범위는 다음과 같이 구분한다.

1. `ADMITTED_WALK_HEADING_OBSERVATION`: 실제 Main의 NavigationSessionComposition, PS/GPS/Compass/Guide/Nav, Output/Actuator/native 콜백과 `RouteCompassSession` scheduler lease·300ms 재평가 경로를 연결한다. 이미 승인된 보행 상태와 통제된 SDK 수집 owner를 전제한다. 실제 GPS 처리 함수에 합성 Android Location을 넣는다. 스케줄러 콜백은 결정적 시계로 구동하고 Android View의 실제 Handler 전달·기기 타이밍은 통제 경계로 남긴다. heading 입력은 물리 센서의 quaternion/gravity/magnetic 변환 이후이다.
2. `READY_START_CONTROLLER`: 실제 Entry/Native/Calibration/Startup/Confirmation/Admission/Gateway/Activation/Workflow 및 TTS 콜백을 연결한 10개 행동시험이다. 센서 준비 capture가 일부 통제되어 있으며 PS binding 없는 초기 목적지 admission만 포함한다.
3. `RAW_SENSOR_CONVERSION`: `RouteCompassRawSensorReplayTest`의 원 rotation-vector/gravity/magnetic/acceleration 입력이 제품 converter와 sensor state, facing/navigation 판단에 도달한다. 원 ns 시각·등록 세대·각도 정확도 미보고·초기 평평한 휴대폰·몸 앞 장착·버튼 roll·sway·큰 회전·자기장 이상을 검사한다. Android 하드웨어 callback과 실제 센서 품질은 별도 범위다.
4. `FULL_CONTROLLER_JOURNEY`: `GuidanceFullJourneyReplayTest`가 READY HOME workflow부터 실제 시작 버튼과 같은 production 진입점, 독립 readiness producers, native START/DONE+3초, 실제 admission/Gateway ACK/RuntimeActivation, 기존 PS binding, provider·암호화 저장 ACK, 첫 음성 ACK, 보행·회전·도착 확인·END를 같은 composition/epoch/clock에서 연결한다. Android HOME View 및 실제 청취를 포함하지 않는다. 12개 사례는 정상 흐름, 초기 정확도 개선, 첫 음성 START 대기/재생 중 튐, 명확한 합류/모호한 분기, 재탐색·저장 ACK 지연, GPS·방향 동시 지속 오류와 회복, 약한 도착·확인·END를 다룬다. 실제 SDK StepTracker의 걸음 입력과 OfficialEnvironmentRuntimeController도 같은 PS owner에 연결한다. 각 최종 실행의 소스·하네스 고정 여부를 별도로 확인한다.

실제 Main의 View 300ms 등록·반복 dispatch·수집 폐기는 별도 `MainActivityCompassSchedulerAndroidTest`로 대조했고, 멈춘 PENDING 합성 검사의 실제 HOME 목적지 시작 재기동은 `MainActivityPendingTtsPreparationAndroidTest`로 확인했다. 두 계측은 isolated `.speechstorefixture`와 API35 emulator에서 수행하며 사용자 앱 데이터를 변경하지 않는다. 서로 다른 범위의 통과를 한 번의 실제 현장 보행 성공으로 합산하지 않는다.

추가로 `RouteCompassSensorLifecycleTest.rawRotationVectorSampleUsesTheProductConverterAndReachesGuidanceState`는 Android [`SensorEvent.values`](https://developer.android.com/reference/android/hardware/SensorEvent#values)의 5개 `TYPE_ROTATION_VECTOR` 원시 float 샘플을 제품 quaternion→row-major converter에 넣고, 등록 세대·센서 타임스탬프 admission을 거쳐 실제 `RouteCompassSensorState`가 방향 관측을 내는지 확인한다. converter 행렬 계수는 AOSP [`SensorManager.getRotationMatrixFromVector`](https://android.googlesource.com/platform/frameworks/base/+/android14-qpr2-s1-release/core/java/android/hardware/SensorManager.java#1683)와 일치한다. SDK heading-error 필드와 3원소 호환 quaternion도 포함한다. 별도 시험은 4원소 quaternion 행렬과 잘못된 입력을 검사한다. 이는 원시 센서 수학의 JVM 단위 근거이며, `SensorEventListener` 등록/실제 Android callback delivery 또는 기기 센서 timing을 입증하지 않는다. 데이터 기반 `ADMITTED_WALK_HEADING_OBSERVATION` 재생 입력은 여전히 변환 이후 heading observation이다.

지속 GPS 회복 사례에서 retry deadline보다 먼저 새 ROUTE가 요청될 수 있는 이유도 토큰 단위로 기록한다. 복구가 feedback owner를 교체하면서 이전 owner의 실패 cue와 retry lease를 폐기하고, 동일 PS binding의 현재 owner가 적격 방향을 사용한다. 원 두 사례의 DONE→새 heading→교체 REQUEST 순서는 그대로 검사한다. 별도 사례는 DONE 직전의 원 측정이 아직 유효할 때 재사용되는지 검사하며, 요청 시 원 시각·정상 유효기간·입력 provenance를 확인한다. 같은 실패 cue 재시도는 `RouteNavigatorSpeechRetryTest`에서 backoff를 유지하는지 별도로 확인한다.

따라서 현재 `FULL_HOME_TO_ARRIVAL=false`이다. 이 두 부분을 합쳐 전체 Main 앱 정상 흐름이 검증됐다고 선언하면 안 된다. 새 골은 실제 readiness producers·목적지 admission·연속 위치/방향 스트림·경로 요청/저장 ACK·음성·합류·회전·도착을 동일 lifecycle/owner/clock/run으로 연결하고, 원 raw orientation 변환도 재생해야 한다. 실제 휴대폰 음향·성능·현장 이동 성공은 별도로 남긴다. 구체적인 완료 조건은 `GOAL_PROMPT.md`에 있다.

## 초기 실행과 전달

`BASELINE.md`는 2026-10-09 최초 결과의 변경 불가 기록이다. 활성 goal에서는 저장된 실패 trace를 다시 실행하고, 실패가 제품 동작인지 harness 경계인지 구분해 필요한 수정과 재검증을 진행한다. 테스트 기대를 약화시키거나 실패를 SKIP하여 녹색으로 만들지 않는다.


## 2026-10-09 제품 수정과 판정

- 시작 준비: 멈춘 PENDING 한국어 합성 검사를 현재 native 시작 owner에서 실제 SDK close/replace/start한다. 이미 실행 중이거나 실제 terminal 결과가 있는 검사는 그대로 둔다. 실제 availability를 true로 만들지 않는다.
- 음성 owner: 경로 발화마다 실제 positioning collection binding의 현재성 검사를 capture해 START/INFO/DONE에도 사용한다. 새 수집 binding이 생겨도 이전 발화 완료를 허용하지 않는다.
- 저장 경합: 동일 ACTIVE epoch·실제 Gateway generation/session/flags·account owner의 readiness 갱신은 이미 pending/검증된 저장 ownership 작업을 보존한다. END/SAFE_STOP, 새 Gateway/epoch/account 경계는 폐기한다.
- 방향 유예: 같은 센서의 음성 출력 세대만 바뀌면 원 firstBAD/deadline은 보존하고 기존 음성 권한을 폐기한다. 실제 측정 반전은 전체 폐기하며, 반전 분류·queue 등록·owner 교체를 같은 잠금으로 직렬화한다. 새 측정 반전 비교에 원 retained evidence를 사용해도 원 시각/정확도나 음성 권한을 갱신하지 않는다.
- RAW fallback: 최신 적격 자기장 이상을 과거 정상 gravity/magnetic pair가 가리지 않게 한다. 기존 GPS9초·방향4.5초·원 위치TTL10초 정책은 유지한다.

`FULL_CONTROLLER_JOURNEY`는 단일 HOME→END 제품 컨트롤러 재생이다. Android HOME View, active camera/mount service 전달 일부, 실제 하드웨어 callback·청취·현장 보행은 통제된 경계 또는 별도 미검증이다. 역사 `FULL_HOME_TO_ARRIVAL=false`를 바꿔 현장 성공으로 표시하지 않는다. `baseline_open_requirements`는 최초 목록을 보존하고 `open_requirements`는 해당 실행에서 직접 확인하지 않은 범위를 표시한다. 원 RED·준비 실패·빌드 실패는 private 실행별 폴더에 보존한다.

호스트는 14:49 재시작되어 기존 Quick Tunnel과 transient 서비스가 종료됐다. 기존 DB container/volume·private 설정·보안 값을 재사용해 서비스를 복구했고 공개 origin은 `https://online-nokia-webcams-crops.trycloudflare.com`이다. 최종 APK와 전체 공개 다운로드 검증 결과는 아래 최종 근거를 따른다. 이전 주소와 APK는 역사 근거로 보존한다.


## 최종 고정 소스 시험

제품 소스 `cbc31e858bd79b2b2f09d9291ddfc064bcd4cc18c48bdd230c2b4c471d21dc99`에서 다음 직접 실행이 모두 통과했다. 실패·오류·skip은 각각 0이고 실행 중 소스/하네스/시험 변경은 없었다.

| 실행 | 구성 | 결과 | 근거 |
|---|---|---:|---|
| 기본 전체 | 센서39 + 준비10 + RAW15 + 단일전체12 | 76/76 PASS | [summary](/home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009/run-20261009T174344-096868/summary.json) |
| 추가 전체 | 추가센서40 + 준비/회복12 | 52/52 PASS | [combined summary](/home/ddobagi/.local/state/walksafe-development/guidance-supplemental-20261009/run-20261009T174505-316530/combined-summary.json) |
| 방향·음성 회귀 | Nav 유예/소유권·START/DONE·Session/Compass7suite | 93/93 PASS | [summary](/home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009/direction-final-regression-20261009T174255/summary.json) |

독립 `tts_fix_review`가 같은 제품 기준에서 queued/in-flight 실제 반전 경합, 원 deadline, retained provenance, 늦은 START/DONE, 잠금 순서 및 수정된 시험 기대를 읽기 검토했다. 남은 P1/P2 지적은 없다. 시험 직접 실행과 최종 통합 판정은 Root가 수행했다.

실제 Main 스케줄러1개/API35(4.38초) 및 PENDING 한국어 합성검사 재기동1개/API35(3.115초)는 앞선 소스의 별도 직접 계측 근거다. 현재 최종221개 재생/회귀 건수에 합산하지 않는다. 실제 단말 설치·청취·야외 보행은 아직 확인하지 않았다.


## 최종 APK 전달

- [수정 APK 다운로드](https://online-nokia-webcams-crops.trycloudflare.com/downloads/WalkSafe-overnight-20261006.apk): 현재 public 파일과 전체 HTTPS GET의 **456,305,076 bytes**가 동일하다(HTTP200, no-store).
- SHA-256: `06d20af9cdb7880b4778dab3f0e32579ba3d5c8b6ded56250a8c8b2ba1c926ef`.
- 기존 signer 일치, bypass/quick-start/long-lived-login OFF, 개발 계정/비밀번호 empty, 현재 origin/sourceID DEX 일치. 기존6모델과 모든 assets/native entry SHA가 이전 공개APK와 동일하다.
- [빌드·서명·flag·모델 근거](/home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009/final-apk-20261009T174857/summary.json), [전체 다운로드 근거](/home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009/final-public-download-20261009.json), [최종 통합 근거](/home/ddobagi/.local/state/walksafe-development/guidance-sensor-replay-20261009/final-delivery-20261009.json).
- 현장 첫무음 로그에서는 캘리브레이션 DONE+3초 뒤 OFFLINE_KOREAN_TTS가 PENDING이었다. 취소 분기 자체는 현장 로그로 확정하지 못했고, 해당 미기동 경계는 실제 Main HOME 시작 계측으로 검증했다. 이후 밝기/장착각 차단은 별도 실제 조건이다. 신규APK에서 실제청취/보행 성공은 미검증이다.
- 이전 공개APK02044...와 후보9b8f... 및 원RED/빌드실패는 private preserved/source별폴더에 보존했다. 기존 데이터 초기화·물리단말 설치·전체commit/push는 하지 않았다.
