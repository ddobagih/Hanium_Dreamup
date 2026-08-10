# PRE-P Validation Convergence Design/Build Plan R003 독립검수 R001

## 1. 검수 대상과 판정

| 항목 | exact 값 |
|---|---|
| review_id | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R003-INDEPENDENT-REVIEW-R001` |
| 검수일 | `2026-07-31` |
| target | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R003.md` |
| target SHA-256 | `909481f68fe030acacaa5b379fed42258563c3f0ceefd0ec02421a28cb784ee7` |
| target bytes | `35,847` |
| target lines | `780` |
| target type | `regular file, non-symlink, nlink=1` |
| target status | `NON_EFFECTIVE_PLAN_ONLY` |
| verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY` |
| findings | `BLOCKING=4 / MAJOR=2 / MINOR=0` |
| review authority | `NONE` |

검수 시작과 종료 시 target의 SHA-256, bytes와 lines가 위 값과 같음을
확인했다. 이 review는 target을 수정하지 않으며, candidate 또는 external
environment build, authority resolve, active apply, checkpoint/control 전환,
R007 successor 또는 P 작업 권한을 만들지 않는다.

```text
VERDICT=REJECTED_NON_EFFECTIVE_PLAN_ONLY
BLOCKING=4
MAJOR=2
MINOR=0
TARGET_UNCHANGED=true
R003_REMAINS_NON_EFFECTIVE_PLAN_ONLY=true
PLAN_EXECUTION_AUTHORIZED=false
STAGE_A_AUTHORIZED=false
STAGE_B_AUTHORIZED=false
STAGE_C_AUTHORIZED=false
STAGE_D_AUTHORIZED=false
STAGE_E_AUTHORIZED=false
STAGE_F_AUTHORIZED=false
STAGE_G_AUTHORIZED=false
EXTERNAL_ENV_WRITE_AUTHORIZED=false
ACTIVE_WRITE_AUTHORIZED=false
CHECKPOINT_WRITE_AUTHORIZED=false
R007_SUCCESSOR_BUILD_AUTHORIZED=false
P17_BUILD_AUTHORIZED=false
P_APPLY_AUTHORIZED=false
```

## 2. 검수 범위

Target 전체 780행과 현재 read-only source 사실을 다음 축으로 대조했다.

- R002 independent review의 `BLOCKING=4 / MAJOR=2` remediation 반영 여부
- old v2.4/seq39 historical routing과 v2.4.1 current direct index 17
- single runner의 BEFORE seq39 / AFTER seq40 conditional routing
- external environment exact root, provisioning, seal, lease와 lifetime
- Phase-0 contract의 lane-before freeze 및 미래 산출물 의존 여부
- Stage A~G 권한 분리, post-authority resolution과 exact apply
- Stage-A immutable aggregate와 Stage-B resolved output namespace
- v2.4 raw archive, compound supersession/activation와 seq40 history
- after-control/apply 전용 builders와 두 독립 build
- synthetic runtime-pack, broad host bind 금지와 실제 full19 소비
- event, checkpoint, managed snapshot, target set과 envelope의 hash DAG
- claim ceiling, active-root write 0과 P17 deferred

## 3. BLOCKING findings

### PRE-P-R003-BLOCKING-001 — STAGE-A FROZEN NAMESPACE MUTATED BY STAGE B

#### 근거

Target §3과 §5는 Stage A의 exact candidate allowed root를 다음 하나로
고정한다.

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r003/
```

이 root의 `candidate-archive-manifest.json`은 exact member
path/hash/bytes/mode/role/order와 recursive digest를 갖고, extra/missing
member를 FAIL로 한다. §4의 Stage-A aggregate와 independent review는 이
frozen member set과 모든 upstream review를 결속한다.

그러나 §10의 Stage B는 다음 subtree를 같은 root 아래에 새로 만든다.

```text
pre-p-validation-convergence-candidate-r003/
  authority-resolved/<challenge-id>/
```

따라서 Stage B가 첫 directory member를 생성하는 순간 Stage-A archive의
recursive path/content digest와 exact member set이 달라진다. Stage B
authority는 Stage-A aggregate hash/review를 input으로 결속하지만, Stage C
시점의 물리 root는 그 review가 검수한 root와 더 이상 같지 않다.

이는 add-only write라는 사실로 해결되지 않는다. Target 자체가 extra member를
FAIL로 하고 recursive digest를 claim하기 때문이다. Stage-A frozen source와
Stage-B resolved evidence를 동일 mutable namespace에 두면 Stage C가 두
세대의 immutable identity를 동시에 재검증할 수 없다.

