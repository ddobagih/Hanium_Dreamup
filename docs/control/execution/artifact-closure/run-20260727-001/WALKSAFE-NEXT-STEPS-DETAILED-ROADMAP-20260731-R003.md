# WalkSafe 다음 단계 상세 로드맵 20260731 R003

- 문서 ID:
  `WS-WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R003`
- 작성일:
  `2026-07-31`
- 상태:
  `NONCANONICAL_PLAN_ONLY / NOT_EXECUTABLE / ROADMAP_REVIEW_PENDING`
- 현재 권한:
  `ABSENT_DENY_ALL`
- 현재 control:
  v2.4 `ACTIVE`, sequence `39`, canonical Gap/Backlog `r021/r021`
- 공식 성과 delta:
  `PRODUCT/CHECKPOINT/CANONICAL/GOAL/ARTIFACT/FORMAL/DEVICE_EVENT/GATE/PRODUCTION/RELEASE=0`

## 0. 목적과 R003의 효력

이 문서는 다음 Codex가 WalkSafe를 안전하게 이어가기 위한 상세 비실행
로드맵이다. 즉시 가능한 실제 작업은 rejected PRE-P R007의
`18 BLOCKING + 4 MAJOR`를 닫을 add-only successor 설계를 준비하는
것뿐이다.

R003는 R002를 수정하지 않는다. R002와 두 review를 history로 보존하고
그 review의 required correction을 이 문서에서 닫는다.

| R002 review | 판정 | SHA-256 |
|---|---|---|
| formal `ROADMAP_REVIEW` | `3 BLOCKING / 1 MAJOR / 0 MINOR` | `d9f2004ed8d9f430154d3bd1e64fa11c03beb1b83ac1e01f683d9cc0b1429547` |
| skeptical `ROADMAP_REVIEW` | `7 BLOCKING / 4 MAJOR / 0 MINOR` | `c73dcac1ca7c31349f5fe40f0f8e6c0d9936467268dd46744264cec76cd8c6bb` |

R003가 고치는 핵심은 다음과 같다.

- successor보다 먼저 작성·검수할 `G0_SPEC_ONLY` 단계를 추가
- gate의 예상값인 `GateExecutionSpec`과 실행 뒤 actual
  `GateExecutionResult`를 분리
- B16↔M04를 `M04a→B16→M04b`로 단방향화
- 모든 finding의 `accountable_owner_phase`를 정확히 하나로 고정하고
  contributor/milestone과 분리
- H1 synthetic Stage-C fixture와 H2 actual Stage-C object를 다른 tagged
  type으로 분리
- 기술 인계서와 V0 사이의 권한 충돌을
  `AUTHORITY_BOUNDARY_DECISION` 없이는 진행 불가로 고정
- G6→P7→G7→ready의 유일 경로와 PASS 상태 전파를 명시
- Stage D에도 exact one-use approval을 요구
- reviewer actor 독립성, evidence 재실행과 H1 write allowlist를 완성
- 공식 artifact 표현을 `closed-equivalent=126/257`로 복원

이 R003와 그 review가 findings-zero여도 다음은 생기지 않는다.

```text
R007_FINDINGS_CLOSED=false
PRE_P_CLOSURE_SUCCESSOR_EXISTS=false
PRE_P_SUCCESSOR_READY=false
AUTHORITY_BOUNDARY_DECISION_EXISTS=false
CONSTRUCTIVE_FIXTURE_AUTHORIZED=false
JOURNAL_OR_STAGE_A_TO_G_AUTHORIZED=false
CHECKPOINT_OR_CANONICAL_WRITE_AUTHORIZED=false
PRODUCT_OR_RELEASE_AUTHORIZED=false
OFFICIAL_CREDIT_DELTA=0
```

## 1. 보호 입력과 history-only 경계

### 1.1 immutable input manifest

| 입력 | SHA-256 | 처분 |
|---|---|---|
| 기술 인계서 | `f5adb13001ba61bd44998415ff4af0e267dcec4bc7515a18ff8ad0a73830b6bb` | 읽기 전용 |
| R007 target | `02766312b1bbb00eb05e2789fe4d054cbf749407e6c5bd26dce62250e6dd98ef` | rejected history |
| R007 formal review | `c2e6c226204d977db9706a58935125b472c829e5790c91ef83e3c866dde51269` | `18/4/0`, 읽기 전용 |
| R007 skeptical review | `0bc4fb67ab80acaae69ae8024b7f39156bc5c98d6f6df2e9bfa508fb2c91fe44` | `18/4/0`, 읽기 전용 |
| R001 roadmap | `77672dc0ecc592b2225acd3c247a91f54d4f2d33fb03126121c5b3d54d15e6ef` | rejected history |
| R001 formal roadmap review | `bdaf1771fcd9974a5c5e4728ee45d90759b86a08ff8934136e94e9d18e9c7ffa` | `3/1/0`, 읽기 전용 |
| R001 skeptical roadmap review | `9c6c8267c9459a3090c9b78bf0ba9338e4d7f5da44ad013d8e6036b258a4b12c` | `6/5/0`, 읽기 전용 |
| R002 roadmap | `a8a4a11505a3652fff051e78aafa56a2f15828b398e80b24d0280b3f31bdc77e` | rejected history |
| R002 formal roadmap review | `d9f2004ed8d9f430154d3bd1e64fa11c03beb1b83ac1e01f683d9cc0b1429547` | `3/1/0`, 읽기 전용 |
| R002 skeptical roadmap review | `c73dcac1ca7c31349f5fe40f0f8e6c0d9936467268dd46744264cec76cd8c6bb` | `7/4/0`, 읽기 전용 |
| v2.4 checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | seq39, 읽기 전용 |
| static v2.4 manifest | `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07` | 읽기 전용 |
| canonical Gap r021 | `f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a` | 읽기 전용 |
| canonical Backlog r021 | `bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0` | 읽기 전용 |
| DOC-01 | `a0c993257c1b80211c2e8e4db5aa464708ac4aba9d47ed9b434eda277ee6547f` | 읽기 전용 |
| DOC-05 | `cea8b58fc5afa527614522481df56d69618c6fd1015c10b63d6cc8b6e0678c67` | 읽기 전용 |
| runner | `4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d` | 읽기 전용 |

