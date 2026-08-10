# PRE-P Validation Convergence Design/Build Plan R007 독립검수 R001

## 1. 검수 대상과 최종 판정

| 항목 | exact 값 |
|---|---|
| review_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R007-INDEPENDENT-REVIEW-R001` |
| 검수일 | `2026-07-31` |
| target | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007.md` |
| target SHA-256 | `02766312b1bbb00eb05e2789fe4d054cbf749407e6c5bd26dce62250e6dd98ef` |
| target bytes / lines | `259,476 / 5,350` |
| target physical | `regular, non-symlink, mode=0664, nlink=1` |
| target terminal LF / NUL | `true / 0` |
| target status | `DEFERRED_NON_EFFECTIVE_DRAFT` |
| freeze state | `DEFERRED_FREEZE_FOR_REVIEW` |
| verdict | `REJECTED_DEFERRED_NON_EFFECTIVE_DRAFT` |
| findings | `BLOCKING=18 / MAJOR=4 / MINOR=0` |
| C0 / regression findings | `0 / 0` |
| review authority | `NONE` |

`2026-07-31 08:52:11 KST`와 `08:52:32 KST`에 10초보다 긴 간격으로
target fingerprint를 다시 읽었고 두 관측이 위 SHA-256/bytes/lines와
동일했다. 두 관측 모두 `DEFERRED_FREEZE_FOR_REVIEW=true`였으며 target은
regular non-symlink, terminal LF, NUL 0이었다. 이 검수는 해당 동결 bytes만
대상으로 하며 target을 수정하지 않는다.

```text
VERDICT=REJECTED_DEFERRED_NON_EFFECTIVE_DRAFT
BLOCKING=18
MAJOR=4
MINOR=0
C0_FINDINGS=0
REGRESSION_FINDINGS=0
TARGET_UNCHANGED=true
R007_REMAINS_DEFERRED_NON_EFFECTIVE_DRAFT=true
R007_INDEPENDENT_REVIEW_ZERO_FINDINGS=false
PLAN_EXECUTION_AUTHORIZED=false
BOOTSTRAP_AUTHORIZED=false
STAGE_A_REQUEST_ALLOWED=false
STAGE_A_AUTHORIZED=false
STAGE_B_AUTHORIZED=false
STAGE_C_AUTHORIZED=false
STAGE_D_AUTHORIZED=false
STAGE_E_AUTHORIZED=false
STAGE_F_AUTHORIZED=false
STAGE_G_AUTHORIZED=false
AUTHORITY_JOURNAL_WRITE_AUTHORIZED=false
EXTERNAL_ENV_WRITE_AUTHORIZED=false
ACTIVE_WRITE_AUTHORIZED=false
CHECKPOINT_WRITE_AUTHORIZED=false
APPLICATION_RECEIPT_WRITE_AUTHORIZED=false
RECOVERY_WRITE_AUTHORIZED=false
P_BUILD_AUTHORIZED=false
P_APPLY_AUTHORIZED=false
```

R007 §24가 자가 기록한 `16 BLOCKING / 4 MAJOR`는 독립 검수의 상한이
아니다. 그 20건은 모두 재현되었고, frozen target 및 실제 source tree를
대조해 서로 독립적인 BLOCKING 두 건을 추가했다. 따라서 원장보다 큰
`18/4/0`이 이 review의 비중복 판정이다.

## 2. 범위, 교차검증과 비중복 원칙

Target 전체 5,350행을 다음 축으로 검수했다.

- bootstrap/preissuance/review grant, receipt, capability와 revocation FSM
- Stage-A source snapshot, sealed subject, builder/runtime materialization
- Stage-B BEFORE/AFTER projection, exact10, full19와 runtime binding
- Stage-C N26, transaction/recovery, live root, exact6와 application receipt
- signed subject graph, lock/journal ordering, strict schema/producer/path closure
- exact26/C0 managed-set 산술, registry 수와 claim ceiling

