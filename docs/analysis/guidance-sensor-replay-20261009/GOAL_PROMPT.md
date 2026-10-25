# WalkSafe 새 세션 골 프롬프트

사용자가 승인한 작업이다. 이 문서를 새 세션의 최초 지시로 사용한다. 실제 저장소는 `/home/ddobagi/Hanium_Dreamup`이다. 저장된 프로젝트 표시 경로 `/home/ddobagi/ChatGPT/한이음 프로젝트`를 제품 소스 경로로 사용하지 않는다. 모든 터미널 실행은 `workdir=/home/ddobagi/Hanium_Dreamup`, `shell=/bin/bash`, `login=false`를 명시한다.

먼저 이 세션에서 `get_goal`로 상태를 확인하고, 미완료 골이 없다면 `create_goal`로 아래의 목표를 설정한다. 사용자가 토큰 예산을 지정하지 않았으므로 token_budget은 설정하지 않는다. 기존 미완료 골이 있다면 지우거나 덮어쓰지 말고 현재 목표와 충돌하는지 확인한다.

## 설정할 목표

실제 WalkSafe의 GPS·방향 관측, 위치 추정과 유예, 음성 출력 및 경로 안내 코드를 사용하는 재현 스크립트로, 실제 사용자에게 생길 수 있는 정상·일시 오류·지속 오류·회복·시작 및 도착 실패를 찾아 원인을 분석하고 최소 수정·동일 사례 재검증·관련 회귀·독립 검토를 반복한다. 정상 환경의 홈 → 목적지 설정 → 안내 시작 → 첫 시계 방향·미터 음성 → 경로 합류·보행·회전 → 도착·사용자 도착 확인·종료가 하나의 실제 실행 흐름으로 이어지는지를 검증하고 재현된 문제를 해결한다. 테스트가 통과하도록 상태를 강제 설정하거나 기대를 약화시키지 않는다. 필수 범위의 재현 실패와 검증 공백이 남아 있으면 골을 완료하지 않는다.

## 시작 자료와 실행

1. 저장소 `AGENTS.md`, 최신 `daylog/2026-10-09.md`, 팀·검증·작업 기록 지침을 읽는다. 프로젝트의 ‘현재 작업 원칙 — 기능 구현 우선’이 오래된 Goal graph 통제 절차보다 우선한다. 별도 Goal graph 복구·대규모 통제 문서를 만들지 않는다.
2. `docs/analysis/guidance-sensor-replay-20261009/README.md`, `BASELINE.md`와 이 프롬프트를 읽는다.
3. `scripts/replay_guidance_sensors.py`, `apps/android/app/src/test/resources/navigation/guidance_sensor_replay_v1.json`, `GuidanceSensorReplayTest.kt`, `GuidanceSensorReplayHarness.kt`, `navigation/start/GuidanceReadyStartReplayTest.kt`를 확인한다. 입력과 기대는 합성 local ENU 좌표와 상대 시각이다.
4. 파일 담당과 실행 중 프로세스를 확인한다. Root가 이 세션을 생성하기 전 스크립트·공유 시험 쓰기를 종료한다. `work/part-a-four-sessions-20261001/gradle.lock`을 사용하여 공유 Gradle을 한 번만 실행한다. 같은 파일 한 수정 담당자를 유지하고 작은 독립 분석·시험·독립 검토만 필요한 역할에 위임한다.
5. `python3 scripts/replay_guidance_sensors.py --list`로 목록·스키마를 확인하고 `python3 scripts/replay_guidance_sensors.py`로 현 소스의 기준 결과를 생성한다. 특정 사례는 `--case '정규식' --phase sensors`, 시작 체인은 `--phase ready-start`로 실행한다. 새 실행의 결과 디렉터리는 명령 출력에 나온다. `summary.json`, JUnit XML, `gradle.log`, 사례별 trace를 함께 읽는다.

## 핵심 사용자 정책

