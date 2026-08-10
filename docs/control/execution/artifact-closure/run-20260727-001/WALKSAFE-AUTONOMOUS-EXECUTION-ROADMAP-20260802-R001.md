# WalkSafe 자율 실행 로드맵 20260802 R001

## 0. 지위, 효력과 권한 경계

```text
document_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R001
document_class = INTERNAL_EXECUTION_ROADMAP
review_status = PENDING_TWO_INDEPENDENT_REVIEWS
official_progress_delta_at_issue = 0
canonical_mutation_at_issue = 0
release_claim = NOT_ELIGIBLE
```

이 문서는 2026-08-02 사용자가 요청한 “전체 로드맵을 완성하고 독립 검수한
뒤, 추가 확인 없이 저장소 내부 구현을 계속하라”는 작업 범위를 현재 정본에
연결한다. 아래 두 adjacent 검수가 동일한 이 파일의 SHA-256을 대상으로 각각
`BLOCKING/MAJOR/MINOR=0/0/0`을 판정해야 첫 작업 묶음 `WP-001`을 실행한다.

- formal review:
  `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R001-independent-review-r001.md`
- skeptical review:
  `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R001-independent-skeptical-review-r001.md`

사용자 지시는 저장소 안의 계획·코드·테스트·내부 증거·add-only 통제 후보를
설계, 구현, 검증하고 다음 내부 ready 작업을 계속하는 권한이다. 다음은 여전히
별도 실제 권한·환경·사건이 필요하며 이 문서나 내부 에이전트가 대신하지 않는다.

- 규범 정책, 보존기간, 동의 또는 안전 기준 변경
- 비밀값·유료자원·프로덕션 배포·외부 통지·실제 데이터 삭제
- 실제 기기·참여자·기관·독립 전문가 행위
- formal PASS, Gate 폐쇄·면제, 출시·인수·이관·종료 서명

내부 검증은 `INTERNAL_VERIFIED`까지만 주장한다. 제품 파일은 유효한 단일
leaf와 fresh full implementation gate가 성립하기 전에는 수정하지 않는다.

## 1. 정본과 시작 상태

