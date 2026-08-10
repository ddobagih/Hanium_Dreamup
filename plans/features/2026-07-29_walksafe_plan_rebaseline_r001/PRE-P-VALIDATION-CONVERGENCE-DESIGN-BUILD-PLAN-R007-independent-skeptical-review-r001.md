# PRE-P Validation Convergence Design/Build Plan R007 독립 공격검수 R001

## 1. 검수 대상과 최종 판정

| 항목 | exact 값 |
|---|---|
| review_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R007-INDEPENDENT-SKEPTICAL-REVIEW-R001` |
| 검수일 | `2026-07-31` |
| target | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007.md` |
| target SHA-256 | `02766312b1bbb00eb05e2789fe4d054cbf749407e6c5bd26dce62250e6dd98ef` |
| target bytes | `259,476` |
| target lines | `5,350` |
| target type | `regular file, non-symlink, mode=0664, nlink=1` |
| target status | `DEFERRED_NON_EFFECTIVE_DRAFT` |
| verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY` |
| disposition | `DEFERRED_FOR_SUCCESSOR_REVISION` |
| findings | `BLOCKING=18 / MAJOR=4 / MINOR=0` |
| review authority | `NONE` |

Target이 `DEFERRED_FREEZE_FOR_REVIEW=true`를 선언한 뒤, `2026-07-31
08:52:36 KST`와 `08:52:55 KST`에 10초보다 긴 간격으로 SHA-256, bytes,
lines, mode, nlink와 file type이 위 값으로 동일함을 확인했다. 검수 종료
직전에도 같은 identity를 다시 확인했다.

이 review는 target을 수정하지 않았고 formal independent review를 대체하지
않는다. Target §1의 `ABSENT_DENY_ALL`과 §24의 zero-delta 경계를 그대로
유지한다.

```text
VERDICT=REJECTED_NON_EFFECTIVE_PLAN_ONLY
DISPOSITION=DEFERRED_FOR_SUCCESSOR_REVISION
BLOCKING=18
MAJOR=4
MINOR=0
TARGET_UNCHANGED=true
CURRENT_AUTHORITY=ABSENT_DENY_ALL
PLAN_EXECUTION_AUTHORIZED=false
JOURNAL_BOOTSTRAP_AUTHORIZED=false
AUTHORITY_JOURNAL_WRITE_AUTHORIZED=false
STAGE_A_REQUEST_ALLOWED=false
STAGE_A_AUTHORIZED=false
STAGE_B_AUTHORIZED=false
STAGE_C_AUTHORIZED=false
RECOVERY_STAGE_C_AUTHORIZED=false
ACTIVE_WRITE_AUTHORIZED=false
CHECKPOINT_WRITE_AUTHORIZED=false
APPLICATION_RECEIPT_WRITE_AUTHORIZED=false
SUCCESSOR_BUILD_AUTHORIZED=false
P_APPLY_AUTHORIZED=false
CURRENT_CHECKPOINT_SEQUENCE=39
TARGET_MUTATION_COUNT=0
APPLICATION_RECEIPT_EXISTS=false
OFFICIAL_PRE_P_VALIDATION_SUCCESS=false
OFFICIAL_CREDIT_DELTA=0
```

## 2. 범위, 방법과 중복 제거

동결된 target 전체 5,350행과 target이 직접 결속한 current runner/source
tree를 read-only로 다음 축에서 대조했다.

- bootstrap/preissuance/review grant가 첫 syscall 전에 실제로 구성 가능한지
- A/B/C/recovery 권한의 root, lifecycle, revocation과 one-use guard가 닫히는지
- T1/X1/V1, Stage-B publication과 BEFORE/AFTER DAG에 시간 역전이 없는지
- source snapshot, projection, runtime pack과 invocation input이 실제 tree
  type을 손실 없이 표현하는지
- full19/exact6의 consumer, executable, output oracle와 raw evidence가
  독립 재계산 가능한지
- checkpoint 이후 crash recovery가 exact6 suffix와 application receipt를
  과잉 허용하지 않는지
- current source와 target의 official ceiling이 서로 일치하는지

Target §24의 `16 BLOCKING / 4 MAJOR` ledger는 세 auditor-domain 원시 count를
합한 값이 아니라 이미 중복 제거된 목록이다. 이 review는 각 항목을 본문
계약에 다시 대조했고 그대로 열려 있음을 확인했다. 그 20건에 다음 두
독립 BLOCKING을 추가했다.

1. mixed tree의 symlink/directory physical identity를
   `DirManifestPhysical.ordered_members:[FilePhysical]`로 표현할 수 없다.
2. source-only BEFORE projection에서 아직 없는 successor routing file과
   successor runner CLI를 호출하므로 BEFORE validation이 실행 불가능하다.

생성 scratch target의 비결정성은 `BLOCKING-011`, projection schema shape와
publication order는 `BLOCKING-012`, mixed tree node type 손실은
`BLOCKING-017`로 각각 한 번만 센다. BEFORE runner 문제는 단순 publication
순서 변경만으로 닫히지 않으므로 `BLOCKING-018`로 분리했다.

읽기 전용 current-tree 대조 결과:

```text
SOURCE_TREE_SYMLINK_COUNT=29
examples:
  .venv/bin/python
  .venv/lib64
  apps/android-gateway/node_modules/.bin/tsc
  apps/web/node_modules/.bin/next