#### Required remediation

- Stage-B resolved output을 Stage-A candidate root의 child가 아닌 exact sibling
  root로 분리하고 fresh Stage-B authority에 그 canonical root를 직접
  결속한다.
- 또는 Stage-A archive hash domain을 immutable exact subtrees에만 한정하고,
  future resolved namespace는 Stage-A root recursive digest에서 명시적으로
  분리된 별도 archive/authority domain으로 설계한다.
- 어느 방식을 택하든 Stage-A manifest의 extra-member 규칙, aggregate hash
  domain, Stage-B allowed root와 Stage-C source bindings가 같은 경계를
  표현해야 한다.
- Stage B 전후 Stage-A root의 root FD, dev/inode/mode와 recursive
  path/content digest가 byte-exact unchanged임을 검사한다.
- Stage-B output에는 자체 NOREPLACE archive manifest, independent review와
  Stage-A immutable source binding을 둔다.

### PRE-P-R003-BLOCKING-002 — RESOLVED FULL19/DIRECT-TEST EXECUTION ABSENT

#### 근거

Target §8은 다음 v2.4.1 non-discovery direct exact3의 current consumer를
full19 index 17로만 지정한다.

```text
tests/walksafe_project_continuation_v2_4_1_successor_20260731.py
tests/walksafe_goal_graph_v2_4_1_successor_20260731.py
tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py
```

Lane D FINAL에 예약된 유일한 full19 raw path는
`raw/full19-template-run.json`이다. D FINAL은 Stage A에서 unresolved
after-control보다 먼저 생성되고 synthetic runtime-pack보다도 먼저다. 따라서
이 template run은 Stage-B authority-bound seq40 event/checkpoint와 최종
runtime-pack을 사용한 AFTER acceptance가 될 수 없다.

§11이 Stage-B resolved bytes로 두 final projection에서 실제 실행한다고
열거한 순서는 다음뿐이다.

```text
LIVE Quick2
BEFORE seq39 Quick2
AFTER seq40 v2.4.1 Quick2
regression A
regression B
AFTER Quick2 repeat
active pre/post identity
```

ordered full19 1~19 actual run이 없다. Stage C도 checkpoint 뒤
v2.4.1 Quick2/postcheck만 명시하며 full19를 실행한다고 하지 않는다. Stage-B
authority-resolved tree에도 두 full19 run의 durable command/result/raw receipt
경로가 없다.

그 결과 다음을 실제 rc0으로 증명할 acceptance 경로가 없다.

- index 17의 v2.4.1 continuation/Goal/seq40 direct exact3
- index 3·4·15~19의 successor semantics
- synthetic runtime-pack이 index 5~14 toolchain command를 실제 지원한다는 사실
- exact19 order/digest, skip/NOT_RUN 0과 두 projection run 동일성

full19 successor contract를 생성하는 것과 그 contract를 resolved AFTER
world에서 실행하는 것은 서로 다른 증거다.

#### Required remediation

- Stage-B 두 final projection 각각의 validation sequence에 ordered full19
  actual run을 추가한다.
- exact index 1~19마다 argv token array, cwd, env/interpreter, input hashes,
  timeout/output cap, rc/result/skip/NOT_RUN와 stdout/stderr raw paths를
  authority-resolved evidence tree에 보존한다.
- index 17에서 v2.4.1 direct exact3을 실제 실행하고 old v2.4 current test
  count가 0임을 함께 검증한다.
- index 5~14도 synthetic runtime-pack만 사용해 실행하며 unmanifested host
  access가 0임을 observed deny trace로 확인한다.
- full19가 regression A/B와 별개면 명시적 순서를 고정한다. Regression A/B가
  full19 일부를 대신한다면 exact consumer mapping과 중복/누락 방지 digest를
  contract에 넣는다.
- Stage C postcheck가 Quick2만 수행하는지 full19까지 수행하는지 명시하고,
  필요한 최소 postcommit 검증 범위를 위험 근거와 함께 고정한다.

### PRE-P-R003-BLOCKING-003 — SINGLE RUNNER BEFORE/AFTER SELECTOR UNDEFINED

#### 근거

Target의 active CAS target은
`scripts/run_walksafe_test_layers_20260711.sh` 한 파일이다. §8은
`routing-manifest-before-seq40.json`과
`routing-manifest-after-seq40.json`을 candidate evidence로 만들고 다음 두
상태를 요구한다.

- checkpoint-last 전 seq39/v2.4: old v2.4 current routing
- checkpoint-last 뒤 seq40/v2.4.1: old v2.4 exact3 historical,
  v2.4.1 direct exact3 current

