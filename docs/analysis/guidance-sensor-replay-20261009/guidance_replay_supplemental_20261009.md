# 추가 센서·준비 회복 재생 사례

이 원 세션이 사용자 요청으로 만든 **센서 40개 + 준비 회복 12개**다. 기존 기본 JSON,
제품 코드, 공용 하네스는 수정하지 않는다. Sol 세션이 전체 목표와 제품 수정 담당이며,
이 자료는 추가 입력·기대·실행 근거의 인계다.

## 파일과 실행

- 생성/실행기: `scripts/replay_guidance_supplemental_20261009.py`
- 고정 입력: `apps/android/app/src/test/resources/navigation/guidance_replay_supplemental_20261009.json`
- 준비 시험: `apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/GuidanceReplaySupplementalReadiness20261009Test.kt`

실제 저장소 `/home/ddobagi/Hanium_Dreamup`에서 실행한다.

```bash
python3 scripts/replay_guidance_supplemental_20261009.py --list
python3 scripts/replay_guidance_supplemental_20261009.py --phase all
python3 scripts/replay_guidance_supplemental_20261009.py --phase sensors --case 'ext_gps_recovers_before_deadline_.*'
python3 scripts/replay_guidance_supplemental_20261009.py --phase readiness
```

`--generate`는 결정적으로 고정 JSON을 다시 생성한다. 검토 중 입력을 바꾸지 않으면
재생 시에는 필요 없다. `--output /absolute/fresh/directory`로 결과를 분리한다. 기존
원 결과 폴더는 덮어쓰지 않는다. 센서 실행은 기존 runner의 `--cases`를 그대로 사용하며,
준비 시험은 공용 `gradle.lock` 한 소유자로 전용 JUnit/trace를 저장한다. 소스 또는 하네스가
실행 중 변경되면 검증 완료로 판정하지 않는다. 첫 phase가 실행 불가이면 같은 공유
컴파일 실패를 반복하지 않고 다음 phase를 NOT_RUN으로 남긴다.
`--case`는 센서 ID만 필터링한다. 준비 phase는 항상 12개를 실행한다.

## 센서 입력 40개

| 상황 | 개수 | 확인하려는 조건 |
|---|---:|---|
| 음수 정확도·미보고·NaN으로 오류 종류 변경 | 3 | 적격 최근 근거와 최초 BAD 시각을 유지하고 새 정상 측정으로 회복 |
| GPS 유예 만료 직전 9600/9800/9950ms 회복 | 3 | 최초 BAD 1000ms, deadline 10000ms를 미루지 않고 음성 유지 |
| 중복 burst·역순·1ms/1000ms 미래 GPS | 4 | 수신시각을 원 측정시각으로 바꾸거나 진행·도착 근거를 교체하지 않음 |
| 방향 BAD 시작 위상 300/900/1250ms | 3 | 4500ms 유예 직전 회복과 고정 onset |
| GPS 먼저/방향 먼저/동일시각 두 순서의 회복 | 4 | 한 센서 회복이 다른 실패를 가리거나 불필요하게 음성을 취소하지 않음 |
| 음성 START 대기/재생 중 센서 transient | 3 | 적격 근거가 유지되는 동안 동일 발화가 잘리지 않음 |
| ERROR/STOP 뒤 과거 START·DONE | 2 | 오래된 완료와 실제 새 자동 재시도를 구분 |
| START 대기 중 7종 owner 교체 | 7 | foreground/epoch/session/actor/수집세대/입력세대/route 폐기 후 stale callback 무효 |
| GPS/방향 유예 중 권한 취소, 취소→재허용 | 3 | hard boundary 즉시 무효화, 기존 폐기 binding 재사용 금지 |
| GPS 유예 중 실제 180도 반전 | 2 | 약한 GPS 유예가 실제 명령 반전을 늦추지 않음 |
| 단발 좌/우/목적지 쪽 GPS 점프 | 3 | 점프만으로 합류·진척·자동 도착을 확정하지 않음 |
| 불규칙 수신 위상과 359/0도·작은 sway | 3 | 작은 변동이 취소 반복을 만들지 않고 native DONE 도달 |