독립 재계산 결과 다음 산술은 target과 일치한다. 이 부분에는 C0 finding을
추가하지 않았다.

```text
S=603
C_intersection_S=1
C_minus_S={tests/requirements.lock}
A=23
A_intersection_S=0
A_intersection_C=0
Q_outside_S_A_C=true
M_after=627
M_after_path_set_sha256=
  2c98e5795a5bf26e90a2487f41899d2a88114a3e4e02f830924f33066b9cdbb6

exact26=26
ordinals=1..26
unique_targets=26
CAS_REPLACE=2
NOREPLACE=23
CHECKPOINT_CAS_LAST=1

registry_rows=132
registry_roles=28/23/7/3/53/16/2
selected/excluded=74/58
```

Current checkpoint와 R006 plan/formal/skeptical physical fingerprints도 target의
기록과 일치했다. 이는 future execution이나 validation PASS를 뜻하지 않는다.
R007이 직접 `NOT_EXECUTABLE`, formal test `0/279`, gate `0/5`로 선언하고
아래 constructibility/oracle findings가 남으므로 execution credit은 0이다.

원장 밖 후보의 비중복 처리는 다음과 같다.

| 후보 | 독립 처리 |
|---|---|
| mixed tree의 directory/symlink value universe 부재 | `BLOCKING-017`로 별도 집계; regular-file projection field 모순인 `BLOCKING-012`와 다름 |
| source-only BEFORE가 future CLI/input을 요구 | `BLOCKING-018`로 별도 집계; result schema 부재인 `BLOCKING-007`과 다름 |
| multi-command raw stream framing 부재 | exact6는 `BLOCKING-010`, full19는 `BLOCKING-014`의 extractor/raw-source closure에 포함 |
| Stage-A evolving sealed subject/per-wave root 불명확 | grant/producer predecessor constructibility인 `BLOCKING-001`과 graph completeness인 `MAJOR-004`의 remediation에 포함 |
| complete strict SandboxIntent 부재 | Stage-B input은 `BLOCKING-008`, exact6 input은 `BLOCKING-010`, full19 evidence는 `BLOCKING-014`에 포함 |
| `MemberOriginMap`의 directory/symlink/direct-origin union 부재 | tree value 문제는 `BLOCKING-017`, late-bound runtime role 문제는 `MAJOR-002`에 포함 |
| absolute `.git` gitfile 및 external gitdir bind 부재 | row18과 Git consumers 전체 closure인 `BLOCKING-015`에 포함 |

같은 원인에 여러 증상이 있으면 한 finding으로 셌지만, schema를 추가해도
남는 독립 실행 모순은 합치지 않았다.

## 3. BLOCKING findings

### PRE-P-R007-BLOCKING-001 — preissuance/review grant와 Stage-A wave lifecycle을 canonical bytes로 만들 수 없음

§4.1.2 476~504행의 `StageAPreIssuanceGrantPayload`는 정의되지 않은
`CanonicalRootSpec`을 사용하고 `signature`를 payload 내부에 둔 상태에서
`JCS(payload)`를 서명한다. grant/consume/closed filenames는 있지만 strict
payload, predecessor, signature domain과 success/failure cardinality가 없다.
Review publication도 506~526행에서 consumption을 기록한다고만 한다.

또한 §5.2 1243~1250행은 predecessor가 actual이 된 뒤 wave manifest를
발행하지만, §8.1 1927~1929행은 전체 `STAGE_A_SEALED_SUBJECT` 한 개를 bind하고
1978~2034행은 그 subject의 reserved slots에 wave마다 새 members를 publish한다.
Per-wave immutable subject root/manifest 또는 prefix successor 관계가 없어
각 grant가 서명한 exact subject bytes도 결정되지 않는다.

Required remediation:

- `CanonicalRootSpec` strict union과 signature-outside-payload wrapper를 정의한다.
- issue/consume/close/review-consume의 exact path, predecessor, domain과 one-use
  transition을 물리화한다.
- 각 wave가 읽는 immutable subject-prefix manifest와 다음 prefix의
  append-only successor relation을 명시하고 grant가 그 exact digest를 묶게 한다.

### PRE-P-R007-BLOCKING-002 — revocation 및 공통 consume/lease guard FSM이 닫히지 않음

§12는 `REVOKED` journal kind를 허용하지만 §13.3 4031~4057행의 exact FSM에는
revocation edge가 없다. issue/consume/lease/renew에서 current head, event
time, hard deadline, one-use, unrevoked와 renewal ordinal을 동일하게 검증하는
strict guard payload 및 failure transition도 없다.

Required remediation: 모든 stage/recovery에 대해 legal revocation edges와
공통 head/time/deadline/one-use/unrevoked guard를 하나의 strict transition
table로 정의하고 negative predecessors까지 고정한다.

### PRE-P-R007-BLOCKING-003 — capability pair가 signed literal root 집합으로 물리화되지 않음

§13.2 3943~4007행의 scope는 `roots:[literal roots]`와 “source snapshot
allowlist”, “exact26 table”, “its root” 같은 alias를 사용한다. A/B/C/recovery
각각의 exact `(operation, CanonicalRootSpec)` payload, canonical path,
publisher, signature domain이 없어 operation×root 권한을 독립 재계산할 수
없다. Crash custodian scope도 같은 결함을 가진다.

Required remediation: stage별 capability를 signed literal pair array로
materialize하고 anchor, path, object type와 allowed operation을 각 row에
직접 넣어 alias 및 cross-product 해석을 금지한다.

### PRE-P-R007-BLOCKING-004 — Stage-C/recovery receipt가 N26/T1/X1/V1과 application target을 결속하지 않음

§13.1 3883~3901행은 모든 receipt의 공통 fields만 정의한다. Stage-C/recovery
tagged extension이 없어 N26, T1, X1, V1, executor phase와 byte-equal
`ApplicationReceiptTargetSpec`을 canonical receipt bytes에 넣을 수 없다.
다른 절에서 해당 이름을 참조하는 것만으로 strict field/type이 생기지 않는다.

Required remediation: stage-discriminated receipt union을 만들고 C/recovery
branch에 위 bindings, capability pairs, target spec과 predecessor phase를
required exact fields로 추가한다.

### PRE-P-R007-BLOCKING-005 — D–G non-operative labels의 별도 future approval boundary가 없음

§3 96~108행은 D–G를 `NON_OPERATIVE_FUTURE_LABEL_ONLY`로 선언하지만 seq40
이후 successor가 이 label을 effective grant/receipt로 바꾸기 전에 필요한
별도 user approval artifact, predecessor와 signature domain을 정의하지 않는다.

Required remediation: R007의 D–G를 영구 비실행 label로 제한하고, 별도
successor plan, zero-finding reviews와 explicit user approval 없이는
materialize할 수 없는 strict boundary를 둔다.

### PRE-P-R007-BLOCKING-006 — T1/X1/StageCReviewBinding과 V1 bytes가 구성 불가능함

§15와 §17 4889~4898행은 T1/X1을 topology label 및 hash input으로만 쓴다.
§18 4914~4929행의 review binding도 strict payload, publisher, exact path와
signature wrapper가 없다. 그러므로 transaction manifest와 C receipt가
참조할 actual T1/X1/V1을 independently construct할 수 없다.

Required remediation: T1, X1, StageCReviewBinding 각각에 strict schema,
canonical path, publisher, inward-only references와 domain-separated
hash/signature 공식을 정의한다.

### PRE-P-R007-BLOCKING-007 — Stage-B exact10 result와 repeat-equality oracle이 없음