그러나 단일 runner가 어떤 root/checkpoint를 어떤 argv로 읽고 두 상태 중
하나를 선택하는지 정의하지 않는다. Exact target universe에는 routing
manifest 두 파일이 없으므로 apply 후 runner가 external manifest를 읽는
설계라면 파일이 존재하지 않는다. Manifest를 runner에 embed하는
설계라면 embedded path/nodeid/hash와 선택 알고리즘이 명시되지 않았다.

특히 checkpoint-last transaction에서 runner는 checkpoint보다 먼저 새 bytes로
promote된다. 이 exact prefix 상태에서는 새 runner가 seq39 BEFORE route를
선택해야 하고 checkpoint CAS 직후부터 동일 bytes가 seq40 AFTER route를
선택해야 한다. 다음 상태에 대한 fail-closed 규칙도 없다.

- missing 또는 malformed checkpoint
- sequence가 39/40 이외인 경우
- seq39와 v2.4.1 또는 seq40과 v2.4 package identity 조합
- wrong tail/event/schema/manifest
- explicit staged root와 live root 혼용
- partial target promotion 중 source/checkpoint drift

현재 active runner는 static arrays를 사용한다. R003 successor가 conditional
routing을 새로 구현해야 하지만 target은 그 semantic delta의 exact selector
contract와 negative execution을 제공하지 않는다.

#### Required remediation

- Runner의 authoritative state selector를 exact 알고리즘으로 정의한다.
  Explicit `--root`/checkpoint path 또는 동등한 typed input을 사용하고
  implicit current working directory나 active absolute path fallback을
  금지한다.
- 허용 상태를 seq39/v2.4 BEFORE와 seq40/v2.4.1 AFTER exact2로 한정하고,
  sequence/package/event/tail/static manifest를 함께 검증한 뒤 route를
  선택한다. 다른 모든 조합은 validation/execution 전 rc2다.
- Routing manifests를 final targets로 promote하거나, 두 manifest의 exact
  bytes/hash/nodeid arrays를 runner 내부에 embed하고 builder/review가 이를
  독립 재계산하도록 한다.
- BEFORE, AFTER, partial-prefix, missing/malformed, wrong package/sequence/event,
  stale test reintroduction과 staged-root/live-root 혼용 negative tests를
  추가한다.
- Runner의 `unit`, `all`, registry validation과 full19 index 15/17/18이
  동일 selector result/hash를 소비하도록 한다.

### PRE-P-R003-BLOCKING-004 — AUTHORITY RECEIPT/LEASE VALIDITY NOT DURABLY CLOSED

#### 근거

Target은 Stage-A authority receipt의 required fields와 Stage-B receipt의
aggregate/source/environment/runtime-pack/challenge/nonce/expiry bindings를
설명한다. Seq40 activation subevent도 Stage-B authority
receipt/challenge/hash를 결속한다.

그러나 Stage-A와 Stage-B authority receipt의 exact physical path,
NOREPLACE/file-type policy, canonical serialization, signature/verifier와
custody가 §5 expected paths 및 Stage-B authority-resolved tree에 없다.
Resolved event가 receipt path/hash를 참조하더라도 Stage C와 이후 history
checker가 읽어 재검증할 durable artifact 위치가 정해지지 않았다.

Stage-B receipt에는 expiry가 있지만 다음 경계에서 계속 유효해야 한다는
검사가 없다.

- resolver build-01/02 시작과 종료
- resolved independent review 시작과 종료
- Stage-C authority 발급
- transaction 시작, each CAS와 checkpoint CAS
- postcheck와 application receipt 생성

External environment도 §7에서 `Stage C 직전까지` recursive manifest,
realpath/dev/inode/mode를 재검증한다고만 한다. Stage-C transaction과
postcheck 도중 같은-owner host process가 mode/content를 바꾸거나 lease가
만료되는 경우의 pre/post 검증이 닫히지 않았다. Read-only bwrap bind는
sandbox 내부 write를 막지만 bind source의 외부 변경까지 snapshot으로
고정하지 않는다.

따라서 expired Stage-B semantic authorization 또는 drift한 hosted-cpu
environment로 Stage C apply/postcheck가 진행돼도 target 문언상 이를 반드시
막는 경계가 없다.

#### Required remediation

- Stage A/B/C authority receipt 각각의 exact add-only path, canonical schema,
  signer/verifier identity, signature/hash/bytes, regular/non-symlink/NOREPLACE
  policy를 예약한다.