시간은 기존 admitted-walk 하네스의 첫 ROUTE 요청을 0ms로 둔다. 반복 시각은 샘플
수를 늘리기 위한 숫자가 아니라 deadline·원 측정시각·이벤트 순서 경계를 검사한다.
같은 실패 cue의 backoff와 새 적격 cue의 교체 요청을 동일한 금지 조건으로 만들지 않는다.

## 준비 회복 12개

기존 `GuidanceFullJourneyReplayHarness`의 production controller/producers, 실제 PS/Fused
callback과 동일 navigation composition을 사용한다. ACTIVE·confirmed·readiness flag를
직접 설정하지 않고 정상 플랫폼 관측을 각각 전달한다.

- 타이머 종료 뒤 camera/사용환경 확인이 늦는 2개.
- 수동 장착 확인 없이 production 초기 assumption 사용, 카메라 회복과 초기 assumption
  결합, 카메라와 사용환경 확인이 같이 늦는 3개.
- 명시적으로 UNAVAILABLE인 모델 단독/카메라 결합 실패의 시작 차단 2개. 실제 Main처럼 READY를 유지하며 모델 실패·원인 고지와 Gateway/provider/경로 출력 금지를 확인한다.
- 카메라 입력이 실제 TTS DONE+3초 전후 1ms에 들어오는 2개.
- native START만 있고 DONE이 없는 동안 경과시간만으로 승인을 만들지 않는 1개.
- provider 응답 뒤 저장 ACK가 3초 지연되는 1개.
- 기존 HOME 요청 이후 offline engine ready callback이 오는 1개.

회복 가능한 관측이 회복되면 두 번째 시작 버튼 없이 같은 destination·PS binding·epoch로
실제 Gateway START, provider 요청, 저장 ACK, 첫 시계 방향/미터 native START/DONE,
END 확인 경로를 연결한다. 준비가 끝나기 전에 경로 요청하거나 저장 ACK 없이 안내를
시작하면 실패다. 지연 동안 계속 들어온 GPS/heading/camera 관측은 명시적 합성 입력이며
기존 시각을 현재처럼 갱신한 상태 설정이 아니다.
`unloadDetector()`는 대기 중인 load가 아니라 실패한 모델을 표현하므로 무조건 자동
회복 대상으로 삼지 않는다. PENDING load와 실제 실패를 분리하지 못한 최초 기대는 원
RED에 보존했다. 초기 fixture의 SAFE_STOP은 실제 Main의 준비 차단 controller 누락에 따른 결과였으며, 현재는 MODEL.UNAVAILABLE와 실제 DONE+3초 후에도 시작 차단이 유지되는지 검사한다. `withTimestampSdk`의 API17 leaf를
센서 하네스와 동일하게 적용해 JVM default SDK0 때문에 원 측정시각 API가 바뀌는 것을
방지한다. 실제 Android 기기 SDK·동작을 검증했다고 주장하지 않는다.

## 근거와 한계

- 센서 40개는 `ADMITTED_WALK_HEADING_OBSERVATION`: 이미 승인된 보행, 방향 변환 후 관측.
- 준비 12개는 `READY_CONTROLLER_TO_FIRST_SPEECH`: controlled platform observation으로
  실제 준비/승인/controller 경로를 확인한다. Android HOME 화면·물리 센서·실제 청취·현장
  보행·도착의 성공 근거로 확대하지 않는다.
- 두 범위의 건수를 더해 `FULL_HOME_TO_ARRIVAL=true` 또는 전체 Goal 완료를 주장하지 않는다.
- 공용 전체 흐름 하네스가 계속 확장되는 중이므로 처음에는 dependency/adapter/fixture
  공백도 드러날 수 있다. 최초 RED/컴파일 원문을 보존하고 제품·기대/어댑터·환경을 분류한다.
  기대 삭제·SKIP·예외 삼킴으로 녹색을 만들지 않는다.
- 독립 검토와 제품 수정은 이 자료를 인수하는 Sol 세션이 전체 목표에 통합한다.

## 실행 기록

- 입력 생성 결정성, 40개 고유 ID와 canonical ID 비충돌, 이벤트 순서, 기존 runner schema
  검증 통과. 준비 사례 12개 목록은 parameterized test와 일치한다.