### 1.2 실행 금지

- Master 인계서 old S1 exact seq39→40/P17
- PRE-P R001~R007의 명령, 승인 문구, nonce와 receipt
- continuation R004~R009 실행 블록
- stale FP-048 포인터
- 기존 M15
- 과거 exact4 receipt를 current exact5 Gateway 증거로 재해석
- 과거 approval/review/receipt를 다른 subject class에 재사용

## 2. review·authority subject type

| tagged subject | 대상 | 최대 효력 |
|---|---|---|
| `ROADMAP_REVIEW` | 이 R003 exact SHA | 인계 로드맵 품질 판정 |
| `G0_SPEC_REVIEW` | 미래 G0 spec exact SHA | read-only 기준선 검사 설계 판정 |
| `SUCCESSOR_PLAN_REVIEW` | 미래 PRE-P successor exact SHA | successor 설계 판정 |
| `AUTHORITY_BRIDGE_REVIEW` | 미래 V0 bridge exact SHA | bridge 설계 판정 |
| `AUTHORITY_BOUNDARY_DECISION` | handoff+successor+bridge exact SHA | V1 한 attempt 허용 또는 거부 |
| `CONSTRUCTIVE_EVIDENCE_REVIEW` | successor+evidence+G6 SHA | evidence 품질 판정 |

서로 다른 tagged subject는 서로를 만족시키지 않는다.

```text
ROADMAP_REVIEW cannot satisfy G0_SPEC_REVIEW
ROADMAP_REVIEW cannot satisfy SUCCESSOR_PLAN_REVIEW
G0_SPEC_REVIEW cannot authorize V0/V1
SUCCESSOR_PLAN_REVIEW cannot authorize V0/V1 or H2
AUTHORITY_BRIDGE_REVIEW cannot replace AUTHORITY_BOUNDARY_DECISION
CONSTRUCTIVE_EVIDENCE_REVIEW cannot authorize H2
```

`accepted successor`라는 모호한 상태명은 쓰지 않는다. 미래 준비 여부는
§12의 exact predicate 하나로만 계산한다.

## 3. 전체 단계와 normative DAG

### 3.1 지금부터 successor 작성 전까지

```text
R003 frozen
→ formal ROADMAP_REVIEW
→ skeptical ROADMAP_REVIEW
→ both same-R003-SHA findings 0/0/0
→ Z0 add-only G0_SPEC_ONLY 작성
→ same-G0-spec-SHA formal/skeptical G0_SPEC_REVIEW 0/0/0
→ G0 reviewed spec read-only 실행
→ G0 PASS
→ PRE_P_CLOSURE_SUCCESSOR 첫 revision 작성 가능
```

G0 spec은 successor가 아니다. 이를 successor보다 먼저 만들 수 있으므로
R002의 자기선행 순환이 없다.

### 3.2 successor 설계와 evidence

```text
G0 PASS
→ P0 protected/mutable inventory freeze
→ P1 schema work → G1-SPEC PASS
→ P2 authority work → G2-SPEC PASS
→ P3 graph work → G3-SPEC PASS
→ P4 raw-oracle/debt work → G4-SPEC PASS
→ P5 Stage-C fixture contract work → G5-SPEC PASS
→ P6 integrated closure specification
→ V0 bridge design + two reviews
→ AUTHORITY_BOUNDARY_DECISION
→ if ALLOW_ONE_ISOLATED_ATTEMPT: V1
→ G1-EVIDENCE PASS
→ G2-EVIDENCE PASS
→ G3-EVIDENCE PASS
→ G4-EVIDENCE PASS
→ G5-EVIDENCE PASS
→ G6 PASS
→ four class-specific P7 reviews
→ G7 PASS
→ PRE_P_SUCCESSOR_READY predicate evaluation
```

어느 predecessor든 `NOT_RUN` 또는 `FAIL`이면 다음 단계는 `NOT_RUN`이다.
실제 실행 후 assertion이 틀렸을 때만 `FAIL`이다.

```text
predecessor NOT_RUN → current NOT_RUN
predecessor FAIL → current NOT_RUN
current not executed → NOT_RUN
current executed and assertion failed → FAIL
current executed and every assertion passed → PASS
```

## 4. Z0 `G0_SPEC_ONLY`

### 4.1 목적

G0가 미래 successor의 명령을 필요로 하지 않도록 별도 add-only
`G0ExecutionSpec`을 먼저 작성한다. Z0는 plan-only 문서 작업이며 제품,
checkpoint, canonical, journal과 candidate를 쓰지 않는다.

### 4.2 G0 spec 필수 field

```text
subject_type = G0_SPEC_ONLY
protected_path_manifest[]
expected_sha256[]
expected_bytes[]
expected_mode[]
expected_file_type[]
expected_nlink[]
absence_role_definition[]
authority_absence_definition[]
mutation_detection_basis
verifier_physical
verifier_sha256
literal_argv[]
literal_environment[]
read_set[]
write_set = one new add-only raw-output directory
expected_exit = 0
expected_result_schema
expected_result_exact_values
raw_stdout_path
raw_stderr_path
result_path
receipt_path
```

protected path에는 §1.1 전부를 포함한다. absence role은 active v2.5, canonical
r022, P candidate와 M candidate가 무엇인지 path/ID/schema로 폐쇄적으로
정의한다. authority absence는 journal/bootstrap/Stage A~G/P17/checkpoint/
canonical/product authority role 전부를 폐쇄적으로 열거한다.

두 G0 spec reviewer는 path/role exhaustiveness와 read-only 성질을 확인한다.
spec이나 두 review 중 하나가 없거나 findings-zero가 아니면:

```text
G0 = NOT_RUN
successor authoring = NOT_STARTED
```

### 4.3 G0 result

`G0ExecutionSpec`에는 actual output SHA를 쓰지 않는다. 실행 뒤 별도
`G0ExecutionResult`가 다음을 결속한다.

