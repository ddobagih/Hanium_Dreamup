# 공통 운영 - 병렬 에이전트·자원 피크·중단 복구

문서 ID: `WS-ARTIFACT-COMPLETION-PARALLEL-RESOURCE-20260726-001`

상태: `ACTIVE_OPERATING_RULE`

## 1. 목적

병렬 에이전트와 컴퓨터 자원을 적극 사용하되, Android·Gradle·전체 gate 등 고메모리 작업의 피크만 통제해 사용자 로그아웃이나 시스템 종료 위험을 낮춘다.

자원을 아끼기 위해 단일 에이전트로 실행하지 않는다. 에이전트 수와 heavy process 동시성은 별도로 관리한다.

## 2. 현재 호스트 기준

계획 작성 시점:

| 항목 | 값 |
|---|---:|
| CPU | AMD Ryzen 9 9950X, 16 cores / 32 logical CPUs |
| RAM | 29 GiB |
| Swap | 8 GiB |
| 조사 시 available memory | 약 23 GiB |

실제 실행 전 available memory와 swap을 다시 확인해 Green·Amber·Red 정책을 적용한다.

## 3. 기본 에이전트 구성

| 역할 | 권장 수 | 책임 |
|---|---:|---|
| Coordinator·Merge owner | 1 | focus, 파일 lease, merge, checkpoint, canonical transition |
| 구현 작업자 | 2~3 | 충돌하지 않는 정확한 코드 경로 |
| 문서·추적 작업자 | 1~2 | artifact bundle, trace, register impact |
| 테스트·증거 설계자 | 1 | fail-first와 검증 범위 |
| 독립 reviewer | 2 | read-only 기술·품질 판정 |
| Heavy executor | 1 | Android·Node build와 전체 gate |

경량 단계에서는 8~10개 agent를 사용할 수 있다. heavy build 중에도 4~6개의 경량 분석·작성·review agent를 유지한다.

전체 슬롯을 모두 채우지 않고 복구·추가 review를 위한 여유 슬롯을 최소 2개 남긴다. 슬롯 부족 시 기존 작업을 단일 에이전트로 합치지 않고 신규 spawn만 대기시킨다.

## 4. Heavy resource semaphore

| Resource class | 최대 동시 실행 |
|---|---:|
| `android_gradle` | 1 |
| `full_start_or_resume_gate` | 1 |
| `node_or_web_build` | 1 |
| `python_heavy_test` | 2 |
| `canonical_merge` | 1 |
| `checkpoint_writer` | 1 |

규칙:

- Android 작업 agent는 직접 Gradle을 여러 개 실행하지 않고 Heavy executor에 요청한다.
- `apps/android/.gradle`과 build directory를 공유하므로 Gradle 2개를 동시에 실행하지 않는다.
- 전체 start/resume gate 중에는 모든 source write와 다른 build/test를 중지한다.
- Gradle 중에도 read-only 조사, 문서 작성, review, 다음 packet 설계는 계속한다.
- Node build와 Android Gradle을 동시에 실행하지 않는 것을 기본값으로 한다.
- 자원 측정 후 충분한 headroom이 확인돼도 전체 gate와 canonical merge는 항상 단일 실행한다.

## 5. 메모리 단계

| 상태 | 기준 | 실행 정책 |
|---|---|---|
| Green | available RAM 14 GiB 이상, swap 사용 1 GiB 미만 | heavy 1개와 경량 tool-active 최대 6개 |
| Amber | available RAM 8~14 GiB 또는 swap 1~3 GiB | heavy 1개와 경량 tool-active 최대 3개 |
| Red | available RAM 8 GiB 미만 또는 swap 3 GiB 초과 | 새 heavy 시작 금지, 실행 중 작업 안전 종료 |

Red에서도 agent를 모두 중단하지 않는다. shell·build 실행만 제한하고 이미 확보한 자료를 바탕으로 한 분석·계획·review는 계속한다.

OOM, desktop logout, kernel kill 징후가 있으면 다음 heavy 작업을 시작하기 전에 시스템 journal과 peak RSS를 별도 원인 조사 대상으로 둔다. 추측만으로 원인을 확정하지 않는다.

## 6. Work packet 계약

모든 병렬 packet은 다음 필드를 가진다.

```text
packet_id
goal_id
owner_agent
mode: READ | WRITE
exact_paths
dependencies
resource_class
expected_outputs
acceptance_criteria
input_snapshot_hash
```

파일 소유권:

- 하나의 writable path는 동시에 한 owner만 가진다.
- checkpoint, transition history, Goal package, canonical Gap·Backlog, artifact register는 Coordinator만 쓴다.
- build output과 gate log는 Heavy executor만 생성한다.
- reviewer는 read-only다.
- 공유 파일은 여러 packet으로 나누지 않고 한 owner에게 묶는다.
- 소유권 밖의 예상하지 못한 변경을 발견하면 해당 merge를 중단하고 사용자에게 확인한다.

