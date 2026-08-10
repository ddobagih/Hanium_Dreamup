# PRE-P Validation Convergence Design/Build Plan R005 독립 공격검수 R001

## 1. 검수 대상과 판정

| 항목 | exact 값 |
|---|---|
| review_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R005-INDEPENDENT-SKEPTICAL-REVIEW-R001` |
| 검수일 | `2026-07-31` |
| target | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R005.md` |
| target SHA-256 | `675645378e7f87d002cfba2e109bc72712c6998dd620a53de42864f8c7f3a25d` |
| target bytes | `39,839` |
| target lines | `997` |
| target type | `regular file, non-symlink, nlink=1` |
| target status | `NON_EFFECTIVE_PLAN_ONLY` |
| verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY` |
| findings | `BLOCKING=7 / MAJOR=2 / MINOR=0` |
| review authority | `NONE` |

검수 시작과 종료 시 target의 SHA-256, bytes와 lines가 위 값과 같음을
확인했다. 이 review는 R005를 수정하지 않았으며 R005 formal independent
review를 대체하지 않는다. candidate/external environment build, authority
journal 생성, authority resolve, active exact26 apply, checkpoint 전환, R007
successor 또는 P 작업 권한을 만들지 않는다.

```text
VERDICT=REJECTED_NON_EFFECTIVE_PLAN_ONLY
BLOCKING=7
MAJOR=2
MINOR=0
TARGET_UNCHANGED=true
R005_REMAINS_NON_EFFECTIVE_PLAN_ONLY=true
PLAN_EXECUTION_AUTHORIZED=false
STAGE_A_REQUEST_ALLOWED=false
STAGE_A_AUTHORIZED=false
STAGE_B_AUTHORIZED=false
STAGE_C_AUTHORIZED=false
AUTHORITY_JOURNAL_WRITE_AUTHORIZED=false
ACTIVE_WRITE_AUTHORIZED=false
CHECKPOINT_WRITE_AUTHORIZED=false
R007_SUCCESSOR_BUILD_AUTHORIZED=false
P17_BUILD_AUTHORIZED=false
P_APPLY_AUTHORIZED=false
```

## 2. 검수 범위와 독립 재계산

Target 전체 997행과 current source/checkpoint를 read-only로 다음 축에서
대조했다.

- R004 formal/skeptical findings의 실제 폐쇄 여부
- R005 단독 normative 선언과 실행 계약의 self-contained completeness
- attempt/review/receipt namespace와 retry collision
- append-only global/transaction ledger의 concurrent writer exclusivity
- stage별 receipt와 global record kind의 canonical schema/FSM
- pending C와 B→C atomic handoff의 current validity
- ordinary C와 recovery executor 사이의 fencing
- partial commit, reconcile, file/parent durability와 application receipt scope
- C0 managed-set 공식, S/C/A/K 실제 membership과 V1 review binding
- current/future runner inventory arithmetic
- exact26 target count/mode/duplicate와 official claim ceiling

§16 target table은 다음과 같이 정확했다.

```text
ACTIVE_TARGET_COUNT=26
CAS_REPLACE=2
NOREPLACE=23
CHECKPOINT_CAS_LAST=1
DUPLICATE_PATH_COUNT=0
APPLICATION_RECEIPT_EXCLUDED_FROM_EXACT26=true
```

Current runner와 R005 successor arithmetic도 각각:

```text
CURRENT_ASSIGNED=31+23+7+3+45+18=127
CURRENT_DISCOVERED=132
CURRENT_ORPHAN=5
SUCCESSOR_ASSIGNED=28+23+7+3+53+16+2=132
SUCCESSOR_UNASSIGNED=0
RUNNER_ALL_DISCOVERED=74
RUNNER_ALL_DIRECT=2
RUNNER_ALL_EXECUTION=76
EXCLUDED_DISCOVERED=58
```

로 산술상 일치했다. 아래 finding은 이 수 자체가 아니라 R005가 후보를 만들고,
authority를 직렬화하며, exact26을 durable하게 적용하는 계약의 모순이다.

## 3. BLOCKING findings

### PRE-P-R005-BLOCKING-001 — 단독 NORMATIVE PLAN이 실행 계약을 포함하지 않음

#### 근거

Target §1 24~41행은 R005만 future execution의 normative plan이고 R004 이전
문서는 merge/import하지 않으며, R005에 없는 operation/path/schema를
금지한다고 선언한다.

그러나 §4 155~183행의 Stage-A/B subject tree는 다음과 같은 role directory만
열거한다.

```text
lane-a/{build-01,build-02,candidate,raw}/
lane-c/{candidate,raw}/
control-core/{candidate,raw}/
lane-d-core/{candidate,raw}/
lane-b-final/{candidate,raw}/
lane-d-final/{candidate,raw}/
after-control/{build-01,build-02,candidate,templates,raw}/
runtime-pack/{local-combined,hosted-cpu}/{build-01,build-02,candidate}/
apply/{builders,templates,raw}/
```

다음 execution-critical contract가 R005 안에 없다.

- `tests/requirements.lock` build-01/02 builder와 input/index/cache/hash policy
- preflight successor와 Android gateway exact5의 exact builder/output/test schema
- v2.4.1 continuation/Goal/event core의 exact source/output/template paths
- historical/current/dual baseline validator의 exact outputs와 semantics
- single runner와 full19 candidate를 만드는 exact builder/source mapping
- seq40 event, checkpoint, supersession, anchor, target-set와 envelope template
- candidate member에서 §16 active target으로 가는 exact source→destination map
- synthetic runtime-pack의 transitive executable/library/config closure
- bwrap 또는 동등 sandbox의 exact argv, bind roots, clearenv와 host deny set

§13~§15는 regression/full19 결과 형식을 다시 적지만 위 candidate bytes와
isolation environment를 어떻게 생성하는지는 정의하지 않는다. §14
754~777행은 각 environment가 `own synthetic runtime pack`을 쓴다고만 하므로
unmanifested host 접근 0을 실제로 보장할 실행 계약이 없다.

R004를 참조해 보충하면 §1의 non-normative/no-import 선언을 위반하고, 보충하지
않으면 미기재 path/schema/operation 금지에 걸려 Stage A output을 만들 수 없다.

#### Required remediation

- successor plan에 모든 builder, template, candidate output, manifest/review/raw
  path와 exact source→destination mapping을 완전히 열거한다.
- 각 lane의 input identities, content-generation rule, build-01/02 equality,
  positive/negative acceptance와 downstream manifest bindings를 적는다.
- runtime-pack은 executable/shebang/stdlib/ELF loader/shared-library/config
  transitive closure와 exact sandbox argv/bind/clearenv/deny trace를 포함한다.
- 또는 필요한 계약을 immutable normative appendix로 분리해
  path/SHA-256/bytes/lines와 precedence를 R005 successor에 직접 결속한다.
  History-only R004를 암묵적으로 재사용해서는 안 된다.

### PRE-P-R005-BLOCKING-002 — GLOBAL/TRANSACTION SEQUENCE PATH가 동시 writer를 배타하지 못함

#### 근거

Target §5.1 215행과 237~253행은 global path를:

```text
records/<12digit>-<RECORD_KIND>.json
```

으로 정하고, current next sequence의 exact path에 NOREPLACE 경쟁하면 한
writer만 성공한다고 주장한다. 그러나 filename에 record kind가 들어가므로 같은
next sequence를 선택한 서로 다른 kind writer는 같은 path에서 경쟁하지 않는다.

예를 들어 다음 두 path는 동시에 존재할 수 있다.

```text
records/000000000123-ISSUED.json
records/000000000123-LEASE_RENEWED.json
```

둘 다 개별 NOREPLACE에 성공한 뒤 replay가 duplicate sequence/fork를 발견해도
immutable file을 제거할 수 없어 journal이 영구 invalid 상태가 된다.

§8.1 404~418행의 transaction path도:

```text
records/<12digit>-PREWRITE.json
records/<12digit>-TARGET_CAS_COMMITTED.json
records/<12digit>-FILE_FSYNCED.json
...
```

처럼 progress kind가 filename에 있어 ordinary/recovery writer가 다른 kind로
같은 numeric sequence를 동시에 publish할 수 있다.

#### Required remediation

- global과 transaction record 모두 경쟁 path를
  `records/<12digit>.json` exact1로 만들고 kind는 signed body에만 둔다.
- 또는 sequence별 공통 NOREPLACE claim inode/directory를 먼저 publish하고
  winner만 kind-specific body를 쓰는 two-phase protocol을 정의한다.
- append 전후 contiguous replay, file+parent fsync와 loser의 no-side-effect
  규칙을 같은 atomic allocation protocol에 결속한다.
- different-kind same-sequence, same-kind different-payload, crash-after-claim,
  crash-after-body와 recovery/renewal 동시 append를 fault-inject해 exact one
  winner만 남는지 검증한다.

### PRE-P-R005-BLOCKING-003 — PENDING-C와 EARLY FAILURE INCIDENT CLOSE가 FSM에 의해 거부됨

#### 근거

Target §5.3 300~307행은 full replay가 `close before prepare`를 거부한다고
정한다. 그러나 §7 392~395행은 handoff 전에 C receipt가 만료되면 C pending
attempt를 incident-close한 뒤 fresh C attempt를 발급하라고 한다.

Pending C는 아직 ineffective이고 consume/lease/prepare를 거치지 않았다. 따라서
필요한 `CLOSED_INCIDENT`를 쓰면 generic replay rule이 이를 invalid로 판정한다.
A/B도 build/lease/validation 도중 PREPARED 전 실패할 수 있어 같은 모순이
발생한다.

Incident close를 생략하면 prior attempt가 terminal state가 아니므로 §4
202~205행의 immutable retry chain과 fresh attempt 발급 조건을 만족하지 못한다.

#### Required remediation

- stage×record-kind exact FSM을 두고 `CLOSED_INCIDENT`는
  `ISSUED|CONSUMED|LEASED|BUILDING|PREPARED`의 모든 nonterminal state에서
  허용한다.
- `CLOSED_SUCCESS`는 required PREPARED 이후, `DELEGATED_AND_CLOSED`는 B
  PREPARED+C valid pending exact pair에서만 허용한다.
- pending C expiry, A/B early build failure, failed review, revoked-after-prepare와
  incident-close race를 각각 replay test로 고정한다.
- incident close 뒤 old consume/lease/prepare/success와 child write를 모두
  rc2/write0으로 거부한다.

### PRE-P-R005-BLOCKING-004 — RECOVERY가 STALE ORDINARY EXECUTOR를 FENCE하지 못함

#### 근거

Target §5의 global journal state transition과 §8 437~477행의 target
CAS/NOREPLACE는 서로 다른 filesystem operation이다. Recovery가 original C를
incident-close하고 recovery authority를 consume하더라도 old executor가 다음
순서로 target을 쓸 수 있다.

```text
old executor: C/lease/head valid 확인
recovery:     original C close + recovery issued/consumed
old executor: 확인 직후의 stale authority로 target CAS
```

Global replay는 사후에 stale write를 발견할 수 있지만 journal head 변경과
repository rename/CAS를 원자적으로 묶지 않는다. R005에는 crash-release되는
transaction lock, monotonic fencing epoch 또는 recovery가 old writer 부재를
증명하는 gate가 없다.

Transaction progress에 global head를 기록하는 것만으로 check와 target mutation
사이 TOCTOU를 막을 수 없다. Ordinary C와 recovery writer가 겹치면 §8의 unique
prefix 및 checkpoint-last 보장도 깨진다.

#### Required remediation

- ordinary C가 첫 consume부터 terminal close까지 보유하는 exact transaction
  OFD/flock path를 transaction manifest에 예약한다.
- recovery는 original C incident close 뒤 동일 exclusive lock을 새 fencing
  epoch로 획득해야만 live prefix를 읽거나 쓸 수 있다.
- old executor는 lock/epoch를 잃으면 target/progress write 전에 반드시
  kernel-level로 실패해야 한다. 단순 read-check만 사용하지 않는다.
- process crash, pause/resume, lease expiry 직전 CAS, recovery acquisition과
  old executor delayed write를 교차 fault-inject한다.

### PRE-P-R005-BLOCKING-005 — RECONCILED PREFIX가 누락된 DURABILITY를 복원하지 않음

#### 근거

Target §8.1 437~441행의 정상 순서는:

```text
target commit
-> file fsync
-> parent fsync
-> PREFIX_ADVANCED
```

이다. 그러나 §8.2 463~465행은 progress record가 target commit보다 늦게
유실되면 live exact26 before/after hash만으로 prefix를 복원하고 곧바로
`RECONCILED_PREFIX` record를 먼저 쓴다.

다음 crash 상태에서는 file/parent durability가 증명되지 않는다.

```text
target CAS/NOREPLACE 성공
crash before FILE_FSYNCED
or crash before PARENT_FSYNCED
```

Process crash 후 path/hash가 보인다는 사실은 file data와 directory entry가
power-loss durable하다는 증거가 아니다. 이 상태를 reconciled prefix로
승격하고 나중에 checkpoint를 commit하면 checkpoint가 비내구 target을 전제로
할 수 있다.

#### Required remediation

- inferred prefix의 각 after member를 ordinal 순서로 다시 file fsync하고 parent
  fsync한 뒤 signed reconciliation durability record를 쓴다.
- 기존 progress가 `TARGET_CAS_COMMITTED`, `FILE_FSYNCED`,
  `PARENT_FSYNCED` 중 어디까지 durable한지 exact state machine으로 복원한다.
- fsync failure 또는 file/parent identity drift에서는 prefix를 advance하지 않고
  recovery incident로 닫는다.
- commit/file-fsync/parent-fsync/progress-publish 각 경계에서 process/power-loss
  fault test를 수행한다.

### PRE-P-R005-BLOCKING-006 — NORMAL STAGE C가 APPLICATION RECEIPT 쓰기를 허용하지 않음

#### 근거

Target §7 367~374행은 Stage C effective scope를:

```text
C_ALLOW = exact26 target operations ∪ transaction journal writes
C_DENY = all paths/operations - C_ALLOW
```

로 정의한다. §16 859~863행은 application receipt가 repository 안의:

```text
docs/control/execution/goal-gates/
  WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-
  APPLIED-20260731-001/application-receipt.json