- exact G0 spec SHA와 두 G0 spec review SHA
- 실제 argv/environment
- raw stdout/stderr SHA
- protected stat/hash result
- absence/authority/mutation result
- Quick2 raw output와 `PASS/PASS`
- exact exit와 semantic `PASS/FAIL`

G0 PASS exact predicate:

```text
all protected stat/hash = expected
Quick2 = PASS/PASS
checkpoint sequence = 39
canonical Gap/Backlog = r021/r021
active v2.5/r022/P/M candidate = absent
current authority = ABSENT_DENY_ALL
protected mutation = 0
G0 semantic failures = 0
```

## 5. Gate spec/result 분리와 상태 전파

### 5.1 `GateExecutionSpec`

실행 전에 freeze하며 actual result SHA를 포함하지 않는다.

```text
gate_id
subject_sha
predecessor_spec_sha[]
predecessor_required_status = PASS
input_manifest_sha[]
verifier_physical + verifier_sha
literal_argv[]
literal_environment[]
read_set[]
write_set[]
expected_exit
expected_output_path[]
expected_output_schema_sha[]
expected_semantic_predicate
failure_state_contract
```

### 5.2 `GateExecutionResult`

실행 뒤 add-only로 발행한다.

```text
gate_spec_sha
tested_successor_sha
attempt_id
authority_predecessor_sha[]
actual_exit
raw_stdout_sha
raw_stderr_sha
actual_output_sha[]
semantic_assertion_result[]
status = NOT_RUN | FAIL | PASS
receipt_path + receipt_sha
```

spec이 미래 result를 참조하거나 result가 자기 자신을 선행 참조하면
거부한다.

### 5.3 spec gate와 evidence gate

| Gate | 설계 단계 | V1 actual evidence |
|---|---|---|
| G1 | `G1-SPEC`: physical/type/projection spec 완전성 | `G1-EVIDENCE`: projection materialization과 repeat equality |
| G2 | `G2-SPEC`: authority FSM/capability/recovery spec 완전성 | `G2-EVIDENCE`: replay/revocation/crash negative matrix |
| G3 | `G3-SPEC`: producer/graph/digest spec 완전성 | `G3-EVIDENCE`: graph/digest independent equality |
| G4 | `G4-SPEC`: Stage-B/raw oracle/four debt spec 완전성 | `G4-EVIDENCE`: exact10/full19/source-only/debt evidence |
| G5 | `G5-SPEC`: synthetic Stage-C/exact6/receipt fixture spec | `G5-EVIDENCE`: synthetic exact6와 receipt byte closure |

P1~P5에서는 `*-SPEC`만 PASS할 수 있다. V1 전 모든 `*-EVIDENCE`는
`NOT_RUN`이다.

## 6. phase, 단일 owner와 milestone

### 6.1 accountable owner

각 finding의 `accountable_owner_phase`는 정확히 하나다.

| Phase | accountable finding |
|---|---|
| P1 | B11, B12, B17, M02 |
| P2 | B01, B02, B03, B05, M03 |
| P3 | B16, M04 |
| P4 | B07, B08, B13, B14, B15, B18, M01 |
| P5 | B04, B06, B09, B10 |

산술:

```text
P1 = 3 BLOCKING + 1 MAJOR
P2 = 4 BLOCKING + 1 MAJOR
P3 = 1 BLOCKING + 1 MAJOR
P4 = 6 BLOCKING + 1 MAJOR
P5 = 4 BLOCKING
TOTAL = 18 BLOCKING + 4 MAJOR
```

### 6.2 contributor와 ordered milestone

accountable owner는 바뀌지 않지만 다음 foundation을 앞 단계가 생산할 수
있다.

| finding | accountable owner | contributor/milestone |
|---|---|---|
| B06 | P5 | P3 `B06a` topology → P5 `B06b` synthetic instance verification |
| B09 | P5 | P5 `B09a` contract/fixture root → `B09b` same-root fixture integration |
| B10 | P5 | P3 `B10a` exact6 schema foundation → P5 `B10b` synthetic construction |
| B16 | P3 | `M04a → B16a assignment → M04b → B16b verification` |
| M04 | P3 | `M04a` graph type/extractor → B16 assignment → `M04b` membership/digest |

actual closure row에는 단수 `accountable_owner_phase`, 배열
`contributor_phase_ids[]`, 배열 `ordered_milestone_receipt_sha[]`를 쓴다.
G6는 owner cardinality와 milestone 완전성을 따로 검사한다.

### 6.3 cycle-free normative edge

```text
B17 → B01/B03/B08/B11
B11 → B12
B01/B02/B03 → B05/M03
B10a schema → B06a topology
B01 prefix edge + B06a → M04a
M04a → B16a exactly-one assignment
B16a → M04b final graph digest
M04b → B16b producer verification
B09a fixture root → B10b synthetic exact6
B10b → B09b same-root fixture integration
B09b + B10b + B06b → B04 synthetic receipt fixture
```

M04는 R007 finding 한 행으로만 disposition하고 M04a/M04b 두 receipt를
결속한다. B16도 한 행으로 B16a/B16b 두 receipt를 결속한다.

## 7. H1 synthetic type과 H2 actual type

다음 두 집합은 disjoint tagged union이다.

### 7.1 H1 constructive-only

```text
StageCLiveRootContractSpec
ConstructiveStageCRootFixture
ConstructiveExact6Input
ConstructiveExact6Result
ConstructiveStageCRootIntegrationReceipt
ConstructiveCReceiptFixture
```

- synthetic/isolated root만 사용
- checkpoint, canonical, product bytes를 바꾸지 않음
- actual sequence, actual application receipt 또는 official credit을 주장하지
  않음
- G5-EVIDENCE와 G6가 소비하는 유일한 Stage-C 계열

### 7.2 H2 actual-only

```text
StageCLiveRootManifest
ActualExact6Input
ActualExact6Result
StageCLiveRootIntegrationReceipt
StageCApplicationReceipt
```

- 미래 actual checkpoint durability 뒤만 생산
- 별도 Stage C approval과 fenced apply/post-check 필요
- H1 G5/G6/P7 evidence로 사용할 수 없음

negative invariant:

```text
constructive tag cannot satisfy actual tag
actual tag cannot be required before PRE_P_SUCCESSOR_READY
ConstructiveCReceiptFixture cannot authorize H2 or Stage D
StageCApplicationReceipt cannot be fabricated by V1
```

## 8. 18 BLOCKING closure rows

모든 row는 다음 공통 field를 가진다.

```text
finding_id
accountable_owner_phase
contributor_phase_ids[]
predecessor_receipt_sha[]
strict_output_path[] + schema_sha[]
positive_fixture_id[]
negative_fixture_id[]
verifier_physical + verifier_argv[]
raw_evidence_path[]
ordered_milestone_receipt_sha[]
row_completion_receipt_sha
authority_ceiling
disposition
```

### B01 — grant/review-consume/prefix lifecycle

- owner:
  P2
- required:
  `CanonicalRootSpec`, issue/consume/review-consume/close/failure artifact의
  path/type/publisher/predecessor/cardinality/signature domain
- edge:
  `previous_prefix_digest → next_prefix_digest`, grant가 exact prefix/head/
  subject/deadline 결속
- reject:
  replay, fork, prefix mutation, review-consume 누락, self-signature
- close:
  JCS bytes, prefix chain과 success/failure cardinality 독립 재계산

### B02 — revocation과 common guard FSM

- owner:
  P2
- required:
  current head/time, original hard deadline, retry 상속 산식, renewal ordinal,
  one-use/unrevoked/consumed/failure predecessor
- reject:
  deadline reset, expired/revoked/duplicate consume, wrong head, ordinal skip
- close:
  A/B/C/recovery legal·illegal edge exhaustiveness와 unreachable state 0

### B03 — literal physical capability row

- owner:
  P2
- exact row:
  `{operation,path,parent_anchor,object_type,publisher,signature_domain}`
- reject:
  alias, glob, cross-product, parent/sibling expansion, root escape
- close:
  signed bytes에서 allow row를 독립 재열거한 exact equality

### B04 — synthetic C/recovery receipt fixture와 actual contract

- owner:
  P5
- H1 output:
  `ConstructiveCReceiptFixture`
- H2 future contract:
  `StageCApplicationReceipt`
- binding:
  N26/T1/X1/V1, capability rows, byte-equal target spec, predecessor/executor
- reject:
  constructive/actual cross-use, common-field-only, wrong phase, self/future cycle
- close:
  H1 synthetic strict schema/JCS/SCC/byte equality evidence만 G6에 결속

### B05 — D~G future activation artifact

- owner:
  P2
- canonical role:
  `authority/future/{D|E|F|G}/activation-approval.json`
- field:
  stage tag, exact future subject SHA, same-SHA reviews, predecessor receipt,
  nonce/scope/expiry/revocation/one-use/publisher/signature domain
- reject:
  current token, H1/H2 review, 다른 stage approval/receipt 재사용
- close:
  stage간 authority conversion path 0

### B06 — N26/T1/X1/StageCReviewBinding/V1

- owner:
  P5
- milestone:
  P3 `B06a` strict payload/path/publisher/hash topology,
  P5 `B06b` synthetic instance
- required order:
  inward-only `N26 → T1/X1 → StageCReviewBinding → V1`
- reject:
  label-only, self/future reference, publisher mismatch, actual/fixture cross-use
- close:
  topology receipt와 synthetic independent digest-equality receipt 둘 다 PASS

### B07 — Stage-B exact10와 repeat oracle

- owner:
  P4
- scope:
  two environments × BEFORE/AFTER/regression-A/regression-B/AFTER-repeat
- field:
  raw/parsed path, extractor Physical, parser, expected/actual/result,
  signed wrapper/publisher
- reject:
  opaque evidence, partial invocation, wrong environment, repeat 누락
- close:
  raw exact10 aggregate와 repeat byte equality 독립 재계산

### B08 — Stage-B input manifests와 SandboxIntent

- owner:
  P4
- field:
  ordered physical member, alias, set digest, publisher/predecessor,
  literal argv/bind/environment/input/output/tracer
- order:
  actual reference → input manifest → command manifest
- reject:
  implicit alias, broad mount, undeclared environment, early publish
- close:
  actual sandbox argv와 signed intent byte equality

### B09 — constructive root fixture와 future actual contract

- owner:
  P5
- H1:
  `StageCLiveRootContractSpec`, `ConstructiveStageCRootFixture`,
  `ConstructiveStageCRootIntegrationReceipt`
- schema field:
  627-member shape, exact26/U1 physical identity/digest shape, seq40 tail,
  exclusions와 future actual application-receipt binding
- order:
  B09a fixture root → B10b → B09b same-root integration
- reject:
  generic DirPhysical, invocation별 root, U1 누락, fixture를 actual로 주장
- close:
  synthetic root 독립 재열거 equality; actual H2 receipt는 G6 predecessor 아님

### B10 — exact6 schema와 synthetic oracle

- owner:
  P5
- milestone:
  P3 `B10a` six typed schema, P5 `B10b` synthetic construction
- command count:
  `1/1/1/1/1/2`
- field:
  raw source, parser, expected/actual/result, producer/path, framing digest
- reject:
  string role, untyped assertion, row006 command 혼합, B09b 선참조
- close:
  synthetic raw evidence에서 six assertion 독립 재계산

### B11 — generated scratch determinism

- owner:
  P1
- input:
  P0가 실제 동결한 generated inventory
- required:
  literal policy, exclusion/collision order, empty/seed rule, pre/post digest
- reject:
  glob, source collision, stale scratch, mutable count 재사용
- close:
  clean-room two-run digest equality

### B12 — BEFORE/AFTER projection single DAG

- owner:
  P1
- required:
  typed manifests/receipts와 유일한 publication DAG
- reject:
  conflicting order, missing/extra field, noncanonical cast
- close:
  topological-order cardinality 1, cycle 0

### B13 — RuntimeActual use chain

- owner:
  P4
- required:
  resolved payload/hash, exhaustive consumer map, use receipt,
  normalized-output producer/path/schema
- consumers:
  local full19, hosted full19, synthetic Stage-C exact6, future actual/recovery
- reject:
  exact6/recovery 누락, owner/hash-domain 누락