- 최초 실제 실행:
  `/home/ddobagi/.local/state/walksafe-development/guidance-supplemental-20261009/first-current-source/`
  공용 신규 full-journey harness의 import/type 공백으로 JUnit 0개. 제품 사례 실패 또는
  PASS로 기록하지 않는다. 원 로그를 Sol 파일 담당에 전달했고 Root의 잘못된 enum import도
  실제 `navigation` 패키지로 수정했다. 나머지 공유 파일은 해당 담당이 수정한다.
- 후속 실제 실행 결과와 미분류 failure는 아래에 추가한다.

### 최초 실제 behavioral 결과와 교정

- `after-shared-fixture-import-fix/sensors/`: source `ac4345ae3a74be128791e8a48aa2fb4f0b9bae80a471f97b555e2b5c537bf4ad`,
  source/harness drift 없이 40개 실행, 34PASS/6FAIL/error skip0.
- 6개 중 미래 GPS 2개는 hard rejection 시 raw를 폐기할 수 있는데 반드시 이전 raw를
  유지하도록 요구한 부정확한 기대였다. 미래 근거 미게시·허위 도착 금지·새 정상 측정의
  회복/실제 DONE으로 교정했다. 방향 grace+늦은 START 1개는 교체 요청1200ms의
  실제 DONE=4200ms를3200ms에 검사했다. 요청/callback 계약을 유지해4500ms 검사로
  교정했다. 원 RED·입력·trace를 덮어쓰지 않는다.
- 나머지 actor/location_generation/input_generation 3개는 START 대기 중 owner 변화
  뒤 늦은 DONE이 이전 cue를 완료 처리했다. `owner` event가 실제 교체·퇴역 operation을
  모두 재현한 것인지, actual Main의 gate와 completion callback에 공백이 있는지
  **미분류**로 Sol 세션에 전달했다. assert는 유지했다.
- 교정3개 JUnit은 모두 통과했지만 해당 실행 중 shared full-journey harness 변경을
  감지해 runner가 실행 검증을 거절했다. `corrected-input-and-readiness/`에 보존하며
  coherent PASS로 집계하지 않는다.
- `readiness-first-execution/readiness/`: 같은 product source, drift 없이12개 실행,
  12FAIL/error skip0. 초기 assumption, 명시적인 모델 UNAVAILABLE, 소비된 calibration
  snapshot 등 expectation/adapter 공백과 first-route speech 누락을 구분하는 초도 RED다.
  이를12개의 확정 제품 결함으로 보고하지 않는다. 상태 강제 주입 없이 실제 경계·시간SDK
  adapter·기대만 교정한 최신 입력을 별도 final 실행으로 검사한다.

### 최종 인계 기준 실행

- 센서 `final-handoff-run/sensors/`: product source
  `aa95da59a81b22514ffc3babc9309a369737707ecf0355ac8263f0470cf9c042`,
  JUnit40개 failure/error/skip0. Product drift는 없지만 shared harness drift가 검출돼
  **EXECUTION_OR_ENVIRONMENT_ERROR**이며 coherent PASS로 집계하지 않는다.
  Sol이 공유 하네스를 고정한 뒤 현재40개를 다시 실행해야 한다.
- 준비 `readiness-ownership-ack-handoff/readiness/`: 같은 product source,
  source/harness drift 없이 **12개 실행, 2PASS/10FAIL/error skip0**,
  `execution_verified=true`, **RED_REQUIRES_TRIAGE**.
- 2PASS는 모델 명시적 UNAVAILABLE의 단독/카메라 결합 SAFE_STOP 경계다.
- 7FAIL은 준비가 회복돼 같은 destination/PS owner로 실제 Gateway ACK→ACTIVE,
  provider 요청/응답까지 갔지만 `STORE_WRITE→STORE_CLEAR`,
  `ROUTE_ARCHIVE_COMPLETE`, `route_snapshot_store_failed automatic_retry_pending`에서
  route가 설치되지 않은 사례다. Native 음성 assert를 제거해 통과시키지 않았다.
  실제 저장/readback owner·clock·암호화 fixture 계약 또는 제품 실패를 분류해야 한다.
- 나머지3FAIL은 `late_official_after_calibration`, `start_callback_without_done`,
  `provider_archive_ack_delayed`의 current readiness 미충족이다. 이 결과를10개의 확정
  제품 버그나 현장 실패 건수로 확대하지 않는다. 초기 calibration/helper 재사용의
  어댑터 한계도 같이 검토해야 한다.