```

이고 exact26에서 제외된 postcheck output이라고 명시한다. 따라서 application
receipt는 exact26 target도 external transaction journal도 아니다.

§15 815~816행은 exact6 all PASS 뒤 application receipt를 써야 정상 완료라고
하지만 이를 쓰는 순간 C_DENY에 걸린다. §8.2의 post-checkpoint recovery scope는
receipt 쓰기를 허용하지만 정상 성공을 고의 failure/recovery로 우회하는 것은
valid authority path가 아니다.

#### Required remediation

- ordinary C allowlist에 위 exact application receipt의
  `NOREPLACE_CREATE`, file fsync와 parent fsync를 별도 operation으로 추가한다.
- 이 operation은 checkpoint committed, exact6 all PASS, target write0 상태에서만
  활성화하고 pre-checkpoint/overwrite/delete/alternate path를 금지한다.
- application receipt는 계속 exact26과 managed count에서 제외하되 C receipt,
  X1, transaction manifest와 postreceipt가 physical path/hash/bytes를 결속하게
  한다.
- normal success와 post-checkpoint recovery success 양쪽이 같은 최종 receipt
  identity를 유일하게 만드는지 검증한다.

### PRE-P-R005-BLOCKING-007 — C0의 `C subset S` PRECONDITION이 CURRENT SOURCE에서 거짓

#### 근거

Target §9 481~501행은:

```text
S = seq39 managed_changed_paths, count 603
C = tests/requirements.lock
    scripts/run_walksafe_test_layers_20260711.sh