- close:
  trace consumer와 declared map exact equality

### B14 — full19 raw framing

- owner:
  P4
- field:
  19 rows, raw evidence, extractor Physical, parser/JSON pointer,
  normalization, expected/actual/result
- multi-command:
  ordinal, raw path, offset, length, command SHA, framing-manifest digest
- reject:
  rc0-only, `REVIEWED_EXIT_CONTRACT`, hash 없는 stream framing
- close:
  immutable raw bytes에서 19 semantic result 재계산

### B15 — Git/row18 complete closure

- owner:
  P4
- required:
  source/test/control/.git arrays, gitfile→external gitdir anchor,
  GIT_ENVIRONMENT와 Git executable Physical
- reject:
  broad prefix, external gitdir/executable 누락, path escape
- close:
  actual trace equality와 undeclared read 0

### B16 — FutureSealed exactly-one producer

- owner:
  P3
- milestone:
  `M04a → B16a producer assignment → M04b → B16b verification`
- required:
  role별 producer, input manifest, output path, resolution barrier
- reject:
  duplicate/zero producer, unresolved role, premature consume
- close:
  producer=1, resolved=1, duplicate=0, cycle=0의 row-level receipt

### B17 — mixed-tree physical union

- owner:
  P1
- union:
  regular/directory/symlink/direct-system-origin
- field:
  relative link, lstat identity, parent anchor, no-follow/no-escape,
  anchored copy와 tree digest
- apply:
  source/projection/role, `MemberOriginMap`, lock/archive/direct origin
- reject:
  absolute link, root escape, type mismatch, FilePhysical-only 축소
- close:
  P0 inventory 전 member의 union exhaustiveness

### B18 — source-only BEFORE spawn

- owner:
  P4
- predecessor:
  B08/B12와 debt D-A/D-B
- required:
  current positional BEFORE와 successor AFTER의 literal spawn 분리
- reject:
  live unassigned inventory 은폐, absent flag, AFTER overlay, stale runner count
- close:
  frozen current-control projection에서 current CLI isolated spawn receipt

## 9. 4 MAJOR closure rows

### M01 — row15 executable closure

- owner:
  P4
- required:
  final runner의 subprocess executable Physical 전수
- include:
  `dirname`, `mktemp`, `chmod`, `rm`, `find`, `sort`와 trace에 나타난 전부
- reject:
  broad PATH, undeclared executable, 근거 없는 제거 주장
- close:
  trace와 declared closure equality

### M02 — exhaustive late-bound role

- owner:
  P1
- required:
  closed enum, allowed phase, constructor와 wrong-phase checker
- Stage A allow:
  executable/environment/package closure
- Stage A deny:
  live root/transaction/exact26/U1/repository inventory/RuntimeActual
- close:
  enum cardinality와 wrong-phase negative PASS

### M03 — disjoint recovery

- owner:
  P2
- branch:
  `EXACT6_SUFFIX`는 durable prefix+never-dispatched suffix,
  `APPLICATION_FINALIZATION`은 actual `POSTCHECK_PASSED` 뒤만
- reject:
  cross-use, broad post-check state, common capability reuse
- close:
  branch intersection 0

### M04 — graph foundation과 final digest

- owner:
  P3
- M04a:
  node/edge type, extractor, canonical serialization/order/domain bytes
- M04b:
  B16 assignment을 포함한 complete membership와 exact digest verification
- ownership:
  B01 prefix edge는 한 번 생산되고 graph가 한 번 소비
- reject:
  missing/duplicate node, implementation order, B16↔M04 cycle
- close:
  M04a/M04b receipt와 independent digest equality를 한 row에 결속

## 10. exact 22-row owner matrix와 four debt

### 10.1 finding matrix

| ID | accountable owner | ordered milestone 요약 | planning state |
|---|---|---|---|
| B01 | P2 | B17 → grant/prefix lifecycle | `REQUIRED_FUTURE_ROW` |
| B02 | P2 | B01 → common guard FSM | `REQUIRED_FUTURE_ROW` |
| B03 | P2 | B17/B01 → capability rows | `REQUIRED_FUTURE_ROW` |
| B04 | P5 | B09b/B10b/B06b → synthetic receipt fixture | `REQUIRED_FUTURE_ROW` |
| B05 | P2 | authority model → D/E/F/G activation | `REQUIRED_FUTURE_ROW` |
| B06 | P5 | B10a → B06a → B09b/B10b → B06b | `REQUIRED_FUTURE_ROW` |
| B07 | P4 | B08/B13 → exact10/repeat | `REQUIRED_FUTURE_ROW` |
| B08 | P4 | B17/P0 → source/SandboxIntent | `REQUIRED_FUTURE_ROW` |
| B09 | P5 | B09a → B10b → B09b | `REQUIRED_FUTURE_ROW` |
| B10 | P5 | B10a → B09a → B10b | `REQUIRED_FUTURE_ROW` |
| B11 | P1 | B17/P0 → scratch repeat | `REQUIRED_FUTURE_ROW` |
| B12 | P1 | B17/B11 → projection DAG | `REQUIRED_FUTURE_ROW` |
| B13 | P4 | B12 → RuntimeActual chain | `REQUIRED_FUTURE_ROW` |
| B14 | P4 | B13 → full19 framing | `REQUIRED_FUTURE_ROW` |
| B15 | P4 | B17/P0 → Git closure | `REQUIRED_FUTURE_ROW` |
| B16 | P3 | M04a → B16a → M04b → B16b | `REQUIRED_FUTURE_ROW` |
| B17 | P1 | P0 → physical union | `REQUIRED_FUTURE_ROW` |
| B18 | P4 | B08/B12/D-A/D-B → BEFORE spawn | `REQUIRED_FUTURE_ROW` |
| M01 | P4 | final runner → exec closure | `REQUIRED_FUTURE_ROW` |
| M02 | P1 | B17/P0 → late-bound enum | `REQUIRED_FUTURE_ROW` |
| M03 | P2 | B02/B03 → recovery split | `REQUIRED_FUTURE_ROW` |
| M04 | P3 | B01/B06a → M04a → B16a → M04b | `REQUIRED_FUTURE_ROW` |