- Root는 실제 ACTIVE ownership IO ACK를 처리한 뒤 provider 요청을 검사하도록 자신의
  테스트 순서를 교정했다. Route response의 저장 ACK는 여전히 실제 queued IO를 통해
  받으며 임의 성공 상태를 주입하지 않는다.
- 현재52개 입력/기대/스크립트 및 원 RED·실행 trace를 Sol 세션에 넘긴다. 이 인계 뒤
  Root는 해당 네 파일의 쓰기와 공유 Gradle 실행을 종료하며 Sol이 수정·독립 검토·재검증을
  인수한다. 공유 daylog 작성도 Sol의 단일 writer가 담당한다.

전체 원 결과 경로:
`/home/ddobagi/.local/state/walksafe-development/guidance-supplemental-20261009/`

### Sol 인수 후 원인 분류와 재검증

- `run-20261009T154636-229523`은 product `d90b7a6f...`의 동일 소스·공통 하네스로 52개를 실행했다. 센서 40PASS, 준비 9PASS/3RED, error/skip0이며 합계의 coherence도 확인했다.
- 저장 즉시 CLEAR의 7개 원인은 fixture route priority가 실제 STAIR_AVOID 응답 계약을 위반한 것이었다. providerRouteId와 priority를 분리해 교정했다. 같은 시각 camera의 CORRECTION_REQUIRED 및 공식 환경 LIMITED를 fixture가 UNAVAILABLE로 바꾼 오류도 실제 Main 매핑으로 교정했다. 원 입력과 READY 대기/DONE/ACK 단언은 보존했다.
- 남은 모델 2개의 SAFE_STOP 기대는 실제 Main의 시작 차단 계약과 달랐다. MODEL.UNAVAILABLE·실제 prompt START/DONE+3초·모델 원인 block·출력 금지 검사로 교정했고 독립 검토를 받았다.
- 저장 ACK 지연은 제품 결함이었다. 정상 ACTIVE readiness 갱신마다 같은 archive ownership을 재제출해 response operation을 무효화했다. 도착 확인의 purge도 scope가 비어 실행되지 않는 같은 경합이 있었다. 실제 Gateway/session/epoch/account가 같을 때 pending 및 검증된 ownership을 보존하고 실제 경계에서는 기존 무효화를 유지하도록 최소 수정했다.
- product `81cb4870...`의 `archive-race-recheck-20261009T160013`에서 전체 흐름 4개·준비 12개와 저장 경합·암호화 회귀가 통과했다. 함께 실행한 106개 중 기존 fixture 무인자 메서드가 사라진 10개만 실패했다. 진입점을 복원한 `archive-fixture-abi-recheck-20261009T160245`의 관련 23개는 모두 통과했다. 추가 첫 음성의 실제 Navigator ACK 검사와 최종 52개 실행은 후속 고정 버전으로 확인한다.


### Root 최종 통합 실행

- `run-20261009T174505-316530`: 센서40/40, 준비12/12 모두 PASS, failure/error/skip0. 양 phase와 공통 하네스의 실제 소스/해시가 같고 combined execution_verified=true이다.
- 고정 제품 소스는 `cbc31e858bd79b2b2f09d9291ddfc064bcd4cc18c48bdd230c2b4c471d21dc99`. 실제 native START 승인과 dispatch 당시 Nav cue DONE ACK 검사도 유지된다. 새 StepTracker/OfficialEnvironmentRuntime을 포함한 공용 FullHarness는 phase 사이에 변경되지 않았다.
- [최종 통합 근거](/home/ddobagi/.local/state/walksafe-development/guidance-supplemental-20261009/run-20261009T174505-316530/combined-summary.json). 원34PASS/6RED, harness drift로 거절한 실행, 준비의 원 RED 및 각 교정 사유는 위 역사 기록과 private 폴더에 보존한다.
- 기본76/관련회귀93과 함께 Goal의 필수 통제된 코드 검증에 통합했다. Android 화면 전체·하드웨어/음향/현장 성공으로 확대하지 않는다. 최종 APK/공개 다운로드 결과는 README 및 현재 delivery receipt를 따른다.
