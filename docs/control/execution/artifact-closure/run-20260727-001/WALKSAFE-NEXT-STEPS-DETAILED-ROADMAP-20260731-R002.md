# WalkSafe 다음 단계 상세 로드맵 20260731 R002

- 문서 ID:
  `WS-WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R002`
- 작성일:
  `2026-07-31`
- 상태:
  `NONCANONICAL_PLAN_ONLY / NOT_EXECUTABLE / ROADMAP_REVIEW_PENDING`
- 현재 권한:
  `ABSENT_DENY_ALL`
- 현재 checkpoint:
  v2.4 `ACTIVE`, sequence `39`
- 현재 canonical Gap / Backlog:
  `r021 / r021`
- 공식 성과 delta:
  `PRODUCT/CHECKPOINT/CANONICAL/GOAL/ARTIFACT/FORMAL/DEVICE_EVENT/GATE/PRODUCTION/RELEASE=0`

## 0. 목적, 변경 이유와 claim ceiling

이 문서는 다음 Codex가 WalkSafe 작업을 어떤 순서로 준비해야 하는지
설명하는 비실행 로드맵이다. 현재 즉시 가능한 일은 rejected PRE-P R007의
`18 BLOCKING + 4 MAJOR`를 닫을 새 설계를 add-only로 작성하는 일뿐이다.
제품 구현, checkpoint 전환, 정식 시험과 배포는 아직 시작할 수 없다.

R001 로드맵은 finding 번호를 빠짐없이 매핑했지만 두 독립검수에서 다음
판정을 받았다.

| 입력 | 판정 | review SHA-256 |
|---|---|---|
| R001 formal roadmap review | `3 BLOCKING / 1 MAJOR / 0 MINOR` | `bdaf1771fcd9974a5c5e4728ee45d90759b86a08ff8934136e94e9d18e9c7ffa` |
| R001 skeptical roadmap review | `6 BLOCKING / 5 MAJOR / 0 MINOR` | `9c6c8267c9459a3090c9b78bf0ba9338e4d7f5da44ad013d8e6036b258a4b12c` |

R002는 R001을 고치지 않고 별도 경로로 추가하며 다음 결함을 닫는다.

- 열린 22행도 통과할 수 있던 G6를 fail-closed로 변경
- constructive verification과 successor 수용 사이의 권한 순환 분리
- H2와 H3의 Stage A~C 중복 실행 경로 제거
- Stage D→E→F→G를 서로 대체할 수 없는 네 transaction으로 분리
- H1의 보호 규칙과 미래 H2~H5의 승인된 write set을 구분
- B09와 B10의 순환을 `B09a→B10→B09b→B04`로 해소
- finding별 누락 physical field와 네 regression debt gate 보완
- roadmap, successor plan, authority bridge, evidence review의 subject type 분리

이 문서와 그 검수가 findings-zero가 되더라도 다음은 변하지 않는다.

```text
R007_FINDINGS_CLOSED=false
PRE_P_CLOSURE_SUCCESSOR_EXISTS=false
PRE_P_CLOSURE_SUCCESSOR_READY_PREDICATE=false
CONSTRUCTIVE_FIXTURE_AUTHORIZED=false
JOURNAL_BOOTSTRAP_AUTHORIZED=false
STAGE_A_TO_G_AUTHORIZED=false
CHECKPOINT_OR_CANONICAL_WRITE_AUTHORIZED=false
PRODUCT_OR_RELEASE_AUTHORIZED=false
OFFICIAL_CREDIT_DELTA=0
```

## 1. 첫 읽기와 보호 입력

다음 Codex는 첫 쓰기 전에 아래 순서로 읽는다.

1. `WALKSAFE-PROJECT-TECHNICAL-HANDOFF-20260731-R001.md`
2. 위 인계서의 adjacent independent review
3. rejected PRE-P R007 target
4. R007 formal review
5. R007 skeptical review
6. R001 로드맵과 두 adjacent review
7. live v2.4 checkpoint와 canonical r021/r021
8. 이 R002와 그 adjacent review
9. `daylog/2026-07-31.md`

### 1.1 immutable 보호 manifest

| 입력 | SHA-256 | 현재 처분 |
|---|---|---|
| 기술 인계서 | `f5adb13001ba61bd44998415ff4af0e267dcec4bc7515a18ff8ad0a73830b6bb` | 읽기 전용 |
| R007 target | `02766312b1bbb00eb05e2789fe4d054cbf749407e6c5bd26dce62250e6dd98ef` | rejected history |
| R007 formal review | `c2e6c226204d977db9706a58935125b472c829e5790c91ef83e3c866dde51269` | `18/4/0`, 읽기 전용 |
| R007 skeptical review | `0bc4fb67ab80acaae69ae8024b7f39156bc5c98d6f6df2e9bfa508fb2c91fe44` | `18/4/0`, 읽기 전용 |
| R001 roadmap | `77672dc0ecc592b2225acd3c247a91f54d4f2d33fb03126121c5b3d54d15e6ef` | rejected roadmap history |
| v2.4 checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | seq39, 읽기 전용 |
| canonical Gap r021 | `f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a` | 읽기 전용 |
| canonical Backlog r021 | `bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0` | 읽기 전용 |
| DOC-01 | `a0c993257c1b80211c2e8e4db5aa464708ac4aba9d47ed9b434eda277ee6547f` | 읽기 전용 |
| DOC-05 | `cea8b58fc5afa527614522481df56d69618c6fd1015c10b63d6cc8b6e0678c67` | 읽기 전용 |
| runner | `4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d` | 읽기 전용 |

### 1.2 금지된 역사 경로

- Master 인계서의 old S1 exact seq39→40/P17 실행
- PRE-P R001~R007의 명령, 승인 문구, nonce와 receipt
- continuation R004~R009 실행 블록
- stale FP-048 포인터
- active v2.5, r022 또는 P/M physical candidate가 이미 있다고 가정하는 행위
- 과거 exact4 receipt를 현재 exact5 Gateway 증거로 재해석하는 행위

## 2. subject type과 오인 방지 규칙

다음 subject class는 서로 다른 tagged type이다.

| subject type | 검수 대상 | 허용 효과 |
|---|---|---|
| `ROADMAP_REVIEW` | 이 R002 exact bytes | 인계 로드맵 품질 판정만 |
| `SUCCESSOR_PLAN_REVIEW` | 미래 PRE-P closure successor exact SHA | 설계 finding 판정만 |
| `AUTHORITY_BRIDGE_REVIEW` | 격리 fixture 전용 bridge exact SHA | bridge 설계 판정만 |
| `CONSTRUCTIVE_EVIDENCE_REVIEW` | successor SHA + CAS evidence manifest SHA | evidence 완전성 판정만 |

다음 negative invariant를 강제한다.