§10 3066~3092행은 환경마다 before, after, regression-A, regression-B,
after-repeat의 다섯 실행을 요구한다. 하지만 §10 3340~3386행의 subject는
결과를 opaque `FilePhysical` 배열로만 묶는다. exact10 PASS/FAIL/NOT_RUN
union, per-assertion extractor, environment aggregate와 repeat byte-equality
receipt가 없다.

Required remediation: 두 환경의 exact10 `StageBValidationResult`, raw/parsed
evidence paths, literal assertion extractors, aggregate와 AFTER-repeat equality
receipt를 strict signed schemas로 정의한다.

### PRE-P-R007-BLOCKING-008 — Stage-B 세 SourceInputManifest와 complete intent binding이 없음

§10 3193~3212행과 3244~3252행은 두 resolved input 및 regression-final input을
경로 배열/pseudocode로만 표시한다. Alias, actual Physical tagged union, set
digest, signature, publisher와 “all actual 후 command manifest 전” predecessor가
정의되지 않는다. §8의 prose는 copied inputs/projection/env/output/HOME/TMP
bind가 intent에 있다고 주장하지만 `SandboxIntent`의 complete strict schema와
literal bwrap argv binding도 제공하지 않는다.

Required remediation: 세 signed `StageBSourceInputManifest`와 Stage-B
`SandboxIntent`를 strict schema로 만들고 ordered aliases, full projection/
environment/input/output binds, executable, literal argv, digest와 publication
edge를 고정한다.

### PRE-P-R007-BLOCKING-009 — checkpoint 이후 StageCLiveRootManifest가 없음

§8.1 1927~1943행의 live-root branch는 `DirPhysical`과 generic
`source_manifest:FilePhysical`뿐이다. Exact26/U1/checkpoint 적용 뒤 627-member
set, exact26 physical identities, seq40 tail와 exclusions를 봉인하는 strict
manifest/path/publication receipt가 없다. Exact6 intents/results도 same actual
live root를 결속하지 못한다.

Required remediation: checkpoint durability 뒤 `StageCLiveRootManifest`와
physical receipt를 publish하고 모든 exact6 input/intent/result가 동일 actual
manifest를 참조하게 한다.

### PRE-P-R007-BLOCKING-010 — Exact6InputManifest와 per-command/per-assertion oracle이 없음

§16 4597~4617행은 `input_roles:[string]`, `assertions:[string]`만 두고
4749~4767행의 result도 `inputs:[FilePhysical]`와 untyped
`assertion_results`를 쓴다. Directory/live-set/runtime scalar를 어떻게
bundle로 expand하는지, expected/actual/parser/recomputation 규칙이 없다.

특히 exact6 row006은 두 command를 실행하지만 invocation당 stdout/stderr/raw
trace sibling은 하나뿐이다. Command별 raw file 또는 deterministic
framing/offset/hash가 없어서 pytest stdout과 두 번째 command의 canonical
repository-state JSON을 분리할 수 없다.

Required remediation: exact6별 signed typed input bundle, command별 raw
framing, literal extractor, expected/actual schema와 independent verifier를
정의한다.

### PRE-P-R007-BLOCKING-011 — generated scratch target 때문에 source/projection이 비결정적임

§8.2는 `.next`, `dist`, coverage, Gradle/build directories와 tsbuildinfo를
empty 또는 exact seed mountpoint로 요구한다. 그러나 §17 source exclusions는
이들을 제외하지 않는다. Frozen tree에는 `apps/android-gateway/dist`,
`apps/web/.next`, `apps/web/tsconfig.tsbuildinfo`, `apps/android/.gradle`,
`.kotlin`, `build`, `app/build`이 이미 존재한다. Copy-only snapshot과
required-empty mountpoint를 동시에 만족할 deterministic rule이 없다.

Required remediation: 모든 scratch target을 source/projection manifest에서
명시적으로 제외하거나 reviewed normalization builder, collision policy와
pre/post digest rule을 정의한다.

