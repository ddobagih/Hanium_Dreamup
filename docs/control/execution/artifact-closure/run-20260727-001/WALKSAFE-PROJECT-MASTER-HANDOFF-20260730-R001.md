# WalkSafe 프로젝트 종합 재개 인계서 20260730 R001

- 문서 ID: `WS-WALKSAFE-PROJECT-MASTER-HANDOFF-20260730-R001`
- 상태: `HANDOFF_CANDIDATE_NON_EFFECTIVE`
- 작성일: `2026-07-30`
- 저장소:
  `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715`
- branch: `codex/walksafe-rc2-hardening-20260715`
- HEAD/base: `a3ad7eead6b5d834d3e0675422475a9aad351e3d`
- 최신 실행 재개 인계 이력:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R009.md`
- 실행 재개 인계 SHA-256:
  `c5cef4f0f0b7dd79d63df10500b0aa47bfe788a9f88551c06ee5ab18b25a0047`
- 실행 재개 인계 bytes: `7,797`
- 실행 재개 인계 review:
  `CONTINUATION-EXECUTION-HANDOFF-20260730-R009-independent-review-r001.md`
- 실행 재개 인계 review SHA-256:
  `9f5cb3d297323fdd602b88270d1fd04292f32fdaa7a95400c9c0e01a5598ec1c`
- 실행 재개 인계 review bytes: `3,044`
- 실행 재개 chain 판정:
  `FAIL_HISTORY_ONLY_NOT_A_RESTART_AUTHORITY`
- 종합 인계 독립검수:
  `WALKSAFE-PROJECT-MASTER-HANDOFF-20260730-R001-independent-review-r001.md`
- 효력: 설명·재개·계획용. canonical/Goal/product/release 변경 권한 없음

## 0. 이 문서의 목적과 사용법

이 문서는 새 터미널의 Codex가 과거 조사와 실패한 문서 succession을 반복하지
않고, 현재 막힌 한 지점부터 실제 제품 완성을 다시 진행하도록 만든 종합
인계서다.

최종 목적은 문서를 많이 만드는 것이 아니다.

```text
WalkSafe 제품 구현
→ 68개 정책·Gap의 실제 내부 잔여 종결
→ 257개 산출물의 내용·승인·실행·사건 증거 종결
→ 하나의 불변 후보에 대한 정식시험·실기기·현장 검증
→ 5개 release gate 폐쇄
→ 배포·운영·이관 또는 승인된 종료
```

이 문서가 checkpoint, 기능 정책, 정식 승인 receipt를 대체하지 않는다.
충돌 시 live checkpoint와 해당 대상에 결속된 findings-zero receipt를 우선한다.
다만 오래된 계획의 “다음 행동”은 이 문서의 §4와 §7에 적은 최신 완료 사실로
교정해서 읽는다.

### 0.1 repository AGENTS의 알려진 runtime pointer skew

repository `AGENTS.md`는 안전·정책·dirty-tree·daylog 원칙을 위해 반드시
읽고 지킨다. 그러나 첫 네 파일과 quick check를 v2.3으로 고정한 시작 부분은
현재 v2.4 ACTIVE / sequence 39 checkpoint보다 오래된 runtime pointer다.
이 skew는 2026-07-29 계획 결함 대장에도 기록됐고 이번 docs-only 작업에서
`AGENTS.md`를 임의 수정하지 않았다.

- v2.3 README와 imported v2.2 Master는 역사·구조 참고자료로 읽는다.
- v2.3 continuation/Goal checker의 현재 FAIL은 active v2.4를 v2.3으로
  검증한 예상 불일치이며 복구 대상 source regression으로 보지 않는다.
- 현행 재개 무결성은 이 문서 §7.4의 v2.4 checker 두 개로 확인한다.
- `AGENTS.md`의 나머지 제품 경계, 승인, 단일 leaf, non-destructive,
  daylog·local-memory 원칙은 그대로 유효하다.

새 Codex는 이 사실을 확인하기 위해 v2.3 기준선을 되돌리거나 현재
checkpoint를 v2.3으로 낮추지 않는다.

## 1. 프로젝트를 왜 하는가

WalkSafe는 한이음 드림업의 시각장애인 도심 보행 보조 Android 프로젝트다.

- 카메라와 온디바이스 모델로 가까운 보행 위험을 감지한다.
- TMAP 보행 경로와 위험 안내를 결합한다.
- 손상된 점자블록 신고를 수집하고 관리자가 검수해 기관 전달을 준비한다.
- 사용자 앱, 관리자 앱, Android Gateway, Backend, 데이터·모델·운영 증거를
  하나의 안전한 제품 경계로 완성한다.

안전시험 전에는 흰지팡이·안내견·보호자를 대체하거나 보행 안전을 보장하는
제품이라고 주장하지 않는다. 제출용 문서가 있어도 실제 제품·시험·운영 증거가
없으면 프로젝트 완료가 아니다.

## 2. 현재 공식 상태

### 2.1 실행 정본

| 항목 | 현재 값 |
|---|---|
| control package | v2.4 `ACTIVE` |
| checkpoint tail | sequence `39`, `CANONICAL_BINDINGS_UPDATED` |
| canonical Gap / Backlog | r021 / r021 |
| Goal focus | `WS-GOAL-EPIC-03` Workstream `READY` |
| materialized focus leaf | 없음 |
| ready frontier | `EPIC-03`, `EPIC-12` |
| canonical r022 | 없음 |
| active v2.5 | 없음 |
| P/M physical candidate | 없음 |

live r021 Backlog/checkpoint의 `current_work`가 가리키는
`FP-048/GAP-057 준비`는 알려진 stale planning defect다. 지금
materialize/start하지 않는다. R002의 `FP-008/GAP-017`도 아직 비정본
candidate다. authoritative next leaf는 S2의 M 적용 뒤 새 frontier를
재계산해서만 결정한다.

정본 결속값:

| 파일 | SHA-256 | bytes |
|---|---|---:|
| `docs/control/walksafe-project-continuation-checkpoint.json` | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| `docs/control/goals/walksafe-completion-graph-v2-4/static-plan-manifest-v2.4.0.json` | `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07` | 39,534 |
| `docs/control/audits/walksafe-implementation-gap-analysis-20260726-r021.json` | `f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a` | 488,160 |
| `docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r021.json` | `bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0` | 59,266 |

이 저장소는 의도적으로 큰 dirty working tree를 가진다. 위 정본 파일도 HEAD
하나만 checkout해서 재구성할 수 있다고 가정하지 않는다. `git reset --hard`,
`git checkout --`, `git clean` 또는 광범위 정리를 하지 않는다.

### 2.2 제품·증거 상태

| 축 | 공식 현재값 | 의미 |
|---|---:|---|
| active r021 Gap | B5 / C16 / E4 / M11 / P32 / I0 | `IMPLEMENTED=0` |
| artifact closed-equivalent | 126/257 | 약 49.0%이나 전체 프로젝트 진척률이 아님 |
| artifact open | 131/257 | 실제 내용·결정·실행·사건이 남음 |
| formal test | PASS 0/279 | 279개 모두 `NOT_RUN` |
| actual device / real event | 0 / 0 | 내부 시험으로 대체 금지 |
| release gate | 0/5 | 전부 `NOT_RUN`, `waived=false` |
| production deployment | 0 | 배포·canary·rollback 증거 없음 |
| release | `NOT_ELIGIBLE` | 프로젝트 완료 아님 |

단일 “전체 퍼센트”는 공식 지표가 아니다. 질문을 받으면 최소한
Feature / Artifact / Internal verification / Formal / Device / Gate / Release를
분리해 보고한다. `126/257`만 전체 완성도라고 말하지 않는다.

### 2.3 2026-07-30 작업의 공식 delta

다음 값은 증가하지 않았다.

- 제품 경로 `apps/**`, `backend/**`의 이 세션 구현 delta: 0
- canonical Gap/Backlog, checkpoint, Goal event delta: 0
- artifact closed-equivalent delta: 0
- formal, actual-device, gate, release delta: 0

대신 exact68 재평가, FP-008 범위 설계, r022/v2.5 전환 설계와 prototype
검증이 진행됐다. 이는 재사용 가능한 실행 준비이지만 제품 완료 credit은 0이다.

## 3. 현재 blocker — 원인이 확인된 한 파일의 5줄

### 3.1 snapshot 사실

| 항목 | checkpoint expected | live |
|---|---|---|
| file count | 603 | 603 |
| path-set SHA-256 | `e445b7ccd8b76ef476248894e3d2f84eba5d2b3ba90e2be198b07c37e2d767b1` | 동일 |
| content-set SHA-256 | `69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a` | `51fa51b966219dc4d8c1ff8317e7209323726298365b4ec413b2520bdc90bf1b` |

drift는 다음 한 파일로 격리됐다.

```text
scripts/run_walksafe_test_layers_20260711.sh
expected: 4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d / 15,588 bytes
live:     4280dabbaa60ec098c8c52dc278f95b178ac5b2a6aa7dc2a1feae419f81f5b0f / 15,889 bytes / mode 775
```

두 sealed evidence copy와 live runner의 diff는 정확히 다음 테스트 등록 5줄이다.

```text
tests/test_walksafe_w3_engineering_evidence_20260726.py
tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
tests/test_walksafe_phase1_exact257_successor_r011_20260729.py
tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py
tests/test_walksafe_v2_5_control_candidate_20260730.py
```

이 5줄은 2026-07-30 작업 중 새 테스트를 runner에 연결하면서 추가됐다.
따라서 원인 불명 외부 drift가 아니다. 이 세션이 만든 변경을 뒤늦게
미확인 blocker처럼 다루면서 R004~R008 문서 loop가 길어졌다. 새 Codex는
원인 조사를 다시 하지 않는다.

현재 quick check는 fail-closed로 다음 exact 이유 때문에 실패하는 것이
정상이다.

```text
continuation rc=1
- v2.4 seq39 checkpoint projection differs
- v2.4 working snapshot content-set SHA-256 differs

Goal graph rc=1
- 위 continuation 오류 2개
- v2.4 seq39 queue projection
```

제품 회귀나 r022/v2.5 적용을 뜻하지 않는다. 현 상태에서 quick PASS를
주장하지 않는다.

### 3.2 두 처리 전략

| 전략 | 내용 | 비용·위험 | 권고 |
|---|---|---|---|
| `CHECKPOINT_PROJECTED_SOURCE_RESTORE` | runner에서 이 세션이 추가한 5줄만 역적용해 expected bytes로 복원 | 최소 변경. 테스트 파일은 삭제되지 않고 직접 실행 가능 | 권고 |
| `REVIEWED_ADD_ONLY_EXACT_SNAPSHOT_ACCEPTANCE` | live runner와 새 Git inventory를 새 snapshot successor로 수용 | 새 manifest/checker/review/승인 필요. 단순 hash 교체 금지 | 명확한 이유가 있을 때만 |

권고 전략 A는 evidence copy를 파일 전체로 덮는 것이 아니다. `apply_patch`로
위 5개 등록만 제거하고 mode 775와 나머지 사용자 변경을 보존한다. 복원 뒤
runner SHA-256이 expected와 exact 일치해야 한다.

이 인계서는 전략을 대신 선택하지 않는다. 다음 Codex는 source write 전에
사용자의 exact 전략 지시를 확인한다. R009는 outer-shell bootstrap finding이
남은 실패 이력이므로 실행하지 않는다. 이 종합 인계서의 단순 read-only
검사로 현재 사실을 확인한다. restore 뒤에는 v2.4 quick 두 개의 PASS가 새
기준이다.

## 4. 완료돼서 다시 하지 않을 일

### 4.1 전체 계획 재기준선

`plans/features/2026-07-29_walksafe_plan_rebaseline_r001.md`

- 상태: `PLAN_REVIEWED_NOT_ACTIVATED`
- 전체 hard dependency와 E1~E6 계획 수립 완료
- 당시 §11의 “exact68부터”는 아래 R002 완료 사실로 supersede

### 4.2 exact68와 r022 데이터 후보 R002

`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/`

- 68개 전수 재평가 완료
- changed 31 / byte-exact carry-forward 37 / status change 8
- 후보 상태: B5 / C14 / E4 / M6 / P39 / I0
- 다음 후보: `FP-008/GAP-017`
- targeted test: 15 PASS
- 독립검수: findings 0/0/0
- 상태: `STAGED_CANDIDATE_NOT_APPLIED`
- 적용 경로: `VERSIONED_SUCCESSOR_CONTROL_REQUIRED_FOR_OPERATIONAL_DELTA`

exact68을 다시 만들지 않는다. canonical은 여전히 r021이므로 후보 수치를
공식 수치로 바꾸어 말하지 않는다.

### 4.3 FP-008 다음 leaf 준비안

`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/FP008-NEXT-LEAF-PREPARATION-R001.md`

- target: `EPIC-03-FP008-ADMIN-REVIEW-DELIVERY`
- 정책/Gap: `FP-008/GAP-017`
- backend, registered-device, 신고 검수, audit/export, Android 관리자 앱,
  삭제·만료·동시성의 수용기준과 negative test 설계 완료
- 독립검수: findings 0/0/0
- 상태: 준비 전용, materialize/start/제품 권한 없음

81KB 준비안을 다시 쓰지 않는다. 실제 시작 시 구현 입력으로 읽는다.

### 4.4 최신 r022/v2.5 전환 설계

최신 설계는 다음 R007 하나다.

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
R022-CONTROL-MIGRATION-CANDIDATE-R007.md
```

- SHA-256:
  `2c0eb19da8b9a2df77cb43b2ffd169583591f0a23150d9ef5eff00cf39327610`
- 독립검수: `PASS_FOR_DESIGN_ONLY_SOURCE_DRIFT_BLOCKED`, findings 0/0/0
- P: v2.4 selector-preflight, seq39 → seq40, exact P17
- M: seq40/P receipt 기반 v2.5+r022 main transition, exact M15
- P와 M은 서로 다른 build/review/승인을 사용

R002~R006 설계는 FAIL history로만 보존한다. 새 구현 근거로 섞지 않는다.

### 4.5 local v2.5 prototype

다음 5개는 유용한 코드 조각과 negative fixture를 가진 prototype이지만
R007 P/M candidate가 아니다.

| 파일 | 현재 SHA-256 |
|---|---|
| `scripts/build_walksafe_v2_5_control_candidate_20260730.py` | `31e66d3ba43764e3d4cc5987813f39d5a70ad9796873690e37875191458d30f3` |
| `scripts/walksafe_v2_5_candidate_validation.py` | `423a60a3195313b11f65789c91ba772bb3bc3213c63bba4ec23c30f3e69434d5` |
| `scripts/check_walksafe_project_continuation_v2_5_candidate.py` | `1a2dc4f1d8c28c5163cae8306a160e8f942f962ea2ec190a625b64dc688ece5c` |
| `scripts/check_walksafe_goal_graph_v2_5_candidate.py` | `98d656e8c3c54e122a419eee9d2c3b3285f2594f4abd3abbb5597d8e9470feda` |
| `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `905eae38d7d24977be2b04fc1c7b6d83c432776c7129939f3d2af1de527cc31f` |

strict JSON, path confinement, deterministic build, add-only recovery, source CAS와
negative fixture 패턴만 R007 구현에 선별 재사용한다. 이 파일들을 그대로
publish/apply하지 않고, physical `v2-5-control-candidate-r001`을 임의 생성하지
않는다. 물리 candidate가 없으므로 해당 실물을 전제하는 테스트는 현재
통과 대상이 아니다.

### 4.6 최신 재개 문서

실행 재개 chain의 최신 이력은 R009지만 review 결과는
`BLOCKING/MAJOR/MINOR=0/1/0`이다. outer `BASH_ENV`가 이미 실행된 shell까지
checker 내부에서 신뢰하려 한 bootstrap 경계가 남았다. R004~R009와 review는
결함 추적 history로만 보존하고 source write나 candidate 실행 근거로 사용하지
않는다.

이번에는 R010을 만들지 않는다. 이 succession은 제품·artifact 진척을 만들지
않고 이미 과도한 문서 loop가 됐다. 새 터미널은 이 종합 인계서와 그
findings-zero review, live read-only hash/diff를 재개 근거로 사용한다.

## 5. 전체 제품 완성 로드맵

### S0. 재개와 source convergence

목표:

- live 사실과 이 종합 인계서 receipt 확인
- 사용자 source 전략 확정
- 선택한 전략을 최소 변경으로 수행
- v2.4 continuation/Goal quick PASS

검증:

- file count 603, path-set exact
- 전략 A면 runner SHA/bytes exact expected, mode 775
- continuation rc=0
- Goal graph rc=0
- 새 unexpected drift 0

종료조건:

- `SOURCE_DRIFT=0`
- 변경 전/후와 보존한 5개 테스트가 daylog·local-memory에 기록됨

### S1. P selector-preflight transaction

목표:

- R007 §8.2의 exact P17을 physical candidate로 구현
- v2.4 seq39에서 selector-ready seq40으로만 전환

순서:

```text
drift 0
→ 별도 P build 지시
→ deterministic P17 build/freeze
→ candidate-bound independent review findings 0
→ verified external A0/R0/L_P/B
→ fresh SELECTOR_PREFLIGHT_ONLY challenge/approval
→ P apply/post-check/receipt
→ seq40
```

검증:

- R007 §10의 P DAG와 fail-closed negative fixture
- source CAS와 add-only/no-replace/durability
- approval subject·nonce·response exact binding
- post-apply continuation/Goal checker PASS
- M 또는 제품 상태 조기변경 0

중단조건:

- external A0/R0/L_P/B를 검증할 수 없음
- review finding 존재
- source 또는 candidate CAS mismatch

### S2. M v2.5+r022 main transition

목표:

- exact seq40와 P receipt에서 R007 §9 M15를 구현
- reviewed R002 r022 pair와 v2.5 control을 원자적으로 활성화

순서:

```text
exact seq40 + P17 + P receipt
→ 별도 M build 지시
→ deterministic M15 build/freeze
→ candidate-bound independent review findings 0
→ verified external A0/R0/L_M/B
→ fresh V25_MAIN_TRANSITION_ONLY challenge/approval
→ atomic apply, checkpoint-last
→ post-check/receipt/recovery closure
```

검증:

- candidate-independent ACTIVE checker
- final member exact bytes와 dependency DAG
- crash/partial/committed recovery truth table
- v2.5 active, canonical r022, stale r021 포인터 제거
- artifact/formal/device/gate/release 조기 credit 0

P approval, nonce, claim과 receipt를 M에 재사용하지 않는다.

### S3. frontier 재계산과 단일 leaf 시작

목표:

- 활성 전환 뒤 ready frontier를 다시 계산
- FP-008이 여전히 결정적 다음 leaf인지 확인
- 정확히 하나의 Work Item만 materialize/start

순서:

```text
active v2.5+r022
→ frontier recompute
→ FP-008 여부 확인
→ 별도 successor design/review
→ fresh FP008 challenge/authorization
→ GOAL_MATERIALIZED
→ GOAL_READY
→ full start gate
→ GOAL_STARTED
```

R007 §14는 과거 “Approval2”를 폐기하고
`FP008_AUTHORIZATION=ABSENT_DENY_ALL`로 고정한다. 과거 runbook의 승인을
재사용하지 않는다. 전환 후 frontier가 달라지면 FP-008을 강행하지 않는다.

### S4. FP-008 내부 제품 slice

구현 입력은 §4.3 준비안이다. 최소 순서는 다음과 같다.

1. backend schema·migration·registered-device state
2. 관리자 auth/session/device enforcement
3. report list/detail/review/approve/reject/duplicate 처리
4. 결정적 export, audit, manual agency-delivery evidence 경계
5. API/OpenAPI와 Android 관리자 transport/controller/UI
6. deletion draining, expiry, lease, crash reconciliation
7. fail-first·targeted·component regression
8. implementation/verification evidence와 Gap·Backlog successor
9. 독립검수와 canonical transition

내부 구현이 끝나도 실제 관리자, 등록 실기기, 기관 receipt, formal 결과가
없으면 Gap 완료 상한은 `PARTIAL`이다.

### S5. EPIC-02·03 내부 coverage 완성

- EPIC-02: 보행 시작 조건과 실제 lifecycle 연계 잔여
- EPIC-03: 관리자 업무, privacy deletion 전 저장소 연계·부분실패 복구,
  암호화·키 분리·회전·감사, 외부 복구수단 provisioning

각 Work Item은 다음 공통 loop를 정확히 한 번 돈다.

```text
정책·Gap exact pair
→ fail-first acceptance
→ 최소 구현
→ targeted/component regression
→ implementation·verification evidence
→ Gap·Backlog successor
→ independent review
→ canonical transition
→ next frontier
```

### S6. EPIC-04·05·06 내부 구현

EPIC-02 완료 뒤 파일 충돌 없는 준비 작업을 병렬화한다.

- EPIC-04: 도착 확인, 이탈 중지·설명·선택·재탐색
- EPIC-05: 승인 모델 fence, 거리·품질 gate, 위험 메시지·진동
- EPIC-06: wake word·청취 cue·오프라인 음성, 접근 가능한 안전정지

canonical `IN_PROGRESS` leaf는 동시에 하나만 둔다.

### S7. EPIC-07·08·09·10과 EPIC-11

hard dependency:

```text
EPIC-01 → EPIC-02, EPIC-03
EPIC-02 → EPIC-04, EPIC-05, EPIC-06
EPIC-02 + EPIC-03 → EPIC-07
EPIC-02 + EPIC-03 + EPIC-07 → EPIC-08
EPIC-05 + EPIC-07 → EPIC-10
EPIC-03 + EPIC-07 + EPIC-08 → EPIC-09
EPIC-09 + EPIC-10 → EPIC-11
```

- EPIC-07: 승인 원본 schema·권리·보존·삭제 lifecycle
- EPIC-08: 암호화 영속 queue·고정 ID·receipt·재부팅 복구
- EPIC-10: 승인 dataset·평가·TFLite 동등성·model bundle
- EPIC-09: 용량·비용·배터리·발열·안전정지
- EPIC-11: 통합 후보·배포 전 종합 closure

후보 모델의 외부신고 사용 차단은 P0 안전 우선순위다.

### S8. 배포·운영 준비

- signed immutable candidate
- production config·secret·role separation
- canary·rollback·backup/restore·recovery
- 지원 기기·OS·TMAP·Gateway·Backend·DB·model manifest

절차 문서만으로 실제 배포·훈련 완료를 주장하지 않는다.

### S9. EPIC-12 정식 검증·출시·이관

EPIC-12 완료 dependency:

```text
EPIC-04, 05, 06, 08, 09, 10, 11 COMPLETE
```

그 뒤 같은 immutable candidate에 대해 다음을 수행한다.

- approved formal test plan
- formal 279
- 실제 기기·현장·TalkBack·장시간·보안·개인정보·AI 검증
- 5 release gate, `waived=false`
- TST-22·REL-02 권한 있는 결정
- production deploy·canary·smoke·rollback
- 운영 안정화·복구·비용·권한·데이터 처리
- 운영 이관 또는 승인된 종료

EPIC-12의 현재 `READY`는 준비 branch만 열렸다는 뜻이다. 조기 formal PASS나
release 권한이 아니다.

## 6. 257개 산출물 병렬 계획

open 131은 제품 DAG와 함께 다음 네 lane으로 진행한다.

| Lane | 수 | 할 일 | 종료 증거 |
|---|---:|---|---|
| A `INTERNAL_READY` | 62 | 내용·trace·내부 review | required content·적격 승인 |
| B fact/owner/attest | 24 | FACT 6 + OWNER 14 + ATTEST 4 | 실제 귀속 가능한 사실·결정·독립 확인 |
| C `INTERNAL_RUN_REQUIRED` | 24 | 환경 준비 뒤 실제 실행 | raw output·receipt·review |
| D `REAL_EVENT_PENDING` | 21 | 기기·배포·기관·인수 사건 | 정당하게 발생한 actual-event receipt |

합계는 항상 131이고 누락·중복을 허용하지 않는다.

- Lane A와 독립적인 B 준비는 병렬 가능하다.
- C는 관련 code/build/data/candidate와 권한·환경 준비 뒤 실행한다.
- D는 증거를 만들기 위해 사건을 조작하지 않는다.
- root/merge 한 명만 canonical 후보를 쓴다.
- artifact 상태를 올릴 때 exact257 replay와 독립검수를 수행한다.

현행 상세 계획:

```text
docs/control/execution/artifact-audits/20260727/final-257/
remaining-work-execution-plan-r004-plan-only-successor.md
```

현재는 R0 계획만 끝났고 R1~R5 실행은 시작되지 않았다.

## 7. 새 Codex가 실제로 재개하는 순서

### 7.1 빠른 orientation

먼저 다음만 읽는다.

1. repository `AGENTS.md` — §0.1의 stale v2.3 runtime pointer 경계를 함께 적용
2. 이 종합 인계서
3. 이 종합 인계서의 independent-review receipt
4. R009 independent-review receipt의 실패 판정과 §3 후속 경계
5. checkpoint와 v2.4 static manifest의 상태 필드
6. exact68 R002 review
7. FP-008 preparation review
8. R022 control design R007 review

R004~R009 execution block은 실행하지 않는다. source 전략 전에는 아래
단순 read-only snapshot/hash/diff와 현행 quick의 exact FAIL만 확인한다.

### 7.2 첫 30분

1. branch/HEAD와 정본 네 파일 pin 확인
2. 종합 인계 target과 findings-zero receipt 확인
3. snapshot hashes와 현재 exact quick FAIL 재현
4. sealed copy와 runner diff가 5줄뿐인지 재확인
5. 사용자에게 전략 A/B, exact before/after와 권고 A를 한 번에 보고

새 사실이 같으면 더 조사하거나 새 계획 문서를 만들지 않는다.

### 7.3 사용자 전략 지시 뒤

전략 A를 지시받으면:

1. runner의 다섯 등록만 `apply_patch`로 제거
2. runner SHA/bytes/mode와 603-file snapshot 재검증
3. v2.4 continuation·Goal quick PASS
4. 다섯 테스트는 삭제하지 않고 직접 targeted 실행
5. daylog와 local-memory 기록
6. P candidate build의 exact 입력·출력·검증 범위를 보고

전략 B를 지시받으면:

1. live runner와 Git inventory의 exact manifest 작성
2. overwrite 없이 add-only successor 생성
3. independent review findings 0
4. 과거 checkpoint hash만 바꾸지 않고 별도 승인·전이
5. 새 validator convergence 뒤 P 준비

### 7.4 검증 명령

현재 blocked state 확인:

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_4.py \
  --root . \
  --checkpoint docs/control/walksafe-project-continuation-checkpoint.json \
  --print-working-snapshot-hashes
```

source convergence 뒤:

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_4.py \
  --root . \
  --checkpoint docs/control/walksafe-project-continuation-checkpoint.json

python3 -B scripts/check_walksafe_goal_graph_v2_4.py \
  --root . \
  --checkpoint docs/control/walksafe-project-continuation-checkpoint.json
```

r022와 prototype의 candidate-independent subset:

```bash
PYTHONPATH=. .venv/bin/python -B -m pytest -p no:cacheprovider -q \
  tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py \
  tests/test_walksafe_v2_5_control_candidate_20260730.py \
  -k 'not physical_bundle_and_both_shared_core_wrappers_pass and not physical_candidate_is_seq1_only_and_non_effective and not check_is_read_only_and_active_canonical_targets_are_absent'
```

physical candidate가 없는 동안 제외한 세 테스트를 PASS로 보고하지 않는다.
candidate 생성 뒤 동일 physical target에 결속해 다시 포함한다. 현재 관찰
결과는 `42 passed, 3 deselected`다.

## 8. 승인·중단 경계

| 행동 | 필요한 권한 |
|---|---|
| 읽기, hash, 현재 FAIL 재현, diff, test collect | 추가 승인 불필요 |
| source 전략 A/B write | 사용자의 exact 전략 지시 |
| P candidate build | drift 0 뒤 별도 명시적 build 지시 |
| P apply | findings-zero candidate에 대한 fresh `SELECTOR_PREFLIGHT_ONLY` 승인 |
| M candidate build | P closure 뒤 별도 명시적 build 지시 |
| M apply | findings-zero candidate에 대한 fresh `V25_MAIN_TRANSITION_ONLY` 승인 |
| FP-008 materialize/start | 전환 후 별도 successor·review·fresh challenge/승인 |
| internal product work | `GOAL_STARTED` 뒤 승인 범위 내 |
| formal/device/participant/external/prod/secret/paid | 해당 권한·환경·사건에 대한 별도 승인 |
| gate/release/인수/이관/종료 | 권한 있는 실제 결정 |

한 branch가 외부 조건으로 막혀도 독립 artifact Lane A/B와 read-only 준비를
계속한다. synthetic approval, key, ACL, journal, receipt 또는 actual event를
repository 안에서 만들어 대체하지 않는다.

## 9. 작업 운영 규칙 — 같은 9시간을 반복하지 않기

1. 매 작업 시작 시 “제품/증거의 어떤 공식 축을 움직이는가”를 한 줄로 적는다.
2. 2시간 단위 보고에는 반드시 다음 중 하나가 있어야 한다.
   - 제품 코드와 fail-first test의 실제 delta
   - artifact 상태와 결속 evidence의 실제 delta
   - canonical transition의 실제 delta
   - exact blocker와 사용자에게 필요한 단일 결정
3. 문서 successor는 concrete finding을 닫을 때만 만든다.
4. 같은 finding에 두 번 실패하면 세 번째부터는 구현 전에 독립 adversarial
   review를 먼저 한다.
5. 문서·prototype line count를 제품 진척으로 보고하지 않는다.
6. 공식 delta가 0이면 0이라고 먼저 말하고 준비 성과를 별도 표기한다.
7. canonical writer는 한 명, 병렬 agent는 조사·test·review 중심으로 둔다.
8. 실제 제품 leaf를 시작한 뒤에는 fail-first → 최소 구현 → 검증 → evidence
   loop를 끝내기 전 새 계획 문서를 만들지 않는다.

진행 보고 형식:

```text
공식: control / canonical / active leaf / product delta / artifact delta /
      formal / device / gate / release
준비: 완료한 조사·설계·prototype과 재사용 범위
검증: 명령 / rc / pass-fail / 제외한 항목
다음: 단일 행동 / 예상 시간 / 필요한 권한
```

## 10. 대략적 시간 범위

외부 승인 대기와 실제 기기·기관 일정을 제외한 거친 추정이다.

| 구간 | 예상 |
|---|---:|
| S0 전략 A 복원·quick·기록 | 20~45분 |
| S0 전략 B 새 snapshot 수용 | 반나절~1일 |
| S1 P candidate 구현·검수 준비 | 3~6시간 이상 |
| S2 M candidate 구현·검수 준비 | 6~12시간 이상 |
| S3 frontier·leaf materialization | 1~2시간 |
| S4 FP-008 내부 구현 slice | 1~3일 |
| S5~S8 나머지 내부 제품·산출물·통합 | 여러 작업일~수주 |
| S9 formal/device/gate/release | 장비·사람·외부 권한 일정에 종속 |

R007의 외부 A0/R0/L/B 검증이나 fresh approval이 준비되지 않으면 S1/S2 시간은
늘어난다. 예상 시간은 완료 주장이나 deadline이 아니다.

## 11. 다음 터미널에 전달할 문장

첫 재개 시 다음처럼 지시한다.

```text
WALKSAFE-PROJECT-MASTER-HANDOFF-20260730-R001.md와 독립검수를 읽고
현행 상태를 확인해. R004~R009 execution block은 실패 이력이므로 실행하지
마. 아직 write하지 말고 source drift가 정확히 runner의 등록 5줄뿐인지
확인한 뒤 전략 A/B의 exact before/after를 짧게 보고해.
```

권고 전략 A로 진행하기로 결정했다면 다음 문장을 별도로 준다.

```text
CHECKPOINT_PROJECTED_SOURCE_RESTORE로 진행해. runner의 확인된 테스트 등록
5줄만 최소 역적용하고 나머지 dirty worktree는 보존해. v2.4 continuation과
Goal quick PASS, targeted test, daylog·local-memory 기록까지 수행한 뒤
P candidate build의 정확한 범위와 다음 승인 지점만 보고해.
```

그 보고가 정확하면 P build는 별도 지시한다.

```text
R007 findings-zero 설계에 따라 P selector-preflight candidate build와
candidate-bound 독립검수까지 진행해. apply·승인 요청은 아직 하지 마.
```

## 12. 이 인계서의 완료조건

이 문서는 다음을 만족할 때 새 터미널의 기준으로 사용할 수 있다.

- target SHA-256/bytes가 independent-review receipt와 일치
- review findings BLOCKING/MAJOR/MINOR = 0/0/0
- R009 review의 `0/1/0` 실패를 정확히 표시하고 실행 근거로 사용하지 않음
- branch/HEAD와 정본 네 파일 pin 일치
- source drift가 §3과 동일하거나, 이후 successor receipt가 정확히 설명
- 이 문서가 제품·artifact·formal·device·gate·release credit를 만들지 않음

하나라도 다르면 write를 시작하지 말고 달라진 사실만 먼저 보고한다.