```text
ROADMAP_REVIEW != SUCCESSOR_PLAN_REVIEW
ROADMAP_REVIEW != AUTHORITY_BRIDGE_REVIEW
ROADMAP_REVIEW != CONSTRUCTIVE_EVIDENCE_REVIEW
SUCCESSOR_PLAN_REVIEW receipt cannot authorize execution
AUTHORITY_BRIDGE_REVIEW receipt cannot authorize bootstrap or Stage A-G
CONSTRUCTIVE_EVIDENCE_REVIEW receipt cannot authorize bootstrap or Stage A-G
one subject type cannot satisfy another subject type predicate
```

`accepted successor`라는 비정형 상태명은 사용하지 않는다. 미래에 사용할
수 있는 것은 다음 비권한 predicate뿐이다.

```text
PRE_P_SUCCESSOR_READY_PREDICATE =
  successor exact SHA frozen
  AND G6 CLOSED_CANDIDATE=22
  AND G6 OPEN=0
  AND debt CLOSED_CANDIDATE=4
  AND failed_or_missing_evidence=0
  AND formal SUCCESSOR_PLAN_REVIEW=0/0/0
  AND skeptical SUCCESSOR_PLAN_REVIEW=0/0/0
  AND formal CONSTRUCTIVE_EVIDENCE_REVIEW=0/0/0
  AND skeptical CONSTRUCTIVE_EVIDENCE_REVIEW=0/0/0
  AND all four reviews bind the same successor SHA
  AND both evidence reviews bind the same evidence-manifest SHA
```

이 predicate가 true여도 실행 authority와 공식 credit은 생기지 않는다.

## 3. G0 — 현재 기준선 재검산

G0는 새 successor 계획을 쓰기 전에 수행하는 read-only gate다.

### 3.1 exact read-only 명령

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_4.py \
  --root . \
  --checkpoint docs/control/walksafe-project-continuation-checkpoint.json

python3 -B scripts/check_walksafe_goal_graph_v2_4.py \
  --root . \
  --checkpoint docs/control/walksafe-project-continuation-checkpoint.json

sha256sum \
  docs/control/walksafe-project-continuation-checkpoint.json \
  docs/control/audits/walksafe-implementation-gap-analysis-20260726-r021.json \
  docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r021.json \
  scripts/run_walksafe_test_layers_20260711.sh