### PRE-P-R007-BLOCKING-012 — projection schema/type와 publication order가 모순됨

Canonical `DirManifestPhysical`은 §5.1 887~894행에서
`root/root_physical/ordered_members/set digests`를 요구한다. §10
3313~3339행의 template/environment manifests는 다른 fields와 `FilePhysical`
arrays를 쓰며 source snapshot을 바로 `DirManifestPhysical`로 취급할 canonical
conversion이 없다.

또한 §10 3193~3242행은 resolved apply/equality 뒤 projection을 만드는 흐름인
반면 3421~3424행은 candidate apply/equality publication을 BEFORE validation
뒤에 둔다. 하나의 acyclic normative order로 해석할 수 없다.

Required remediation: BEFORE/AFTER 각각 canonical type-correct manifest와
receipt를 정의하고 exactly one publication DAG만 normative하게 남긴다.

### PRE-P-R007-BLOCKING-013 — RuntimeActual producer/consumer/use-receipt chain이 불완전함

§9.6 2977~3018행의 binding은 `resolved_subject_digest`의 exact payload/domain
separator를 정의하지 않고 consumer를 local/hosted full19 row19 둘로만
제한한다. Exact6 row006과 recovery도 같은 scalar를 소비하지만 C/recovery
use receipt가 없다. `normalized_outputs`도 canonical path, producer와
normalization schema가 없다.

Required remediation: resolved-subject object/hash를 정확히 정의하고 모든
local/hosted/C/recovery consumer intent, use receipt와 normalized-output
producer/path/schema를 결속한다.

### PRE-P-R007-BLOCKING-014 — full19 semantic assertion을 immutable raw evidence에서 재계산할 수 없음

§9.5 2887~2962행은 generic `EvidenceExtractor` type만 제시하고 다수 assertion을
`REVIEWED_EXIT_CONTRACT` 및 rc0 postcondition으로 처리한다. Exact 19-row
extraction map, structured pytest summary/nodeid, JSON pointer/parser와
expected/actual type이 없다. Row03처럼 한 invocation에 세 command가 있는
경우에도 command별 raw framing/offset/hash가 없다.

Required remediation: assertion별 canonical raw source와 command framing,
extractor executable Physical, parser/normalization, expected/actual/PASS를
literal map으로 publish한다. Reviewed rc0 alone은 semantic oracle이 아니다.

### PRE-P-R007-BLOCKING-015 — row18 및 Git consumers의 transitive closure가 불완전함

§9.4 2771~2795행의 row18은 broad prefixes만 허용하고 `.git` 및 complete
transitive source/test/control reads를 누락한다. Executable roles도
`[PYTHON,PYTEST]`여서 GIT이 없다. 실제 repository의 `.git`은 94-byte gitfile로
외부 absolute gitdir
`/home/ddobagi/Code/hanium-dreamup/.git/worktrees/...`를 가리키지만 current
sandbox bind 계약은 그 target을 materialize하지 않는다. Rows16/19의 Git
reads에도 같은 physical 문제가 적용된다.

Required remediation: row18과 Git-using rows의 exact source/test/control/.git
members, external gitdir physical mapping, Git environment와 GIT executable을
complete literal consumer closure로 추가한다.

### PRE-P-R007-BLOCKING-016 — FutureSealed role의 producer와 resolution boundary가 충돌함

§9.5 2921~2938행은 row17/18 FutureSealed values의 producer를
`after-control-builder.py`로 지정한다. §10 3523~3524행은
`lane-d-final-builder.py`가 dependencies 뒤 FutureSealed roles를 해소한다고
한다. Canonical DAG에서 두 producer의 위치가 다르므로 exact bytes와 seal
시점을 결정할 수 없다.

Required remediation: 각 FutureSealed role에 exactly one producer, input
manifest, canonical output path와 single resolution barrier를 고정한다.