- 기능 제한 중 목적지 검색 차단은 정상이다. 제한 중 검색을 무조건 허용하는 방식으로 증상을 숨기지 않는다. 정상 환경에서 어떤 조건이 잘못 제한으로 판정되는지 조사하고 원인을 고친다. 직전의 검색 허용 확대는 이미 철회되었다.
- 앱 실행·로그인 후 이미 수집 중인 위치·방향 스트림을 안내 시작에 연속 사용한다. 새 시작 점검 때문에 실제 수집 owner/binding/세대나 측정 시각을 불필요하게 초기화하지 않는다.
- 현재 실제 앱 설정은 GPS 3초 단위 × 3회 확인(총 9초), 방향 0.3초 관측과 1.5초 단위 × 3회 확인(총 4.5초)이다. 이것을 사용자 새 승인 없이 바꾸지 않는다. 기본 `RouteNavigator()`의 1.5초를 앱의 4.5초 정책이라고 잘못 시험하지 않는다. 위치 원 관측의 기존 유효기간 10초는 별도로 확인한다.
- 최초 문제 시각을 고정한다. BAD가 반복되거나 재측정을 시도한다고 유예 시각을 뒤로 미루지 않는다. 유예 동안 새 측정은 계속 받고 기존의 적격한 최근 정상 근거로 안내와 기능을 유지한다. 현재 정상 근거와 유예로 유지하는 최근 근거를 구분한다. 센서의 원 시각·정확도·공분산을 현재 값처럼 꾸미지 않는다.
- 실제로 적격한 새 정상 관측이 회복되면 수동 ‘다시 시도’ 없이 안내를 연결한다. 지속 오류에는 원인별 고지·필요한 사용자 조치·자동 재측정을 연결한다. 정상 관측 자체의 판정과 각 모델 회복 단계를 확인하며 arbitrary timer·새 정상 판정 대기 단계를 덧붙이지 않는다.
- 권한 취소, 계정·owner/epoch 교체, 명시적 종료, 실제 명령 반전은 즉시 무효화 경계이다. 약한 위치·참조 안내로 정밀 진척·회전 통과·자동 도착을 확정하지 않는다. 최초 적격 근거가 없을 때도 정상 값이나 안전한 통로를 만들어내지 않는다.
- 몸이 바라보는 방향 기준 단일 ‘몇 시 방향, 몇 미터’ 안내를 사용한다. 작은 좌우 흔들림은 경향성으로 처리하고 실제 큰 방향 전환은 반영한다. 11시~1시 같은 범위를 읽거나 무관한 포괄 멘트를 남발하지 않는다. 같은 음성을 반복 취소·재시작하여 첫 음절만 반복하게 만들지 않는다.
- 시작 정지 안내의 실제 음성 DONE 후 3초 관찰을 사용한다. 추정 재생시간으로 DONE을 만들지 않는다. 실내/실외 판별과 경로 시작점 강제 이동, 카메라 흔들림 조건, 이전 보행 중단 팝업을 복구하지 않는다.

## 현재 스크립트의 검증 범위와 반드시 메울 공백

현재 성공 여부와 관계없이 `FULL_HOME_TO_ARRIVAL=false`이다. `ADMITTED_WALK_HEADING_OBSERVATION`은 이미 승인된 보행과 몸 방향 변환 이후 heading 입력을 전제한다. 실제 Main 조립의 PS/GPS/Compass/Guide/Nav와 실제 Output/Actuator/native 콜백 제어를 시험하지만 앱 실행부터의 모든 초기화가 아니다. `READY_START_CONTROLLER`는 실제 준비·캘리브레이션·승인·Gateway ACK를 실행하지만 일부 Android effects와 센서 준비 capture가 통제되며 PS 등록 이후 목적지 admission까지 연결하지 않는다. 이 두 부분의 PASS를 합쳐 홈→도착 전체 PASS라고 하지 않는다.

이 골에서 반드시 추가 연결할 것은 다음이다.

