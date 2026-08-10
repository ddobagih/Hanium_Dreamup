# PRE-P Validation Convergence Design/Build Plan R004 독립 공격검수 R001

## 1. 검수 대상과 판정

| 항목 | exact 값 |
|---|---|
| review_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R004-INDEPENDENT-SKEPTICAL-REVIEW-R001` |
| 검수일 | `2026-07-31` |
| target | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R004.md` |
| target SHA-256 | `f8392f525df655d519b39be9cc08557bd0b817975235cf26a41eadf541eb6bf4` |
| target bytes | `45,964` |
| target lines | `928` |
| target type | `regular file, non-symlink, nlink=1` |
| target status | `NON_EFFECTIVE_PLAN_ONLY` |
| verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY` |
| findings | `BLOCKING=4 / MAJOR=2 / MINOR=0` |
| review authority | `NONE` |

검수 시작과 종료 시 target의 SHA-256, bytes와 lines가 위 값과 같음을
확인했다. 이 review는 target을 수정하지 않았으며, R004 formal independent
review를 대체하지 않는다. candidate/external environment build, authority
resolve, active apply, checkpoint/control 전환, R007 successor 또는 P 작업
권한을 만들지 않는다.

```text
VERDICT=REJECTED_NON_EFFECTIVE_PLAN_ONLY
BLOCKING=4
MAJOR=2
MINOR=0
TARGET_UNCHANGED=true
R004_REMAINS_NON_EFFECTIVE_PLAN_ONLY=true
PLAN_EXECUTION_AUTHORIZED=false
STAGE_A_AUTHORIZED=false
STAGE_B_AUTHORIZED=false
STAGE_C_AUTHORIZED=false
EXTERNAL_ENV_WRITE_AUTHORIZED=false
ACTIVE_WRITE_AUTHORIZED=false
CHECKPOINT_WRITE_AUTHORIZED=false
R007_SUCCESSOR_BUILD_AUTHORIZED=false
P17_BUILD_AUTHORIZED=false
P_APPLY_AUTHORIZED=false
```

## 2. 검수 범위와 확인된 target 수

Target 전체 928행과 현재 read-only source를 다음 축으로 대조했다.

- R003 physical review의 `BLOCKING=4 / MAJOR=2` remediation 실제 폐쇄 여부
- plan/review와 A/B/C authority receipt 사이의 시간·hash 의존성
- fixed receipt/root/review namespace에서 expiry와 fresh challenge 재생 가능성
- Stage-B two-build, review, renewal과 environment lease lifetime
- Stage-C partial prefix, checkpoint-last, postcommit recovery와 durable receipt
- S0, V1, source CAS와 repository 내부 review write의 hash DAG
- current discovery `132/127/5`에서 successor `132/132/0`으로 가는 exact routing
- official artifact/formal/device/gate/release claim ceiling
- exact active target universe의 수, mode와 중복

§15의 표를 literal path로 재계산한 결과는 다음과 같다.

```text
ACTIVE_TARGET_COUNT=26
CAS_REPLACE=2
NOREPLACE=23
CHECKPOINT_CAS_LAST=1
DUPLICATE_PATH_COUNT=0
APPLICATION_RECEIPT_EXCLUDED_FROM_PRECHECKPOINT_TARGET_COUNT=true
```

따라서 target 표 자체의 path 수나 중복에는 별도 finding이 없다. 아래 finding은
그 26개를 안전하게 resolve/apply/recover하는 계약과 official claim에 관한
것이다.

## 3. BLOCKING findings

### PRE-P-R004-BLOCKING-001 — EXPIRY 이후 FRESH CHALLENGE를 수용할 ADD-ONLY NAMESPACE 부재

#### 근거

Target §5 291행과 §11 573~625, 650~670행은 다음 경로를 고정한다.

```text
.../PRE-P-...-R004-stage-a-candidate-independent-review-r001.md
.../journal/000001-stage-a-pre-p-r004-001/authority-receipt.json
.../journal/000002-stage-b-pre-p-r004-001/authority-receipt.json
.../journal/000003-stage-c-pre-p-r004-001/authority-receipt.json
.../pre-p-validation-convergence-authority-resolved-r004/
  stage-b-pre-p-r004-001/