```

위 네 SHA는 §1.1 값과 exact 일치해야 한다.

### 3.2 G0 exact predicate

```text
Quick2 = PASS/PASS
checkpoint sequence = 39
canonical Gap/Backlog = r021/r021
protected SHA mismatch = 0
active v2.5 candidate = absent
r022 candidate = absent
P/M physical candidate = absent
current authority = ABSENT_DENY_ALL
protected target mutation = 0
```

두 checker만으로 나머지 predicate를 추정하지 않는다. successor는
`G0InputManifest`, physical stat/hash/absence 결과,
`G0VerificationResult`와 raw output path를 정의해야 한다. 어느 physical
경로나 기대값이 미정이면 G0는 `NOT_RUN`, 불일치면 `FAIL`이다.

## 4. H1 전체 DAG와 상태 의미

각 finding은 다음 상태를 순서대로 거친다.

```text
PLANNED
→ DRAFTED
→ SCHEMA_FROZEN
→ FIXTURE_SPECIFIED
→ FIXTURE_VERIFIED
→ CLOSED_CANDIDATE
```

앞 상태를 건너뛸 수 없다. 계획 문구만으로 `FIXTURE_VERIFIED`나
`CLOSED_CANDIDATE`가 되지 않는다.

### 4.1 normative phase DAG

```text
P0 protected input + mutable inventory freeze
→ P1a B17 physical tagged union
→ P1b B11, B12, M02
→ P2a B01, B02, B03, M03
→ P2b B05
→ P3a B10 schema foundation
→ P3b B06 topology, B16, M04
→ P4 B07, B08, B13, B14, B15, B18, M01 + debt D-A~D-D
→ P5a B09a live-root publication contract
→ P5b B10 exact6 construction and oracle
→ P5c B09b same-root integration verification
→ P5d B06 T1/X1/V1 instance verification
→ P5e B04 C/recovery receipt construction
→ P6 integrated graph and closure manifest specification
→ V0 authority bridge design/review
→ V1 isolated constructive verification
→ G6 exact closure disposition
→ P7 four same-subject reviews
```

필수 edge:

```text
B17 → B01
B17 → B03
B17 → B08
B17 → B11 → B12
B01 prefix-successor edge → M04 graph
B10 schema foundation → B06 topology
B09a → B10 → B09b → B06 instance verification → B04
B01+B02+B03+B05+M03 → authority negative matrix
D-A+D-B+D-C+D-D → B18 and G6
G6 PASS → P7
```

동일 wave 안에서도 위 edge가 있는 작업은 병렬 종료할 수 없다. 병렬화는
동일한 frozen input을 읽는 독립 조사·fixture 초안과 formal/skeptical
review에만 사용한다.

### 4.2 P0에 추가로 고정할 mutable inventory

R001의 “현재 7개 generated target”, “29개 symlink” 같은 숫자를 재사용하지
않는다. 미래 successor의 P0가 다음을 실제 tree에서 재열거하고 exact
physical manifest로 동결한다.

- generated scratch target 전체
- symlink, directory, regular file과 direct-system-origin 전체
- repository inventory와 external gitdir mapping
- 현재 runner discovery/assignment inventory
- backend/local-test/hosted lock 물리 identity
- Gateway route inventory
- historical/current artifact baseline pair

각 manifest는 canonical ordering, member count, member physical identity,
tree digest, producer, timestamp가 아닌 immutable predecessor digest를
가져야 한다.

## 5. G1~G7 공통 실행 가능 gate 계약

R002는 gate를 실행하지 않는다. 미래 successor는 아래 모든 cell을 실제
값으로 채운 add-only `GateExecutionSpec`을 제공해야 한다.

| 필드 | 필수 규칙 |
|---|---|
| gate ID | `G1`~`G7` 중 하나 |
| input manifest | canonical path, SHA-256, bytes, physical type |
| verifier | executable Physical, SHA-256, publisher |
| argv | shell alias가 아닌 ordered literal argv |
| environment | allowlisted ordered key/value와 runtime Physical |
| read set | exact physical roots와 capability pair |
| write set | 격리된 add-only output path만 |
| raw output | stdout/stderr/result canonical path와 SHA-256 |
| expected exit | exact integer, 기본 `0` |
| expected result | strict JSON schema와 exact predicate |
| receipt | canonical path, predecessor, producer, signature domain |
| failure state | `NOT_RUN`, `FAIL`, `PASS` 중 하나 |

미래 canonical role:

| Gate | input | output role | 최소 expected result |
|---|---|---|---|
| G1 | P0 inventory + B17/B11/B12/M02 schema | `closure/gates/g1/result.json` | physical exhaustiveness, projection determinism, wrong-phase rejection |
| G2 | G1 receipt + B01/B02/B03/B05/M03 | `closure/gates/g2/result.json` | legal FSM complete, replay/revocation/cross-stage rejection |
| G3 | G2 receipt + B10 foundation + B06/B16/M04 | `closure/gates/g3/result.json` | producer exactly one, graph cycle 0, digest equality |
| G4 | G3 receipt + P4 outputs + four debt rows | `closure/gates/g4/result.json` | raw evidence recomputation, debt open 0 |
| G5 | G4 receipt + B09a/B10/B09b/B06-instance/B04 | `closure/gates/g5/result.json` | exact6/post-check byte closure, future/self cycle 0 |
| G6 | G1~G5 receipts + 22 closure rows + 4 debt rows | `closure/gates/g6/disposition.json` | exact predicates in §9 |
| G7 | G6 receipt + successor/evidence review bundle | `closure/gates/g7/review-convergence.json` | four reviews same subject, each 0/0/0 |

verifier Physical, SHA, exact argv, frozen input SHA나 raw output path가 하나라도
비어 있으면 해당 gate는 `PASS`가 아니라 `NOT_RUN`이다. `rc=0`만으로
expected semantic result를 대신할 수 없다.

## 6. 18 BLOCKING closure specification

아래 절은 미래 closure row의 요구사항이다. 현재 disposition은 모두
`REQUIRED_FUTURE_ROW`이며 `CLOSED_CANDIDATE`를 주장하지 않는다.

### B01 — grant bytes, review-consume와 prefix lifecycle

- owner:
  P2a
- predecessor:
  B17 `TreeMemberPhysical`, P0 frozen subject prefix
- strict output:
  `CanonicalRootSpec`, payload 밖 signature wrapper,
  wave별 `issue`, `consume`, `review-consume`, `close`, `failure` canonical
  artifact
- 필수 physical field:
  각 artifact path, object type, publisher, predecessor, cardinality와
  domain-separated signature bytes
- 필수 edge:
  `previous_prefix_digest → next_prefix_digest`; append-only successor만 허용
- grant binding:
  grant는 exact prefix digest와 exact head/subject/deadline을 결속
- positive:
  exact grant와 review grant가 각각 한 번만 소비되고 유일한 close로 종료
- negative:
  replay, fork, prefix mutation, review-consume 누락, self-signature 거부
- verifier/receipt:
  JCS bytes, prefix chain, success/failure cardinality 독립 재계산 receipt

### B02 — revocation과 공통 guard FSM

- owner:
  P2a
- predecessor:
  B01 lifecycle types
- strict output:
  issue/consume/lease/renew/revoke 공통 transition table
- 필수 field:
  current head, event time, original hard deadline, retry deadline 상속 산식,
  renewal ordinal, one-use, unrevoked, consumed state, failure predecessor
- positive:
  A/B/C/recovery legal edge 전수
- negative:
  deadline reset, expired/revoked consume, duplicate consume, wrong head,
  ordinal skip
- verifier/receipt:
  legal/illegal edge exhaustiveness와 unreachable state 0 receipt

### B03 — literal capability pair와 physical row

- owner:
  P2a
- predecessor:
  B17, B01
- strict output:
  stage별 signed capability row
- 각 row exact field:
  `{operation, path, parent_anchor, object_type, publisher, signature_domain}`
- 금지:
  자연어 alias, glob, operation×root 자동 cross-product, parent/sibling 확대
- positive:
  signed exact operation과 anchored root만 허용
- negative:
  alias expansion, undeclared operation, root escape와 wrong object type 거부
- verifier/receipt:
  signed bytes에서 모든 row를 독립 재열거한 equality receipt

### B04 — C/recovery receipt tagged extension

- owner:
  P5e
- predecessor:
  B09b, B10 verified result, B06 verified instance
- strict output:
  stage-discriminated receipt union
- C/recovery 필수 binding:
  `N26`, `T1`, `X1`, `V1`, capability rows,
  byte-equal `ApplicationReceiptTargetSpec`, predecessor와 executor phase
- positive:
  exact post-check target과 receipt가 같은 canonical bytes를 결속
- negative:
  common field-only, wrong phase, self/future reference와 untyped extension 거부
- verifier/receipt:
  strict schema/JCS, SCC cycle 0과 byte-equality receipt

### B05 — D~G future activation artifact

- owner:
  P2b
- predecessor:
  B01/B02/B03 authority model
- strict output:
  `NON_OPERATIVE_FUTURE_LABEL_ONLY` boundary와 stage별
  `FutureActivationApproval`
- canonical role:
  `authority/future/{stage}/activation-approval.json`
- 필수 field:
  stage tag, exact frozen future successor SHA, 같은 SHA를 본 two-review
  receipt SHA, predecessor receipt, nonce, scope, expiry, revocation,
  one-use, publisher와 signature domain
- positive:
  stage D, E, F, G가 서로 다른 subject와 approval로만 열림
- negative:
  H1/H2 review·grant·receipt, 현재 token 또는 다른 stage approval 재사용 거부
- verifier/receipt:
  cross-stage conversion path 0과 one-stage-only receipt

### B06 — N26/T1/X1/StageCReviewBinding/V1 DAG

- owner:
  P3b schema/topology, P5d instance verification
- predecessor:
  B10 schema foundation; instance 단계는 B09b와 B10 evidence
- strict output:
  각 객체의 strict payload/type, canonical path, publisher,
  domain-separated hash/signature formula
- required order:
  inward-only topological order로 `N26 → T1/X1 → StageCReviewBinding → V1`
- positive:
  독립 구현 두 개가 같은 canonical input bytes와 digest 계산
- negative:
  label-only, self/future reference, publisher mismatch와 actual bytes 누락 거부
- verifier/receipt:
  schema topology receipt와 별도 instance digest-equality receipt

### B07 — Stage-B exact10 result와 repeat oracle

- owner:
  P4
- predecessor:
  B08 inputs, B13 RuntimeActual
- scope:
  두 환경 × BEFORE/AFTER/regression-A/regression-B/AFTER-repeat
- strict output:
  invocation별 raw path, parsed evidence path, per-assertion extractor
  Physical, parser, expected/actual/result, signed wrapper와 publisher
- 상태:
  `PASS/FAIL/NOT_RUN`
- positive:
  raw bytes에서 exact10과 environment aggregate 및 repeat equality 재계산
- negative:
  opaque evidence, partial invocation, wrong environment와 repeat 누락 거부
- verifier/receipt:
  invocation cardinality, assertion equality와 repeat byte-equality receipt

### B08 — Stage-B source manifests와 SandboxIntent

- owner:
  P4
- predecessor:
  B17 physical union, P0 inventory
- strict output:
  세 signed `StageBSourceInputManifest`와 complete `SandboxIntent`
- 필수 field:
  ordered physical member, sandbox alias, set digest, publisher, predecessor,
  literal argv/bind/environment/input/output/tracer
- publication order:
  actual reference → input manifest → command manifest
- negative:
  implicit alias, broad mount, undeclared environment와 manifest 전 publish 거부
- verifier/receipt:
  actual sandbox argv와 signed intent byte equality

### B09 — Stage-C live root의 두 단계 봉인

- owner:
  P5a와 P5c
- B09a strict output:
  실제 checkpoint durability 뒤 발행되는 `StageCLiveRootManifest`와 physical
  publication receipt의 schema
- 필수 field:
  627 member, exact26 physical identity, U1 physical identity/digest,
  seq40 tail, exclusions와 checkpoint application receipt
- B09b strict output:
  모든 exact6 intent/input/result가 같은 B09a manifest를 결속했다는 별도
  integration receipt
- positive:
  독립 재열거한 root와 manifest exact equality
- negative:
  pre-checkpoint root, generic `DirPhysical`, invocation별 다른 root와 U1 누락
- verifier/receipt:
  B09a publication과 B09b integration을 서로 다른 receipt로 기록

### B10 — exact6 schema, input, command와 assertion oracle

- owner:
  P3a schema foundation, P5b construction/verification
- strict output:
  six typed input manifests/bundles, command framing,
  assertion extractor/result/verifier
- expected command count:
  `1/1/1/1/1/2`
- 필수 field:
  physical raw source, parser, expected, actual, PASS/FAIL, producer,
  canonical path와 framing digest
- positive:
  B09a root를 읽는 immutable raw evidence에서 six result 독립 재계산
- negative:
  string-only role, untyped assertion, row006 두 command 혼합과 B09b 선참조 거부
- verifier/receipt:
  schema-foundation receipt와 P5b actual construction receipt 분리

### B11 — generated scratch 결정성

- owner:
  P1b
- predecessor:
  P0 exact generated inventory, B17
- strict output:
  literal `GeneratedScratchPolicy`, exclusion/collision order, empty/seed
  placeholder 규칙, pre/post digest
- positive:
  P0에서 실제 동결한 전체 target을 두 번 materialize해 digest equality
- negative:
  glob 제외, source collision, stale scratch와 mutable inventory 재사용 거부
- verifier/receipt:
  clean-room repeat materialization equality receipt

### B12 — BEFORE/AFTER projection schema와 단일 DAG

- owner:
  P1b
- predecessor:
  B17, B11
- strict output:
  type-correct `BeforeProjectionManifest`, `AfterProjectionManifest`,
  각 receipt와 하나의 normative publication DAG
- positive:
  유일한 topological order로 두 projection 구성
- negative:
  상충 order, missing/extra field와 noncanonical cast 거부
- verifier/receipt:
  topological order cardinality 1, graph cycle 0 receipt

### B13 — RuntimeActual producer/consumer/use chain

- owner:
  P4
- predecessor:
  B12 projection
- strict output:
  resolved-subject payload/hash, exhaustive consumer map, use receipt와
  normalized-output producer/path/schema
- 소비자:
  local full19, hosted full19, Stage-C exact6, recovery
- positive:
  actual trace consumer와 declared map exact equality
- negative:
  exact6/recovery 누락, owner 없는 normalized output와 hash domain 누락 거부
- verifier/receipt:
  모든 consumer가 같은 scalar와 use receipt를 결속

### B14 — full19 raw assertion extraction과 framing hash

- owner:
  P4
- predecessor:
  B13
- strict output:
  19개 row별 raw evidence, extractor executable Physical, parser/JSON pointer,
  normalization, expected/actual/result
- multi-command 필수 field:
  command ordinal, raw path, byte offset, byte length, command SHA-256,
  전체 framing manifest digest
- positive:
  immutable raw bytes만으로 19개 semantic assertion 재계산
- negative:
  `REVIEWED_EXIT_CONTRACT`, rc0-only, hash 없는 framing과 stream 혼합 거부
- verifier/receipt:
  reviewer 설명 없이 같은 결과를 만드는 independent result receipt

### B15 — Git·row18 complete closure

- owner:
  P4
- predecessor:
  B17, P0 repository inventory
- strict output:
  complete source/test/control/.git member arrays, gitfile→external gitdir
  anchored mapping, `GIT_ENVIRONMENT`, Git executable Physical
- positive:
  actual trace와 literal closure equality, undeclared read 0
- negative:
  broad prefix, external gitdir/Git executable 누락과 path escape 거부
- verifier/receipt:
  row18과 모든 Git consumer의 sandbox replay receipt

### B16 — FutureSealed single producer

- owner:
  P3b
- predecessor:
  B06 topology, M04 graph foundation
- strict output:
  role별 exactly-one producer, input manifest, canonical output path,
  resolution barrier
- positive:
  producer 1, resolved role 1
- negative:
  lane-d-final/after-control 이중 소유, producer 0와 premature consume 거부
- verifier/receipt:
  duplicate 0, unresolved 0, cycle 0 receipt

### B17 — mixed tree physical tagged union과 origin 전체 적용

- owner:
  P1a
- predecessor:
  P0 mutable inventory
- strict output:
  regular/directory/symlink/direct-system-origin을 구분하는
  `TreeMemberPhysical`
- 필수 field:
  relative link target, lstat identity, parent anchor, no-follow/no-escape,
  anchored copy, tree digest
- 적용 범위:
  source/projection/role binding, `MemberOriginMap`, lock, archive와
  direct-system-origin branch 전부
- positive:
  P0에서 동결한 symlink/directory/origin 전체를 보존한 repeat projection
- negative:
  absolute link, root escape, target type mismatch와 FilePhysical-only 축소
- verifier/receipt:
  모든 member consumer가 같은 tagged union을 사용한다는 exhaustiveness receipt

### B18 — source-only BEFORE 실행

- owner:
  P4
- predecessor:
  B08/B12, debt D-A와 D-B
- strict output:
  BEFORE current positional CLI/input과 AFTER successor CLI/routing을 분리한
  literal spawn contract
- positive:
  frozen current-control projection에서 current runner bytes가 지원하는
  BEFORE command의 실제 isolated spawn 성공
- negative:
  live unassigned inventory를 숨김, absent successor flag 요구,
  AFTER overlay 또는 stale runner count 사용 거부
- verifier/receipt:
  argv/environment/input SHA, exit/output와 process trace를 결속한 spawn receipt

## 7. 4 MAJOR closure specification

### M01 — row15 executable closure

- owner:
  P4
- predecessor:
  final sealed runner schema
- strict output:
  final runner의 모든 subprocess executable Physical과 role 집합
- 최소 조사:
  `dirname`, `mktemp`, `chmod`, `rm`, `find`, `sort` 및 trace에 나타난 전부
- positive:
  subprocess trace와 declared closure exact equality
- negative:
  실행파일 누락, broad PATH authority와 근거 없는 “제거됨” 주장
- verifier/receipt:
  undeclared executable 0 receipt

### M02 — exhaustive typed late-bound role

- owner:
  P1b
- predecessor:
  B17/P0 repository inventory
- strict output:
  폐쇄형 `LATE_BOUND_ROLE` enum, allowed phase, constructor와 wrong-phase checker
- Stage A actual 허용:
  executable, environment, package closure
- Stage A actual 금지:
  future live root, transaction, exact26, U1, repository inventory,
  RuntimeActual scalar
- positive:
  각 허용 role이 지정 phase에서만 구성
- negative:
  enum 밖 role과 future role 조기 구성 거부
- verifier/receipt:
  exhaustive enum cardinality와 wrong-phase negative receipt

### M03 — recovery scope 분리

- owner:
  P2a
- predecessor:
  B02 FSM, B03 capability rows
- branch 1:
  `EXACT6_SUFFIX`는 adoptable durable prefix와 never-dispatched suffix만
- branch 2:
  `APPLICATION_FINALIZATION`은 actual `POSTCHECK_PASSED` 뒤만
- positive:
  각 branch가 자기 predecessor와 capability만 소비
- negative:
  cross-use, `POSTCHECK_FAILED` 확대와 공통 capability 재사용 거부
- verifier/receipt:
  두 tagged branch의 intersection 0 receipt

### M04 — canonical review-subject graph bytes

- owner:
  P3b
- predecessor:
  B01 prefix-successor edge, B06/B16 node schema
- strict output:
  node/edge canonical serialization, deterministic order, domain separator,
  exact digest-input byte sequence, producer/checker Physical
- ownership:
  prefix-successor edge는 B01에서 한 번 생산하고 M04 graph가 한 번 소비
- positive:
  모든 nested Physical이 정확히 한 node/edge로 들어가고 독립 digest 일치
- negative:
  missing/duplicate node, implementation-defined order와 edge 이중생산 거부
- verifier/receipt:
  expected cardinality와 graph digest equality receipt

## 8. 실제 22행 closure matrix 요구

다음 표는 미래 successor가 만들어야 할 22행의 단일 소유권과 predecessor를
고정한다. `row class`는 현재 계획상 요구사항이며 실제 disposition이 아니다.

| ID | owner | normative predecessor | strict output 요약 | row class |
|---|---|---|---|---|
| B01 | P2a | B17, P0 prefix | grant/review-consume/prefix lifecycle | `REQUIRED_FUTURE_ROW` |
| B02 | P2a | B01 | common guard/revocation FSM | `REQUIRED_FUTURE_ROW` |
| B03 | P2a | B17, B01 | literal physical capability rows | `REQUIRED_FUTURE_ROW` |
| B04 | P5e | B09b, B10, B06-instance | C/recovery tagged receipt | `REQUIRED_FUTURE_ROW` |
| B05 | P2b | B01, B02, B03 | D~G future activation artifact | `REQUIRED_FUTURE_ROW` |
| B06 | P3b/P5d | B10-schema; B09b/B10-instance | N26/T1/X1/V1 topology+instance | `REQUIRED_FUTURE_ROW` |
| B07 | P4 | B08, B13 | exact10 raw/parsed/repeat oracle | `REQUIRED_FUTURE_ROW` |
| B08 | P4 | B17, P0 inventory | source inputs/SandboxIntent | `REQUIRED_FUTURE_ROW` |
| B09 | P5a/P5c | G4; B10 result | live-root publish+integration | `REQUIRED_FUTURE_ROW` |
| B10 | P3a/P5b | B09a for instance | exact6 schema/input/oracle | `REQUIRED_FUTURE_ROW` |
| B11 | P1b | B17, P0 scratch inventory | generated determinism | `REQUIRED_FUTURE_ROW` |
| B12 | P1b | B17, B11 | BEFORE/AFTER single DAG | `REQUIRED_FUTURE_ROW` |
| B13 | P4 | B12 | RuntimeActual use chain | `REQUIRED_FUTURE_ROW` |
| B14 | P4 | B13 | full19 raw/framed assertion | `REQUIRED_FUTURE_ROW` |
| B15 | P4 | B17, P0 Git inventory | Git/row18 complete closure | `REQUIRED_FUTURE_ROW` |
| B16 | P3b | B06 topology, M04 | FutureSealed single producer | `REQUIRED_FUTURE_ROW` |
| B17 | P1a | P0 inventories | mixed-tree tagged union | `REQUIRED_FUTURE_ROW` |
| B18 | P4 | B08, B12, D-A, D-B | source-only BEFORE spawn | `REQUIRED_FUTURE_ROW` |
| M01 | P4 | final runner schema | executable closure | `REQUIRED_FUTURE_ROW` |
| M02 | P1b | B17, P0 inventory | typed late-bound enum | `REQUIRED_FUTURE_ROW` |
| M03 | P2a | B02, B03 | disjoint recovery branches | `REQUIRED_FUTURE_ROW` |
| M04 | P3b | B01, B06, B16 | canonical graph bytes | `REQUIRED_FUTURE_ROW` |

미래 actual closure row에는 위 요약 외에 다음 field가 모두 있어야 한다.

```text
finding_id
owner_phase
predecessor_artifact_sha[]
strict_output_path[]
strict_output_schema_sha[]
producer_physical
positive_fixture_id[]
negative_fixture_id[]
verifier_physical
verifier_argv[]
expected_exit
raw_evidence_path[]
verification_receipt_sha[]
authority_ceiling
disposition
```

## 9. 네 regression debt gate

네 debt는 R007의 22 finding 수에 합치거나 새 finding으로 중복 계산하지
않는다. 그러나 모두 G4와 G6의 필수 predecessor다.

| debt ID | immutable input | required output·oracle | owner | 현재 |
|---|---|---|---|---|
| D-A lock epoch | backend/local-test/hosted lock physical manifest | exact pip-tools/runtime/input, isolated two-run lock equality, live write 0 | P4 lock lane | `OPEN` |
| D-B runner epoch | runner SHA, discovered/assigned/unassigned manifest | current-control/historical/non-running staged 분리, current CLI isolated spawn | P4 runner lane | `OPEN` |
| D-C Gateway epoch | historical exact4 receipt와 live exact5 route inventory | 두 epoch typed validation과 `/api/field-walk` current evidence | P4 Gateway lane | `OPEN` |
| D-D artifact epoch | 20260722 README identity와 current README identity | historical replay/current validation dual receipt | P4 artifact lane | `OPEN` |

각 debt row도 exact input SHA, output path, verifier Physical/argv,
positive/negative fixture, expected result와 receipt를 가져야 한다.

```text
debt row count = 4
unique debt ID = 4
debt OPEN = 0       # G6 PASS 시점
debt CLOSED_CANDIDATE = 4
missing debt evidence = 0
```

live runner, lock, Gateway 또는 artifact 파일을 이 로드맵에 맞추려고 즉석
수정하지 않는다. successor가 정의한 isolated projection에서 먼저 재현하고,
live 변경은 나중 단계의 exact reviewed write set과 별도 승인이 있을 때만
가능하다.

## 10. G6 fail-closed disposition

P6는 schema를 합치는 단계이고 G6는 실제 evidence 뒤 disposition을 판정하는
단계다. 둘을 같은 사건으로 부르지 않는다.

G6 PASS exact predicate:

```text
closure row count = 22
unique finding ID = 22
BLOCKING row count = 18
MAJOR row count = 4
unowned = 0
multi-owned = 0
OPEN = 0
CLOSED_CANDIDATE = 22
verified completion receipt count = 22
failed_or_missing_evidence = 0
positive fixture failure = 0
negative fixture false-accept = 0
implicit alias/glob = 0
duplicate producer = 0
graph cycle = 0
future/self reference = 0
debt row count = 4
debt OPEN = 0
debt CLOSED_CANDIDATE = 4
```

row의 strict output, positive fixture, negative fixture, independent verifier,
raw evidence, completion receipt와 authority ceiling 중 하나라도
미검증이면 그 row는 `OPEN`이다. `OPEN>0`이면 target freeze 후 final P7
review를 시작하지 않는다.

G6 result는 successor target SHA와 evidence-manifest SHA를 함께 결속한
`closure/gates/g6/disposition.json`과 immutable raw verification bundle로
발행한다.

## 11. constructive verification 권한 순환 해소

실행 evidence가 있어야 successor를 최종 검수할 수 있지만 현재 권한은
`ABSENT_DENY_ALL`이다. 이를 일반 지시나 roadmap review로 우회하지 않는다.

### 11.1 specification과 actual evidence의 type 분리

```text
CONSTRUCTIVE_FIXTURE_SPEC
  = 설계 안에 포함되는 비실행 schema/fixture/verifier 계약