- 같은 lifecycle, gateway session/actor, clock/큐, 실제 NavigationSessionComposition와 run, PS binding/generation, Guide, 음성 owner를 공유하는 단일 흐름. READY 홈에서 목적지를 선택하고 실제 시작 버튼과 같은 진입점을 호출한다. 이미 ACTIVE·confirmed·navigationAllowed·accepted match를 넣어서 시작하지 않는다.
- 현재 하네스의 자체 300ms polling/Guide retry 모델을 실제 Main이 등록하는 스케줄러와 연결·대조한다. 테스트의 수동 재시도 호출이 실제 자동 재개 누락을 대신 살려내지 않아야 한다.
- 실제 `WalkReadinessCoordinator.capture()`와 producers, capability resolver, 공식 환경·장착 평가 결과가 실제 입력을 소비하게 한다. GPS 입력 하나로 카메라·장착·모델까지 READY가 되는 통합 대체물을 사용하지 않는다. Android의 권한·서비스·계정 저장 결과·카메라 정상 관측은 명시적인 플랫폼 입력 경계로 통제할 수 있다.
- 실제 `NativeDestinationStartAdmission.capture()`의 목적지 객체 identity, session identity, permission gate identity, route generation, PS binding/generation, current coordinator/collection 결속을 연결한다. PS/binding이 없는 시작 체인을 완성된 위치 admission이라고 보지 않는다.
- 안내 시작 전에 실제 GPS·방향을 미리 공급하고 실제 TTS START/DONE+3초, readiness, confirmation, Gateway ACK, actual RuntimeActivation, 기존 PS 스트림, 실제 Acq 목적지 요청, provider 응답과 저장 ACK, Guide 첫 안내와 보행·회전·도착까지 이어간다. Android 정적 시계 때문에 JVM이 막히면 시간/SDK 경계의 최소 test seam이나 적절한 Android 테스트 런타임을 마련한다. 무조건 true·예외 삼킴으로 통과하지 않는다.
- rotation vector, gravity, magnetic, acceleration의 실제 방향 변환·품질 판단 입력도 별도 재생한다. 후면 카메라/몸 방향 기준과 초기 장착·버튼 조작, 보행 중 sway, 일시 휴대폰 조작, unreported angular accuracy를 다룬다. 직접 heading 주입을 물리 센서 변환 시험으로 주장하지 않는다.
- 단일 전체 정상 흐름, 초기에 정확도가 점점 좋아지는 시작, 첫 음성이 START를 기다리는 중 센서 튐, 재생 중 튐, 경로 밖의 명확한 최근접 지점과 모호한 분기, 재탐색 응답/저장 ACK 지연, GPS·방향 동시 회복, 도착 후보와 확인·종료를 추가한다.

## 분석·수정·재검증 반복 방법

- 각 실패를 제품 행동, 잘못된 사례 기대·입력/어댑터, 실행 환경으로 근거에 따라 분류한다. 비단언 예외도 제품 결함일 수 있다. 입력 준비 실패나 미지원 SDK를 제품 성공/실패로 바꾸지 않는다.
- 사례 ID와 원본 trace/RED를 보존하고 실제 첫 실패 단계·취소 원인·원 관측 시각·firstBAD/deadline·owner/epoch·REQUEST/START/STOP/DONE·재시도·위치 projection/진척/도착을 확인한다. 로그에 실제 토큰·정밀 좌표·목적지 주소·영상·음성 원본을 불필요하게 쓰지 않는다.
- 음성 ERROR 재시도는 실제 failedAt와 현재 speechRetryInterval/등록 Runnable 하한을 계산해 그 이후에 검사한다. 정상 센서 회복이 일반 실패 backoff에 막히는 경우에는 실제 적격 회복 시각과 사용자 자동 재개 정책을 별도로 대조한다. 유효한 기존 대기 시간을 확인하지 않은 조기 단언으로 제품 결함을 만들지 않는다.
- 제품 문제면 가장 작은 원인 수정 후 같은 입력·기대의 재검증을 수행한다. 당시 실패 원문과 변경 후 결과를 나란히 보관한다. 실제 잘못된 fixture면 목적을 유지한 최소 입력/어댑터 수정과 원래 이유를 기록한다. RED를 SKIP하거나 assert를 삭제해서 녹색으로 만들지 않는다.
- 이미 통과한 검사는 새 변경·실패·의문이 없으면 반복하지 않는다. 변경된 영역의 회귀, 동시 위치/방향·TTS, 실제 전체 정상 흐름 대조는 계속 유지한다. 필요한 경계 사례·결정적 seed를 늘리되 무의미한 조합 수나 단순 성공 건수를 목표로 삼지 않는다.
- 독립 검토자는 실제 코드와 기대·현재 설정을 검토한다. 구현자의 자체 시험을 독립 검토로 합산하지 않는다. 단말·실제 음향·현장 미검증은 별도로 남긴다.