.../PRE-P-...-R004-stage-b-resolved-independent-review-r001.md
```

같은 절은 receipt TTL을 A/B/C `900/900/300`초, hard deadline을
`21600/3600/1800`초로 정하고 expiry/drift 시 `fresh challenge → re-resolve →
two-build → independent review`를 요구한다. 그러나 candidate/resolved root,
receipt와 review는 NOREPLACE/add-only이고 preexist를 금지한다. 첫 attempt가
일부라도 생성되거나 expired review를 남기면 같은 fixed `-001`/`r001`
namespace에 fresh attempt를 쓸 수 없다.

첫 B renewal
`renewals/000001/authority-receipt.json`만 예약되어 있으며 A/C renewal, 두 번째
이후 B renewal, fresh challenge attempt와 environment lease extension의 exact
경로·head order가 없다. sealed `lease.json`도 overwrite할 수 없으므로 Stage B
또는 C 전에 lease가 만료됐을 때 plan 문언만으로 retention을 연장할 수 없다.

이는 안전한 STOP은 만들지만 target이 약속한 fresh challenge/re-resolve를
재생하지 못한다. 특히 Stage-B full19×2, regression×2와 independent review가
hard deadline 안에 끝나지 않으면 R004 namespace는 영구 소진된다.

#### Required remediation

- A/B/C 각 authority attempt에 monotonic attempt ID를 두고 candidate/resolved,
  journal, renewal, incident, closure와 review의 exact sibling path를 표로
  예약한다.
- 실패 attempt는 변경하지 않고 immutable incident/closure receipt로 닫고,
  다음 attempt가 predecessor attempt의 path/hash/bytes와 failure reason을
  결속하게 한다.
- A/B/C 모든 renewal과 environment-retention renewal의 ordered NOREPLACE
  namespace, latest-head CAS, maximum renewal count와 hard-deadline 규칙을
  정의한다.
- Stage-B build-01/02와 overall review가 동일 challenge ID를 소비하고 다른
  attempt의 member를 섞지 못하도록 attempt-domain digest를 둔다.
- expiry 전후, rejected review 뒤, partially populated root 뒤 fresh attempt가
  이전 root에 1 byte도 쓰지 않고 성공/중단하는 negative replay를 추가한다.

### PRE-P-R004-BLOCKING-002 — STAGE-C PARTIAL COMMIT의 DURABLE RECOVERY가 실행 불가능

#### 근거

Target §11 607행은 receipt를 `one_use=true`로 고정한다. §14 768~775행은 각
target CAS/NOREPLACE 뒤 file/parent fsync와 `durable exact-prefix progress`를
요구하지만 그 progress의 physical path, schema, writer authority, NOREPLACE
order와 recovery reader가 없다.

§11의 fixed journal에는 A/B/C authority receipt 세 종류만 있고
pre-checkpoint 또는 post-checkpoint recovery receipt 경로가 없다. 그런데 §14
789행은 checkpoint 전 crash를 `same valid C receipt`로 resume한다고 하고,
791~795행은 checkpoint 뒤 fresh recovery receipt로 exact6/receipt-only
recovery를 요구한다.

다음 상태가 정의되지 않는다.

- C receipt의 one-use가 transaction 시작, 첫 CAS, checkpoint 또는 application
  receipt 중 언제 소비되는지
- process crash 뒤 같은 receipt 재진입이 replay인지 같은 transaction resume인지
- CAS는 성공했지만 prefix progress 기록 전 crash한 경우의 authoritative prefix
- C TTL/lease가 partial prefix에서 만료됐을 때 fresh recovery scope
- checkpoint commit 뒤 exact6 또는 application receipt fsync 실패 시 사용할
  signed recovery receipt와 journal head
- application receipt가 존재하지만 parent fsync 여부가 불명인 경우

checkpoint 전 partial prefix는 old seq39 official state에 새 target 일부가
존재하는 상태이고, checkpoint 뒤 failure는 rollback이 영구 금지된 seq40
상태다. 이 구간은 단순히 작업을 미루는 것으로 안전하게 종료할 수 없으므로
blocking이다.

#### Required remediation

- repo 밖 immutable transaction journal에 transaction/attempt ID별
  `PREWRITE`, target별 before/after CAS, file fsync, parent fsync, prefix-head,
  checkpoint commit, postcheck와 application receipt fsync record의 exact
  paths/schema/order를 예약한다.
- `one_use`를 한 process invocation이 아니라 한 transaction ID에만 사용하며
  authenticated crash resume는 허용하는지 명확히 정의한다.
- checkpoint 전 suffix-resume capability와 checkpoint 뒤
  exact6/application-receipt-only capability를 별도 signed receipt, scope, TTL,
  denied operations와 path로 고정한다.
- missing/stale progress record에서는 live exact26 before/after hash로 유일한
  prefix만 복원하고 non-prefix는 write 0으로 거부한다.
- T0~T6와 각 target의 rename/fsync 전후 crash injection test를 실행해 target
  write/rollback 없이 유일한 recovery 경로만 허용함을 검증한다.

### PRE-P-R004-BLOCKING-003 — STAGE-B REVIEW WRITE가 S0/SOURCE CAS를 스스로 깨뜨림

#### 근거

Target §11 650~658행은 resolved root를 final manifest 뒤 seal한 다음 repository
내부이면서 resolved root 밖인 다음 sibling review를 작성한다.

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R004-
  stage-b-resolved-independent-review-r001.md
```