CONSTRUCTIVE_FIXTURE_EVIDENCE
  = 격리 root에서 실제 실행한 raw output/CAS manifest/receipt

STAGE_C_LIVE_ROOT
  = 미래 H2의 실제 checkpoint 이후 live root

STAGE_C_APPLICATION_RECEIPT
  = 미래 H2의 actual fenced apply/post-check receipt
```

`CONSTRUCTIVE_FIXTURE_EVIDENCE`는 actual Stage-C live root나 application
receipt가 아니다. H1 fixture는 synthetic/isolated root만 사용하고
checkpoint·canonical·product bytes를 바꾸지 않는다.

### 11.2 V0 authority bridge

실제 fixture가 필요한 경우 다음 순서를 지킨다.

1. future successor candidate exact bytes를 freeze한다.
2. fixture/verifier/input bundle을 freeze하고 CAS manifest SHA를 만든다.
3. 별도 add-only `CONSTRUCTIVE_FIXTURE_AUTHORITY_BRIDGE`를 작성한다.
4. bridge에 다음 exact field를 넣는다.
   - successor SHA와 fixture bundle SHA
   - isolated root Physical
   - exact read/write/exec set
   - write set이 격리 add-only evidence path뿐이라는 조건
   - nonce, hard deadline, revocation, one-use와 consumed state
   - network, secret, external service와 live checkpoint write 금지
   - raw output/receipt path와 crash/recovery truth table
5. bridge exact SHA에 formal/skeptical `AUTHORITY_BRIDGE_REVIEW=0/0/0`을
   받는다.
6. 그 뒤에만 exact bridge subject 한정 사용자 승인을 질문할 수 있다.

bridge review는 approval이 아니다. 과거 사용자 일반 지시나 이 R002
`ROADMAP_REVIEW`는 bridge approval을 대신하지 않는다. 기술 인계서의
승인 경계와 bridge 필요성이 충돌한다고 판단되면 실행하지 말고 exact
충돌과 선택지만 사용자에게 제시한다.

### 11.3 V1 isolated evidence

정확한 bridge approval이 생긴 경우에만 다음을 실행한다.

1. mixed tree/generated scratch dry materialization
2. BEFORE/AFTER repeat projection digest equality
3. current CLI BEFORE와 successor CLI AFTER의 별도 spawn
4. 두 환경 exact10과 repeat oracle
5. full19 raw assertion 재계산
6. synthetic sealed root exact6 재계산
7. authority negative/replay/revocation/crash matrix

각 실행의 raw input/output/verifier/receipt를 한
`ConstructiveEvidenceCASManifest`에 결속한다. actual checkpoint 이후
live-root 또는 application receipt를 주장하지 않는다.

승인·환경이 없거나 fixture가 실패하면:

```text
V1 = NOT_RUN 또는 FAIL
G6 = FAIL
P7 final review = NOT_STARTED
PRE_P_SUCCESSOR_READY_PREDICATE = false
```

### 11.4 evidence freeze와 재검수

final review subject pair:

```text
successor_target_sha
constructive_evidence_manifest_sha
g6_disposition_sha
```

셋 중 하나라도 바뀌면 기존 `SUCCESSOR_PLAN_REVIEW`와
`CONSTRUCTIVE_EVIDENCE_REVIEW`를 재사용하지 않고 모두 새 revision으로
다시 수행한다.

## 12. P7 final review convergence

P7은 G6 PASS 뒤에만 시작한다.

필수 review 네 개:

1. formal `SUCCESSOR_PLAN_REVIEW`
2. skeptical `SUCCESSOR_PLAN_REVIEW`
3. formal `CONSTRUCTIVE_EVIDENCE_REVIEW`
4. skeptical `CONSTRUCTIVE_EVIDENCE_REVIEW`

독립성:

- successor 작성자와 merge 담당자는 formal/skeptical reviewer가 될 수 없음
- fixture/verifier 작성자와 evidence producer는 evidence reviewer가 될 수 없음
- formal과 skeptical reviewer는 서로의 결론을 복사하지 않음
- reviewer는 target/evidence를 수정하지 않음

G7 PASS:

```text
all four reviews bind same successor SHA = true
both evidence reviews bind same evidence manifest SHA = true
all four reviews bind same G6 disposition SHA = true
formal successor findings = 0/0/0
skeptical successor findings = 0/0/0
formal evidence findings = 0/0/0
skeptical evidence findings = 0/0/0
target/evidence mutation = 0
official delta = 0
```

하나라도 findings-zero가 아니면 target, evidence와 reviews를 history로
보존하고 add-only next revision으로 돌아간다.

## 13. H2 — journal과 Stage A→B→C, 정확히 한 번

H2는 `PRE_P_SUCCESSOR_READY_PREDICATE=true`만으로 자동 시작하지 않는다.
각 단계는 별도 사용자 승인, subject, nonce, scope와 one-use receipt가
필요하다.

```text
ready predicate 확인
→ journal-bootstrap 전용 승인
→ bootstrap-only receipt 검증
→ Stage A 전용 승인
→ immutable candidate/environment/pack build
→ candidate-bound independent review
→ Stage B 전용 승인
→ frozen subject validation
→ Stage C 전용 승인
→ fenced apply
→ post-check
→ Stage-C application receipt
```

각 단계 공통 stop:

- exact input/CAS mismatch
- authority expired/revoked/consumed
- wrong predecessor, subject 또는 nonce
- review finding 존재
- undeclared read/write/exec
- raw evidence/receipt 누락
- crash state가 truth table에 없음

Stage C가 끝나도 formal, 실제 기기, artifact, Gate, release credit은 0이다.

### 13.1 H2 result verification only

H2 뒤에는 `H2_RESULT_VERIFICATION_ONLY / NO_REEXECUTION`을 한 번 수행한다.

- 단일 입력:
  exact Stage-C application-receipt SHA
- 허용:
  receipt schema/signature/predecessor와 protected state의 read-only 확인
- 금지:
  journal, Stage A/B/C, post-check 또는 application receipt 재실행·재발행
- negative:
  consumed approval, nonce, grant 또는 receipt replay와 cross-stage reuse 거부

이 verification은 old Master S1이나 old R007 Stage A~C의 재실행 경로가
아니다.

## 14. H3 selector-preflight Stage D→E→F→G

H2 application receipt 뒤에도 D~G는 자동으로 열리지 않는다. 네 stage는
서로 다른 transaction이다.

| Stage | exact predecessor | 한 stage가 할 수 있는 일 | completion |
|---|---|---|---|
| D | H2 result-verification receipt | P-successor 설계와 same-SHA formal/skeptical review만 | D design-review receipt |
| E | D receipt + E 전용 approval | exact P17 candidate build와 candidate-bound review만 | E candidate-review receipt |
| F | E receipt + F 전용 approval | reviewed candidate의 attempt-scoped resolution만 | F resolved-subject receipt |
| G | F receipt + G 전용 approval | exact resolved P subject fenced apply만 | durable selector-ready application receipt |

각 stage의 필수 field:

- exact subject SHA
- predecessor receipt SHA
- 서로 다른 nonce, scope, hard deadline, revocation과 one-use state
- exact read/write/exec set
- strict output path/schema
- independent review
- completion/stop condition과 crash receipt

금지:

- D approval로 E/F/G 수행
- E candidate review를 F resolution review로 사용
- F receipt 없이 G apply
- old S1, old R007, 과거 approval/nonce/receipt 재사용
- G durable receipt 전 frontier 재계산, canonical/product/artifact credit

Stage G durable selector-ready receipt만 다음 main transition 설계의
predecessor가 된다.

## 15. H3 main control 전환과 frontier

기존 M15나 stale FP-048을 실행하지 않는다. Stage G receipt를 입력으로
main transition용 add-only successor를 새로 설계·검수·승인한다.

목표:

- v2.5와 r022 후보의 actual bytes·DAG·recovery 재검증
- checkpoint-last atomic apply
- stale pointer 제거
- 중간 실패 때 seq39/r021 또는 완전한 새 상태 중 하나만 유지
- artifact/formal/device/Gate/release 조기 credit 0

전환이 durable하게 끝난 뒤에만 live dependency를 다시 계산한다.

현재 참고값:

```text
focus = WS-GOAL-EPIC-03
ready frontier = EPIC-03, EPIC-12
materialized leaf = none
```

이 값은 미래 전환 뒤 재계산 대상이다. FP-008을 과거 계획대로 강행하지
않는다. 재계산 결과가 FP-008일 때도 별도 design/review/authorization 뒤
정확히 한 leaf만 materialize/start한다.

단일 Work Item loop:

```text
exact policy·Gap pair
→ fail-first acceptance
→ 최소 구현
→ targeted/component regression
→ implementation·verification evidence
→ Gap·Backlog successor
→ independent review
→ atomic canonical transition
→ next frontier
```

canonical `IN_PROGRESS` leaf는 동시에 하나다. 충돌 없는 조사·문서 준비·
독립 검증만 병렬화한다.

## 16. 제품 dependency와 257개 산출물

### 16.1 제품 dependency

- EPIC-02·03:
  보행 lifecycle, 관리자 업무, privacy deletion, 저장소 부분실패 복구,
  암호화·키 분리·회전·감사와 외부 복구수단
- EPIC-04·05·06:
  도착 확인, 이탈·재탐색, 승인 모델 fence, 거리·품질 gate, wake word,
  offline voice와 접근 가능한 안전정지
- EPIC-07~11:
  데이터 권리 lifecycle, 암호화 queue/reboot recovery, dataset/model
  동등성, 장시간·배터리·용량과 통합 후보

dependency:

```text
EPIC-01 → EPIC-02, EPIC-03
EPIC-02 → EPIC-04, EPIC-05, EPIC-06
EPIC-02 + EPIC-03 → EPIC-07
EPIC-02 + EPIC-03 + EPIC-07 → EPIC-08
EPIC-05 + EPIC-07 → EPIC-10
EPIC-03 + EPIC-07 + EPIC-08 → EPIC-09
EPIC-09 + EPIC-10 → EPIC-11
```

내부 구현만으로 실제 관리자, 기관, 실기기 또는 formal evidence를 대신하지
않는다.

### 16.2 산출물 lane

현재 공식 값:

```text
artifact complete = 126/257
open = 131
```

| Lane | 수 | 병렬 준비 | 실제 종료조건 |
|---|---:|---|---|
| A `INTERNAL_READY` | 62 | 내용·trace·내부 review | required content와 적격 승인 |
| B fact/owner/attest | 24 | request packet·owner map | 실제 사실·결정·독립 확인 |
| C `INTERNAL_RUN_REQUIRED` | 24 | 환경·명령·receipt schema | 실제 raw output·receipt·review |
| D `REAL_EVENT_PENDING` | 21 | 대상·권한·일정 준비 | 정당한 actual-event receipt |

```text
62 + 24 + 24 + 21 = 131
duplicate = 0
omitted = 0
```

root/merge 한 명만 canonical 후보를 작성한다. artifact 상태 상승 전
exact257 replay와 독립검수는 필수다. `126/257`을 프로젝트 완료율이라고
표현하지 않는다.

## 17. H4 formal 검증과 H5 release

### 17.1 H4 진입과 완료

진입:

- EPIC-04, 05, 06, 08, 09, 10, 11 완료
- 하나의 immutable candidate
- 승인된 formal plan, 환경, 기기, 참여자와 권한

동일 candidate로 수행:

- formal 279
- 실제 기기·현장 보행
- TalkBack·접근성
- 장시간 사용, 배터리, 발열, 용량
- 장애·재부팅·복구
- 보안·개인정보
- 모델·dataset·동등성

H4 완료 oracle:

```text
formal planned assertions = 279
formal PASS = 279
formal FAIL/NOT_RUN = 0
required actual device/event receipt = complete
candidate SHA mismatch = 0
independent review findings = 0/0/0
```

현재:

```text
formal PASS = 0/279
actual device/event = 0/0
```

### 17.2 H5 진입과 완료

H5는 H4 completion receipt를 exact predecessor로 요구한다.

1. release Gate 5개 모두 actual PASS, `waived=false`
2. 권한 있는 release decision
3. signed immutable candidate와 production configuration
4. deploy·canary·smoke
5. rollback·backup/restore·recovery
6. 운영 안정화·비용·권한·데이터 처리
7. 운영 이관 또는 승인된 종료

현재:

```text
Gate PASS = 0/5
production deployment = 0
release = NOT_ELIGIBLE
project = NOT_COMPLETE
```

## 18. 단계별 stop rule

### H1과 roadmap 단계

- 보호 manifest·R001/R007/reviews/checkpoint/r021/제품 파일 수정 즉시 중단
- add-only roadmap, successor plan, isolated fixture/verification bundle 밖
  write 즉시 중단
- authority bridge approval 전 실제 fixture 실행 금지
- finding/debt owner 누락·중복, alias/glob, cycle, future/self reference 시 중단
- 하나의 required review라도 findings-zero가 아니면 next revision으로 이동

### H2~H5 미래 단계

- predecessor는 immutable history로 보존
- 해당 stage의 exact reviewed write set과 one-use approval 안에서만 write
- 범위 밖 checkpoint/canonical/product 수정 즉시 중단
- R007, R001, r021 bytes는 계속 immutable
- add-only r022 successor와 승인된 checkpoint transition을 r021 변조로
  오인하지 않되, approval/write-set이 없으면 둘 다 금지
- 공식 credit은 해당 독립 gate와 receipt 뒤에만 허용

### 모든 단계 공통

- 비밀, 유료 자원, 실제 사람·기기·외부 서비스가 필요한데 권한이 없으면
  해당 lane을 `BLOCKED`로 미루고 독립 ready lane만 진행
- 증거를 만들기 위해 실제 사건을 조작하거나 mock을 actual로 승격 금지
- 진행되지 않는 항목은 blocker, 필요한 입력과 재개 predicate를 기록

## 19. 사용자 승인 시점

현재 사용자에게 요청할 실행 승인은 없다.

| 미래 시점 | 제시할 exact 정보 | 허용 범위 |
|---|---|---|
| V0 bridge reviews 0/0/0 뒤 | successor/fixture SHA, isolated root, read/write/exec set, nonce/deadline | constructive fixture 한 attempt만 |
| ready predicate 뒤 | successor/evidence/G6 SHA와 bootstrap scope | journal bootstrap만 |
| bootstrap receipt 뒤 | Stage A subject와 write set | candidate/environment/pack build만 |
| candidate-bound review 뒤 | Stage B subject와 commands | validation만 |
| Stage-B PASS 뒤 | Stage C subject, transaction과 recovery | fenced apply/post-check만 |
| Stage D 준비 뒤 | D design subject | D 설계·검수만 |
| Stage E 준비 뒤 | E candidate subject | E build/review만 |
| Stage F 준비 뒤 | F attempt subject | F resolution만 |
| Stage G 준비 뒤 | exact resolved subject | G apply만 |
| formal 준비 뒤 | candidate/plan/device/participant/authority | 승인된 시험 범위만 |
| release 준비 뒤 | five Gate와 운영 증거 | 명시된 release/deploy action만 |

“계속 진행해” 같은 일반 지시, 과거 승인, roadmap review 또는 다른 stage
receipt는 위 approval을 대체하지 않는다.

## 20. 팀 구성, 병렬화와 merge

| 역할 | 책임 |
|---|---|
| root/merge | 보호 manifest, 공통 type/domain/DAG, final freeze와 daylog |
| P1 lane | physical tree, scratch, projection, late-bound type |
| P2 lane | authority lifecycle, capability, recovery, D~G boundary |
| P3 lane | exact6 foundation, object topology, producer와 graph bytes |
| P4 lane | Stage-B, runtime, full19, Git, runner와 four debt |
| P5 lane | live-root, exact6 instance, integration과 C receipt |
| verification producer | 승인된 isolated V1 실행과 raw evidence |
| formal reviewer | 구성 가능성, schema, DAG, completion oracle |
| skeptical reviewer | 권한 확대, replay, crash, false-accept 반례 |

merge 규칙:

- lane은 자기 row 초안과 fixture만 작성
- 공통 schema/domain/publication DAG는 root/merge 한 명만 결속
- author/merge/evidence producer/reviewer 독립성은 §12를 따름
- 기존 frozen target은 수정하지 않고 finding 시 next revision 추가
- daylog는 root/merge가 한 번 통합

## 21. 단계별 성공기준과 예상 범위

| 단계 | 성공기준 | 계획용 예상 |
|---|---|---:|
| R002 roadmap review | same-SHA formal/skeptical `0/0/0` | 수시간~1일 |
| P0 | 보호·mutable inventory exact freeze | 1~2시간 |
| P1 | G1 PASS 가능한 type/projection contract | 1~2 작업일 |
| P2 | G2 PASS 가능한 authority FSM | 1~3 작업일 |
| P3 | G3 PASS 가능한 hash/producer graph | 1~3 작업일 |
| P4 | G4 raw oracle + debt 4건 closure | 2~5 작업일 |
| P5 | G5 acyclic Stage-C/exact6 receipt | 1~3 작업일 |
| V0 | bridge plan과 two reviews | 반나절~2 작업일 |
| V1 | isolated evidence | 1~3 작업일 이상 |
| G6/P7 | 22+4 closed와 four reviews | 1~3 작업일 이상 |
| H2 | 별도 승인 뒤 A→B→C | 환경·recovery에 따라 재산정 |
| D→G | 네 stage별 설계/검수/승인 | 각 stage 준비 뒤 재산정 |
| 제품·artifact | frontier와 dependency 기반 | 여러 작업일~수주 |
| H4~H5 | 사람·기기·외부 권한 종속 | 별도 일정 |

일정 단축을 위해 finding, debt, evidence나 review subject를 생략하지 않는다.

## 22. 다음 Codex용 실행 문장

```text
먼저 WALKSAFE-PROJECT-TECHNICAL-HANDOFF-20260731-R001.md,
WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R001.md와 두 rejected review,
그리고 WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R002.md와 그
adjacent review를 읽어라. G0 protected hashes와 v2.4 Quick2를 read-only로
재검산하라. 값이 모두 같고 R002 roadmap review가 같은 SHA에 대해
formal/skeptical 0/0/0이면 rejected R007을 수정·실행하지 말고 add-only
PRE_P_CLOSURE_SUCCESSOR의 첫 revision만 작성하라. R002의 22행+4 debt
matrix, phase DAG, G1~G7 GateExecutionSpec와 subject type을 그대로
구체화하라. target freeze 뒤 constructive fixture가 필요하면 먼저 별도
authority bridge를 설계하고 두 review 0/0/0 없이 approval을 질문하거나
실행하지 마라. evidence와 G6가 준비되면 같은 successor/evidence/G6 SHA를
formal/skeptical plan review와 formal/skeptical evidence review에 결속하라.
네 review 중 하나라도 0/0/0이 아니면 기존본을 보존하고 add-only next
revision으로 반복하라. ready predicate가 true여도 bootstrap 또는 Stage A
권한이 생겼다고 해석하지 마라.
```

## 23. R002 인계 완료조건

R002 자체의 인계 완료는 다음과 같다.

```text
R007 finding mapping = 18 BLOCKING + 4 MAJOR
actual planning rows = 22, unique IDs = 22
regression debt rows = 4, unique IDs = 4
G6 OPEN fail-closed predicate present = true
constructive spec/evidence/actual Stage-C types separated = true
authority bridge and approval timing separated = true
H2 A-C executes once = true
H2 result verification reexecution = false
Stage D→E→F→G separate transactions = true
phase-scoped stop rules = true
roadmap/successor/bridge/evidence review types separated = true
old S1/R007/stale FP-048 execution prohibited = true
current seq39/r021/r021 and official zero delta stated = true
R002 formal ROADMAP_REVIEW = 0/0/0
R002 skeptical ROADMAP_REVIEW = 0/0/0
```

이 완료는 R007 finding closure, future successor readiness, constructive
verification 실행, journal bootstrap, Stage A~G, 제품 진척, formal,
실기기, artifact closure, Gate, production 또는 release를 뜻하지 않는다.