A = §16 exact23 NOREPLACE
M_after = sorted(unique(S union A union C) - {K})
precondition: C subset S
```

로 정의한다. Current checkpoint와 filesystem을 재계산한 실제 membership은:

```text
|S|=603
scripts/run_walksafe_test_layers_20260711.sh in S=true
tests/requirements.lock in S=false
|C intersection S|=1
C-S={tests/requirements.lock}
A intersection S=empty
K in S=false
|M_after|=627
C_subset_S=false
```

`tests/requirements.lock`은 tracked file이지만 seq39 managed-changed set에는
없다. Stage C에서 bytes를 CAS_REPLACE하면 새 changed member가 되므로 union
formula에는 들어가지만 `C subset S` precondition은 항상 실패한다.

따라서 R005 resolver/checker는 올바른 source에서도 C0를 만들지 못하거나,
precondition을 무시하면 자기 normative contract를 위반한다.

#### Required remediation

- exact precondition을 다음과 같이 현재 source에 맞춰 고정한다.

```text
C intersection S={scripts/run_walksafe_test_layers_20260711.sh}
C-S={tests/requirements.lock}
A intersection S=empty
A intersection C=empty
K not in S or A or C
M_after_count=627
```

- existing-but-unmanaged CAS target이 after managed set에 새로 들어가는 의미와
  before/after identity 검사를 schema에 둔다.
- working_tree_snapshot, session_handoff.changed_files와
  source_commit_or_snapshot의 path/content digest를 627개로 독립 재계산한다.
- wrong assumption `C subset S`, missing requirements member와 count626을
  negative fixture로 거부한다.

## 4. MAJOR findings

### PRE-P-R005-MAJOR-001 — GLOBAL RECORD KIND별 CANONICAL FIELD-STATE MATRIX 부재

#### 근거

Target §5.2 255~296행은 16개 allowed record kind를 두고 모든 payload가 다음
common fields를 갖는다고 한다.

```text
stage/attempt/challenge/nonce/transaction
receipt identity
scope/allowed/denied roots
issued/event/not-before/expires
lease interval/hard deadline
prior attempt
before/after/revocation state
source checkpoint
request/challenge/raw response
```

`ISSUED`, `LEASE_RENEWED`, `PREPARED`, `DELEGATED_AND_CLOSED`,
`CLOSED_INCIDENT`, recovery consume/close에서 각 field가 actual, signed absent,
null 또는 forbidden 중 무엇인지 정하지 않는다. 예를 들어 lease record의
raw-response identity, pending C incident의 after state, recovery record의
ordinary receipt fields는 유일한 canonical bytes를 갖지 않는다.

R005 §6은 stage receipt에 대해서는 stage matrix를 도입했지만 global
record-kind와 transaction progress-kind에는 같은 해법을 적용하지 않았다.

#### Required remediation

- global record kind×field와 transaction progress kind×field matrix를
  `REQUIRED_ACTUAL`, `SIGNED_ABSENT`, `FORBIDDEN`으로 고정한다.
- absent와 null을 혼용하지 않고 RFC8785 JCS exact representation을 정의한다.
- 각 kind의 allowed predecessor states, required prior record, resulting state,
  terminal/replay behavior를 schema version에 결속한다.
- unknown/irrelevant/future field, missing actual, forbidden non-null과 canonical
  alternate bytes를 모두 rc2/write0으로 거부한다.

### PRE-P-R005-MAJOR-002 — B→C HANDOFF가 PENDING C의 CURRENT VALIDITY를 EXACT GATE로 두지 않음

#### 근거

Target §7 376~390행은 `DELEGATED_AND_CLOSED` publication 전에 B가 current,
unrevoked, consumed, lease-valid, PREPARED여야 한다고 구체적으로 열거한다.
그러나 함께 effective되는 C pending receipt에 대해서는 다음 handoff-time
precondition이 없다.

```text
C state == ISSUED and PENDING_DELEGATION
not_before <= handoff_event < expires_at
C unrevoked and unconsumed
C expected_global_head compatible with handoff predecessor
unique B attempt <-> C attempt pair
exact transaction/scope/V1 unchanged
```

§7 392~393행은 handoff 전에 C가 만료되면 incident-close해야 한다고 하므로
expired C를 활성화하면 안 된다는 의도는 보이지만, publisher/replay gate가 이를
요구하지 않는다. B만 valid하면 stale/expired/revoked C를 effective로 만들 수
있는 해석 여지가 남는다.

#### Required remediation

- `DELEGATED_AND_CLOSED` schema와 replay FSM에 B와 C 양쪽의 exact pre-state,
  receipt/hash, TTL/revocation/consume/head 조건을 모두 둔다.
- 한 B PREPARED가 exact one C pending에만 handoff되고 C도 exact one B
  predecessor만 소비하도록 pair digest를 고정한다.
- handoff record event time을 B lease와 C validity window 양쪽에 대조한다.
- expired/not-yet-valid/revoked/consumed/stale-head C, C scope/V1 drift와 two-C
  race를 rc2로 거부한다.

## 5. R004 findings closure 재판정

| predecessor finding | R005 skeptical 재판정 |
|---|---|
| R004 skeptical B-001 retry namespace | attempt sibling/review domain은 개선됐으나 record path concurrency와 early incident FSM 때문에 완전 폐쇄 아님 |
| R004 skeptical B-002 durable recovery | transaction records는 추가됐으나 executor fencing, reconcile fsync와 normal receipt scope 때문에 미폐쇄 |
| R004 skeptical B-003 S0/review write | Stage-B reviews를 external journal로 옮겨 설계상 폐쇄 |
| R004 skeptical B-004 discovery arithmetic | orphan5 roles와 direct registry를 열거해 설계상 폐쇄 |
| R004 skeptical M-001 stage receipt schema | stage receipt matrix는 개선됐으나 global record-kind matrix에서 같은 underdetermination 재발 |
| R004 skeptical M-002 official ceiling | §1 exact current/after assertions으로 설계상 폐쇄 |
| R004 formal B-001 journal replay | replay 항목은 추가됐으나 sequence filename race로 미폐쇄 |
| R004 formal M-001 B→C semantics | atomic record는 추가됐으나 pending C validity/FSM이 미폐쇄 |
| R004 formal M-002 C0/V1 | external V1은 개선됐으나 current C0 set precondition이 거짓 |

## 6. 확인된 official claim ceiling

R005 §1의 official ceiling은 current authority와 일치하며 이 review가 어떤
credit도 변경하지 않는다.

```text
artifact_closed_equivalent=126/257
artifact_open=131/257
artifact_completion_credit_delta=0
formal_pass=0/279
formal_not_run=279/279
formal_test_credit_delta=0
actual_device_event=0/0
actual_event_credit_delta=0
gate_pass=0/5
gate_not_run=5/5
remaining_gates_waived=false
production_deployment=0
release_status=NOT_ELIGIBLE
approval_credit_delta=0
canonical_gap_backlog=r021/r021
canonical_delta=0
product_credit_delta=0
```

## 7. 최종 claim ceiling

```text
R005_ACCEPTED=false
R005_EXECUTABLE=false
R005_FORMAL_REVIEW_SUPERSEDED=false
STAGE_A_QUESTION_READY=false
STAGE_A_CANDIDATE_BUILT=false
STAGE_B_RESOLVED=false
PRE_P_APPLIED=false
V2_4_1_ACTIVE=false
R007_SUCCESSOR_BUILT=false
P17_BUILD_DEFERRED=true
P_APPLIED=false
OFFICIAL_CREDIT_DELTA=0
```

R005 target은 그대로 `NON_EFFECTIVE_PLAN_ONLY`다. 위 7 blocking과 2 major를
모두 닫은 add-only successor plan과 별도 독립검수 없이 R005를 실행하거나
Stage A authority를 요청해서는 안 된다.