그러나 §13 721행의 S0는 active source를 결속하고, 730~734행의
`source-self-exclusions.json`은 authority/candidate/resolved roots, raw/temp,
event/checkpoint/target-set/envelope/application receipt만 열거한다. resolved
root 밖 Stage-B review path는 포함하지 않는다.

따라서 Stage-B resolver가 S0→E0→C0→T1→X1을 만든 뒤 review를 쓰면 Stage-C
시작 시 repository source가 S0와 다르다. 이를 source CAS failure로 처리하면
정상 review 뒤에도 Stage C가 항상 막히고, 암묵적으로 무시하면 undeclared
source delta를 허용한다.

§13 726행의 V1은 physical Stage-B review hash를 소비하고 727행의 A_C는 V1과
source CAS를 결속하지만, review 이후 source identity로 전환하는 node와 allowed
delta가 없다. V1 composite의 durable physical path/schema도 없다.

#### Required remediation

- Stage-B overall review의 exact future path를 S0에 signed ABSENT tombstone으로
  예약하고 source hash domain에서 제외할지, review를 repo 밖 immutable
  authority domain으로 둘지 하나를 고정한다.
- repo 안에 둘 경우 review 뒤 `S1/R004_POST_RESOLVED_REVIEW_SOURCE_SNAPSHOT_V1`
  node를 만들고 `S0→...→X1→review/V1→S1→A_C`의 allowed exact-one-file delta를
  정의한다.
- V1 object의 exact physical path, canonical bytes/hash domain과 replay
  checker를 예약한다. Physical review가 자기 V1 hash를 포함하는 self-cycle은
  금지한다.
- missing/extra/tampered review, review-before-seal, S0와 S1 사이 unrelated
  source write와 review path replacement를 rc2/write0으로 검증한다.

### PRE-P-R004-BLOCKING-004 — `132/132/0` ROUTING 목표가 현재 계약으로 도달 불가

#### 근거

Target §2 52행은 current inventory를 discovered `132`, assigned `127`, orphan
`5`로 기록한다. 현재 read-only runner inventory와 `test_*.py` discovery를
재계산한 orphan exact5는 다음과 같다.

```text
tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
tests/test_walksafe_phase1_exact257_successor_r011_20260729.py
tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py
tests/test_walksafe_v2_5_control_candidate_20260730.py
tests/test_walksafe_w3_engineering_evidence_20260726.py
```

§6 312~324행의 allowed transformations와 §8 417~433행의 authoritative AFTER
table은 history exact6 중 첫 번째 seq39 test만 다룬다. 나머지 current orphan
4개의 합법적 role/consumer가 없다.

새 current direct5와 D direct2는 이름이 `tests/walksafe_...py`이므로 현재
`find ... -name 'test_*.py'` discovery에는 들어가지 않는다. non-discovery
direct registry로 분리하면 discovered count는 132를 유지할 수 있지만 기존
orphan4가 남는다. 반대로 current direct5를 discovery에 넣으면 count는 최소
137이며 D direct2까지 넣으면 139다.

그럼에도 §8 435~436행, §14 786행과 §16 852행은 `132/132/0`을 acceptance와
stop condition으로 고정한다. 현재 transformation contract로는 그 acceptance가
항상 실패한다.

#### Required remediation

- current discovered132 전체의 exact path→layer/role registry를 successor
  regression contract에 고정하고 orphan5 각각의 history/current/forbidden
  역할을 명시한다.
- current direct5와 D direct2를 non-discovery direct registry로 둘지
  discovery member로 둘지 결정하고, runner validator의 두 registry를 분리한다.
- non-discovery를 선택하면 discovered/assigned `132/132`와 direct
  `5+2`를 별도 수치로 검증한다. discovery를 선택하면 기대 count를 실제 member
  수로 갱신한다.
- stale R011/R022/v2.5/W3 파일을 current test로 실행하지 않을 경우에도
  inventory에서 누락시키지 말고 exact historical/forbidden role과
  `current_consumer_count=0`을 둔다.
- current132, direct5, D-direct2의 missing/duplicate/cross-registry assignment를
  모두 rc2/child-exec0으로 거부한다.

## 4. MAJOR findings

### PRE-P-R004-MAJOR-001 — AUTHORITY SCHEMA가 STAGE별 과거/미래 BINDING을 구분하지 않음

#### 근거

Target §4 107~136행은 Stage-A receipt 뒤 candidate를 만들고 검수하며, 그 뒤
Stage-B receipt로 resolved root를 만들고 검수한 다음 Stage-C receipt를
발행하는 순서를 가진다.

그러나 §11 597~610행은 A/B/C 공통 payload의 required field로 다음을 모두
열거한다.