### PRE-P-R007-BLOCKING-017 — mixed source/projection tree를 FilePhysical-only value universe로 표현할 수 없음

§4.1.2 576~591행의 source snapshot member는 `type`을 기록하지만 symlink의
relative target과 lstat/copy semantics를 정의하지 않는다. Canonical
`DirManifestPhysical`은 §5.1 887~894행에서
`ordered_members:[FilePhysical]`만 허용하고 Stage-A/B role bindings 및
§10 3313~3336행 projection도 FilePhysical-only다.

실제 frozen source에는 `.venv/bin/python`, `.venv/lib64`,
`apps/*/node_modules/.bin/*` 등을 포함한 symlink 29개와 directory nodes가
있다. `MemberOriginMap`도 origin/lock-or-archive를 `FilePhysical`로 강제해
directory, symlink와 direct system origin을 표현하지 못한다. 따라서 strict
membership, O_NOFOLLOW anchored materialization과 link-target-preserving
projection을 동시에 만들 수 없다.

이 finding은 regular-only projection fields/order가 서로 맞지 않는
`BLOCKING-012`와 독립적이다. 그 schema를 canonical field names로 고쳐도
tree node value universe가 여전히 닫히지 않는다.

Required remediation: regular/directory/symlink/direct-origin strict tagged
unions, relative link target, lstat identity, no-escape copy/link semantics와
tree digest를 정의하고 snapshot/projection/role/origin schemas에서 동일하게
사용한다.

### PRE-P-R007-BLOCKING-018 — source-only BEFORE가 absent successor runner와 routing input을 요구함

§10 3409~3413행은 BEFORE root가 Stage-A source snapshot만 복사한다고 한다.
그 current runner는 target §2에 결속된 SHA `4f75501a...b42d`이며 실제 CLI는
첫 positional argument를 layer로 받는다. 반면 §10 3072~3075행과
3095~3115행의 `BEFORE_RUNNER_VALIDATE`는 future
`--layer/--root/--checkpoint/--control-selector/--routing-manifest` flags를
요구한다. Routing manifest 자체도 exact26 ordinal 16의 아직 absent한
NOREPLACE successor target이다.

따라서 source-only BEFORE에는 새 CLI를 해석할 bytes도 required routing file도
없다. `BLOCKING-007`의 result schema를 추가하거나 `BLOCKING-012`의 publication
order를 정리해도 이 command는 실행되지 않으므로 별도 finding이다.

Required remediation: current positional CLI/input으로 source-only BEFORE를
검증하는 별도 literal command/oracle을 만들거나 reviewed historical
runner/routing inputs를 BEFORE subject에 명시적으로 넣고 BEFORE 의미를
재정의한다.

## 4. MAJOR findings

### PRE-P-R007-MAJOR-001 — row15 executable closure가 실제 runner subprocess를 누락함

§9.5 2877행의 row15 roles는 `[BASH,TEST_LAYER_RUNNER,PYTHON]`뿐이다. Frozen
runner는 `dirname`, `mktemp`, `chmod`, `rm`, `find`, `sort` 등을 실행한다.
Future successor가 이를 제거한다는 actual reviewed source/trace도 없다.

Required remediation: final runner의 complete executable closure를 literal
roles로 넣거나 successor가 해당 subprocess를 제거했음을 sealed bytes와
trace로 증명한다.

### PRE-P-R007-MAJOR-002 — Stage-C future invocation data의 late-bound mapping이 불완전함

`RuntimeInputManifest`에는 typed `LATE_BOUND_ROLE`이 있지만 §16의 future
live-root, transaction, exact26, U1, RuntimeActual과 repository inventory는
plain strings로 남는다. Stage A에 actual이어야 하는 executable/environment/
package closure와 Stage C에서만 actual이 되는 data의 allowed phase/constructor가
완전하게 매핑되지 않는다.