```text
row count = 22
unique finding ID = 22
accountable owner cardinality per row = 1
multi-owned = 0
unowned = 0
BLOCKING = 18
MAJOR = 4
```

### 10.2 regression debt

| ID | exact purpose | B18 predecessor | G4/G6 predecessor | current |
|---|---|---|---|---|
| D-A | backend/local-test/hosted lock epoch와 two-run generation | yes | yes | `OPEN` |
| D-B | runner current/historical/non-running inventory와 CLI spawn | yes | yes | `OPEN` |
| D-C | Gateway historical exact4/current exact5 typed validation | no | yes | `OPEN` |
| D-D | 20260722/current artifact baseline dual validation | no | yes | `OPEN` |

각 debt는 immutable input, exact output path/schema, verifier Physical/argv,
positive/negative fixture와 receipt를 가진다.

## 11. V0/V1 authority와 evidence

### 11.1 H1 add-only write allowlist

H1에서 허용 가능한 미래 write role은 다음뿐이다.

- roadmap successor와 그 two reviews
- `G0_SPEC_ONLY`와 그 two reviews/result
- PRE-P closure successor와 non-execution fixture/verifier spec
- `CONSTRUCTIVE_FIXTURE_AUTHORITY_BRIDGE`와 two bridge reviews
- `AUTHORITY_BOUNDARY_DECISION` 및 decision receipt
- 승인된 isolated V1 raw evidence/result/CAS manifest
- G6/G7 result와 class-specific reviews
- root/merge가 한 번 작성하는 daylog

checkpoint, canonical, 제품, runner, lock과 actual Stage-C path는 허용하지
않는다.

### 11.2 V0 bridge

bridge field:

```text
technical_handoff_sha
tested_successor_sha
fixture_bundle_sha
verifier_bundle_sha
isolated_root_physical
exact_read/write/exec_set
nonce + hard_deadline + revocation + one_use
network/secrets/external/live-write = deny
raw_output/result/receipt paths
crash/recovery truth table
```

bridge formal/skeptical review는 서로 다른 actor이며 same bridge SHA에
각각 `0/0/0`이어야 한다.

### 11.3 mandatory governing decision

현재 기술 인계서는 successor final reviews 전 실행 승인 질문을 금지한다.
따라서 bridge review만으로 V1 approval을 질문하지 않는다.

필수 `AUTHORITY_BOUNDARY_DECISION`:

```text
subject:
  technical_handoff_sha
  tested_successor_sha
  bridge_sha
  formal_bridge_review_sha
  skeptical_bridge_review_sha
disposition:
  DENY
  or ALLOW_ONE_ISOLATED_ATTEMPT
issuer:
  explicit user
receipt:
  nonce/scope/deadline/revocation/one-use consume/close
effect:
  V1 isolated evidence only
```

decision이 없거나 `DENY`이면:

```text
V1 = NOT_RUN
G1-EVIDENCE ... G7 = NOT_RUN
PRE_P_SUCCESSOR_READY = false
H2 = NOT_STARTED
```

### 11.4 V1 seven task rows

1. mixed tree/generated scratch materialization
2. BEFORE/AFTER projection repeat equality
3. current CLI BEFORE와 successor CLI AFTER separate spawn
4. two-environment exact10/repeat
5. full19 raw assertion recomputation
6. synthetic Stage-C exact6/receipt fixture
7. authority replay/revocation/crash matrix

G6가 요구할 산술:

```text
V1 unique task rows = 7
V1 PASS = 7
V1 FAIL = 0
V1 NOT_RUN = 0
CAS manifest member task receipts = 7
```

각 task receipt와 CAS manifest는 다음을 직접 결속한다.

```text
tested_successor_sha
gate_spec_sha
input_manifest_sha
fixture_sha
verifier_sha
attempt_id
authority_decision_sha
authority_consume_receipt_sha
raw output SHA
```

successor, spec, input, fixture 또는 verifier SHA가 하나라도 바뀌면 이전
evidence와 review를 재사용하지 않는다. V1 seven task를 새 attempt로 다시
실행하고 새 CAS manifest를 만들기 전까지 G6/G7은 `NOT_RUN`이다.

## 12. G6, P7, G7과 ready predicate

### 12.1 G6 PASS

```text
G0 PASS receipt = 1
G1-SPEC ... G5-SPEC PASS receipts = 5
AUTHORITY_BOUNDARY_DECISION = ALLOW_ONE_ISOLATED_ATTEMPT
authority consume/close PASS receipts = 2
G1-EVIDENCE ... G5-EVIDENCE PASS receipts = 5
closure rows = 22
unique finding IDs = 22
OPEN = 0
CLOSED_CANDIDATE = 22
verified row completion receipts = 22
debt rows = 4
debt OPEN = 0
debt CLOSED_CANDIDATE = 4
V1 task PASS = 7/7
failed_or_missing_evidence = 0
positive fixture failure = 0
negative fixture false-accept = 0
implicit alias/glob = 0
duplicate producer = 0
graph cycle = 0
future/self reference = 0
constructive/actual Stage-C cross-use = 0
```

G6 result는 exact successor SHA, evidence CAS SHA, authority decision SHA와
모든 predecessor PASS receipt를 결속한다.

### 12.2 P7 four class-specific reviews

1. formal `SUCCESSOR_PLAN_REVIEW`
2. skeptical `SUCCESSOR_PLAN_REVIEW`
3. formal `CONSTRUCTIVE_EVIDENCE_REVIEW`
4. skeptical `CONSTRUCTIVE_EVIDENCE_REVIEW`

공통:

- 네 review 모두 같은 successor SHA와 G6 disposition SHA 결속
- evidence review 두 개만 같은 evidence CAS SHA를 subject로 결속
- 서로 다른 review class를 “same subject”라고 부르지 않음

review receipt identity:

```text
reviewer_actor_id
reviewer_session_id
tool_and_version
reviewed_subject_type
reviewed_sha[]
timestamp
signature_domain
findings
```

독립성:

```text
formal_actor != skeptical_actor for each review pair
successor_author/merge not in successor_reviewers
fixture_or_verifier_author/evidence_producer not in evidence_reviewers
bridge_author/merge not in bridge_reviewers
```

### 12.3 G7 PASS

G7 verifier는 다음을 직접 확인하고 immutable receipt를 발행한다.

```text
G6 status = PASS
G6 exact receipt SHA = expected
four required reviews exist
all four findings = 0/0/0
all four bind same successor SHA
all four bind same G6 disposition SHA
evidence review pair binds same evidence CAS SHA
review actor disjointness = PASS
successor/evidence/G6/reviews post-freeze mutation = 0
official delta = 0
```

### 12.4 유일한 ready predicate

```text
PRE_P_SUCCESSOR_READY =
  G6 status=PASS
  AND exact G6 receipt SHA present
  AND G7 status=PASS
  AND exact G7 receipt SHA present
  AND G7 successor SHA = G6 successor SHA
  AND G7 evidence SHA = G6 evidence SHA
  AND G7 disposition SHA = reviewed G6 disposition SHA
  AND authority decision/consume/close lineage PASS
  AND post-G7 mutation=0
  AND official delta=0
```

G7 `NOT_RUN/FAIL`이면 ready=false이고 H2는 시작하지 않는다. ready=true도
H2 authority가 아니다.

## 13. H2 — bootstrap과 Stage A→B→C

ready=true 뒤에도 각 단계별 exact user approval이 필요하다.

```text
ready predicate
→ journal-bootstrap approval/receipt
→ Stage A approval
→ candidate/environment/pack build
→ candidate-bound review
→ Stage B approval/validation
→ Stage C approval/fenced apply/post-check
→ actual StageCApplicationReceipt
```

공통 stop:

- predecessor PASS/subject/nonce mismatch
- expired/revoked/consumed authority
- undeclared read/write/exec
- missing raw evidence/receipt
- undefined crash state

H2 뒤 `H2_RESULT_VERIFICATION_ONLY / NO_REEXECUTION`은
`StageCApplicationReceipt`를 read-only 검증할 뿐 authority를 만들지 않는다.
H2 approval, nonce, grant와 receipt를 재사용하지 않는다.

## 14. Stage D→E→F→G

| Stage | exact predecessor | allowed action | completion |
|---|---|---|---|
| D | actual StageCApplicationReceipt + exact D design subject + D one-use approval | P-successor 설계·검수만 | D approval consume/close + D design-review receipt |
| E | D receipts + exact E candidate subject + E one-use approval | exact P17 candidate build·candidate-bound review만 | E consume/close + candidate-review receipt |
| F | E receipts + exact F attempt subject + F one-use approval | reviewed candidate resolution만 | F consume/close + resolved-subject receipt |
| G | F receipts + exact G resolved subject + G one-use approval | exact resolved P subject fenced apply만 | G consume/close + selector-ready application receipt |

`H2_RESULT_VERIFICATION_ONLY` receipt는 D authority가 아니다. D activation
subject와 D가 출력할 successor plan은 서로 다른 tagged type이다. D output의
미래 reviews를 D 사전 approval로 순환 재사용하지 않는다.

G durable selector-ready receipt 전에는 frontier, canonical, product,
artifact credit을 바꾸지 않는다.

## 15. main transition, 제품과 artifact

Stage G 뒤 별도 main-control successor를 설계·검수·승인한다.

- v2.5/r022 actual bytes, DAG와 recovery 재검증
- checkpoint-last atomic apply
- stale pointer 제거
- 중간 실패 시 seq39/r021 또는 완전한 새 상태 중 하나만 유지

전환 뒤 live frontier를 다시 계산한다. 현재 focus `EPIC-03`, ready frontier
`EPIC-03/EPIC-12`, materialized leaf `none`은 참고값이다. FP-008을 강행하지
않고 재계산 결과와 별도 design/review/authorization을 따른다.

단일 Work Item:

```text
exact policy/Gap
→ fail-first acceptance
→ minimal implementation
→ targeted/component regression
→ evidence
→ Gap/Backlog successor
→ independent review
→ atomic canonical transition
```

공식 artifact 현재값:

```text
artifact closed-equivalent = 126/257
open = 131
```

이는 seq39 projection 값이며 전체 artifact completion, 프로젝트 완료율 또는
이번 문서의 새 credit이 아니다.

| Lane | 수 | 병렬 준비 | 실제 종료 |
|---|---:|---|---|
| A internal-ready | 62 | 내용/trace/review | required content와 적격 승인 |
| B fact/owner/attest | 24 | request/owner map | actual fact/decision/attestation |
| C internal-run-required | 24 | env/command/receipt spec | actual raw output/receipt/review |
| D real-event-pending | 21 | target/authority/schedule | legitimate actual-event receipt |

## 16. formal, Gate와 release

H4 진입:

- EPIC-04/05/06/08/09/10/11 완료
- 하나의 immutable candidate
- 승인된 plan/environment/device/participant/authority

H4 완료:

```text
formal planned = 279
formal PASS = 279
formal FAIL/NOT_RUN = 0
required actual device/event receipts complete
candidate SHA mismatch = 0
independent findings = 0/0/0
```

현재 formal `0/279`, actual device/event `0/0`이다.

H5는 H4 completion receipt 뒤 five Gate actual PASS(`waived=false`),
release decision, signed candidate/config, deploy/canary/smoke,
rollback/backup/restore/recovery, 운영 안정화와 이관을 요구한다.

현재 Gate `0/5`, production `0`, release `NOT_ELIGIBLE`,
project `NOT_COMPLETE`다.

## 17. 단계별 stop rule

### Roadmap/Z0/G0

- R003 두 review가 같은 SHA findings-zero가 아니면 Z0 시작 금지
- G0 spec 두 review가 findings-zero가 아니면 G0 실행 금지
- G0가 PASS가 아니면 successor 작성 금지

### H1

- §11.1 add-only allowlist 밖 write 금지
- governing decision 없이 V1 approval 질문·실행 금지
- actual Stage-C/checkpoint/canonical/product path write 금지
- owner 중복, cycle, alias/glob, missing evidence 시 downstream `NOT_RUN`