## 기존 자료와 보호 경계

- `docs/analysis/implementation-spec-20261004/` 등 기존 분석은 과거 사실이며 현재 코드를 확인해 사용한다. 최신 전달 기준은 `work/overnight-guidance-20261006/verification.json`과 `work/guidance-overnight-recovery-20261008/`이다. 기존 WIP·계정·교육·동의·기기 데이터를 보존한다. reset/stash/checkout/uninstall/광범위 삭제·전체 commit/push 금지.
- 초기 재생 하네스와 입력 스키마는 필요한 범위에서 수정·확장 가능하다. 동일 파일 writer를 한 명으로 유지한다. 실행기를 모사한 별도 앱 알고리즘을 만들지 않는다.
- 디스크 여유가 작으므로 큰 APK/중복 소스 스냅샷을 불필요하게 늘리지 않는다. 공간이 실제로 부족하면 본 작업이 만든 동일한 generated/private 복제물만 bytes·필요한 원본을 보존해 정리한다. 사용자 파일이나 과거 미확인 증거를 지우지 않는다.
- 기존 진단 자동 전달·동의·삭제·인증 경계와 Cloudflare 사용자 API를 보존한다. 새 테스트는 실제 계정/서버/기기를 바꾸지 않는다. 현장 로그는 실제 업로드가 확인될 때만 읽어 해당 source와 대조한다.
- 제품 수정이 실제로 완료되면 관련 회귀·독립 검토·APK 빌드·서명·우회/개발 자동로그인 OFF·기존 모델/앱 데이터 보존·다운로드 전체 파일 일치를 확인해 기존 Cloudflare APK 주소를 갱신한다. 실제 단말이 없으면 설치/청취/보행 성공으로 기록하지 않는다.

## 완료와 인계

다음 조건을 충족했을 때만 골을 완료한다: 단일 실제 코드 HOME→목적지→시작→첫 음성→보행·회전→도착·확인·종료 재생이 통과하고, 각 단계의 정상·센서 순간 튐·유예 중 회복·지속 오류·소유권/권한 경계·음성 지연/실패 사례에서 재현된 제품 실패와 필수 검증 공백이 없으며, 변경된 코드와 테스트에 대한 독립 검토가 끝나고 필요한 최종 APK/근거가 제공되어 있다. ‘실기기 없음’만으로 가능한 알고리즘/콜백 검증을 남기지 않는다. 현장 성공이나 무결함은 선언하지 않는다.

사용자가 자는 동안 승인 범위의 가역적인 분석·수정·시험은 반복 확인을 묻지 말고 진행한다. 의미 있는 발견·수정·실패·인계 때만 짧게 알리고 동일 상태 알림을 반복하지 않는다. 새 정보·환경 변경 없이는 진행할 수 없는 동일한 차단이 3개 연속 골 턴에서 반복될 때만 blocked로 기록한다. 어려움·미완료·시간이 오래 걸린다는 이유로 blocked/complete 처리하지 않는다. 실행 실패의 원인·보존 결과·다음 행동과 파일 담당을 최신 daylog에 남긴다.