- Seq40 event, apply envelope, Stage-C authority와 application receipt가
  필요한 predecessor authority receipts를 physical path/hash/bytes로
  재생 가능하게 결속한다.
- Stage-B expiry와 external environment lease를 resolver pre/post, resolved
  review pre/post, Stage-C issuance, transaction start/each boundary,
  checkpoint, postcheck와 receipt 생성 전후에 검증한다.
- expiry, nonce/challenge mismatch, receipt replacement, environment
  manifest/inode/mode/content drift는 active write 0으로 중단하고 fresh
  challenge → re-resolve → two-build → independent review를 다시 수행한다.
- Stage-C 도중 expiry 또는 checkpoint-after failure에 대한 receipt-only
  recovery authority/validity 규칙을 별도로 정의한다.

## 4. MAJOR findings

### PRE-P-R003-MAJOR-001 — PHASE-0 REGRESSION CONTRACT CANNOT FINAL-PIN FUTURE SUITE MEMBERS

#### 근거

Target §6은 어떤 lane보다 먼저 regression A/B의 다음 값을 완전히 열거하고
review-freeze한다.

- ordered exact argv와 cwd
- ordered test file/nodeid array와 per-file hash
- discovery digest와 current/historical routing class

그 뒤 Lane B가 single runner와 successor preflight helper를 만들고 Lane C가
gateway successor helper를 만든다. §8은 이 두 future files를 runner
`unit`과 `all`의 current consumers로 지정한다.

```text
tests/walksafe_test_database_preflight_successor_20260731_r003.py
tests/walksafe_android_gateway_public_routes_successor_20260731_r003.py
```

Regression A/B가 changed runner의 `unit` 또는 `all`을 실행한다면 ordered suite
members, nodeids, per-file hashes와 discovery/routing result가 lane output에
의존한다. Lane 전 Phase-0 contract는 아직 존재하지 않는 generated file의
observed bytes/hash/nodeids를 final-pin할 수 없다. 반대로 regression A/B가
runner를 사용하지 않으면 changed runner와 current supplemental semantics를
회귀 acceptance가 검증하지 않는다.

Target은 Phase-0 contract builder가 future lane bytes를 산출할 exact
content source/template까지 소유하거나, lane 뒤 successor-resolved regression
contract를 다시 만드는 구조를 정의하지 않는다. 단순히 future path와 예상
hash를 미리 적는 것은 실제 lane output과의 provenance를 닫지 않는다.

#### Required remediation

- Phase-0에서는 predecessor suite identity, 이전 observation과 허용되는 exact
  transformation rule만 immutable하게 freeze한다.
- A/B/C/control/D lane freeze 뒤 successor runner/helpers의 actual
  path/hash/nodeids를 입력으로 별도 successor-resolved regression contract를
  두 independent builds에서 생성·review하고 Stage-B validation 전에
  final-pin한다.
- 또는 Phase-0 builder가 future file bytes를 생성하는 authoritative
  content/template source, generator hash와 expected nodeid extraction까지
  직접 소유하며 lane builders가 그 frozen bytes를 NOREPLACE copy만 하도록
  명시한다.
- Predecessor identity와 successor execution identity를 별도 digest로
  유지하고 허용 transformation 외 delta를 FAIL로 한다.

### PRE-P-R003-MAJOR-002 — SEQ40 MANAGED/TARGET HASH DEPENDENCY DAG UNDEFINED

#### 근거

Target §10은 Stage-B unresolved slots에 다음을 포함한다.

```text
seq40 event hash
after checkpoint hash
exact target hashes
apply transaction identity
```

같은 절은 seq40 event가 exact transition set, managed count/path/content,
`session_handoff.changed_files`와 source snapshot을 검증한다고 한다. §12의
exact target universe에는 다음이 모두 포함된다.

- `transition-event-seq40.json`
- v2.4.1 manifests와 supersession artifacts
- checkpoint

Exact-target transition set은 이 target들의 final after hash/bytes를 가져야
한다. Event가 managed content-set 또는 exact transition set을 통해 자신의
final hash가 포함된 domain을 결속하면
`event → content/target-set → event` self-hash cycle이 생긴다. Checkpoint가
event hash를 담고 target set이 checkpoint hash를 담는 구조도 reverse
reference가 추가되면 같은 문제가 생긴다.

반대로 event나 checkpoint를 managed/source hash domain에서 제외하거나
canonical blanking을 사용한다면 현재 managed snapshot과 다른 계산법이므로
그 exact exclusion/domain을 checker와 builder에 명시해야 한다. Target은
각 digest의 member domain, self-exclusion, canonicalization과 topological
build order를 정의하지 않는다. Build-01/02가 같은 bytes를 만들었다는 사실은
그 bytes가 순환 없는 올바른 hash domain을 사용했다는 증명이 아니다.