PRESENT_GENERATED_SCRATCH_TARGETS:
  apps/android-gateway/dist
  apps/web/.next
  apps/web/tsconfig.tsbuildinfo
  apps/android/.gradle
  apps/android/.kotlin
  apps/android/build
  apps/android/app/build

CURRENT_RUNNER_SHA256=
  4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d
CURRENT_RUNNER_ARGUMENT_MODEL=FIRST_POSITIONAL_LAYER_ONLY
```

## 3. BLOCKING findings

### PRE-P-R007-BLOCKING-001 — preissuance/review grant bytes와 lifecycle이 구성 불가능함

§4.1.2 476~504행의 `StageAPreIssuanceGrantPayload`는 정의되지 않은
`CanonicalRootSpec`을 사용하고 `signature`를 payload 안에 둔 채 그 payload
전체를 다시 서명한다. 같은 절의 grant/consume/closed 경로는 정확한 strict
payload, predecessor, signature domain과 success/failure cardinality를
정의하지 않는다. Review publication도 506~526행에서 consumption을
“records”한다고만 하고 canonical consumption artifact가 없다.

Required remediation: `CanonicalRootSpec` strict union을 정의하고 signature를
payload 밖 wrapper로 옮긴 뒤, preissuance와 review grant의 issue/consume/
close path, tagged payload, predecessor, signature domain과 one-use failure
전이를 완전히 물리화한다.

### PRE-P-R007-BLOCKING-002 — revocation과 공통 consume/lease guard FSM이 닫히지 않음

§12는 `REVOKED` kind를 열거하지만 §13.3 4031~4057행의 exact FSM에는
revocation edge가 없다. current head, event time, hard deadline, one-use,
unrevoked, consumed state를 issue/consume/lease/renew 각각에서 같은 방식으로
검증하는 strict guard payload와 실패 predecessor도 없다.

Required remediation: 모든 stage와 recovery에 대해 legal `REVOKED` edge,
current-head 조건, time interval, renewal ordinal, one-use와 revocation
precondition을 하나의 strict transition table로 정의한다.

### PRE-P-R007-BLOCKING-003 — capability pair가 signed literal root 집합이 아님

§13.2 3943~3996행의 `Scope`는 `roots:[literal roots]`와 자연어 alias를
사용한다. A/B/C/recovery별 표도 source snapshot, “its root”, live repo 같은
alias라서 operation과 exact anchored root의 signed pair bytes를 만들 수
없다. 이것은 `BLOCKING-001`의 preissuance grant lifecycle과 별개로, 실제
stage receipt가 부여할 권한 범위의 결함이다.

Required remediation: A/B/C/recovery 각각에 strict `CanonicalRootSpec[]`을
literal path, parent anchor, object type, allowed operation과 함께 넣고,
operation×root cross-product나 alias expansion을 금지한다.

### PRE-P-R007-BLOCKING-004 — C/recovery receipt가 N26/T1/X1/V1과 application target을 결속하지 않음

§13.1 3883~3899행은 모든 receipt에 공통 field만 정의한다. C와 recovery
전용 tagged extension이 없어서 N26, T1, X1, V1,
`ApplicationReceiptTargetSpec`과 executor phase를 strict bytes로 묶지
못한다. §13.2 4026행의 “byte-equal TargetSpec” 문장만으로 receipt schema를
구성할 수 없다.

Required remediation: stage-discriminated receipt union을 만들고 C/recovery
branch에 위 bindings, exact capability pairs, application target spec과
predecessor phase를 required fields로 추가한다.

### PRE-P-R007-BLOCKING-005 — D–G future label의 별도 승인 경계가 물리화되지 않음

§3 96~108행은 D–G를 stage/token 표에 넣은 뒤 현재는
`NON_OPERATIVE_FUTURE_LABEL_ONLY`라고 선언한다. 그러나 seq40 뒤 successor가
이 label을 effective receipt/grant로 바꾸기 위해 반드시 거쳐야 할 별도 user
approval artifact, predecessor와 signature domain은 없다.

Required remediation: R007에서는 D–G를 비실행 label로만 남기고, 별도
successor plan/review/user approval 없이는 어떤 future issuer도 이를
materialize할 수 없다는 strict handoff boundary를 정의한다.

### PRE-P-R007-BLOCKING-006 — T1/X1과 StageCReviewBinding/V1 bytes가 정의되지 않음

§17 4889~4898행은 `T1(N26 exact table)`과
`X1(N26,U1,recovery,exact6)`을 hash topology label로만 쓴다. §18
4914~4929행도 `review-binding payload`의 strict schema, exact path fields,
publisher, signature wrapper 없이 V1 공식을 계산한다. 따라서 transaction
manifest와 C receipt가 참조할 actual T1/X1/V1을 생성할 수 없다.

Required remediation: T1/X1/StageCReviewBinding의 strict payload, canonical
path, publisher, inward-only references와 domain-separated signature/hash
공식을 각각 정의한다.

### PRE-P-R007-BLOCKING-007 — Stage-B 비-full19 exact10 결과와 repeat oracle이 없음

§10 3066~3092행은 환경당 before, after, regression-A, regression-B,
after-repeat 다섯 invocation을 요구한다. 그러나 §10 3340~3386행의 review
subject는 이를 opaque `environment_invocation_results`/evidence files로만
묶는다. PASS/FAIL/NOT_RUN strict result, per-assertion extractor, aggregate와
AFTER-repeat byte equality receipt가 없다.

Required remediation: 두 환경의 exact10 `StageBValidationResult` union,
canonical result/evidence path, assertion extraction map, environment aggregate
및 after/repeat equality receipt를 정의한다.

### PRE-P-R007-BLOCKING-008 — Stage-B 세 input manifest의 strict signed schema가 없음

§10 3193~3212행과 3244~3252행은 두 resolved input과 regression-final input을
경로 배열로만 표시한다. §10은 command manifest schema는 정의하지만 input
manifest의 alias, actual Physical union, set digest, signature, publisher와
publication predecessor는 정의하지 않는다.

Required remediation: 세 파일을 strict signed
`StageBSourceInputManifest`로 정의하고 ordered members, sandbox aliases,
digest, signature와 “all references actual 후 command manifest 전” publication
edge를 고정한다.

### PRE-P-R007-BLOCKING-009 — checkpoint 이후 Stage-C live root 봉인이 없음

§8.1 1927~1943행의 live role은 `DirPhysical + source_manifest:FilePhysical`
뿐이다. exact26/U1/checkpoint 적용 뒤의 627 files, exact26 physical identities,
seq40 tail와 excluded paths를 재열거하는 `StageCLiveRootManifest`가 없다.
§16의 exact6 result도 generic environment/pack references만 갖는다.

Required remediation: checkpoint durability 뒤 strict live-root manifest와
physical receipt를 publish하고 모든 exact6 intent/input/result가 그 same
actual manifest를 결속하도록 한다.

### PRE-P-R007-BLOCKING-010 — exact6 input bundle과 assertion oracle이 재계산 불가능함

§16 4597~4617행은 input roles와 assertion을 string array로 두고,
4749~4767행의 result는 `inputs:[FilePhysical]`와 untyped
`assertion_results`만 둔다. directory/live-set/runtime scalar를 어떻게
materialize하는지, 각 assertion의 raw source, parser, expected/actual과
independent recomputation rule이 없다.

Required remediation: exact6별 signed input manifest/bundle, typed role
expansion, literal extractor map, expected/actual schema와 independent verifier
contract를 정의한다.

### PRE-P-R007-BLOCKING-011 — source snapshot의 generated scratch target이 비결정적임

§8.2는 `.next`, `dist`, coverage, Gradle/build directories와 tsbuildinfo를
empty 또는 exact seed mountpoint로 요구한다. 하지만 §17의 source-snapshot
exclusions에는 이 경로가 없고 current tree에는 위 경로 중 일곱 개가 이미
존재한다. §8.2의 “projection builder creates ... exact empty” 문장은 기존
source member의 exclude/normalize/collision 순서를 정하지 않는다.

Required remediation: 모든 scratch target을 source/projection manifest에서
명시적으로 제외하거나, deterministic normalization/empty placeholder
builder와 before/after digest 규칙을 정의한다.

### PRE-P-R007-BLOCKING-012 — BEFORE/AFTER projection schema와 publication order가 서로 모순됨

Canonical `DirManifestPhysical`은 §5.1 887~894행에서
`root,root_physical,ordered_members,set digests`이다. 반면 §10
3313~3338행의 projection schemas와 3417~3420행의 cast는 missing/extra field와
서로 다른 `root` 의미를 쓴다. 또한 3421~3424행은 candidate
apply/equality를 BEFORE 뒤에 두지만 canonical DAG 3443~3447행은 resolved
candidate build를 BEFORE 앞에 둔다.

Required remediation: BEFORE/AFTER 각각 canonical type-correct manifest와
receipt를 정의하고, 한 개의 acyclic publication sequence만 normative하게
남긴다.

### PRE-P-R007-BLOCKING-013 — RuntimeActual producer/consumer/use receipt chain이 불완전함

§9.6 2977~3018행의 binding은 `consumer_intent_roles`를 local/hosted full19
row19 두 개로만 제한한다. `resolved_subject_digest`도 object shape/domain
separator 없이 “JCS digest”라고만 한다. use receipt의
`normalized_outputs`는 canonical path, producer와 normalization schema가
없다. §16 row006과 crash recovery는 같은 scalar를 소비하지만 binding/use
receipt에 없다.

Required remediation: resolved-subject payload/hash를 정확히 정의하고
local/hosted/Stage-C/recovery consumer intents와 각 use receipt, normalized
output path/producer/schema를 모두 결속한다.

### PRE-P-R007-BLOCKING-014 — full19 semantic assertions가 raw evidence에서 추출되지 않음

§9.5 2948~2962행은 다수 semantic assertion을
`REVIEWED_EXIT_CONTRACT`와 rc0 postcondition으로 처리한다. 이는 assertion별
structured pytest summary/nodeid/raw JSON parser가 아니라 “reviewed rc0”
주장을 재포장한다. 예를 들어 failed/skipped/collected nodeids와 row09 child
count를 immutable bytes에서 얻는 literal JSON pointer/parser가 없다.

Required remediation: 모든 assertion의 canonical raw evidence, extractor
executable Physical, parsing/normalization rule, expected/actual type를
literal extraction map으로 publish한다.

### PRE-P-R007-BLOCKING-015 — row18 consumer/executable closure가 실제 test reads를 누락함

§9.4 2771~2794행의 row18은 tests 외에 scripts/docs-control/workflows와
apps/deploy만 허용하고 GIT도 executable role에 없다. 실제 bound trace tests는
builder를 import하고, 그 builder는 `backend/**`, `contracts/**`, 비-control
docs, 추가 tests, `apps/README.md`, `.git`과 `git` subprocess를 읽는다. strict
trace equality에서는 undeclared read 또는 missing executable로 실패한다.

Required remediation: row18의 complete transitive source/test/control/.git
member arrays, `GIT_ENVIRONMENT`과 GIT executable을 추가하고 실제 trace와
literal equality를 검증한다.

### PRE-P-R007-BLOCKING-016 — FutureSealed role의 생산자와 해소 경계가 단일하지 않음

§9.5 2921~2935행은 row17/18 FutureSealed values의 producer를
`after-control-builder.py`로 지정한다. 그러나 §10 3523~3524행은
`lane-d-final-builder.py`가 FutureSealed roles를 해소한다고 한다. Canonical
DAG에서 lane-d-final은 after-control보다 앞이므로 한 role의 actual bytes를
누가 언제 봉인하는지 결정할 수 없다.

Required remediation: 각 FutureSealed role마다 exactly one producer,
input manifest, canonical output path와 resolution barrier를 표로 고정한다.

### PRE-P-R007-BLOCKING-017 — mixed source/projection tree를 FilePhysical-only manifest로 표현할 수 없음

§4.1.2 576~588행의 source snapshot은 member `type`을 허용하지만 symlink의
`relative_link_target`과 symlink physical identity/copy rule은 없다. 더
치명적으로 canonical `DirManifestPhysical`은 §5.1 887~894행에서
`ordered_members:[FilePhysical]`만 허용하며 Stage-B projection도
3313~3336행에서 FilePhysical-only다.

Current source에는 `.venv/bin/python`, `.venv/lib64`,
`apps/*/node_modules/.bin/*` 등 symlink 29개와 많은 directory node가 있다.
`O_NOFOLLOW` anchored copy, strict membership과 target-preserving projection을
동시에 만족시킬 tagged `TreeMemberPhysical`이 없으므로 source snapshot과
BEFORE/AFTER projection은 materialize할 수 없다.

Required remediation: regular/directory/symlink strict tagged union,
relative-link-target, lstat identity, copy/no-escape semantics와 tree digest를
정의하고 모든 source/projection/role binding에서 같은 type을 사용한다.

### PRE-P-R007-BLOCKING-018 — source-only BEFORE validation이 존재하지 않는 successor CLI/input을 요구함

§10 3409~3413행은 BEFORE root가 Stage-A source snapshot만 복사한다고
명시한다. 그 snapshot의 current runner는 target §2에 결속된 SHA
`4f75501a...b42d`이고 실제로 첫 positional argument만 `LAYER`로 읽는다.
그러나 `BEFORE_RUNNER_VALIDATE`는 §10 3095~3115행에서 새
`--layer/--root/--checkpoint/--control-selector/--routing-manifest` CLI를
요구한다. 더구나 그 routing input은 exact26 row16의 아직 absent인 NOREPLACE
successor target이다.

따라서 source-only BEFORE root에는 그 CLI를 해석할 runner도 routing file도
없다. publication order만 바꿔 AFTER overlay를 사용하면 “BEFORE”의 current
source proof가 사라지므로 `BLOCKING-012`와 별개의 execution blocker다.

Required remediation: current runner의 실제 positional CLI로 source-only
BEFORE를 검증하는 별도 literal command/oracle을 만들거나, reviewed
historical runner/routing input을 source snapshot 안에 별도로 materialize하고
BEFORE 의미를 다시 정의한다.

## 4. MAJOR findings

### PRE-P-R007-MAJOR-001 — row15 successor runner executable closure가 불완전함

§9.5 2877행은 row15 executables를
`[BASH,TEST_LAYER_RUNNER,PYTHON]`으로만 둔다. Current runner와 그 successor
contract는 `dirname`, `mktemp`, `chmod`, `rm`, `find`, `sort` 계열 외부
executables를 사용하거나 제거 여부가 아직 FutureSealed다.

Required remediation: final reviewed runner bytes가 필요로 하는 complete
exec closure를 넣거나, successor가 이 subprocess들을 제거했음을 sealed
source/trace로 증명한다.

### PRE-P-R007-MAJOR-002 — Stage-C invocation-data role의 late-bound type이 불완전함

§16 4597~4612행은 Stage A에서 contract를 봉인하면서 future live-root,
transaction, exact26, U1, runtime scalar와 repository inventory를 plain
`input_roles:[string]`으로 둔다. executable/environment/package closure와
Stage-C에서만 actual이 되는 invocation data의 type/allowed phase가 분리되지
않는다.

Required remediation: 모든 future invocation-data role을 strict
`LATE_BOUND_ROLE`로 만들고 allowed phase/constructor를 정의하며, Stage A에는
actual executable/environment/package closure만 허용한다.

### PRE-P-R007-MAJOR-003 — recovery 권한이 exact6 suffix와 application receipt를 과잉 포괄함

§13.2 3992~3996행은 checkpoint 뒤이고 predecessor가
`POSTCHECK_FAILED`가 아니면 recovery에 exact6와 application receipt를 함께
허용한다. 그러나 §16 4789~4791행의 exact6 recovery는 adoptable complete
prefix와 never-dispatched suffix가 있어야 하며, §15.4의 application receipt는
actual `POSTCHECK_PASSED`가 있어야 한다. 두 precondition이 receipt scope에
분리돼 있지 않다.

Required remediation: recovery capability을 exact6-suffix branch와
application-finalization branch로 나누고 각각 prefix/dispatch proof와 actual
POSTCHECK_PASSED predecessor를 strict fields로 요구한다.

### PRE-P-R007-MAJOR-004 — review-subject graph membership/digest 생산 규칙이 검증되지 않음

§5.2 1079~1090행은 every bound FilePhysical을 graph node/edge에 한 번
넣으라고 하고, §10의 두 review-subject schema는 generic embedded arrays만
둔다. Nested arrays/objects의 어떤 reference가 node인지, node ID producer와
complete edge enumeration을 누가 독립 검증하는지에 대한 executable
construction contract가 없다.

Required remediation: 두 manifest별 exact node/edge extraction algorithm,
node ID, ordering, producer/checker Physical과 expected membership cardinality를
고정하고 graph digest를 독립 재계산한다.

## 5. 독립 판정과 다음 revision 수용 조건

R007은 R006보다 훨씬 구체적이며 다음 산술/ceiling은 target과 일치한다.

```text
ACTIVE_TARGET_COUNT=26
CAS_REPLACE=2
NOREPLACE=23
CHECKPOINT_CAS_LAST=1
DUPLICATE_PATH_COUNT=0
M_AFTER_COUNT=627
PROJECTED_PATH_SET_SHA256=
  2c98e5795a5bf26e90a2487f41899d2a88114a3e4e02f830924f33066b9cdbb6
CURRENT_CHECKPOINT_SEQUENCE=39
FORMAL_PASS=0/279
GATE_PASS=0/5
PRODUCTION_DEPLOYMENT=0
RELEASE_STATUS=NOT_ELIGIBLE
```

그러나 정확한 산술과 상세한 의도는 실행 가능한 authority/materialization/
oracle 계약을 대신하지 않는다. 위 BLOCKING 18건 중 하나라도 남으면
bootstrap, Stage A request, Stage B validation, exact26 apply 또는 Stage C
postcheck를 승인할 수 없다.

Successor revision의 최소 수용 조건:

1. 18 BLOCKING과 4 MAJOR를 각각 strict schema/path/producer/DAG/test로
   폐쇄한다.
2. mixed tree와 generated scratch를 포함한 source snapshot → BEFORE/AFTER
   projection을 실제 dry materialization fixture로 검증한다.
3. current CLI를 사용하는 BEFORE와 successor CLI를 사용하는 AFTER를
   별도 contract로 실제 spawn해 expected result를 봉인한다.
4. full19/exact6 assertion을 raw evidence에서 independent recomputation한다.
5. successor frozen bytes를 서로 독립적인 formal/skeptical review가 다시
   검수하고 둘 다 `BLOCKING/MAJOR/MINOR=0/0/0`이어야 한다.
6. 그 뒤에도 bootstrap과 Stage A user approval은 별개로 받아야 하며 plan
   review 자체는 authority가 아니다.

현재 안전한 disposition은 preservation과 deferral뿐이다.

```text
DEFERRED_FREEZE_FOR_REVIEW=true
REJECTED_FOR_EXECUTION=true
CURRENT_AUTHORITY=ABSENT_DENY_ALL
ACTIVE_WRITE_ALLOWED=false
AUTHORITY_JOURNAL_WRITE_ALLOWED=false
CURRENT_CHECKPOINT_SEQUENCE=39
TARGET_MUTATION_COUNT=0
APPLICATION_RECEIPT_EXISTS=false
OFFICIAL_PRE_P_VALIDATION_SUCCESS=false
```