```text
prior_receipt_path/hash/bytes
expected_journal_head
plan/review and candidate/resolved manifest/review bindings
required ABSENT tombstones
delegation_depth=0
revocation_state
one_use=true
```

Stage A 발급 시 candidate manifest/review는 미래이고 Stage B 발급 시 resolved
manifest/review는 미래다. 해당 field를 actual, null, signed ABSENT tombstone
중 무엇으로 canonicalize하는지 stage별 규칙이 없다. Stage A가 R004 plan과
완료된 R004 review를 결속하는 것 자체는 acyclic이지만, 공통 schema가 미래
output까지 required로 표현해 receipt validator의 유일한 bytes를 정할 수 없다.

또한 `delegation_depth=0`인 B receipt에서 C를 독립 발급하는지, predecessor
delegation으로 발급하는지 명시되지 않았다. `expected_journal_head`,
revocation과 one-use closure의 physical head/use record도 없다.

#### Required remediation

- A/B/C별 schema matrix를 두어 각 field를 `ACTUAL`, `SIGNED_ABSENT`,
  `FORBIDDEN` 중 하나로 고정한다.
- A receipt는 R004 plan/review, source와 absent candidate/env roots만, A closure
  record는 candidate/env manifest와 review를 결속한다.
- B receipt는 physical A closure, candidate review, environment lease와 absent
  B-attempt root만, B closure는 resolved manifest와 review를 결속한다.
- C receipt는 B closure/review, exact26/T1/X1/V1과 source CAS만 결속한다.
- C가 fresh independent authority인지 B에서 제한 delegation되는지 하나를
  고르고 issuer/verifier, prior-head와 delegation depth 규칙을 일치시킨다.
- immutable journal head/use/closure record의 exact paths와 verifier replay
  algorithm을 정의한다.

### PRE-P-R004-MAJOR-002 — CURRENT OFFICIAL CLAIM CEILING이 EXACT 값으로 닫히지 않음

#### 근거

Target §2 40~55행은 canonical r021과 formal/device/gate/release를 요약하지만
official artifact closed-equivalent `126/257`, open `131/257`을 열거하지 않는다.
`0/279 PASS`는 PASS 0과 `279/279 NOT_RUN`을 분리하지 않아 오해 가능하다.

§12 701~704행의 checkpoint contract와 §16 867행의 stop condition도
canonical/product/formal/device/gate/release가 unchanged 또는 credit delta 0이라고
일반적으로만 표현한다. v2.4→v2.4.1 control activation은 official control
변경이므로 제품·artifact 완료 credit을 만들지 않는 exact before/after ceiling이
필요하다.

#### Required remediation

Stage-B two builds, Stage-C preflight/postcheck와 after checkpoint에 다음 exact
assertion을 공통으로 둔다.

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
release_status=NOT_ELIGIBLE
approval_credit_delta=0
canonical_gap_backlog=r021/r021
```

Checkpoint/event/receipt checker는 위 값의 missing, 숫자 재해석, percentage
대체, v2.4.1 activation을 artifact/product completion으로 계산하는 경우를 모두
거부해야 한다.

## 5. R003 finding closure 재판정

| R003 finding | R004 skeptical 재판정 |
|---|---|
| `PRE-P-R003-BLOCKING-001` frozen namespace mutation | initial A/B sibling 분리는 폐쇄됐으나 retry attempt namespace는 `PRE-P-R004-BLOCKING-001`로 새로 발생 |
| `PRE-P-R003-BLOCKING-002` resolved full19/direct execution absent | §9의 two-env ordered19와 direct exact3으로 설계상 폐쇄 |
| `PRE-P-R003-BLOCKING-003` runner selector undefined | §8의 explicit root/checkpoint/selector와 exact2 state로 설계상 폐쇄 |
| `PRE-P-R003-BLOCKING-004` receipt/lease validity | command-boundary 검사는 추가됐지만 expiry/retry/recovery namespace가 닫히지 않아 미폐쇄 |
| `PRE-P-R003-MAJOR-001` Phase0 future suite final-pin | predecessor-only Phase0와 Stage-B regression-final로 설계상 폐쇄 |
| `PRE-P-R003-MAJOR-002` hash DAG undefined | nominal topology는 추가됐으나 post-S0 review write/V1/source CAS가 닫히지 않아 미폐쇄 |

## 6. 최종 claim ceiling

```text
R004_ACCEPTED=false
R004_EXECUTABLE=false
R004_FORMAL_REVIEW_SUPERSEDED=false
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

R004 target은 그대로 `NON_EFFECTIVE_PLAN_ONLY`다. 위 4 blocking과 2 major를
모두 닫은 add-only successor plan과 독립검수 없이 R004를 실행하거나 Stage A
authority를 요청해서는 안 된다.