#### Required remediation

- Event, source snapshot, managed content-set, checkpoint, exact-target set,
  apply envelope와 receipt 각각의 exact hash member domain을 표로 정의한다.
- Self member와 reverse edge를 금지하고 acyclic topological order를 고정한다.
  예시는 다음과 같다.

```text
frozen source + finalized non-self targets
→ source/managed snapshot with explicit exclusions
→ seq40 event
→ after checkpoint
→ exact-target transition set
→ transaction/apply envelope
```

- Event/checkpoint가 target-set 전체 hash를 역참조하지 않도록 하고, 필요한
  경우 path/order digest와 final content digest를 분리한다.
- Canonical blanking을 사용할 경우 blanked fields, encoding, LF와 hash
  domain을 exact schema로 정의한다.
- Builder가 dependency graph cycle/self-membership/reverse-edge를 거부하고,
  direct negative tests가 event/checkpoint/target-set의 self-hash 재유입을
  검출하도록 한다.

## 5. 확인된 R003 보완과 claim ceiling

R003에는 R002 findings를 겨냥한 다음 구조적 보완이 존재한다.

- old v2.4/seq39 exact3을 AFTER historical로 옮기고 v2.4.1 direct exact3을
  full19 index 17 current consumer로 열거한다.
- external environment exact root, NOREPLACE publish, recursive manifest,
  read-only seal과 hosted-cpu 선택을 정의한다.
- Phase-0 contracts를 lane보다 먼저 만들고 review하도록 순서를 옮긴다.
- PRE-P와 P에 대해 build, resolve와 exact apply를 A~G 일곱 authority로
  분리한다.
- v2.4 raw archive, supersession record/anchor와 compound seq40 event를
  추가한다.
- after-control/apply에 전용 builders, manifests/raw와 two-build equality를
  추가한다.
- synthetic runtime-pack을 도입하고 host `/usr`, `/etc`, `/lib`, `/bin`
  broad bind를 금지한다.

이 보완은 위 six findings를 상쇄하지 않는다. 특히 target의 §14 remediation
matrix가 자체적으로 `closure`라고 적은 것은 독립검수 findings-zero 판정을
대체하지 않는다.

현재 claim ceiling은 다음과 같다.

```text
R002_REMEDIATION_DESIGN_PRESENT=true
R002_REMEDIATION_PROVEN_CLOSED=false
R003_FINDINGS_ZERO=false
R003_EXECUTABLE=false
STAGE_A_QUESTION_ALLOWED=false
CANDIDATE_BUILD_ALLOWED=false
EXTERNAL_ENV_PROVISION_ALLOWED=false
AUTHORITY_RESOLUTION_ALLOWED=false
FULL19_SUCCESSOR_VALIDATED=false
SEQ40_ACTIVATION_ALLOWED=false
PRE_P_APPLY_ALLOWED=false
R007_SUCCESSOR_ALLOWED=false
P17_ALLOWED=false
PRODUCT_OR_CONTROL_CREDIT_ALLOWED=false
```

R003가 `NON_EFFECTIVE_PLAN_ONLY`이고 review findings가 nonzero이므로 target이
계획한 최종 handoff의 Stage-A authority 질문 조건도 성립하지 않는다.

## 6. 최종 경계

이 review의 verdict는 `REJECTED_NON_EFFECTIVE_PLAN_ONLY`다. Target R003은
add-only successor plan으로 위 `BLOCKING=4 / MAJOR=2`를 모두 닫고, 그
successor 자체가 exact byte-fixed independent review에서 findings
`0/0/0`을 얻기 전에는 실행·authority 요청 근거로 사용할 수 없다.

이 review는 다음 권한이나 완료 credit을 만들지 않는다.

- Phase-0 contract, Lane A/B/C/D/control 또는 aggregate build
- external environment provisioning, retention 연장 또는 cleanup
- synthetic runtime-pack discovery/build
- Stage-B authority resolution이나 resolved output review
- active lock, runner, tests, validators와 v2.4.1 files 전환
- seq40 event/checkpoint, compound supersession/activation
- Stage-C atomic apply/recovery 또는 application receipt
- R007 successor design/build, P candidate/resolve/apply
- canonical/product/artifact/formal/device/gate/release 변경

공식 수치는 계속 artifact closed-equivalent `126/257`, formal
`0/279 PASS`, actual-device/real-event `0/0`, release gate `0/5`,
release `NOT_ELIGIBLE`로 유지한다.