packet 완료 보고:

- 변경 경로
- 최종 file hash
- 검증 상태와 로그
- 남은 finding
- 다음 dependency
- lease 반환 여부

## 7. 하나의 Goal과 병렬 packet

v2.4 checkpoint의 canonical focus Goal은 하나만 `IN_PROGRESS`로 유지한다.

병렬화 대상:

- fail-first 설계
- 서로 다른 코드 모듈 구현
- 계약 영향 조사
- 산출물 영향 분석
- 독립 review
- 다음 bundle read-only 준비

직렬화 대상:

- 같은 파일 쓰기
- full gate
- canonical Gap·Backlog 반영
- checkpoint 전이
- final subject digest 고정
- Goal completion과 successor materialization

이 구조는 단일 Goal 엔진을 유지하면서도 단일 에이전트 방식으로 퇴행하지 않게 한다.

## 8. 체크포인트 최소화

checkpoint는 다음 의미 있는 상태에서만 갱신한다.

1. Goal start 또는 work session resume
2. 계획된 중단 직전 working snapshot·handoff
3. canonical binding update
4. Goal complete와 다음 Goal materialize·ready
5. 실제 blocker 또는 package successor 전이

개별 agent·packet 완료마다 checkpoint를 갱신하지 않는다.

세션 중 packet 진행률은 저장소 정본과 분리된 로컬 append-only run ledger에 최소 정보만 기록할 수 있다.

권장 위치:

`~/.local/state/walksafe-agent-runs/<session-id>/run.jsonl`

기록 허용:

- boot ID
- checkpoint tail SHA
- packet 상태
- owner
- exact path hash
- heavy lane 상태

원문 prompt, 개인정보, 비밀값은 기록하지 않는다.

## 9. 계획된 종료

1. 새 packet 배정을 닫는다.
2. heavy 작업은 완료시키거나 receipt 없는 중단 시도로 남긴다.
3. 각 agent가 경로·hash·상태를 Coordinator에게 반환한다.
4. Coordinator가 working snapshot과 session handoff를 한 번 갱신한다.
5. transition history와 기존 receipt는 수정하지 않는다.
6. 현재 focus, blocker, 다음 단일 행동, 재개 조건을 daylog와 local-memory에 남긴다.

## 10. 강제 로그아웃·종료 후 복구

1. 이전 boot의 `RUNNING` packet을 `ABANDONED_BY_REBOOT`로 간주한다.
2. 출력 파일은 exact path와 hash가 맞을 때만 후보로 재사용한다.
3. receipt 없는 gate directory를 성공으로 해석하지 않는다.
4. PASS receipt가 있어도 event commit이 없으면 현재 snapshot에서 새 ID로 다시 실행한다.
5. 확인된 transition history prefix는 보존한다.
6. 미확정 staged suffix만 새 입력에서 재생성한다.
7. 과거 gate event ID와 출력 경로를 재사용하지 않는다.
8. snapshot 불일치가 owned 작업 때문이면 working snapshot과 handoff만 reconcile한다.
9. quick continuation·Goal graph 검사 뒤 새 resume gate를 실행한다.
10. `WORK_SESSION_RESUMED`가 committed된 뒤에만 제품 쓰기를 재개한다.

`tmux`는 단순 로그아웃에 도움을 주지만 shutdown 복구의 정본은 아니다. 복구 가능성은 checkpoint, hash, add-only evidence에 의존한다.

## 11. 진행 보고 형식

```text
Run: <session-id> / Boot: <boot-id>
Authority: checkpoint <version>, seq <n>, tail <sha>
Focus: <goal-id> / <status>
Artifacts: BASELINED <n> / ACTIVE <n> / DRAFT <n> / PLANNED <n> / TOTAL 257
GAP: INTERNAL <n> / EXTERNAL <n> / CLOSED <n>
Packets: DONE <n> / ACTIVE <n> / BLOCKED <n> / TOTAL <n>
Agents: BUILD <n> / DOCUMENT <n> / REVIEW <n> / AVAILABLE <n>
Heavy lane: RUNNING | QUEUED | IDLE
Durable boundary: <event or pause snapshot>
Validation: PASS | FAIL | NOT_RUN
Blockers: <exact blocker>
Next: <one concrete action>
Resume: <first read/check/action>
```

보고 시점:

- packet 묶음 완료
- heavy lane 상태 변경
- gate 결과
- Goal 완료 전이
- 계획된 pause
- 외부 blocker 발생

## 12. 운영 완료 기준

- 단일 에이전트로 불필요하게 직렬화된 독립 작업 0
- writable path 동시 owner 충돌 0
- 동시 Gradle 실행 0
- full gate 중 source write 0
- checkpoint writer 중복 0
- 강제 종료 뒤 재사용한 receipt 없는 PASS 0
- 다음 세션이 focus와 첫 행동을 즉시 복구 가능
- 자원 Red 상태에서 새 heavy 작업 시작 0