| 항목 | 시작값 |
|---|---|
| repository | `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715` |
| branch / base HEAD | `codex/walksafe-rc2-hardening-20260715` / `a3ad7eead6b5d834d3e0675422475a9aad351e3d` |
| policy | `PB-WALKSAFE-FEATURE-POLICY-1.0.1`, COMMITTED |
| control | v2.4 `ACTIVE`, tail seq39 `c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a` |
| checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` |
| Gap / Backlog | r021 `f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a` / r021 `bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0` |
| Goal | focus `EPIC-03`, ready `EPIC-03`·`EPIC-12`, active leaf 없음 |
| Gap counts | blocked 5, conflicting 16, evidence-missing 4, missing 11, partial 32, implemented 0 |
| artifact closure 축 | closed-equivalent 126, open 131, global completion credit 0 |
| artifact scheduler 축 | terminal 104, inactive 1, waiting-applicability 87, waiting-trigger 65, live/due 0 |
| formal / actual / Gate | `0/279` / device·event `0/0` / `0/5`, waived false |
| release / project | `NOT_ELIGIBLE` / `NOT_COMPLETE` |

시작 시 v2.4 continuation과 Goal graph quick check는 모두 PASS했다. dirty
worktree는 tracked 125개, untracked 2,550개이며 다음 외부 복구본으로 고정했다.

```text
/home/ddobagi/.codex/backups/walksafe/20260802T0125KST-rc2-pre-roadmap
tracked patch = 69d2d378de56fd26a142f8220f2e527ff71e6126734664555f90a71a4f3ad558
history bundle = 56a95dd2b43612d27b5140dddb2857830cd2ce82571cad3748f7886dade10751
untracked archive = 2de0201890d73a7067d2c5c879a69ffd31bcba8bfcc7ffbf21d0fdae1a7fe398
untracked entries = 2550
```

`git bundle verify`, `git apply --check --cached`, `gzip -t`가 PASS했다.

## 2. 이전 계획 계보의 처리

다음 파일들은 삭제하거나 고치지 않고 `NON_EFFECTIVE_HISTORY`로만 읽는다.

- PRE-P R001~R007: 최종 R007 formal·skeptical 각각 `18/4/0`
- 상세 roadmap R001~R006: 최종 R006 formal `6/0/0`, skeptical `18/4/0`
- R007 correction plan R001·R002: 모든 검수 FAIL
- R007 correction plan R003: 7,143행 `PRE_REVIEW`, 검수 없음, 자체 문서명·수용문구 불일치

이 계보는 authority/CAS/outbox/schema를 계속 추가하면서 검증 표면만 늘렸고,
실제 source·product·formal·Gate delta를 만들지 못했다. 이 로드맵은 그 설계를
부분 재사용하거나 또 하나의 거대 authority FSM으로 고치지 않는다. 대신
현재 bytes에서 재현되는 네 회귀를 작은 실행 후보와 테스트로 닫고, 각 실제
출력만 독립 검수한다.

최신 기술 인계서의 `FP-048` stale 경고는 보존한다. r021 scheduler가
`FP-048/GAP-057`을 가리키더라도 seq39 checker와 새 canonical transition이
정렬되기 전에는 이를 materialize/start하지 않는다. 미래 leaf는 전환 후 live
frontier에서 다시 계산한다.

## 3. 공통 실행 루프

모든 내부 작업은 다음 한 루프를 따른다.

```text
current bytes와 정본 pin
→ fail-first 재현
→ 하나의 작은 후보 구현
→ targeted·component 회귀
→ 독립 formal·skeptical review
→ 허용된 경우에만 apply
→ post-check와 실제 출력 hash 기록
→ checkpoint/daylog/local-memory 종료 barrier
→ live frontier 재계산
```

규칙은 다음과 같다.

1. canonical writer는 root 한 명뿐이다. 병렬 팀은 조사, 비충돌 구현, 검증,
   독립 review를 맡는다.
2. `IN_PROGRESS` leaf는 동시에 하나만 둔다. 단, 아직 canonical leaf가 없는
   `WP-001`은 제품 기능이 아닌 add-only 검증 후보다.
3. 실패한 historical 파일·receipt를 현재 bytes에 맞춰 덮어쓰지 않는다.
4. 고정 count 대신 실제 registry·file set에서 count를 재계산한다.
5. plan, template, synthetic receipt와 내부 단위시험은 formal·device·Gate
   또는 외부 승인 증거가 아니다.
6. 한 branch가 외부 조건에 막히면 다른 internal-ready branch를 계속한다.
7. 예기치 않은 정책 충돌, 정본 hash 손상, 사용자 변경과 같은 파일 충돌,
   보안·안전 critical failure가 생기면 해당 write를 중단한다.

## 4. 단계별 로드맵

### S0. 복구·정본 수렴 — 완료

- [완료] 저장소·branch·HEAD·dirty 상태 확인
- [완료] v2.4 checkpoint, r021, exact257, 기술·마스터 인계 대조
- [완료] v2.4 Quick2 PASS
- [완료] dirty 전체의 복구 가능한 외부 backup 생성·검증

검증: 위 §1 hash, Quick2 exit 0, backup entry 2,550와 bundle/patch/archive 검사.

### S1. 실행 로드맵 동결·독립 검수 — 현재 단계

1. 이 R001을 한 번 freeze한다.
2. 서로 다른 두 agent가 같은 SHA-256을 formal·skeptical 관점으로 검수한다.
3. 어느 한쪽이라도 blocking/major가 있으면 이 파일을 고치지 않고 R002를
   add-only로 만든다.
4. 같은-SHA 두 검수가 모두 `0/0/0`일 때 `WP-001`을 시작한다.

검증: target SHA·bytes·lines 동일, review 두 개, 대상 hash 동일, findings 합계
각각 `0/0/0`.

### S2. WP-001 — 검증 수렴 add-only 후보

목표는 2026-07-31 bounded regression의 네 epoch 차이를 실제 실행 가능한
검증 계약으로 닫는 것이다. 이 단계는 source/test infrastructure candidate를
만들지만 checkpoint, canonical, product와 artifact credit은 바꾸지 않는다.

#### WP-001-A. Python lock epoch

- 원인: backend와 hosted CPU lock은 Pillow `12.3.0`, local
  `tests/requirements.lock`만 `12.2.0`이다.
- 후보: 같은 pinned Python/pip-tools 입력으로 local lock successor를 두 번
  생성하고 byte-identical 여부를 확인한다. 과거 W5 receipt와 sealed bytes는
  바꾸지 않는다.
- 수용: production/test/CPU lock subset 계약 PASS, 깨끗한 외부 venv에서
  `--require-hashes` 설치 PASS, `pip check` PASS, Pillow `12.3.0`.

#### WP-001-B. runner epoch

- 원인: live runner의 pytest 호출은 4개인데 stale test는 3개를 기대하고,
  발견 132개 중 5개가 layer에 없다.
- 후보: 날짜가 붙은 successor runner에 정확한 current/historical 분류를 둔다.
  seq39 control은 active-session, exact257-r011·r022·v2.5·W3는 명시적
  historical/targeted-only로 분리한다. 기존 runner bytes는 history로 보존한다.
- 수용: discovered=assigned, missing·duplicate·unassigned=0, invocation 계약
  exact 4, successor `validate` exit 0, preflight 대상 전체 PASS.

#### WP-001-C. Gateway epoch

- 원인: historical public exact4와 current `/api/field-walk` 포함 exact5를
  같은 시점으로 비교했다.
- 후보: 기존 current exact5 구현은 바꾸지 않고 add-only mutation regression을
  추가해 historical exact4와 current exact5를 별도 검증한다.
- 수용: current typecheck/test/build PASS, historical exact4 PASS, current
  exact5 PASS, missing·extra·method drift negative fixture 전부 reject.

#### WP-001-D. artifact baseline epoch

- 원인: 2026-07-22 receipt가 고정한 README
  `b3dde3a3...`/2,630 bytes와 live register가 결속한 current README
  `ed331dd2...`/3,108 bytes를 같은 시점으로 비교했다.
- 후보: historical event-time binding과 current navigation/self-seal을 분리하는
  add-only dual-epoch checker와 test를 만든다. 기존 receipt, materializer와
  historical test를 고치지 않는다.
- 수용: historical helper와 current helper가 각각 PASS하고 둘을 교환하거나
  한 축만 맞추거나 symlink/missing/extra-byte이면 reject.

통합 순서는 `A → B → C/D 병렬 → 통합회귀`다. 각 후보는 원인 하나만 닫고
승인 정책, canonical pointer와 제품 behavior는 바꾸지 않는다.

검증:

- 기존 7개 실패의 원인별 fail-first 재현
- candidate 자체 test 전체 PASS
- 기존 v2.4 Quick2 계속 PASS
- legacy historical test는 예상 경계대로 보존
- 검증 대상 전체에서 미분류 failure 0

### S3. WP-002 — 검증 후보 review와 versioned control 전환

1. WP-001 결과 파일·환경·원출력 hash를 freeze한다.
2. 구현자와 다른 두 agent가 정확성·fail-closed·역사보존 관점으로 검수한다.
3. findings-zero candidate에만 versioned continuation/Goal/full-gate 계약을
   준비한다. seq39 prefix와 기존 v2.4 checker/test는 history로 보존한다.
4. 새 계약은 successor runner와 dual-epoch checker를 포함하고, append-only
   다음 event를 replay할 수 있어야 한다.
5. crash 전/후, partial write, stale CAS, retry의 negative test를 통과한 뒤
   한 번의 fenced apply와 checkpoint-last 순서로 전환한다.

수용:

- historical v2.4 seq39 replay PASS
- successor quick/full 계약 PASS
- candidate 두 독립 review `0/0/0`
- source/candidate CAS mismatch 시 write 0
- partial/crash fixture 뒤 old 또는 new complete state만 존재
- artifact/formal/device/Gate/release delta 0

이 단계에서 구체 candidate bytes가 나오기 전 미래 SHA, nonce 또는 receipt를
본문에 자기참조로 넣지 않는다.

### S4. WP-003 — exact68·frontier 재기준선

1. 68개 `source_policy_id → gap_id` mapping을 current bytes에서 재평가한다.
2. Gap/Backlog는 atomic add-only pair로 만들고 독립 검수한다.
3. 새 control successor에서 ready frontier를 재계산한다.
4. 오직 계산 결과가 가리키는 하나의 policy/Gap leaf만
   `GOAL_MATERIALIZED → GOAL_READY`로 만든다.
5. fresh full implementation gate와 event-scoped repository snapshot이 PASS한
   뒤 `GOAL_STARTED`를 기록한다.

FP-008과 FP-048은 현재 후보일 뿐 선결론이 아니다. 재계산 결과가 FP-008이면
관리자 신고 검수·기관 전달 흐름을, FP-048이면 서버 신고 원본 at-rest 암호화를
첫 fail-first 제품 slice로 사용한다.

수용: mapping 68 unique, 누락·중복 0, Gap/Backlog pointer 일치, 단일 active
leaf, full gate 19/19, 정책 변경 0.

### S5. WP-004+ — 내부 제품 leaf 반복

각 leaf는 다음 완료 조건을 모두 충족한다.

- 정책·요구·설계·시험·artifact trace가 exact pair에 연결됨
- 변경 전 acceptance failure가 재현됨
- 범위 밖 제품 변경 없이 최소 구현됨
- targeted, component, 관련 전체 회귀 PASS
- 실제 파일·hash·명령·exit·output hash를 구현·검증 record에 기록
- 독립 review blocking/major 0
- Gap·Backlog successor와 canonical event 정합
- formal/device/Gate/release credit 0 유지

큰 의존 흐름은 다음과 같다.

```text
EPIC-02 + EPIC-03
→ EPIC-04·05·06
→ EPIC-07
→ EPIC-08·10
→ EPIC-09
→ EPIC-11 integration candidate
→ EPIC-12 formal/device/Gates/release decision
```

우선순위는 설명용이며 실제 선택은 매 leaf 종료 후 dependency DAG와 canonical
next action으로 다시 계산한다.

### S6. 257개 artifact 병렬 lane

closure 축의 open 131은 다음과 같이 유지한다.

| lane | 수 | 종료에 필요한 것 |
|---|---:|---|
| INTERNAL_READY | 62 | required content, direct trace, 적격 내부 review·acceptance |
| INTERNAL_RUN_REQUIRED | 24 | exact candidate·환경·raw I/O·exit·receipt·독립 review |
| EVIDENCE_FACT_PENDING | 6 | 날짜·출처·소유자가 분명한 실제 사실 |
| OWNER_APPROVAL_PENDING | 14 | 권한 있는 owner의 exact-subject 결정 |
| ATTESTATION_REVIEW_PENDING | 4 | 실제 독립 reviewer의 attestation |
| REAL_EVENT_PENDING | 21 | 실제 device·field·deploy·기관·인수 사건 |

일반 ready frontier를 소진하기 전 artifact queue를 선점하지 않는다. 현재 즉시
due/live artifact Goal은 0이다. 그 뒤 첫 평가 대상은 `DLV-DES-21`
`WAITING_TRIGGER`이며, 하나의 `ARTIFACT_WORK` materialize 또는 명시적 terminal
disposition 중 하나로 처리한다.

artifact 수는 exact257 add-only replay와 per-ID acceptance가 PASS할 때만 바꾼다.
packet 작성, 단위시험, 일정·담당자 기입만으로 open 수를 줄이지 않는다.

### S7. immutable candidate와 내부 전체 회귀

- 모든 필수 internal leaf와 artifact 준비가 닫힌 동일 source/config/model에서
  candidate manifest를 만든다.
- locked Node·Python, Gateway/Web history boundary, Android unit/assemble/lint,
  Backend/model/control suite를 실행한다.
- failure·skip·deselect는 이유와 소유자를 기록하고 조용히 PASS로 바꾸지 않는다.
- candidate freeze 뒤 code/config/model 변경이 생기면 새 candidate다.

수용: 미분류 test 0, unresolved P0/P1 internal defect 0, raw output·환경·candidate
hash 결속, 내부 PASS와 formal PASS 용어 분리.

### S8. formal·실기기·5 Gate·출시·이관

동일 immutable candidate와 사전 승인된 계획에 대해 실제 권한자가 수행한다.

1. formal 279와 허용된 N/A 결정
2. 실제 기기·현장·TalkBack·장시간·보안·개인정보·AI 검증
3. 5 Gate 각각 raw evidence와 `waived=false` closure
4. TST-22·REL-02 출시 적격 결정
5. deploy·canary·smoke·rollback
6. 운영 안정화·복원·비용·권한·데이터 처리
7. 운영 이관 또는 승인된 종료

실제 증거 전에는 `formal 0/279`, `device/event 0/0`, `Gate 0/5`,
`NOT_ELIGIBLE`, `NOT_COMPLETE`를 유지한다.

## 5. 세션·장애 복구 barrier

각 apply 또는 leaf 종료 때 다음을 한 번에 남긴다.

1. branch, base/current HEAD, 변경 경로 집합과 content hash
2. 실행 명령, exit code, pass/fail/skip, raw output hash
3. current focus, ready·blocked 집합, 다음 단일 행동
4. 아직 주장하지 않는 formal/device/Gate/release 경계
5. `daylog/YYYY-MM-DD.md`
6. local-memory log와 sync 결과; writer lock이면 강제 해제하지 않고 daylog를
   authoritative local handoff로 남김
7. v2.4 또는 활성 successor quick check

세션이 중단되면 새 구현을 시작하지 않고 마지막 유효 checkpoint/event와 실제
working bytes를 대조한다. 부분 후보는 resume 또는 폐기 판정을 증거로 남기고,
history 파일을 current 값으로 덮어 맞추지 않는다.

## 6. 다음 단일 행동

이 R001을 freeze하고 formal·skeptical 두 독립검수를 실행한다. 두 검수가 같은
SHA에서 각각 `0/0/0`이면 `WP-001-A`의 fail-first 재현과 deterministic local
lock successor 생성부터 구현을 시작한다.

로드맵 자체는 제품·artifact·formal·device·Gate·release 상태를 바꾸지 않는다.