### H2~H5

- exact predecessor PASS와 reviewed one-use write set 안에서만 write
- R007/R001/R002/R003/r021 history bytes 불변
- 범위 밖 checkpoint/canonical/product write 즉시 중단
- 공식 credit은 해당 independent gate 뒤에만 허용

### 공통

- 사람, 기기, 외부 서비스, secret, 유료 자원이 필요한데 권한이 없으면
  해당 lane을 미루고 독립 ready lane만 진행
- mock/계획/내부 검사를 actual/formal/release 증거로 승격 금지
- blocker에는 missing input, owner와 exact resume predicate 기록

## 18. 사용자에게 질문할 미래 시점

현재 요청할 실행 승인은 없다.

| 시점 | 사용자에게 제시할 exact subject | 허용 범위 |
|---|---|---|
| V0 bridge reviews 뒤 | handoff/successor/bridge/review SHA와 권한 충돌 | `DENY` 또는 isolated V1 one attempt |
| ready=true 뒤 | G6/G7/successor/evidence SHA와 bootstrap scope | journal bootstrap만 |
| bootstrap receipt 뒤 | Stage A subject/write set | build만 |
| candidate review 뒤 | Stage B subject/commands | validation만 |
| Stage-B PASS 뒤 | Stage C transaction/recovery | apply/post-check만 |
| D/E/F/G 각각 | 해당 stage exact subject/nonce/write set | 해당 한 stage만 |
| formal 준비 뒤 | candidate/plan/device/participant/authority | 승인된 시험만 |
| release 준비 뒤 | five Gate/operation evidence | 명시된 release action만 |

## 19. 팀, 병렬화와 예상 범위

| 역할 | 책임 |
|---|---|
| root/merge | protected manifest, common type/DAG, freeze, daylog |
| Z0/G0 lane | reviewed read-only baseline spec/result |
| P1 | physical union, scratch, projection, late-bound |
| P2 | authority lifecycle/capability/recovery/future boundary |
| P3 | graph type, B16 producer assignment, digest |
| P4 | Stage-B/runtime/full19/Git/runner/four debt |
| P5 | synthetic root/exact6/object/receipt fixture |
| V1 producer | approved isolated seven-task evidence |
| formal/skeptical reviewers | 서로 다른 actor로 구조/반례 검수 |

충돌 없는 조사·fixture 초안만 병렬화하고 common schema/DAG/freeze는
root/merge 한 명이 수행한다. daylog도 root/merge가 한 번 쓴다.

| 단계 | 성공기준 | 계획용 예상 |
|---|---|---:|
| R003 review | same-SHA roadmap reviews `0/0/0` | 수시간~1일 |
| Z0/G0 | reviewed spec와 read-only PASS result | 수시간~1일 |
| P0~P2 | inventory/type/authority spec gates PASS | 2~5 작업일 |
| P3~P5 | graph/raw oracle/synthetic Stage-C specs PASS | 3~7 작업일 |
| V0 decision | bridge reviews와 explicit user decision | 외부 결정 종속 |
| V1/G6/P7/G7 | seven tasks, 22+4 closure, four reviews | 2~6 작업일 이상 |
| H2/D~G | 각 별도 승인 뒤 | 환경/recovery별 재산정 |
| 제품/artifact | live frontier/dependency 기반 | 여러 작업일~수주 |
| formal/release | 사람/기기/외부 권한 기반 | 별도 일정 |

## 20. 다음 Codex용 즉시 지시문

```text
기술 인계서, rejected R007와 두 review, rejected R001/R002 roadmap과 각
review, 그리고 R003와 그 adjacent reviews를 먼저 읽어라. 보호 SHA를
read-only 재확인하라. R003 formal/skeptical ROADMAP_REVIEW가 같은 exact
R003 SHA에 대해 모두 0/0/0인 경우에만 add-only G0_SPEC_ONLY를 작성하라.
G0 spec은 §1.1 전체 path/stat/hash, v2.5/r022/P/M absence, authority absence,
mutation, literal verifier argv/environment와 expected result를 successor
없이 재현 가능하게 고정해야 한다. 같은 G0 spec SHA에 formal/skeptical
G0_SPEC_REVIEW 0/0/0을 받은 뒤 G0를 read-only 실행하라. G0 PASS 전에는
PRE_P_CLOSURE_SUCCESSOR를 쓰지 마라.

G0 PASS 뒤에만 18B+4M의 exact 22-row, four debt, single owner, M04a→B16→
M04b, Gate Spec/Result 분리와 constructive/actual Stage-C tagged split을
가진 add-only successor를 작성하라. evidence가 필요하면 bridge reviews
뒤에도 governing AUTHORITY_BOUNDARY_DECISION 없이는 approval을 질문하거나
V1을 실행하지 마라. decision이 ALLOW_ONE_ISOLATED_ATTEMPT일 때만 seven-task
V1을 수행하고 successor/spec/input/fixture/verifier SHA를 결속하라.
G6 PASS→four class-specific reviews→G7 PASS→ready 외의 우회는 금지한다.
ready=true도 bootstrap/Stage A authority가 아니다.
```

## 21. R003 인계 완료조건

```text
R007 mapping = 18 BLOCKING + 4 MAJOR
finding rows = 22 unique
accountable owner cardinality = 1 per row
debt rows = 4 unique
G0 self-predecessor cycle = 0
Gate spec/result future reference = 0
B16/M04 cycle = 0
constructive/actual Stage-C cross-use = 0
governing decision bypass = 0
G6/P7/G7/ready bypass = 0
Stage D approval missing = 0
review actor identity/disjointness specified = true
evidence reuse after subject change = forbidden
artifact status wording = closed-equivalent 126/257
old S1/R007/stale FP-048 execution = forbidden
current seq39/r021/r021 and official zero delta = stated
formal ROADMAP_REVIEW on exact R003 SHA = 0/0/0
skeptical ROADMAP_REVIEW on exact R003 SHA = 0/0/0
```

이 완료는 R007 finding closure, future successor readiness, authority,
checkpoint/canonical/product/artifact/formal/device/Gate/production/release
진척을 뜻하지 않는다.