Required remediation: 모든 future Stage-C invocation-data role을 typed
late-bound value로 열거하고 allowed phase/constructor를 정의하며, Stage A는
actual executable/environment/package closure만 봉인하게 한다.

### PRE-P-R007-MAJOR-003 — recovery authorization이 exact6 suffix와 application receipt를 과잉 결합함

§13.2 3992~3996행은 recovery에서 exact6와 application receipt operations를
한 scope로 허용한다. 그러나 exact6 adoption은 complete durable prefix와
never-dispatched suffix proof가 필요하고 application receipt finalize는 actual
`POSTCHECK_PASSED`가 필요하다. 두 precondition이 receipt/capability branch로
분리되지 않는다.

Required remediation: recovery capability를 exact6-suffix와
application-finalization branches로 나누고 각각 prefix/dispatch evidence와
actual POSTCHECK_PASSED predecessor를 strict fields로 요구한다.

### PRE-P-R007-MAJOR-004 — review-subject graph membership과 digest input completeness를 증명하지 않음

Candidate/Resolved subject manifests는 §5.2 1212~1214행 및 §10에 generic
`ordered_graph_nodes/edges`를 embed한다. Every bound nested Physical reference를
exactly once node로 추출하는 algorithm, deterministic node ID/edge role,
expected cardinality와 independent producer/checker가 없다. Stage-A per-wave
subject-prefix succession도 graph에 어떻게 포함되는지 정해지지 않는다.

Required remediation: 두 manifest와 per-wave prefix별 exact node/edge
extraction algorithm, ordering, producer/checker Physical, expected membership
cardinality와 digest input bytes를 고정한다.

## 5. 종합 수용 조건과 claim ceiling

R007은 R006보다 훨씬 상세하고 exact26/C0 산술도 일치한다. 그러나 detailed
intent와 correct arithmetic은 canonical bytes, executable materialization과
independent evidence oracle을 대신하지 않는다. BLOCKING 하나라도 남으면
bootstrap, Stage A request, Stage-B validation, exact26 apply 또는 Stage-C
postcheck를 시작할 수 없다.

Add-only successor의 최소 수용 조건은 다음과 같다.

1. 위 `18 BLOCKING / 4 MAJOR`를 strict schema/path/producer/DAG/fixture로
   각각 폐쇄한다.
2. Mixed tree와 generated scratch를 포함한 source→BEFORE/AFTER projection을
   dry materialization하고 link, digest, inode/no-escape negatives를 검증한다.
3. Current CLI의 BEFORE와 successor CLI의 AFTER를 별도 contract로 실제
   spawn해 exact10 결과와 repeat oracle을 만든다.
4. Full19/exact6 결과를 command-framed raw evidence에서 독립 재계산한다.
5. Successor frozen physical bytes가 formal/skeptical review 각각
   `0/0/0`을 받아야 한다.
6. 그 뒤에도 bootstrap/Stage A user approval은 별도이며 review 자체는
   authority가 아니다.

현재 claim ceiling은 target의 deferred 선언을 넘지 않는다.

```text
FORMAL_PASS=0/279
FORMAL_NOT_RUN=279/279
GATE_PASS=0/5
GATE_NOT_RUN=5/5
ACTUAL_DEVICE_EVENT=0/0
CURRENT_CHECKPOINT_SEQUENCE=39
TARGET_MUTATION_COUNT=0
APPLICATION_RECEIPT_EXISTS=false
PRODUCTION_DEPLOYMENT=0
RELEASE_STATUS=NOT_ELIGIBLE
OFFICIAL_PRE_P_VALIDATION_SUCCESS=false
```

따라서 R007은 보존 가능한 deferred draft이지만 실행 가능한 plan은 아니다.
안전한 disposition은 `CURRENT_AUTHORITY=ABSENT_DENY_ALL`을 유지한 채 add-only
successor에서 findings를 닫고 다시 동결 검수하는 것이다.
