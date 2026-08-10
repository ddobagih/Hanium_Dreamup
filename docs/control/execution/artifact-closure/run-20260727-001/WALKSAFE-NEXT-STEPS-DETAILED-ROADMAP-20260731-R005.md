# WalkSafe 다음 단계 상세 로드맵 20260731 R005

- 문서 ID:
  `WS-WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R005`
- 작성일:
  `2026-07-31`
- 상태:
  `NONCANONICAL_PLAN_ONLY / NOT_EXECUTABLE / REVIEW_PENDING`
- 현재 실행/checkpoint/canonical 권한:
  `ABSENT_DENY_ALL`
- 현재 문서 작성 범위:
  explicit user request-bound documentation-only add-only
- 현재 control:
  v2.4 `ACTIVE`, sequence `39`, canonical Gap/Backlog `r021/r021`
- 공식 성과 delta:
  `PRODUCT/CHECKPOINT/CANONICAL/GOAL/ARTIFACT/FORMAL/DEVICE_EVENT/GATE/PRODUCTION/RELEASE=0`

## 0. 목적, 효력과 현재 사실

이 문서는 rejected PRE-P R007의 `18 BLOCKING + 4 MAJOR`를 닫을 미래
add-only successor를 만들고 검증하는 독립 실행 계약 수준의 로드맵이다.
R004 또는 기존 문서를 수정하지 않는다. 이 R005 자체도 plan-only이며
아래 allowlist, schema와 순서는 어느 write나 실행의 권한이 아니다.

### 0.1 documentation scope와 execution authority 분리

`ABSENT_DENY_ALL`은 V1, H2~H5, checkpoint, canonical, product, external,
production과 release 권한의 부재를 뜻한다. 현재 explicit user request가
허용한 별도 documentation-only 범위는 공개 요약, roadmap, 독립 review,
daylog와 그 후속 add-only 계획/control 문서 작성뿐이다. 이것을 constructive
execution authority로 해석하지 않는다.

현재 R005 작성 범위의 근거는 이 user-request session에만 유효하다. root/
merge가 미래 durable pre-successor write를 시작하려면 먼저 그 세션의
immutable origin을 검증한 signed wrapper-only
`DocumentationScopeProvenanceReceipt`를 materialize해야 한다.

```text
scope_type = DOCUMENTATION_ADD_ONLY
immutable_user_request_origin_ref
origin_provenance_verifier_physical + verifier_sha + literal_argv
request_digest
issuer_actor_id + issuer_session_id
authorized_session_id[]
authorized_publisher_role_id[]
allowed_subject_type[] = {
  PUBLIC_SUMMARY,ROADMAP,ROADMAP_REVIEW,DAYLOG,FUTURE_PLAN_CONTROL
}
literal_read_set[]
literal_add_only_write_set[]
allowed_ephemeral_observation =
  REVIEWED_G0_WITH_WRITE_SET_EMPTY_ONLY
execution/checkpoint/canonical/product/external write = DENY
fresh_documentation_nonce
issued_at + hard_deadline + trusted_clock_source
status = PROVENANCE_VERIFIED
signature_domain
```

receipt의 exact write set에는 해당 세션이 만들 roadmap/review/daylog/G0
spec/P0/successor 계획 artifact의 literal path, schema와 publisher role이
있어야 한다. reviewer마다 authorized session/role이 있어도 §2 actor
independence는 완화되지 않는다. roadmap, successor, allowlist 또는 과거
receipt는 이 scope를 만들거나 확장하지 못한다.

미래 세션의 receipt가 missing, expired, replayed, wrong request/session/
path/schema/publisher이면 그 세션의 pre-successor durable write와 G0
ephemeral probe는 모두 `NOT_RUN`이다. continuation에는 fresh documentation
provenance가 필요하며, 이 scope로 V1 user authority를 대신할 수 없다.

R003, R004와 각 review는 history로 보존한다.

| exact input | SHA-256 | review/disposition |
|---|---|---|
| R003 roadmap | `9f72ac89504ed2f3fdee5d50a53713acbb31068cbf4e43a1dce2a000ad618a62` | rejected history |
| R003 formal roadmap review | `0abe4ea3e84d223d08b4ea350e8f9ce3fbf31597fd2b50fd9b7ab4ab36564c91` | `4 BLOCKING / 2 MAJOR / 0 MINOR` |
| R003 skeptical roadmap review | `e32fab4b38c6c7ddb32a65ec51ba0f1a17f9a1199dd05f654c07d87f4fd0a65b` | `7 BLOCKING / 7 MAJOR / 0 MINOR` |
| R004 roadmap | `7c559aab44788608079c9b9768659e227e5e66e672fedb750210b601bf6b9209` | rejected history, `160363 bytes / 3910 lines` |
| R004 formal roadmap review | `fdac0201acc412e9251bcfc7fa60c6c7dd3ae5a8dcf5f10645cfdf8ce6c1b448` | `8 BLOCKING / 3 MAJOR / 0 MINOR` |
| R004 skeptical roadmap review | `15e023b75c479bce8747ff5c32ad4e62fa1fc5b68329ccf7cec880e9b1c824db` | `10 BLOCKING / 3 MAJOR / 0 MINOR` |

R005가 R004 위에 추가 교정하는 범위는 exact `10 BLOCKING / 3 MAJOR`다.
terminal cut, intermediate abort, revocation ordinal, close-tail recovery,
runtime graph projection, retry namespace/deadline, H4 self-cycle,
B04/B06 physical schema, execution terminal enum, H2 consume binding과 M02
closed enum 외 기능은 추가하지 않는다. R004가 보존한 기존 핵심은 다음과
같다.

- G0를 파일, 임시 디렉터리, redirect, receipt가 전혀 없는 진짜 ephemeral
  read-only probe로 바꾼다.
- transient G0 관찰을 P0가 최초 durable record로 동결한 뒤 successor를
  동결하고, 그 다음에만 G1~G5 SPEC을 순서대로 발행한다.
- 모든 gate에 existing exact predecessor result/receipt, subject, attempt,
  authority lineage와 trusted-clock publication DAG를 결속한다.
- H1의 모든 mandatory spec/raw/result/receipt role을 폐쇄적으로 열거한다.
- user decision provenance와 매 attempt fresh one-use lineage를 강제한다.
- V1을 무순환 exact 15 evidence task로 고정하고 M01/Stage-C foundation과
  finalization, M04a/M04b와 D-A~D-D를 직접 검증한다.
- 별도 `G6Disposition`, named G6/G7 binding과 유일한 ready predicate를
  정의한다.
- B09 actual live-root physical publication, B01 detached wrapper, M04
  producer Physical, P0 inventory, 단일 dependency edge manifest를 복원한다.
- H4 안에서 five Gate의 `must_close_before`를 지키고 승인된 N/A를
  formal 279 산술에 포함한다.

현재 사실은 변하지 않는다.

```text
R007_FINDINGS_CLOSED=false
PRE_P_CLOSURE_SUCCESSOR_EXISTS=false
PRE_P_SUCCESSOR_READY=false
AUTHORITY_BOUNDARY_DECISION_EXISTS=false
CONSTRUCTIVE_FIXTURE_AUTHORIZED=false
JOURNAL_OR_STAGE_A_TO_G_AUTHORIZED=false
CHECKPOINT_OR_CANONICAL_WRITE_AUTHORIZED=false
PRODUCT_OR_RELEASE_AUTHORIZED=false
ARTIFACT_CLOSED_EQUIVALENT=126/257
ARTIFACT_OPEN=131
FORMAL=0/279
GATE=0/5
PRODUCTION=0
RELEASE=NOT_ELIGIBLE
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
| R001 formal review | `bdaf1771fcd9974a5c5e4728ee45d90759b86a08ff8934136e94e9d18e9c7ffa` | 읽기 전용 |
| R001 skeptical review | `9c6c8267c9459a3090c9b78bf0ba9338e4d7f5da44ad013d8e6036b258a4b12c` | 읽기 전용 |
| R002 roadmap | `a8a4a11505a3652fff051e78aafa56a2f15828b398e80b24d0280b3f31bdc77e` | rejected history |
| R002 formal review | `d9f2004ed8d9f430154d3bd1e64fa11c03beb1b83ac1e01f683d9cc0b1429547` | 읽기 전용 |
| R002 skeptical review | `c73dcac1ca7c31349f5fe40f0f8e6c0d9936467268dd46744264cec76cd8c6bb` | 읽기 전용 |
| R003 roadmap | `9f72ac89504ed2f3fdee5d50a53713acbb31068cbf4e43a1dce2a000ad618a62` | rejected history |
| R003 formal review | `0abe4ea3e84d223d08b4ea350e8f9ce3fbf31597fd2b50fd9b7ab4ab36564c91` | 읽기 전용 |
| R003 skeptical review | `e32fab4b38c6c7ddb32a65ec51ba0f1a17f9a1199dd05f654c07d87f4fd0a65b` | 읽기 전용 |
| R004 roadmap | `7c559aab44788608079c9b9768659e227e5e66e672fedb750210b601bf6b9209` | rejected history, 읽기 전용 |
| R004 formal review | `fdac0201acc412e9251bcfc7fa60c6c7dd3ae5a8dcf5f10645cfdf8ce6c1b448` | `8/3/0`, 읽기 전용 |
| R004 skeptical review | `15e023b75c479bce8747ff5c32ad4e62fa1fc5b68329ccf7cec880e9b1c824db` | `10/3/0`, 읽기 전용 |
| v2.4 checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | seq39, 읽기 전용 |
| static v2.4 manifest | `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07` | 읽기 전용 |
| canonical Gap r021 | `f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a` | 읽기 전용 |
| canonical Backlog r021 | `bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0` | 읽기 전용 |
| DOC-01 | `a0c993257c1b80211c2e8e4db5aa464708ac4aba9d47ed9b434eda277ee6547f` | 읽기 전용 |
| DOC-05 | `cea8b58fc5afa527614522481df56d69618c6fd1015c10b63d6cc8b6e0678c67` | 읽기 전용 |
| runner | `4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d` | 읽기 전용 |

미래 `G0ExecutionSpec`은 이 표뿐 아니라 exact R005의
`path/SHA-256/bytes/lines/mode/type/nlink`와 실제 두 R005
`ROADMAP_REVIEW`의 같은 physical identity 및 SHA를 추가한다. R005 내용에
자기 SHA를 쓰지 않으며, R005 freeze 뒤 G0 spec이 actual 값을 결속한다.

### 1.2 실행 금지

- Master 인계서 old S1 exact seq39→40/P17
- PRE-P R001~R007의 명령, 승인 문구, nonce와 receipt
- continuation R004~R009 실행 블록
- stale FP-048 포인터와 기존 M15
- 과거 exact4 receipt를 current exact5 Gateway 증거로 재해석
- 과거 approval/review/receipt를 다른 subject, attempt 또는 class에 재사용
- R005 review findings-zero 전 G0 spec, G0 PASS 전 P0/successor
- fresh governing decision 전 V1 또는 user approval 질문

## 2. subject type과 공통 review identity

| tagged subject | exact 대상 | 최대 효력 |
|---|---|---|
| `ROADMAP_REVIEW` | R005 exact SHA | 비권한 roadmap 품질 판정 |
| `G0_SPEC_REVIEW` | G0 spec exact payload/wrapper SHA | ephemeral probe 설계 판정 |
| `SUCCESSOR_PLAN_REVIEW` | PRE-P successor exact SHA | successor 설계 판정 |
| `AUTHORITY_BRIDGE_REVIEW` | V0 bridge exact SHA | isolated scope 설계 판정 |
| `AUTHORITY_BOUNDARY_DECISION` | fresh request/response/bridge lineage | V1 한 attempt 허용 또는 거부 |
| `CONSTRUCTIVE_EVIDENCE_REVIEW` | successor+CAS+G6 disposition | evidence 품질 판정 |

서로 다른 tagged subject는 서로를 만족시키거나 권한으로 변환되지 않는다.

모든 ROADMAP, G0_SPEC, SUCCESSOR_PLAN, AUTHORITY_BRIDGE,
CONSTRUCTIVE_EVIDENCE review는 같은 strict `ReviewReceiptIdentity`를 쓴다.

```text
review_receipt_schema_sha
review_class = FORMAL | SKEPTICAL
reviewer_actor_id
canonical_actor_alias_id
reviewer_session_id
reviewer_credential_or_tool_id
tool_and_version
reviewed_subject_type
reviewed_payload_sha[]
reviewed_publication_receipt_sha[]
subject_author_actor_id[]
subject_merge_actor_id[]
subject_producer_actor_id[]
review_started_at
review_ended_at
trusted_clock_source
signature_domain
findings = {blocking,major,minor}
```

각 pair verifier는 다음을 기계적으로 확인한다.

```text
formal_actor != skeptical_actor
formal_canonical_alias != skeptical_canonical_alias
formal_session != skeptical_session
reviewer_set ∩ subject_author_or_merge_set = ∅
ROADMAP/G0/SUCCESSOR reviewer_set ∩ subject producer set = ∅
BRIDGE reviewer_set ∩ bridge author/merge/requester set = ∅
EVIDENCE reviewer_set ∩ fixture/verifier author/evidence producer set = ∅
reviewed subject type/SHA/publication receipt exact equality
both findings = 0/0/0
```

missing identity, 같은 actor의 session alias, 같은 credential alias,
self-review, wrong subject class, wrong SHA, post-review mutation은 pair
receipt 발행을 거부한다. 이 공통 검사는 R005 review pair, G0 review pair,
V0 bridge pair와 P7 두 pair 각각의 다음 단계 전에 수행한다.

## 3. 전체 단계와 유일한 normative DAG

### 3.1 R005에서 P0까지

```text
R005 payload freeze under current explicit request-bound authoring scope
→ fresh continuation/review-session DocumentationScopeProvenanceReceipt
   PROVENANCE_VERIFIED
→ exact documentation add-only write-set admission
→ formal ROADMAP_REVIEW
→ skeptical ROADMAP_REVIEW
→ same-R005-SHA RoadmapReviewPairReceipt 0/0/0
→ G0ExecutionSpecPayload
→ detached G0SpecPublicationReceipt
→ formal G0_SPEC_REVIEW
→ skeptical G0_SPEC_REVIEW
→ same-spec-SHA G0ReviewPairReceipt 0/0/0
→ G0 ephemeral read-only probe
→ transient G0ObservationEnvelope PASS
→ immediate protected snapshot recheck
→ P0InventoryManifestPayload
→ detached P0FreezeReceipt
```

G0 actual durable output cardinality는 정확히 `0`이다. P0가 G0 관찰의 최초
durable record다.

### 3.2 P0에서 G5-SPEC까지

```text
P0FreezeReceipt PASS
→ P0AllowedAddOnlyDeltaManifest
→ P0SyntheticMemberDerivationReceipt
→ P1~P5 closure contract drafting
→ P6 integrated PRE_P_CLOSURE_SUCCESSOR payload freeze
→ detached SuccessorPublicationReceipt
→ tested-successor-bound ClosureDependencyEdgeManifest payload/receipt
→ G1-SPEC spec freeze/publication → execute → result/publication PASS
→ G2-SPEC spec freeze/publication → execute → result/publication PASS
→ G3-SPEC spec freeze/publication → execute → result/publication PASS
→ G4-SPEC spec freeze/publication → execute → result/publication PASS
→ G5-SPEC spec freeze/publication → execute → result/publication PASS
```

successor payload/publication receipt가 존재하기 전에 G1~G5 SPEC을 쓰거나
실행하지 않는다. 각 `Gn-SPEC` spec은 바로 전 gate의 existing exact PASS
result payload와 publication receipt를 결속한 뒤에만 freeze한다. P1~P5는
successor 초안의 owner work이지 gate PASS가 아니다.

allowed-delta와 synthetic derivation은 P0 payload를 바꾸지 않는 별도
signed wrapper-only artifact다. 둘 다 exact documentation scope 안에서
successor freeze 전에 발행하며 B09a는 세 P0 lineage receipt를 직접
결속한다.

### 3.3 V0, V1, G6, P7, G7

```text
G5-SPEC PASS
→ pre-authority formal/skeptical SUCCESSOR_PLAN_REVIEW 0/0/0
→ PreAuthoritySuccessorReviewPairReceipt
→ V0 bridge payload/publication
→ formal/skeptical AUTHORITY_BRIDGE_REVIEW 0/0/0
→ BridgeReviewPairReceipt
→ fresh UserAuthorityRequest
→ immutable explicit UserAuthorityResponse
→ AuthorityBoundaryDecisionEnvelope
→ if DENY: V1/finalization/G1E..G7/ready NOT_RUN
→ if ALLOW_ONE_ISOLATED_ATTEMPT:
     PostCloseFinalizationAuthorityGrant freeze/publication
     → CloseRecoveryAuthorityGrant freeze/publication
     → 15 V1TaskExecutionSpec freeze
     → one-use AuthorityConsumeReceipt
     → V1 T01~T15 signed result, 또는 crash/expiry 시 관찰된 strict prefix
     → exactly one execution terminal:
          SUCCESS | FAILURE | CRASH | EXPIRED
     → POST_CLOSE_FINALIZATION_ONLY consume
     → exact 15 V1TaskCompletionReceipt
     → if SUCCESS:
          final CAS → 29 milestones → 22+4 completion
          → G1E→G5E → G6 → P7 → G7
          → ready payload → finalization close → ready wrapper
     → if FAILURE/CRASH/EXPIRED or intermediate FAIL/revocation:
          failure CAS → bounded evidence/disposition
          → failure terminal payload → finalization close
          → failure terminal wrapper; ready wrapper cardinality 0
```

five EVIDENCE gate는 `POST_CLOSE_READ_ONLY_VERIFY` profile이다. G1-EVIDENCE
spec은 final CAS receipt와 G5-SPEC PASS를 existing predecessor로 결속하고,
G2~G5 각각의 spec은 직전 EVIDENCE PASS result payload/wrapper가 존재한
뒤에만 freeze한다. 모두 같은 closed attempt/decision lineage를 evidence로
비교할 뿐 spent authority를 실행 권한으로 재사용하지 않는다. G6는 이 exact
ordered five-result chain digest를 비교한다.

어느 predecessor가 missing, `NOT_RUN`, `FAIL`, wrong subject, wrong attempt,
wrong authority이면 current는 `NOT_RUN`이다. 실제 verifier를 실행했고
assertion이 틀렸을 때만 `FAIL`이다.

## 4. Z0/G0 genuine ephemeral read-only 계약

### 4.1 `G0ExecutionSpecPayload`

```text
subject_type = G0_SPEC_ONLY
exact_r005_physical_identity
formal_roadmap_review_sha + receipt_sha
skeptical_roadmap_review_sha + receipt_sha
protected_artifact_ref[]:
  path, sha256, bytes, lines, mode, file_type, nlink
absence_role_definition[]
authority_absence_definition[]
mutation_detection_basis
trusted_clock_source
max_observation_duration_ns
max_g0_to_p0_age_ns
verifier_physical
verifier_sha256
literal_argv[]
literal_environment[]
read_set[]
write_set = []
stdout_sink = EPHEMERAL_CALLER_MEMORY
stderr_sink = EPHEMERAL_CALLER_MEMORY
temp_path_set = []
redirect_path_set = []
network = DENY
expected_exit = 0
expected_semantic_predicate
```

spec은 future G0 review SHA를 선참조하지 않는다. 대신
`execution_bound_read_role[]`에 exact G0 spec payload/receipt와 formal/
skeptical review/pair receipt의 role/cardinality를 고정한다. review 뒤
executor가 actual SHA를 read-only launch input으로 받고, 이 다섯 artifact를
before/after protected snapshot에 포함한다. actual SHA를 durable launch
manifest로 쓰지는 않는다.

`max_observation_duration_ns`와 `max_g0_to_p0_age_ns`는 reviewed spec에
positive literal integer로 고정하며 default나 “최근” 문자열을 허용하지
않는다.

argv에는 shell output redirection, `tee`, process substitution, temp file,
cache, history, trace file와 logger를 넣지 않는다. verifier가 암묵적으로
bytecode/cache/core를 만들 수 있으면 G0 verifier로 부적격이다. terminal
표시도 caller가 보유한 ephemeral bytes를 읽는 행위일 뿐 log/receipt로
저장하지 않는다.

### 4.2 transient `G0ObservationEnvelope`

이 값은 process memory에서 P0 producer로 직접 전달되고 파일, database,
clipboard, shell history, daylog 또는 receipt로 저장되지 않는다.

```text
g0_spec_payload_sha
g0_spec_publication_receipt_sha
g0_review_receipt_sha[2]
observation_started_at
observation_ended_at
trusted_clock_source
literal_argv + environment_digest
before_snapshot_digest
after_snapshot_digest
protected_observation[]
absence_observation[]
authority_observation[]
stdout_sha256
stderr_sha256
actual_exit
Quick2 = PASS/PASS
semantic_status = PASS | FAIL
```

G0 PASS:

```text
before_snapshot_digest = after_snapshot_digest
all protected physical/stat/hash = expected
Quick2 = PASS/PASS
checkpoint sequence = 39
canonical Gap/Backlog = r021/r021
active v2.5/r022/P/M candidate = absent
current authority = ABSENT_DENY_ALL
durable write count = 0
protected mutation = 0
semantic failures = 0
```

durable file/dir 생성, stdout/stderr redirect, cache, snapshot drift 또는
untrusted/reversed clock이면 G0는 `FAIL`; P0와 successor는 `NOT_STARTED`다.

## 5. P0 baseline freeze와 freshness

### 5.1 strict P0 outputs

R005가 고정한 `PRE_SUCCESSOR_ROOT`는 review 전에 literal path로 이미
동결돼 있다. 다음 role은 그 root의 §7 exact path를 가져야 하고 alias/glob은
금지한다.

```text
P0InventoryManifestPayload
P0FreezeReceipt = the sole detached wrapper for P0InventoryManifestPayload
P0AllowedAddOnlyDeltaManifest
P0SyntheticMemberDerivationReceipt
```

`P0InventoryManifestPayload`:

```text
schema_sha
producer_actor_id
producer_physical + producer_sha + literal_argv
independent_verifier_physical + verifier_sha + literal_argv
g0_transient_observation_copy
g0_observation_started_at + ended_at
p0_recheck_started_at + ended_at
trusted_clock_source
g0_after_snapshot_digest
p0_before_write_snapshot_digest
protected_artifact_ref[]
generated_inventory[]
tree_member_inventory[]
symlink_inventory[]
direct_system_origin_inventory[]
git_and_external_gitdir_inventory[]
runner_current_historical_nonrunning_inventory[]
lock_epoch_inventory[]
gateway_exact4_exact5_inventory[]
artifact_baseline_epoch_inventory[]
category_set_digest[]
complete_ordered_set_digest
```

P0 producer는 manifest를 쓰기 직전 보호 집합을 재관찰한다.

```text
0 <= p0_recheck_started_at - g0_observation_ended_at
   <= max_g0_to_p0_age_ns
p0_before_write_snapshot_digest = g0_after_snapshot_digest
R005 and both review SHA = G0-observed exact values
```

불일치나 freshness 초과이면 P0를 발행하지 않고 G0부터 재실행한다.
P0FreezeReceipt는 unsigned inventory payload SHA를 유일한 detached
wrapper로 서명하며
`status=FROZEN`, producer/verifier result, schema/domain과 publication time을
결속한다. 별도 `P0InventoryManifestDetachedSignature`를 만들지 않는다.
allowed-delta와 synthetic-derivation은 §6.1의 signed wrapper-only receipt
profile을 사용한다.

### 5.2 synthetic 627 derivation

`ConstructiveStageCRootFixture`의 ordered member set은 상수 문자열만으로
주장하지 않는다.

```text
derived_set =
  P0 exact eligible inventory
  + P0AllowedAddOnlyDeltaManifest exact members
  - exact exclusion manifest
ordered by canonical UTF-8 byte order
```

`derived member count = 627`, exact26/U1 shape와 set digest가 모두 기대값일
때만 synthetic fixture를 만들 수 있다. 결과가 627이 아니면 fixture를
억지로 채우지 않고 B09/G5-EVIDENCE를 `NOT_RUN`으로 둔다. allowed delta는
literal member/path/type/origin/content SHA를 열거하고 broad prefix,
generated glob와 실행 중 발견한 member 추가를 금지한다.

## 6. 공통 publication과 GateExecution 계약

### 6.1 signature-outside-payload one-way publication

strict publication은 다음 tagged union 중 정확히 하나다.

```text
DETACHED_PAYLOAD:
  UnsignedPayload file → one DetachedSignatureWrapper file
SIGNED_RECEIPT_WRAPPER_ONLY:
  one file {body,signature}; signature는 body 밖 sibling field
RAW_BYTES:
  one immutable byte file + consuming payload의 exact SHA
```

G0 spec, P0 inventory, successor, allowlist/bundle/static-edge manifest,
runtime expansion result, Gate spec/result, bridge/request/decision/
finalization/close-recovery/terminal-wrapper-recovery grant,
revocation version head, task spec, pre-close CAS, finalization-prefix
checkpoint, milestone, row/debt completion, G6/G7, ready payload와
finalization failure evidence/disposition/terminal payload는
`DETACHED_PAYLOAD`다. review/pair, consume/close, task completion, CAS final,
failure CAS, task result, 네 execution terminal receipt,
P0 allowed-delta/derivation,
DocumentationScopeProvenanceReceipt와
DaylogPathBinding, finalization failure terminal receipt는
`SIGNED_RECEIPT_WRAPPER_ONLY`다. raw origin/stdout/stderr는 `RAW_BYTES`다.
각 allowlist entry는 publication profile을 직접 기록한다.

일반 DETACHED wrapper는 payload 바로 뒤에 발행한다. 다음 네 deferred
edge family만 reviewed schema가 허용한다.

```text
D1 P0InventoryManifestPayload
   → P0FreezeReceipt
D2 CASPreClosePayload
   → selected execution terminal receipt
   pre_close_cas_state = exact {
     CAS_ABSENT_BEFORE_PUBLICATION,
     CAS_PRESENT_UNWRAPPED,
     CAS_WRAPPED_BY_SELECTED_TERMINAL
   }
D3 exactly one selected finalization terminal payload {
       ReadyEvaluationPayload,
       FailureTerminalPayload}
   → PostCloseFinalizationCloseReceipt
   → 그 payload의 sole terminal wrapper
D4 D3 close 뒤 wrapper 전 crash/expiry이면
   FinalizationTerminalWrapperRecoveryConsumeReceipt
   → existing payload+close exact Physical adopt
   → missing sole terminal wrapper create-exclusive
```

각 deferred payload는 future wrapper/close SHA를 포함하지 않고, 오른쪽
receipt/wrapper만 왼쪽 exact SHA/schema/domain과 필요한 predecessor
lineage를 inward-reference한다. D2는 result prefix `0..15`와 위 CAS state를
결속한다. CAS가 absent이면 terminal은 CAS wrapper를 주장하지 않고,
`CAS_PRESENT_UNWRAPPED`이면 selected execution terminal(normal or
recovery)이 그 exact CAS의 sole wrapper가 되며,
`CAS_WRAPPED_BY_SELECTED_TERMINAL`이면 이미 닫힌
terminal을 adopt하고 새 terminal을 쓰지 않는다.
D3의 close는 selected payload를, selected wrapper는 payload와 close를
결속한다. D4는 새 close를 만들지 않고 byte/Physical-equal existing
payload+close와 아직 absent인 wrapper path만 허용한다. 따라서 D1~D4의
방향을 합쳐도 역방향 edge와 cycle은 `0`이다. 동일 payload에 일반 detached
signature를 추가하거나 D2/D3 대안을 둘 이상 materialize하면 duplicate
wrapper/branch로 거부한다.

`DETACHED_PAYLOAD`는 다음 순서로만 발행한다.

```text
UnsignedPayload JCS bytes
→ payload_sha256
→ DetachedSignatureWrapper(
     payload_sha256,
     payload_schema_sha,
     publisher_actor_id,
     published_at,
     trusted_clock_source,
     signature_domain,
     detached_signature)
→ wrapper_sha256 = publication_receipt_sha
```

payload는 자기 SHA, wrapper SHA 또는 detached signature를 포함하지 않는다.
wrapper는 payload를 선행 참조하지만 payload는 wrapper를 역참조하지 않는다.
서명 입력은 다음 exact domain bytes다.

```text
UTF8(signature_domain) || 0x00 ||
UTF8(payload_schema_sha) || 0x00 ||
UTF8(payload_sha256) || 0x00 ||
payload_jcs_bytes
```

`SIGNED_RECEIPT_WRAPPER_ONLY`의 서명 입력은
`domain || NUL || schema_sha || NUL || JCS(body)`이고 signature는 body에
없다. 파일 SHA나 자기 path를 body에 넣지 않는다.

payload mutation, signature-in-body, wrong domain/schema/publisher,
한 DETACHED payload의 duplicate wrapper, wrapper-before-payload와
self/future reference는 거부한다.

### 6.2 `ExpectedPredecessorEdge`

Gate spec을 freeze할 때 모든 predecessor result와 receipt는 이미 존재한다.

```text
edge_id
predecessor_kind
predecessor_gate_id
predecessor_subject_type
predecessor_subject_sha
predecessor_spec_payload_sha
predecessor_spec_receipt_sha
predecessor_result_payload_sha
predecessor_result_receipt_sha
expected_status = PASS
expected_attempt_id
expected_execution_authority_lineage_digest_or_na
expected_finalization_lineage_prefix_digest_or_na
```

P0 freeze, review-pair와 authority receipt처럼 gate가 아닌 predecessor는
strict tagged union variant를 쓰며 해당 payload/receipt SHA와 expected
terminal state를 똑같이 요구한다.

### 6.3 `GateExecutionSpecPayload`

```text
gate_id
gate_class = SPEC | EVIDENCE | G6 | G7
subject_type + subject_sha + subject_publication_receipt_sha
spec_frozen_at + trusted_clock_source
attempt_id
authority_lineage_kind
expected_predecessor_edges[]
static_dependency_manifest_payload_sha_or_na
static_dependency_manifest_receipt_sha_or_na
dependency_expansion_result_payload_sha_or_na
dependency_expansion_result_wrapper_sha_or_na
expected_execution_projection_digest_or_na
expected_finalization_projection_digest_or_na
finalization_prefix_failure_checkpoint_payload_sha_or_na
finalization_prefix_failure_checkpoint_wrapper_sha_or_na
input_manifest_sha[]
fixture_manifest_sha[]
verifier_physical + verifier_sha
literal_argv[]
literal_environment[]
read_set[]
write_set[]
expected_exit
expected_output_role/path/schema/publisher/cardinality[]
expected_semantic_predicate
failure_state_contract
```

actual output/result SHA, execution timestamp와 future receipt SHA는 spec에
넣지 않는다.

### 6.4 `GateExecutionResultPayload`

```text
gate_spec_payload_sha
gate_spec_publication_receipt_sha
gate_id + gate_class
subject_type + subject_sha
tested_successor_sha
attempt_id
execution_authority_lineage_digest_or_na
finalization_lineage_prefix_digest_or_na
static_dependency_manifest_payload_sha_or_na
static_dependency_manifest_receipt_sha_or_na
dependency_expansion_result_payload_sha_or_na
dependency_expansion_result_wrapper_sha_or_na
observed_execution_projection_digest_or_na
observed_finalization_projection_digest_or_na
finalization_prefix_failure_checkpoint_payload_sha_or_na
finalization_prefix_failure_checkpoint_wrapper_sha_or_na
verified_predecessor_edges[]:
  expected edge 전 field
  observed_status
  observed_subject_sha
  observed_attempt_id
  observed_execution_authority_lineage_digest_or_na
  observed_finalization_lineage_prefix_digest_or_na
  verification_result
actual_argv + environment_digest
actual_exit
raw_stdout_sha + raw_stderr_sha
actual_output_ref[]
semantic_assertion_result[]
timeline
status = NOT_RUN | FAIL | PASS
```

`verified_predecessor_edges[]`는 spec의 expected set과 edge ID, cardinality,
order가 exact one-to-one여야 한다. pre-finalization gate는 finalization
field가 explicit `NA`이고, post-close artifact는 closed execution lineage와
finalization prefix를 모두 요구한다. missing/extra/duplicate, `NOT_RUN/FAIL`,
wrong subject, attempt, authority 또는 mutated receipt가 하나라도 있으면
현재 status는 `NOT_RUN`만 허용한다. predecessor가 모두 PASS이고 실행한
assertion이 틀리면 `FAIL`, 모두 맞으면 `PASS`다.

이 검사는 G1-SPEC부터 G7까지 공통이다. 모든 gate에 predecessor
missing/extra/duplicate, wrong status/subject/attempt/lineage negative
fixture를 둔다.

G3-EVIDENCE와 G6에서 static manifest와 dependency expansion 여섯 field는
`NA`일 수 없고 서로 같은 exact existing object와 projection digest를
결속한다. G6 PASS 후보에서는 prefix-failure checkpoint 두 field가 exact
`NA`다. 다른 gate에서 graph를 직접 검사하지 않으면 일곱 graph field는
모두 explicit `NA`이며 generic input manifest 배열에서 대신 추정하지 않는다.

### 6.5 trusted-clock timeline

권한을 소비해 실제 V1을 실행하는 task/result의 timeline은 다음 strict
tagged union이다.

```text
NORMAL_EXECUTION_CLOSE:
  spec_frozen_at
  ≤ spec_published_at
  ≤ execution_authority_consume_at
  ≤ execution_started_at
  ≤ raw_collected_at
  ≤ execution_ended_at
  ≤ result_generated_at
  ≤ execution_terminal_published_at
  < execution_hard_deadline

RECOVERY_EXECUTION_CLOSE:
  close_recovery_grant_frozen_at
  < execution_authority_consume_at
  ≤ cause_observed_at
  ≤ close_recovery_consume_at
  ≤ execution_terminal_published_at
  ≤ close_recovery_hard_deadline
```

`EXPIRED`의 원인은 `execution_hard_deadline` 도달뿐이다. decision 전에
승인되고 execution consume 전에 freeze한 watchdog이 그 시각을 관찰하며
`execution_hard_deadline <= cause_observed_at`을 기록한다. close-recovery
grant와 terminal body는 watchdog producer/verifier Physical/SHA,
literal argv, config digest, freeze time와 trusted-clock
correlation을 직접 결속한다.
`close_recovery_hard_deadline`은 원인이 아니라 더 늦은 terminalization
ceiling이고 반드시
`execution_hard_deadline < close_recovery_hard_deadline`이다. recovery
deadline 자체가 지난 뒤 그 grant로 새 terminal을 쓰는 경로는 `0`이다.

post-close finalization은 다음 tagged union을 쓴다.

```text
NORMAL_FINALIZATION_TERMINAL:
  execution_terminal_published_at
  ≤ finalization_consume_at
  ≤ task_completion_published_at
  ≤ selected_final_cas_or_prefix_checkpoint_at
  ≤ selected_terminal_payload_published_at
  ≤ finalization_close_published_at
  ≤ selected_terminal_wrapper_published_at
  ≤ finalization_hard_deadline

FINALIZATION_WRAPPER_RECOVERY:
  terminal_wrapper_recovery_grant_frozen_at
  < finalization_consume_at
  ≤ existing_finalization_close_published_at
  ≤ wrapper_recovery_cause_observed_at
  ≤ terminal_wrapper_recovery_consume_at
  ≤ selected_terminal_wrapper_published_at
  ≤ terminal_wrapper_recovery_hard_deadline
```

wrapper recovery deadline은 original finalization deadline보다 늦고 grant는
original finalization consume 전에 freeze한다. 모든 READY/FAILURE wrapper는
normal 또는 recovery tagged timeline 하나와 exact deadline을 직접
결속한다. 두 timeline 선택, deadline 뒤 wrapper, missing/changed
payload/close Physical 또는 recovery가 새 payload/close를 쓰는 경우는
거부한다.

SPEC gate처럼 add-only plan 검증만 하는 경우 consume/close 대신 exact
`plan_scope_opened_at/closed_at`을 쓰고 같은 단조 순서를 지킨다. V1 close
뒤 수행하는 EVIDENCE aggregation에는 다음 tagged timeline을 쓴다.

```text
underlying_v1_window = exact consume/execution/close interval
execution_close_at
≤ finalization_consume_at
≤ verifier_started_at
≤ raw_collected_at
≤ verifier_ended_at
≤ result_generated_at
≤ result_published_at
```

G6, P7 verification, G7과 ready도 같은 `POST_CLOSE_READ_ONLY_VERIFY`
profile과 underlying closed V1 interval을 결속한다.

모든 시각은 같은 trusted clock source와 monotonic correlation을 가진다.
reversed/equal-forbidden time, execute-before-consume, result-before-raw,
close-before-result, deadline 초과와 untrusted clock은 PASS를 금지한다.

### 6.6 result publication

```text
raw bytes
→ GateExecutionResultPayload(no own receipt SHA)
→ payload_sha
→ detached GateResultPublicationReceipt
```

V1 leaf는 result payload 뒤 execution close를 먼저 발행한다. 별도
finalization grant를 consume한 뒤 최종 `V1TaskCompletionReceipt`가 result
payload SHA, execution close SHA와 finalization prefix를 함께 결속한다.
이 순서로 미래 close SHA를 result payload가 선참조하지 않으면서 completion
receipt가 두 lineage를 증명한다.

## 7. H1 폐쇄형 add-only write allowlist

pre-successor와 post-successor output root를 분리한다.

```text
ROADMAP_DIR =
  docs/control/execution/artifact-closure/run-20260727-001
PRE_SUCCESSOR_ROOT =
  docs/control/execution/artifact-closure/run-20260727-001/
  roadmap-r005-pre-successor-r001
H1_ROOT =
  docs/control/execution/artifact-closure/run-20260727-001/
  walksafe-pre-p-closure-successor-h1-r005-r001
ATTEMPT_NAMESPACE_ID =
  attempt-<64-lower-hex SHA256(canonical(
    tested_successor_sha,fresh_attempt_namespace_nonce,
    namespace_materializer_actor_id,namespace_frozen_at))>
ATTEMPT_ROOT =
  <H1_ROOT>/attempts/<ATTEMPT_NAMESPACE_ID>
```

위 줄바꿈은 가독성용이며 actual 값에는 공백/newline이 없다.
`PRE_SUCCESSOR_ROOT`는 R005가 지금 고정하므로 G0/P0가 successor를
선참조하지 않는다. `H1_ROOT`도 R005가 고정하고 successor는 그 exact
값/parent anchor를 확인만 한다. 두 root는 disjoint이고 symlink/alias가
아니다.

`H1_ROOT`에는 successor/bundle/static SPEC만 한 번 발행한다. 매
fresh attempt는 request 전에 `AttemptNamespaceFreezePayload`와 detached
wrapper를 create-exclusive 발행해 exact `ATTEMPT_ROOT`, attempt ordinal,
fresh namespace nonce, predecessor attempt terminal-or-`NA`, root/parent
Physical과 아래 attempt-specific literal role set 전체를 동결한다.
payload path도
`<H1_ROOT>/attempt-namespaces/<ATTEMPT_NAMESPACE_ID>.payload.json`,
wrapper path도 같은 stem의 `.signature.json`으로 위 digest에서
결정론적으로 유도한다. namespace payload/wrapper는 실행 권한이 아니며
future decision이 exact digest를 승인해야 한다.

bridge, request/response/decision, consume/terminal/recovery, revocation,
task/result/CAS/completion, EVIDENCE Gate, G6/P7/G7/ready/failure는 전부
`ATTEMPT_ROOT` 아래다. 이전 attempt root는 immutable history이고 새
attempt의 write path set과 교집합이 `0`이다. immutable static/P0/history
read set 공유는 허용하되 exact SHA만 읽는다. P0/protected freshness,
dependency definition/manifest, schema 또는 attempt template가 바뀌면
attempt namespace만 추가하지 않고 fresh successor revision과 새
`PRE_SUCCESSOR_ROOT/H1_ROOT`를 먼저 dual-review한다. fixed `r001` path를
재사용하거나 overwrite하는 retry는 `NOT_RUN`이다.

아래 `G1...G5` 표기는 wildcard가 아니라 표에 적은 exact ID 집합을 뜻한다.
각 future spec은 모든 literal path와 schema SHA를 펼쳐 기록한다. 이
allowlist는 필요한 role 집합일 뿐 권한을 만들지 않는다.

signed `H1WriteAllowlistManifest`는 future nonce를 선결정하지 않도록
다음 strict tagged union을 가진다.

```text
StaticAllowlistEntry:
  entry_id + role_id + exact literal_path
  schema_sha + publisher_actor_id + publisher_physical_sha
  write_phase + cardinality + authority_ceiling
AttemptRoleTemplate:
  template_id + role_id + canonical_relative_path_constructor
  schema_sha + publisher_actor_id + publisher_physical_sha
  write_phase + cardinality_domain + authority_ceiling

AttemptConcreteAllowlistEntry:
  source_template_id + role_id + exact literal_path
  attempt_namespace_id + literal_attempt_root
  schema_sha + publisher_actor_id + publisher_physical_sha
  write_phase + exact cardinality + authority_ceiling
```

successor 시점에는 static entry와 attempt template만 freeze한다. 매
`AttemptNamespaceFreezePayload`가 template 전수를
`AttemptConcreteAllowlistEntry[]`로 펼쳐 exact path-set/order/digest를
직접 담고 detached wrapper가 constructor, namespace/root, schema,
publisher와 cardinality equality를 재계산한다. request/response/decision과
각 grant/consume은 같은 concrete digest를 named field로 결속한다. template
자체, namespace freeze 또는 concrete list는 실행 권한이 아니다.

다음 closed expansion에서 `<ATTEMPT_ROOT>`/attempt-specific
`GATE_ROOT` 아래 줄은 template이고 namespace freeze 뒤에는 angle bracket,
function, glob 없이 exact literal entry가 된다.

```text
<ROADMAP_DIR>/WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R005-
  {independent-review-r001.md,independent-skeptical-review-r001.md,
   roadmap-review-pair-receipt-r001.json}
<PRE_SUCCESSOR_ROOT>/g0/{spec.payload.json,spec.signature.json,
              formal-review.json,skeptical-review.json,review-pair.json}
<PRE_SUCCESSOR_ROOT>/p0/{inventory.payload.json,
              freeze-receipt.json,allowed-delta.json,
              synthetic-derivation-receipt.json}
<PRE_SUCCESSOR_ROOT>/control/documentation-scope-provenance-receipt.json
<PRE_SUCCESSOR_ROOT>/control/daylog-path-binding-receipt.json
<H1_ROOT>/successor/{successor.payload.json,successor.signature.json,
                     h1-write-allowlist.payload.json,
                     h1-write-allowlist.signature.json}
<H1_ROOT>/successor/bundles/{closure-schemas,fixtures,verifiers,
                             owner-milestone-manifest,
                             task-crosswalk-manifest}/
  {payload.json,signature.json}
<H1_ROOT>/dependency-edge-manifest/{payload.json,signature.json}
<H1_ROOT>/attempt-namespaces/<attempt-namespace-id>.
  {payload.json,signature.json}
<ATTEMPT_ROOT>/dependency-expansion-result/{payload.json,signature.json}
<GATE_ROOT(gate-id)>/gates/<gate-id>/{execution-spec.payload.json,
                          execution-spec.signature.json,
                          raw-stdout.bin,raw-stderr.bin,
                          result.payload.json,result.signature.json}
<ATTEMPT_ROOT>/pre-authority-successor-review/{formal,skeptical,pair}.json
<ATTEMPT_ROOT>/bridge/{bridge.payload.json,bridge.signature.json,
                  formal-review.json,skeptical-review.json,review-pair.json}
<ATTEMPT_ROOT>/authority/{request.payload.json,request.signature.json,
                     user-response-origin.bin,user-response-provenance.json,
                     decision.payload.json,decision.signature.json,
                     consume-receipt.json,
                     execution-close-receipt.json,
                     execution-failure-close-receipt.json,
                     execution-crash-close-receipt.json,
                     execution-expired-close-receipt.json,
                     close-recovery-grant.payload.json,
                     close-recovery-grant.signature.json,
                     close-recovery-consume-receipt.json,
                     finalization-grant.payload.json,
                     finalization-grant.signature.json,
                     finalization-consume-receipt.json,
                     finalization-close-receipt.json,
                     terminal-wrapper-recovery-grant.payload.json,
                     terminal-wrapper-recovery-grant.signature.json,
                     terminal-wrapper-recovery-consume-receipt.json}
<ATTEMPT_ROOT>/authority/revocation/
  {execution-consume,finalization-consume,close-recovery-consume,
   terminal-wrapper-recovery-consume}/
  versions/<zero-padded-revocation-ordinal>/
  {head.payload.json,head.signature.json}
<ATTEMPT_ROOT>/authority/revocation/
  {execution-consume,finalization-consume,close-recovery-consume,
   terminal-wrapper-recovery-consume}/
  observations/{pre-consume,post-consume-terminal}.json
<ATTEMPT_ROOT>/v1/tasks/<task-id>/{spec.payload.json,spec.signature.json,
                             raw-stdout.bin,raw-stderr.bin,
                             result.payload.json,completion-receipt.json}
<ATTEMPT_ROOT>/v1/{cas-pre-close.payload.json,cas-final-receipt.json,
               cas-failure-receipt.json,
               finalization-prefix-failure-checkpoint.payload.json,
               finalization-prefix-failure-checkpoint.signature.json}
<ATTEMPT_ROOT>/v1/b01/{canonical-root-spec,issue,consume,
                   review-consume,close,failure}.{payload,signature}.json
<ATTEMPT_ROOT>/v1/stage-c/b04/
  {c-recovery-extension-contract,
   constructive-c-recovery-fixture,
   extension-verification}.{payload,signature}.json
<ATTEMPT_ROOT>/v1/stage-c/b06/
  {n26,t1,x1,stage-c-review-binding,v1-binding}.
  {payload,signature}.json
<ATTEMPT_ROOT>/v1/m02/
  {late-bound-role-registry,late-bound-role-verification}.
  {payload,signature}.json
<ATTEMPT_ROOT>/v1/milestones/<milestone-id>/
  {completion.payload.json,completion.signature.json}
<ATTEMPT_ROOT>/v1/closure/findings/<finding-id>/
  {completion.payload.json,completion.signature.json}
<ATTEMPT_ROOT>/v1/closure/debts/<debt-id>/
  {completion.payload.json,completion.signature.json}
<ATTEMPT_ROOT>/g6/{execution-spec.payload.json,execution-spec.signature.json,
              raw-stdout.bin,raw-stderr.bin,
              disposition.payload.json,disposition.signature.json,
              result.payload.json,result.signature.json}
<ATTEMPT_ROOT>/p7/{successor-formal-review.json,
              successor-skeptical-review.json,successor-review-pair.json,
              evidence-formal-review.json,
              evidence-skeptical-review.json,evidence-review-pair.json}
<ATTEMPT_ROOT>/g7/{execution-spec.payload.json,execution-spec.signature.json,
              raw-stdout.bin,raw-stderr.bin,
              result.payload.json,result.signature.json}
<ATTEMPT_ROOT>/ready/{evaluation.payload.json,evaluation.signature.json}
<ATTEMPT_ROOT>/failure/{evidence.payload.json,evidence.signature.json,
                    disposition.payload.json,disposition.signature.json,
                    terminal.payload.json,terminal.signature.json}
<literal-project-root>/daylog/<literal-execution-local-date>.md
```

`GATE_ROOT(g1-spec..g5-spec)=H1_ROOT`,
`GATE_ROOT(g1-evidence..g5-evidence)=ATTEMPT_ROOT`이며 exact 10-row mapping을
namespace freeze에 펼친다. angle bracket나 function 표기는 actual manifest
path에 남지 않는다.

`literal-project-root`, `literal-execution-local-date`와 최종 daylog path는
session 시작 때 하나의 `DaylogPathBinding` signed wrapper-only receipt로
동결하고 allowlist entry에 exact UTF-8 path를 기록한다. 이는 V1/H2
authority가 아니며 root/merge 단 한 write만 허용한다.

closed expansion set:

```text
gate-id = exact {
  g1-spec,g2-spec,g3-spec,g4-spec,g5-spec,
  g1-evidence,g2-evidence,g3-evidence,g4-evidence,g5-evidence
}
task-id = exact {
  t01,t02,t03,t04,t05,t06,t07,t08,t09,t10,t11,t12,t13,t14,t15
}
milestone-id = exact {
  b01a,b02a,b03a,b04a,b05a,b06a,b06b,b07a,b08a,
  b09a,b09b,b09c,b10a,b10b,b11a,b12a,b13a,b14a,b15a,
  m04a,b16a,m04b,b16b,b17a,b18a,m01a,m01b,m02a,m03a
}
finding-id = exact {b01..b18,m01..m04}
debt-id = exact {d-a,d-b,d-c,d-d}
```

중괄호와 범위는 manifest generator 입력 표기일 뿐 filesystem glob가
아니다. static gate는 successor freeze 때, attempt gate/task/milestone/
finding/debt는 namespace freeze 때 각각 10, 15, 29, 22, 4개의 concrete
entry로 펼치고 `literal_path_set_digest`와 exact entry cardinality를
기록한다. schema SHA 또는 publisher Physical SHA가 비어 있으면 해당
manifest/freeze를 publish하지 않는다.

| exact role/ID | cardinality | publisher | phase | authority ceiling |
|---|---:|---|---|---|
| R005 payload H1 write | 0 | none; pre-existing protected input | H1 | read-only |
| R005 formal/skeptical review + pair receipt | 3 | independent reviewers/pair verifier | ROADMAP | review only |
| G0 spec payload + detached receipt | 2 | Z0 author/publisher | Z0 | add-only plan only |
| G0 formal/skeptical review + pair receipt | 3 | independent reviewers/pair verifier | Z0 | review only |
| G0 actual durable output | 0 | none | G0 | read-only ephemeral |
| P0 inventory payload + sole detached freeze receipt | 2 | P0 producer/verifier | P0 | add-only baseline only |
| P0 allowed-delta + synthetic derivation receipt | 2 | P0 producer/verifier | P0 | H1 fixture planning only |
| DocumentationScopeProvenanceReceipt | 1 | origin provenance verifier | session admission | documentation add-only only |
| DaylogPathBinding signed wrapper-only receipt | 1 | root/merge path binder | session start | daylog path declaration only |
| PRE-P successor payload + detached receipt | 2 | root/merge publisher | P1~P6 | add-only plan only |
| H1 write allowlist manifest payload/detached receipt | 2 | root/merge publisher/checker | P6 | allowlist declaration only |
| five pre-successor definition bundles payload/detached receipt | 10 | named producer/checker | P1~P6 | non-execution spec only |
| tested-successor static dependency union manifest payload/detached receipt | 2 | P3 graph producer/checker | post-successor/pre-G1 | static graph binding only |
| attempt namespace freeze payload/detached receipt | 2 per fresh attempt | namespace materializer/checker | pre-request | exact attempt path set only |
| runtime dependency expansion result payload/detached receipt | 2 per consumed attempt | runtime graph producer/checker | post-terminal/finalization | selected projection evidence only |
| G1-SPEC spec payload/receipt/raw stdout/raw stderr/result payload/result receipt | 6 | named gate publisher/verifier | P1 gate | plan verification only |
| G2-SPEC spec payload/receipt/raw stdout/raw stderr/result payload/result receipt | 6 | named gate publisher/verifier | P2 gate | plan verification only |
| G3-SPEC spec payload/receipt/raw stdout/raw stderr/result payload/result receipt | 6 | named gate publisher/verifier | P3 gate | plan verification only |
| G4-SPEC spec payload/receipt/raw stdout/raw stderr/result payload/result receipt | 6 | named gate publisher/verifier | P4 gate | plan verification only |
| G5-SPEC spec payload/receipt/raw stdout/raw stderr/result payload/result receipt | 6 | named gate publisher/verifier | P5 gate | plan verification only |
| pre-authority successor formal/skeptical review + pair receipt | 3 | independent reviewers/pair verifier | post-G5-SPEC | review only |
| V0 bridge payload/receipt + two reviews + pair receipt | 5 | bridge author/publisher + independent reviewers | V0 | bridge design only |
| user request payload/receipt | 2 | authority requester/publisher | V0 | request only |
| immutable user response import/provenance receipt | 2 | explicit user/provenance verifier | V0 | decision evidence only |
| normalized decision pair + execution consume + four terminal roles | 7 roles; 4 actual | decision verifier/authority guard | V0/V1 | exactly one terminal per attempt |
| close-recovery grant pair + consume | 3 roles; actual 2 normally, 3 on recovery terminal | close-only recovery guard | V1 recovery | close-only, no task dispatch |
| four consume-point revocation version slot sets | 24 file roles = 4 consume roles × 3 ordinals × payload/wrapper; actual prefix only | revocation source verifier | consume guards | immutable ordinal sequence only |
| revocation pre/post observation receipts | 8 roles; actual pre=4, post=0..4 | revocation CAS observer | consume/terminal guards | observation only |
| post-close finalization grant payload/receipt + consume + close | 4 | finalization guard | post-execution | add-only finalization only |
| finalization terminal-wrapper recovery grant pair + consume | 3 roles; grant pair actual 2 always, consume optional 0/1, total 2 or 3 | wrapper recovery guard | close-tail recovery | adopt existing payload+close, one wrapper only |
| T01~T15 task spec payload/receipt | 30 | V1 spec publisher | V1 pre-consume | one isolated attempt only |
| T01~T15 raw stdout/raw stderr/result payload/final completion receipt | 60 | V1 producer/finalizer | V1 | one isolated attempt only |
| V1 CAS pre-close + success-final + failure-final + prefix-failure checkpoint pair | 5 | CAS/checkpoint producer/finalizer | V1/finalization | branch-exclusive evidence aggregation |
| B01 canonical root + five lifecycle payload/detached receipts | 12 | P2 B01 serializer/lifecycle publisher | V1 | isolated B01 evidence only |
| B04 contract/constructive fixture/verification payload+wrapper pairs | 6 | P5 serializers + distinct checkers | V1 constructive | C/recovery extension verification only |
| B06 N26/T1/X1/ReviewBinding/V1 payload+wrapper pairs | 10 | P3/P5 serializers + distinct checkers | V1 constructive | hash-topology evidence only |
| M02 late-bound registry/verification payload+wrapper pairs | 4 | P1 serializer + distinct checker | V1 constructive | exhaustive role verification only |
| 29 unique milestone completion payload + detached receipt | 58 | milestone registry producer/verifier | post-close finalization | milestone candidate evidence only |
| B01~B18/M01~M04 row completion payload + detached receipt | 44 | named row closure producer/verifier | post-close finalization | finding candidate evidence only |
| D-A~D-D debt completion payload + detached receipt | 8 | named debt closure producer/verifier | post-close finalization | debt candidate evidence only |
| G1-EVIDENCE spec payload/receipt/raw stdout/raw stderr/result payload/result receipt | 6 | named gate publisher/verifier | post-close finalization | evidence only |
| G2-EVIDENCE spec payload/receipt/raw stdout/raw stderr/result payload/result receipt | 6 | named gate publisher/verifier | post-close finalization | evidence only |
| G3-EVIDENCE spec payload/receipt/raw stdout/raw stderr/result payload/result receipt | 6 | named gate publisher/verifier | post-close finalization | evidence only |
| G4-EVIDENCE spec payload/receipt/raw stdout/raw stderr/result payload/result receipt | 6 | named gate publisher/verifier | post-close finalization | evidence only |
| G5-EVIDENCE spec payload/receipt/raw stdout/raw stderr/result payload/result receipt | 6 | named gate publisher/verifier | post-close finalization | evidence only |
| G6 spec/receipt/raw stdout/raw stderr/disposition payload/receipt/result payload/result receipt | 8 | G6 publisher/verifier | G6 | disposition only |
| P7 four review receipts + two pair receipts | 6 | independent reviewers/pair verifiers | P7 | review only |
| G7 spec/receipt/raw stdout/raw stderr/result payload/result receipt | 6 | G7 publisher/verifier | G7 | readiness verification only |
| ready evaluation payload + terminal detached wrapper | 2 | ready predicate verifier/finalization close guard | ready | post-close finalization only |
| bounded failure evidence/disposition/terminal payload+wrapper pairs | 6 | failure recorder/finalization close guard | failure close | post-close finalization only |
| root/merge daylog | 1 | root/merge | handoff end | log only |

B01 exact 12-role registry:

| base role | payload schema role | detached wrapper schema role | publisher |
|---|---|---|---|
| `canonical-root-spec` | `B01_CANONICAL_ROOT_SPEC_PAYLOAD_V1` | `B01_CANONICAL_ROOT_SPEC_WRAPPER_V1` | P2 canonical-root serializer/signer |
| `issue` | `B01_ISSUE_PAYLOAD_V1` | `B01_ISSUE_WRAPPER_V1` | P2 lifecycle publisher |
| `consume` | `B01_CONSUME_PAYLOAD_V1` | `B01_CONSUME_WRAPPER_V1` | P2 lifecycle publisher |
| `review-consume` | `B01_REVIEW_CONSUME_PAYLOAD_V1` | `B01_REVIEW_CONSUME_WRAPPER_V1` | P2 review-consume publisher |
| `close` | `B01_CLOSE_PAYLOAD_V1` | `B01_CLOSE_WRAPPER_V1` | P2 lifecycle finalizer |
| `failure` | `B01_FAILURE_FIXTURE_PAYLOAD_V1` | `B01_FAILURE_FIXTURE_WRAPPER_V1` | P2 negative rejection verifier |

각 schema role은 successor allowlist registry에 non-empty exact SHA,
publisher actor/Physical SHA, V1 phase와 §7 literal path를 가진 한 row로
펼친다. `failure` pair는 current lifecycle을 failure terminal로 바꾸는
artifact가 아니라 replay/fork/invalid-transition이 effect `NONE`으로
거부됐음을 증명하는 mandatory negative fixture pair다. 따라서 success
attempt에서도 close 전에 존재하며 close pair와 모순되지 않는다. B01 task
scope 밖 current authority/close artifact와 혼용하지 않는다.

allowlist verifier는 mandatory role 누락, undeclared role/path, wrong
publisher/phase/schema/cardinality, alias/glob와 path escape를 각각 negative
fixture로 거부한다. checkpoint, canonical, product, live runner, lock,
actual Stage-C root와 release path는 H1 deny다.

## 8. 단일 owner, milestone과 normative edge manifest

### 8.1 accountable owner

각 finding row의 `accountable_owner_phase`는 단수이고 contributor와 다르다.

| phase | accountable finding |
|---|---|
| P1 | B11, B12, B17, M02 |
| P2 | B01, B02, B03, B05, M03 |
| P3 | B16, M04 |
| P4 | B07, B08, B13, B14, B15, B18, M01 |
| P5 | B04, B06, B09, B10 |

```text
P1 = 3 BLOCKING + 1 MAJOR
P2 = 4 BLOCKING + 1 MAJOR
P3 = 1 BLOCKING + 1 MAJOR
P4 = 6 BLOCKING + 1 MAJOR
P5 = 4 BLOCKING
TOTAL = 18 BLOCKING + 4 MAJOR
```

각 actual row는 다음 strict field를 가진다.

```text
finding_id
severity
accountable_owner_phase
accountable_owner_actor_id
contributor_phase_ids[]
expected_milestone_id[]
expected_milestone_schema_sha[]
expected_milestone_cardinality
ordered_milestone_receipt_sha[]
producer_physical[]:
  role, path, sha256, literal_argv, environment_digest
independent_checker_physical[]:
  role, path, sha256, literal_argv, environment_digest
strict_output_ref[]:
  role, path, schema_sha, payload_sha, publication_receipt_sha
positive_fixture_id[]
negative_fixture_id[]
raw_evidence_ref[]
row_completion_receipt_sha
authority_ceiling
disposition
```

`accountable_owner_phase`와 actor cardinality는 각각 정확히 `1`이다.
contributor를 owner로 승격할 수 없다.

### 8.2 exact milestone manifest

| finding | exact ordered milestone IDs | count |
|---|---|---:|
| B01 | `B01a-CANONICAL-ROOT-LIFECYCLE` | 1 |
| B02 | `B02a-COMMON-GUARD-FSM` | 1 |
| B03 | `B03a-LITERAL-CAPABILITY-ROWS` | 1 |
| B04 | `B04a-SYNTHETIC-C-RECEIPT-FIXTURE` | 1 |
| B05 | `B05a-FUTURE-D-E-F-G-ACTIVATION` | 1 |
| B06 | `B06a-HASH-TOPOLOGY → B06b-SYNTHETIC-INSTANCE` | 2 |
| B07 | `B07a-EXACT10-REPEAT-ORACLE` | 1 |
| B08 | `B08a-INPUT-AND-SANDBOX-INTENT` | 1 |
| B09 | `B09a-ACTUAL-LIVE-ROOT-CONTRACT → B09b-CONSTRUCTIVE-ROOT-DERIVATION → B09c-CONSTRUCTIVE-SAME-ROOT-INTEGRATION` | 3 |
| B10 | `B10a-EXACT6-SCHEMA → B10b-SYNTHETIC-CONSTRUCTION` | 2 |
| B11 | `B11a-GENERATED-SCRATCH-DETERMINISM` | 1 |
| B12 | `B12a-PROJECTION-SINGLE-DAG` | 1 |
| B13 | `B13a-RUNTIME-ACTUAL-USE-CHAIN` | 1 |
| B14 | `B14a-FULL19-RAW-FRAMING` | 1 |
| B15 | `B15a-GIT-ROW18-CLOSURE` | 1 |
| B16 | `M04a-GRAPH-EXTRACTION → B16a-PRODUCER-ASSIGNMENT → M04b-CANONICAL-DIGEST → B16b-EXACTLY-ONE-VERIFICATION` | 4 |
| B17 | `B17a-MIXED-TREE-PHYSICAL-UNION` | 1 |
| B18 | `B18a-SOURCE-ONLY-BEFORE-SPAWN` | 1 |
| M01 | `M01a-EXECUTABLE-DECLARATION → M01b-FINAL-TRACE-EQUALITY` | 2 |
| M02 | `M02a-LATE-BOUND-ENUM` | 1 |
| M03 | `M03a-DISJOINT-RECOVERY` | 1 |
| M04 | `M04a-GRAPH-EXTRACTION → B16a-PRODUCER-ASSIGNMENT → M04b-CANONICAL-DIGEST → B16b-EXACTLY-ONE-VERIFICATION` | 4 |

G6는 이 manifest의 expected ID/schema/count/order와 actual receipt set을
byte-for-byte 비교한다. 특히 B06/B09/B10/B16/M04의 누락, 중복, 역순과
wrong schema를 각각 거부한다.

29개 unique milestone object는 signed `MilestoneArtifactRegistry`의
29개 literal row로 물리화한다. B16과 M04만 같은 four-object chain을
각 row slot에서 공유한다.

```text
milestone_id
literal_payload_path
literal_wrapper_path
payload_schema_sha
wrapper_schema_sha
publication_profile = DETACHED_PAYLOAD
publisher_phase
publisher_actor_id
publisher_physical_sha
producer_physical + producer_sha + literal_argv
supporting_task_id[]
expected_predecessor_milestone_id[]
expected_external_predecessor_role[]
authority_ceiling = POST_CLOSE_FINALIZATION_ONLY
```

exact publisher/schema-role map:

| exact milestone IDs | count | publisher phase | exact payload/wrapper schema roles |
|---|---:|---|---|
| `B11a,B12a,B17a,M02a` | 4 | P1 evidence finalizer | `<ID>_MILESTONE_PAYLOAD_V1` / `<ID>_MILESTONE_WRAPPER_V1` |
| `B01a,B02a,B03a,B05a,M03a` | 5 | P2 evidence finalizer | `<ID>_MILESTONE_PAYLOAD_V1` / `<ID>_MILESTONE_WRAPPER_V1` |
| `M04a,B16a,M04b,B16b,B06a,B10a` | 6 | P3 evidence finalizer | `<ID>_MILESTONE_PAYLOAD_V1` / `<ID>_MILESTONE_WRAPPER_V1` |
| `B07a,B08a,B13a,B14a,B15a,B18a,M01a,M01b` | 8 | P4 evidence finalizer | `<ID>_MILESTONE_PAYLOAD_V1` / `<ID>_MILESTONE_WRAPPER_V1` |
| `B04a,B06b,B09a,B09b,B09c,B10b` | 6 | P5 evidence finalizer | `<ID>_MILESTONE_PAYLOAD_V1` / `<ID>_MILESTONE_WRAPPER_V1` |

`<ID>`와 path metavariable는 successor registry에서 lower-case exact ID와
actual SHA/literal path로 모두 펼친다. empty schema SHA, unnamed publisher,
registry 외 ID와 payload/wrapper path collision은 거부한다.

각 actual `MilestoneCompletionPayload`은 다음 strict schema다.

```text
milestone_artifact_registry_sha
milestone_id
tested_successor_sha + publication_receipt_sha
attempt_id
literal_payload_path + payload_schema_sha
literal_wrapper_path + wrapper_schema_sha
publisher_actor_id + publisher_physical_sha
final_cas_receipt_sha
execution_authority_lineage_digest
finalization_consume_receipt_sha + finalization_lineage_prefix_digest
expected_supporting_task_id[]
actual_supporting_task_receipt_ref[]:
  task_id, task_result_sha, task_completion_receipt_sha, status
expected_predecessor_milestone_id[]
actual_predecessor_milestone_receipt_ref[]:
  milestone_id, payload_sha, wrapper_sha, observed_status
expected_external_predecessor_role[]
actual_external_predecessor_receipt_ref[]:
  role_id, payload_sha_or_na, receipt_sha, observed_status
producer_physical + producer_sha + literal_argv
status = COMPLETE_CANDIDATE | FAIL
```

payload 뒤 exact registry wrapper schema/domain/publisher의 sole detached
wrapper를 발행한다. registry의 expected supporting task, milestone,
external predecessor ID/cardinality/order와 actual receipt ref가 one-to-one이고
모두 같은 successor/attempt/두 lineage에서 PASS일 때만
`COMPLETE_CANDIDATE`다. 29 registry row와 actual payload/wrapper는 ID,
path, schema, publisher가 exact 29↔29 bijection이어야 한다. missing/extra/
duplicate payload 또는 wrapper, wrong schema/publisher, payload-only,
predecessor payload를 receipt로 대체한 경우 G6 milestone predicate는 FAIL이다.

milestone publication은 execution close, 15 task final receipt와 final CAS
뒤 별도 `POST_CLOSE_FINALIZATION_ONLY` consume 아래 supporting task
receipt/CAS를 선행 참조해 수행한다. 아래는 human-readable topological
summary이고 exact internal edge set은 뒤의 29-row registry가 생성하는
`MP001..MP041`이다.

```text
B17a → B11a → B12a → B13a
B17a → B01a → B02a/B03a → B05a/M03a
B17a → B08a → B15a
B10a → B06a
B09a → B09b → B10b → B09c → B06b → B04a
M04a → B16a → M04b → B16b
M01a → B07a/B14a/B18a → M01b
```

### 8.3 static `ClosureDependencyEdgeManifest`와 runtime projection

successor는 node/edge type, closed expansion rule, target literal
path/schema/publisher만 담은 `ClosureDependencyDefinition`을 내장한다.
이 definition에는 successor SHA나 미래 edge-manifest SHA가 없다.

SuccessorPublicationReceipt 뒤 graph producer가 definition을 소비해 하나의
strict `ClosureDependencyEdgeManifestPayload`와 detached receipt를
발행한다. 이 payload는 pre-G1 branch-independent static union, edge
condition과 expansion algorithm의 유일한 정본이며 runtime actual
cardinality를 주장하지 않는다. successor는 그 payload SHA를 역참조하지
않는다. 문서의 DAG 설명, 22-row matrix와 Gate expected static graph digest는
이 payload에서 결정론적으로 생성한다.

payload field:

```text
schema_sha
tested_successor_sha
tested_successor_publication_receipt_sha
closure_dependency_definition_digest
p0_freeze_receipt_sha
node_records[]:
  node_id, node_type, owner, literal_path_constructor,
  schema_sha, publication_profile, publisher_actor/Physical,
  cardinality_domain
edge_records[]:
  edge_id, from_node_id, to_node_id, edge_type,
  edge_condition, cardinality_domain
canonical_node_order
canonical_edge_order
serialization_domain
static_node_union_digest
static_edge_union_digest
static_union_graph_digest
runtime_expansion_algorithm_sha
producer_physical + producer_sha + literal_argv
independent_checker_physical + checker_sha + literal_argv
```

selected execution terminal 뒤 exact attempt root에
`ClosureDependencyExpansionResultPayload`와 detached wrapper를 한 번
발행한다.

```text
tested_successor_sha + static_manifest_payload/receipt_sha
attempt_namespace_freeze_payload/receipt_sha
attempt_id + execution_authority_lineage_digest
selected_execution_terminal_role + receipt_sha
selected_branch = SUCCESS_SUFFIX_CANDIDATE | FAILURE_TAIL
observed_result_count = 0..15
ordered_observed_result_ref[]
pre_close_cas_state + cas_payload_sha_or_na
first_execution_failure_role_or_na
materialized_execution_node_id[]
materialized_execution_edge_id[]
materialized_node_count + materialized_edge_count
execution_projection_digest
selected_finalization_projection_kind =
  SUCCESS_SUFFIX_CANDIDATE | FAILURE_TAIL
ordered_projected_finalization_node_id[]
ordered_projected_finalization_edge_id[]
projected_finalization_node_count + projected_finalization_edge_count
finalization_projection_digest
static_union_membership_verification = PASS
producer/checker actor + distinct Physical/SHA/argv
```

result는 materialized execution subset과 selected terminal이 허용하는
finalization projection을 분리한다. `SUCCESS` terminal이면
`SUCCESS_SUFFIX_CANDIDATE`, 나머지 terminal이면 `FAILURE_TAIL` projection을
static condition에서 결정론적으로 선택하되 projected node를 이미
materialized됐다고 주장하지 않는다. success suffix가 완결되면 G3/G6가
ordered actual set과 projected set을 exact 비교한다. success suffix가
중간에 끊기면 아래 `FinalizationPrefixFailureCheckpoint`가 durable actual
prefix와 failure-tail switch를 결속한다. G3-EVIDENCE와 G6는
`static_union_graph_digest`, execution/finalization projection digest와
expansion result/receipt SHA를 서로 다른 named field로 비교한다. pre-G1
manifest를 실행 뒤 수정하거나 runtime result를 static 정본으로 가장하면
거부한다.

required node set은 `P0`, `B01..B18`, `M01..M04`, `D-A..D-D`,
§8.2 `MilestoneArtifactRegistry`의 exact 29 ID, `T01..T15`,
`G1..G5-SPEC/EVIDENCE`, `G6`, 두 `P7 pair`, `G7`,
`FINALIZATION-CONSUME`, `FINALIZATION-CLOSE`, `READY-PAYLOAD`,
`READY-WRAPPER`, `CAS-FAILURE`, `FAILURE-EVIDENCE-PAYLOAD`,
`FAILURE-EVIDENCE-WRAPPER`, `FAILURE-DISPOSITION-PAYLOAD`,
`FAILURE-DISPOSITION-WRAPPER`, `FAILURE-TERMINAL-PAYLOAD`,
`FAILURE-TERMINAL-WRAPPER`, `P0-ALLOWED-DELTA`,
`P0-SYNTHETIC-DERIVATION`,
`ATTEMPT-NAMESPACE-FREEZE-PAYLOAD`,
`ATTEMPT-NAMESPACE-FREEZE-WRAPPER`,
`DEPENDENCY-EXPANSION-RESULT-PAYLOAD`,
`DEPENDENCY-EXPANSION-RESULT-WRAPPER`,
`FINALIZATION-PREFIX-FAILURE-CHECKPOINT-PAYLOAD`,
`FINALIZATION-PREFIX-FAILURE-CHECKPOINT-WRAPPER`,
`TERMINAL-WRAPPER-RECOVERY-GRANT-PAYLOAD`,
`TERMINAL-WRAPPER-RECOVERY-GRANT-WRAPPER`,
`TERMINAL-WRAPPER-RECOVERY-CONSUME`,
아래 four-role revocation version/observation closed expansion과
아래 exact 12개 B01 artifact node, B04 exact 6, B06 exact 10, M02 exact 4
physical artifact node를 포함한다.

```text
B01-CANONICAL-ROOT-PAYLOAD
B01-CANONICAL-ROOT-WRAPPER
B01-ISSUE-PAYLOAD
B01-ISSUE-WRAPPER
B01-CONSUME-PAYLOAD
B01-CONSUME-WRAPPER
B01-REVIEW-CONSUME-PAYLOAD
B01-REVIEW-CONSUME-WRAPPER
B01-FAILURE-FIXTURE-PAYLOAD
B01-FAILURE-FIXTURE-WRAPPER
B01-CLOSE-PAYLOAD
B01-CLOSE-WRAPPER
```

required closure edge는 다음과 같다.

```text
E001 P0 → B17
E002 B17 → B11
E003 B17 → B01
E004 B17 → B03
E005 B17 → B08
E006 B17 → B15
E007 B17 → M02
E008 B11 → B12
E009 B12 → B13
E010 B12 → B18
E011 B13 → B07
E012 B13 → B14
E013 B08 → B07
E014 B08 → B18
E015 B15 → B14
E016 M01a → B14a
E017 B01 → B02
E018 B01 → B03
E019 B01 → B05
E020 B02 → B05
E021 B03 → B05
E022 B02 → M03
E023 B03 → M03
E024 B10a → B06a
E025 B09a → B09b
E026 B09b → B10b
E027 B10b → B09c
E028 B09c → B06b
E030 B01a → M04a
E031 B06a → M04a
E032 M04a → B16a
E033 B16a → M04b
E034 M04b → B16b
E037 B06b → B04a
E038 D-A → B18
E039 D-B → B18
E040 D-A → G4E
E041 D-B → G4E
E042 D-C → G4E
E043 D-D → G4E
E044 B13a → B10b
E045 B06a → B06b
E046 B10a → B10b
E047 M03 → B04
E048 M03 → B05
E049 M01a → B07a
E050 M01a → B18a
E051 P0 → B11
E052 P0 → B08
E053 P0 → B15
E054 P0 → M02
E055 B17 → B12
E056 B07 → M01
E057 B14 → M01
E058 B18 → M01
E059 B08 → B15
E060 M02 → B13
```

Gate/V1 expansion도 같은 manifest 내부의 다음 exact ID 집합을 쓴다.

```text
BASELINE = {P0-FREEZE,P0-ALLOWED-DELTA,P0-SYNTHETIC-DERIVATION,
            SUCCESSOR-FREEZE,DEPENDENCY-EDGE-MANIFEST}
SPEC_GATE = {G1S,G2S,G3S,G4S,G5S} # each Gn-SPEC result-receipt terminal
PREAUTH = {ATTEMPT-NAMESPACE-FREEZE-PAYLOAD,
           ATTEMPT-NAMESPACE-FREEZE-WRAPPER,
           SUCCESSOR-REVIEW-PREAUTH,BRIDGE,BRIDGE-REVIEW,DECISION}
REVOCATION_VERSION = exact product {
  head-role = EXECUTION | FINALIZATION | CLOSE-RECOVERY |
              TERMINAL-WRAPPER-RECOVERY,
  ordinal = 000000 | 000001 | 000002,
  member = PAYLOAD | WRAPPER
}
REVOCATION_OBSERVATION = exact product {
  head-role = EXECUTION | FINALIZATION | CLOSE-RECOVERY |
              TERMINAL-WRAPPER-RECOVERY,
  phase = PRE-CONSUME | POST-CONSUME-TERMINAL
}
V1_CONTROL = {FINALIZATION-GRANT,CLOSE-RECOVERY-GRANT,V1-SPEC-BUNDLE,
              TERMINAL-WRAPPER-RECOVERY-GRANT-PAYLOAD,
              TERMINAL-WRAPPER-RECOVERY-GRANT-WRAPPER,
              EXECUTION-CONSUME,CLOSE-RECOVERY-CONSUME,CAS-PRECLOSE,
              EXECUTION-TERMINAL-SUCCESS,EXECUTION-TERMINAL-FAILURE,
              EXECUTION-TERMINAL-CRASH,EXECUTION-TERMINAL-EXPIRED,
              DEPENDENCY-EXPANSION-RESULT-PAYLOAD,
              DEPENDENCY-EXPANSION-RESULT-WRAPPER,FINALIZATION-CONSUME,
              CAS-FINAL,CAS-FAILURE,
              FINALIZATION-PREFIX-FAILURE-CHECKPOINT-PAYLOAD,
              FINALIZATION-PREFIX-FAILURE-CHECKPOINT-WRAPPER,
              FINALIZATION-CLOSE,TERMINAL-WRAPPER-RECOVERY-CONSUME}
V1_TASK = {T01,T02,T03,T04,T05,T06,T07,T08,T09,T10,T11,T12,T13,T14,T15}
MILESTONE_COMPLETION = exact 29 IDs from MilestoneArtifactRegistry
ROW_COMPLETION = {B01..B18,M01..M04}
DEBT_COMPLETION = {D-A,D-B,D-C,D-D}
EVIDENCE_GATE = {G1E,G2E,G3E,G4E,G5E} # each Gn-EVIDENCE result-receipt terminal
FINAL_GATE = {G6-SPEC,G6-DISPOSITION,G6-RESULT,
              P7-SUCCESSOR-FORMAL,P7-SUCCESSOR-SKEPTICAL,
              P7-SUCCESSOR-PAIR,
              P7-EVIDENCE-FORMAL,P7-EVIDENCE-SKEPTICAL,
              P7-EVIDENCE-PAIR,
              G7-SPEC,G7-RESULT,READY-PAYLOAD,READY-WRAPPER,
              FAILURE-EVIDENCE-PAYLOAD,FAILURE-EVIDENCE-WRAPPER,
              FAILURE-DISPOSITION-PAYLOAD,FAILURE-DISPOSITION-WRAPPER,
              FAILURE-TERMINAL-PAYLOAD,FAILURE-TERMINAL-WRAPPER}
```

exact terminal edge IDs:

```text
GE001 P0-FREEZE → SUCCESSOR-FREEZE
GE002 SUCCESSOR-FREEZE → DEPENDENCY-EDGE-MANIFEST
GE003 DEPENDENCY-EDGE-MANIFEST → G1S
GE004 G1S → G2S
GE005 G2S → G3S
GE006 G3S → G4S
GE007 G4S → G5S
GE008 G5S → SUCCESSOR-REVIEW-PREAUTH
AN001 G5S → ATTEMPT-NAMESPACE-FREEZE-PAYLOAD
AN002 DEPENDENCY-EDGE-MANIFEST → ATTEMPT-NAMESPACE-FREEZE-PAYLOAD
AN003 ATTEMPT-NAMESPACE-FREEZE-PAYLOAD → ATTEMPT-NAMESPACE-FREEZE-WRAPPER
AN004 ATTEMPT-NAMESPACE-FREEZE-WRAPPER → SUCCESSOR-REVIEW-PREAUTH
GE009 SUCCESSOR-REVIEW-PREAUTH → BRIDGE
GE010 BRIDGE → BRIDGE-REVIEW
GE011 BRIDGE-REVIEW → DECISION
GE012 DECISION → FINALIZATION-GRANT
GE013 DECISION → V1-SPEC-BUNDLE
GE014 FINALIZATION-GRANT → EXECUTION-CONSUME
GE015 V1-SPEC-BUNDLE → EXECUTION-CONSUME
each revocation role R in exact {
  EXECUTION,FINALIZATION,CLOSE-RECOVERY,TERMINAL-WRAPPER-RECOVERY
}:
  RV-R-001 DECISION → R-REVOCATION-000000-PAYLOAD
  RV-R-002..004 each ordinal payload → own wrapper
  RV-R-005 R-REVOCATION-000000-WRAPPER
    → R-REVOCATION-000001-PAYLOAD # optional renewal/revoke
  RV-R-006 R-REVOCATION-000001-WRAPPER
    → R-REVOCATION-000002-PAYLOAD # optional revoke only
  RV-R-007..009 each ordinal wrapper
    → R-REVOCATION-PRE-OBSERVATION # selected-latest condition
  RV-R-010 R-CONSUME → R-REVOCATION-POST-OBSERVATION # optional
RVX001 EXECUTION-REVOCATION-PRE-OBSERVATION → EXECUTION-CONSUME
RVX002 EXECUTION-REVOCATION-POST-OBSERVATION
  → CLOSE-RECOVERY-CONSUME # REVOKED only
RVX003 FINALIZATION-REVOCATION-PRE-OBSERVATION → FINALIZATION-CONSUME
RVX004 FINALIZATION-REVOCATION-POST-OBSERVATION
  → CAS-FAILURE # REVOKED and CAS-FINAL absent
RVX005 FINALIZATION-REVOCATION-POST-OBSERVATION
  → FINALIZATION-PREFIX-FAILURE-CHECKPOINT-PAYLOAD
  # REVOKED and CAS-FINAL present
RVX006 CLOSE-RECOVERY-REVOCATION-PRE-OBSERVATION
  → CLOSE-RECOVERY-CONSUME
RVX007 CLOSE-RECOVERY-REVOCATION-POST-OBSERVATION
  → selected recovery execution terminal
RVX008 TERMINAL-WRAPPER-RECOVERY-REVOCATION-PRE-OBSERVATION
  → TERMINAL-WRAPPER-RECOVERY-CONSUME
RVX009 TERMINAL-WRAPPER-RECOVERY-REVOCATION-POST-OBSERVATION
  → selected terminal wrapper
ER001 DECISION → CLOSE-RECOVERY-GRANT
ER002 CLOSE-RECOVERY-GRANT → CLOSE-RECOVERY-CONSUME
GT001..GT015 EXECUTION-CONSUME → T01..T15
TD001..TD025 exact task edges from §13.7
GT016..GT030 T01..T15 → CAS-PRECLOSE
ET001 CAS-PRECLOSE → EXECUTION-TERMINAL-SUCCESS
ET002 CAS-PRECLOSE → EXECUTION-TERMINAL-FAILURE
ET003 CLOSE-RECOVERY-CONSUME → EXECUTION-TERMINAL-CRASH
ET004 CLOSE-RECOVERY-CONSUME → EXECUTION-TERMINAL-EXPIRED
ET005..ET008 each execution terminal → FINALIZATION-CONSUME
ET009 CLOSE-RECOVERY-CONSUME → EXECUTION-TERMINAL-FAILURE
GT031..GT045 T01..T15 → selected SUCCESS/FAILURE terminal
EO001..EO015 observed strict task-result prefix
  → selected recovery terminal; actual cardinality = 0..15
EP001 SELECTED-EXECUTION-TERMINAL → DEPENDENCY-EXPANSION-RESULT-PAYLOAD
EP002 DEPENDENCY-EDGE-MANIFEST → DEPENDENCY-EXPANSION-RESULT-PAYLOAD
EP003 DEPENDENCY-EXPANSION-RESULT-PAYLOAD
  → DEPENDENCY-EXPANSION-RESULT-WRAPPER
EP004 DEPENDENCY-EXPANSION-RESULT-WRAPPER → G3E-SPEC-PAYLOAD
EP005 FINALIZATION-CONSUME → DEPENDENCY-EXPANSION-RESULT-PAYLOAD
GE018 FINALIZATION-GRANT → FINALIZATION-CONSUME
GT046..GT060 FINALIZATION-CONSUME → T01..T15-COMPLETION
GT061..GT075 SELECTED-EXECUTION-TERMINAL → T01..T15-COMPLETION
TP001..TP025 exact predecessor task completion → current task completion
GT076..GT090 T01..T15-COMPLETION → CAS-FINAL
GE019 FINALIZATION-CONSUME → CAS-FINAL
GM001..GM029 CAS-FINAL → each exact milestone completion
GF001..GF029 FINALIZATION-CONSUME → each exact milestone completion
MT001..MT029 exact supporting task completion → exact milestone completion
MP001..MP041 exact predecessor milestone completion → current milestone completion
GM030..GM062 each milestone row-slot → exact finding completion
GC001..GC022 CAS-FINAL → B01..B18/M01..M04-COMPLETION
GC023..GC026 CAS-FINAL → D-A..D-D-COMPLETION
GF030..GF055 FINALIZATION-CONSUME → each exact finding/debt completion
CR001..CR040 §12.3 expected predecessor completion/debt → completion
PX001..PX012 §12.3 exact external predecessor receipt → completion
CT001..CT045 §12.3 supporting task completion → completion
GC027..GC048 B01..B18/M01..M04-COMPLETION → G1E
GC049..GC052 D-A..D-D-COMPLETION → G1E
GE020 G1E → G2E
GE021 G2E → G3E
GE022 G3E → G4E
GE023 G4E → G5E
GE024 G5E → G6-SPEC
GE025 G6-SPEC → G6-DISPOSITION
GE026 G6-DISPOSITION → G6-RESULT
GE027..GE030 G6-RESULT → each exact P7 review node
GE031 P7-SUCCESSOR-FORMAL → P7-SUCCESSOR-PAIR
GE032 P7-SUCCESSOR-SKEPTICAL → P7-SUCCESSOR-PAIR
GE033 P7-EVIDENCE-FORMAL → P7-EVIDENCE-PAIR
GE034 P7-EVIDENCE-SKEPTICAL → P7-EVIDENCE-PAIR
GE035 P7-SUCCESSOR-PAIR → G7-SPEC
GE036 P7-EVIDENCE-PAIR → G7-SPEC
GE037 G7-SPEC → G7-RESULT
GE038 G7-RESULT → READY-PAYLOAD
GE039 READY-PAYLOAD → FINALIZATION-CLOSE # SUCCESS branch only
GE040 FINALIZATION-CONSUME → FINALIZATION-CLOSE
GE041 FINALIZATION-CLOSE → READY-WRAPPER # SUCCESS branch only
GE042 READY-PAYLOAD → READY-WRAPPER # SUCCESS branch only

FT001..FT015 T01..T15-COMPLETION → CAS-FAILURE
FE001 CAS-FAILURE → FAILURE-EVIDENCE-PAYLOAD
FE002 FAILURE-EVIDENCE-PAYLOAD → FAILURE-EVIDENCE-WRAPPER
FE003 FAILURE-EVIDENCE-WRAPPER → FAILURE-DISPOSITION-PAYLOAD
FE004 FAILURE-DISPOSITION-PAYLOAD → FAILURE-DISPOSITION-WRAPPER
FE005 FAILURE-DISPOSITION-WRAPPER → FAILURE-TERMINAL-PAYLOAD
FE006 FAILURE-TERMINAL-PAYLOAD → FINALIZATION-CLOSE
FE007 FINALIZATION-CLOSE → FAILURE-TERMINAL-WRAPPER
FE008 FAILURE-TERMINAL-PAYLOAD → FAILURE-TERMINAL-WRAPPER
FA001 CAS-FINAL → FINALIZATION-PREFIX-FAILURE-CHECKPOINT-PAYLOAD
FA002 selected first failed/non-runnable success artifact
  → FINALIZATION-PREFIX-FAILURE-CHECKPOINT-PAYLOAD
FA003 FINALIZATION-CONSUME
  → FINALIZATION-PREFIX-FAILURE-CHECKPOINT-PAYLOAD
FA004 FINALIZATION-PREFIX-FAILURE-CHECKPOINT-PAYLOAD
  → FINALIZATION-PREFIX-FAILURE-CHECKPOINT-WRAPPER
FA005 FINALIZATION-PREFIX-FAILURE-CHECKPOINT-WRAPPER
  → FAILURE-EVIDENCE-PAYLOAD
WR001 DECISION → TERMINAL-WRAPPER-RECOVERY-GRANT-PAYLOAD
WR002 FINALIZATION-GRANT → TERMINAL-WRAPPER-RECOVERY-GRANT-PAYLOAD
WR003 TERMINAL-WRAPPER-RECOVERY-GRANT-PAYLOAD
  → TERMINAL-WRAPPER-RECOVERY-GRANT-WRAPPER
WR004 TERMINAL-WRAPPER-RECOVERY-GRANT-WRAPPER
  → TERMINAL-WRAPPER-RECOVERY-CONSUME
WR005 FINALIZATION-CLOSE → TERMINAL-WRAPPER-RECOVERY-CONSUME
WR006 TERMINAL-WRAPPER-RECOVERY-CONSUME → selected terminal wrapper

PE001 P0-FREEZE → P0-ALLOWED-DELTA
PE002 P0-ALLOWED-DELTA → P0-SYNTHETIC-DERIVATION
PE003 P0-SYNTHETIC-DERIVATION → SUCCESSOR-FREEZE
PE004 P0-FREEZE → B09a-PAYLOAD
PE005 P0-ALLOWED-DELTA → B09a-PAYLOAD
PE006 P0-SYNTHETIC-DERIVATION → B09a-PAYLOAD

BG001 EXECUTION-CONSUME → B01-CANONICAL-ROOT-PAYLOAD
BG002 B01-CANONICAL-ROOT-PAYLOAD → B01-CANONICAL-ROOT-WRAPPER
BG003 B01-CANONICAL-ROOT-WRAPPER → B01-ISSUE-PAYLOAD
BG004 B01-ISSUE-PAYLOAD → B01-ISSUE-WRAPPER
BG005 B01-ISSUE-WRAPPER → B01-CONSUME-PAYLOAD
BG006 B01-CONSUME-PAYLOAD → B01-CONSUME-WRAPPER
BG007 B01-CONSUME-WRAPPER → B01-REVIEW-CONSUME-PAYLOAD
BG008 B01-REVIEW-CONSUME-PAYLOAD → B01-REVIEW-CONSUME-WRAPPER
BG009 B01-REVIEW-CONSUME-WRAPPER → B01-FAILURE-FIXTURE-PAYLOAD
BG010 B01-FAILURE-FIXTURE-PAYLOAD → B01-FAILURE-FIXTURE-WRAPPER
BG011 B01-FAILURE-FIXTURE-WRAPPER → B01-CLOSE-PAYLOAD
BG012 B01-CLOSE-PAYLOAD → B01-CLOSE-WRAPPER
BG013 B01-CLOSE-WRAPPER → T07-RESULT-PAYLOAD
BG014 B01-CLOSE-WRAPPER → B01a-PAYLOAD

B04X001..B04X003 each B04 payload → own wrapper
B04X004 B06-V1-WRAPPER → B04-CONTRACT-PAYLOAD
B04X005 B04-CONTRACT-WRAPPER → B04-FIXTURE-PAYLOAD
B04X006 B06-V1-WRAPPER → B04-FIXTURE-PAYLOAD
B04X007 M03-DISJOINT-RECOVERY-CONTRACT → B04-FIXTURE-PAYLOAD
B04X008 B04-FIXTURE-WRAPPER → B04-VERIFICATION-PAYLOAD
B04X009 B04-CONTRACT-WRAPPER → B04-VERIFICATION-PAYLOAD
B04X010 B04-VERIFICATION-WRAPPER → T15-RESULT-PAYLOAD

B06X001..B06X005 each B06 payload → own wrapper
B06X006 B06-N26-WRAPPER → B06-T1-PAYLOAD
B06X007 B06-N26-WRAPPER → B06-X1-PAYLOAD
B06X008 B06-T1-WRAPPER → B06-X1-PAYLOAD
B06X009 B06-X1-WRAPPER → B06-REVIEW-BINDING-PAYLOAD
B06X010 B06-REVIEW-BINDING-WRAPPER → B06-V1-PAYLOAD

M02X001 M02-REGISTRY-PAYLOAD → M02-REGISTRY-WRAPPER
M02X002 M02-REGISTRY-WRAPPER → M02-VERIFICATION-PAYLOAD
M02X003 M02-VERIFICATION-PAYLOAD → M02-VERIFICATION-WRAPPER
M02X004 M02-VERIFICATION-WRAPPER → T01-RESULT-PAYLOAD
M02X005..M02X023 each exact late-bound source binding
  → ACTUAL-EXACT6-INPUT
```

`GT001..GT015`처럼 범위로 쓴 표기는 successor manifest에서 T01부터 T15
순서로 정확히 15개의 literal edge record로 펼친다. 다른 범위도 같은
closed ID 순서와 registry로 exact cardinality를 materialize한다.
wildcard/glob가
아니다.

```text
each GT range = 15
TP001..TP025 = 25
FT001..FT015 = 15
AN001..AN004 = 4
each RV-R-001..010 = 10; four-role static union = 40
RVX001..RVX009 = 9; branch conditions select only legal observation edges
ER001..ER002 = 2
ET001..ET009 = 9; selected terminal inbound/outbound actual = 1/1
EO001..EO015 actual = observed strict prefix count 0..15
EP001..EP005 = 5
FA001..FA005 = 5; selected prefix source actual = exact 1
WR001..WR006 = 6; grant path actual = 3,
  close-tail consume/output path actual = 0 or 3
GM001..GM029 = 29
GF001..GF029 = 29
MT001..MT029 = 29
MP001..MP041 = 41
GM030..GM062 = 33
GC001..GC022 = 22
GC023..GC026 = 4
GF030..GF055 = 26
CR001..CR040 = 40
PX001..PX012 = 12
CT001..CT045 = 45
GC027..GC048 = 22
GC049..GC052 = 4
GE027..GE030 = 4
FE001..FE008 = 8
PE001..PE006 = 6
BG001..BG014 = 14
B04X001..B04X010 = 10
B06X001..B06X010 = 10
M02X001..M02X023 = 23
```

`GT016..GT045`는 non-recovery SUCCESS/FAILURE terminal에서만 exact 15씩
materialize하고 recovery FAILURE/CRASH/EXPIRED에서는 `0`이며, recovery
terminal은 `EO` observed set `0..15`를 쓴다.
`GT076..GT090`부터 `GE042`까지의 CAS-FINAL/milestone/row/Gate/ready edge는
SUCCESS branch only다. `FT001..FT015`와 `FE001..FE008`은 FAILURE branch
only다. `ET001..ET004,ET009` 중 terminal inbound actual cardinality는
정확히 `1`이고 나머지는 `0`이다. 한 attempt의 branch selector는 selected
execution terminal과 exact first
failed/non-runnable prefix role로 하나만 선택하고
`selected_branch = SUCCESS XOR FAILURE`를 runtime expansion/checkpoint에
기록한다.
FAILURE 선택 뒤 새 success-only edge materialization은 `0`이다.

29개 milestone row의 `supporting_task_id[]`와
`expected_predecessor_milestone_id[]`는 다음 exact registry다. 빈 배열도
명시적으로 signed row에 들어간다.

```text
B01a <- task T07; milestone [B17a]
B02a <- task T07; milestone [B01a]
B03a <- task T07; milestone [B01a]
B04a <- task T15; milestone [B06b]
B05a <- task T07; milestone [B02a,B03a]
B06a <- task T06; milestone [B10a]
B06b <- task T15; milestone [B09c,B06a]
B07a <- task T04; milestone [M01a,B08a]
B08a <- task T04; milestone [B17a]
B09a <- task T06; milestone []
B09b <- task T15; milestone [B09a]
B09c <- task T15; milestone [B10b]
B10a <- task T06; milestone []
B10b <- task T15; milestone [B09b,B10a,B13a]
B11a <- task T01; milestone [B17a]
B12a <- task T02; milestone [B11a]
B13a <- task T05; milestone [B12a,M02a]
B14a <- task T14; milestone [M01a,B13a,B15a]
B15a <- task T05; milestone [B08a]
M04a <- task T08; milestone [B01a,B06a]
B16a <- task T08; milestone [M04a]
M04b <- task T09; milestone [B16a]
B16b <- task T09; milestone [M04b]
B17a <- task T01; milestone []
B18a <- task T03; milestone [M01a,B08a,B12a]
M01a <- task T05; milestone [B13a,B15a]
M01b <- task T14; milestone [B07a,B14a,B18a]
M02a <- task T01; milestone [B17a]
M03a <- task T07; milestone [B02a,B03a]
```

`MT001..MT029`는 위 row 순서로 해당 task completion receipt에서 milestone
payload로 가는 정확히 29개 edge다. `MP001..MP041`은 같은 row 순서와 각
대괄호 내부 순서로 predecessor milestone receipt에서 current milestone
payload로 가는 정확히 41개 edge다. CAS와
`FINALIZATION-CONSUME` edge도 각 29개 milestone payload마다 하나씩
materialize한다. task result나 milestone payload를 receipt로 가장하지
않는다.

`expected_external_predecessor_role[]`는 B09a만 exact
`[P0-FREEZE,P0-ALLOWED-DELTA,P0-SYNTHETIC-DERIVATION]`이고 나머지 28개
milestone은 empty array다.

artifact expansion rule도 manifest payload 안에 둔다.

```text
each Gate G:
  G-SPEC-PAYLOAD → G-SPEC-RECEIPT
  G-SPEC-RECEIPT → G-RAW-STDOUT
  G-SPEC-RECEIPT → G-RAW-STDERR
  G-RAW-STDOUT → G-RESULT-PAYLOAD
  G-RAW-STDERR → G-RESULT-PAYLOAD
  G-RESULT-PAYLOAD → G-RESULT-RECEIPT
  terminal(G) = G-RESULT-RECEIPT
each V1 task T:
  T-SPEC-PAYLOAD → T-SPEC-RECEIPT
  T-SPEC-RECEIPT → T-RAW-STDOUT
  T-SPEC-RECEIPT → T-RAW-STDERR
  T-RAW-STDOUT → T-RESULT-PAYLOAD
  T-RAW-STDERR → T-RESULT-PAYLOAD
  observed T-RESULT-PAYLOAD → SELECTED-EXECUTION-TERMINAL
  SELECTED-EXECUTION-TERMINAL → T-COMPLETION-RECEIPT
  FINALIZATION-CONSUME → T-COMPLETION-RECEIPT
each task dependency P → T:
  P-SIGNED-RESULT → T-RAW-STDOUT
  P-SIGNED-RESULT → T-RAW-STDERR
  P-SIGNED-RESULT → T-RESULT-PAYLOAD
  P-COMPLETION-RECEIPT → T-COMPLETION-RECEIPT
each milestone completion M:
  CAS-FINAL → M-PAYLOAD
  FINALIZATION-CONSUME → M-PAYLOAD
  exact supporting T-COMPLETION-RECEIPT → M-PAYLOAD
  each expected predecessor M-RECEIPT → M-PAYLOAD
  M-PAYLOAD → M-RECEIPT
each row/debt completion C:
  CAS-FINAL → C-PAYLOAD
  FINALIZATION-CONSUME → C-PAYLOAD
  each expected predecessor completion/debt receipt → C-PAYLOAD
  each expected external predecessor receipt → C-PAYLOAD
  each expected supporting task completion receipt → C-PAYLOAD
  each expected milestone receipt → C-PAYLOAD
  C-PAYLOAD → C-RECEIPT
  C-RECEIPT → G1E-SPEC-PAYLOAD
```

pre-G1 static manifest는 `static_node_union_digest`,
`static_edge_union_digest`, `static_union_graph_digest`와
`runtime_expansion_algorithm_sha`만 기록한다. selected execution terminal
뒤 runtime expansion result가 concrete execution node/edge cardinality,
set digest, `execution_projection_digest`와 selected conditional
`finalization_projection_digest`를 기록하고 producer와 independent checker가
exact equality를 확인한다. success CAS 뒤 suffix가 중간에 끊기면 별도
prefix-failure checkpoint가 actual finalization prefix를 기록한다. 미래
execution/finalization actual data를 static manifest에 선기록하거나
projection을 materialization 사실로 가장하거나 manifest를 뒤에서 수정하지
않는다.

static manifest 외 edge, runtime result의 missing/extra/duplicate edge,
wrong expansion cardinality, cycle, B16→M04a 또는 M04b→B16a 역방향은
거부한다. static digest, expansion result/receipt 또는 prefix checkpoint
binding이 spec의 named field와 다르면 G3-EVIDENCE와 G6는 PASS할 수
없다.

## 9. constructive H1과 actual H2 Stage-C tagged type

두 집합은 strict disjoint tagged union이다.

### 9.1 H1 constructive-only

```text
StageCLiveRootContractSpec
ConstructiveStageCRootFixture
ConstructiveExact6Input
ConstructiveExact6Result
ConstructiveStageCRootIntegrationReceipt
ConstructiveCReceiptFixture
```

- P0+allowed-delta에서 유도한 synthetic/isolated root만 사용한다.
- checkpoint, canonical, live product와 actual root를 바꾸지 않는다.
- sequence 40, actual application 또는 official credit을 주장하지 않는다.
- G5-EVIDENCE/G6가 소비할 수 있는 유일한 Stage-C 계열이다.

#### 9.1.1 B06 physical object/hash contract

§7 B06 five object는 각 payload와 detached wrapper exact pair다.

| object | payload/wrapper schema role | payload required inward binding | exact semantic hash domain |
|---|---|---|---|
| `N26` | `B06_N26_PAYLOAD_V1` / `B06_N26_WRAPPER_V1` | ordered exact26 target rows, count/order/schema/set digest | `WS-PRE-P-R007-NORMATIVE-TARGET-TABLE-V1` |
| `T1` | `B06_T1_PAYLOAD_V1` / `B06_T1_WRAPPER_V1` | N26 payload/wrapper/value, target-map/equality receipt | `WS-PRE-P-R007-T1-V1` |
| `X1` | `B06_X1_PAYLOAD_V1` / `B06_X1_WRAPPER_V1` | N26/T1, U1 spec, recovery/exact6 contract, target spec | `WS-PRE-P-R007-X1-V1` |
| `StageCReviewBinding` | `B06_STAGE_C_REVIEW_BINDING_PAYLOAD_V1` / `...WRAPPER_V1` | X1, resolved subject, ordered Stage-B formal/skeptical receipts, finding `0/0/0` | `WS-PRE-P-R007-STAGE-C-REVIEW-BINDING-V1` |
| `V1` | `B06_V1_BINDING_PAYLOAD_V1` / `...WRAPPER_V1` | exact ReviewBinding payload/wrapper JCS bytes only | `R007_RESOLVED_REVIEW_BINDING_V1` |

모든 payload/wrapper는 RFC8785 JSON object이고 아래 required field만
허용하며 `additionalProperties=false`다.

```text
common payload envelope:
  object_type = exact table object
  schema_role + schema_sha
  branch = CONSTRUCTIVE_H1
  literal_path
  publisher_actor_id + publisher_role
  publisher_physical_path + publisher_physical_sha + literal_argv
  semantic_domain + semantic_value_sha256

N26 body:
  ordered_target_rows[26]:
    ordinal=1..26, literal_target_path, target_schema_sha, target_value_sha
  target_count=26 + target_order_digest + target_set_digest
T1 body:
  n26_payload_sha + n26_wrapper_sha + n26_semantic_value_sha256
  ordered_target_map_rows[26] + target_map_digest
  equality_receipt_sha + equality_result=PASS
X1 body:
  n26_payload/wrapper/value SHA + t1_payload/wrapper/value SHA
  u1_spec_payload/wrapper SHA + recovery_exact6_contract_sha
  application_receipt_target_spec_jcs + target_spec_jcs_sha256
StageCReviewBinding body:
  x1_payload/wrapper/value SHA + resolved_subject_payload/wrapper SHA
  ordered_stage_b_review_receipts[2] = [formal,skeptical]
  review_pair_receipt_sha + findings={blocking:0,major:0,minor:0}
V1 body:
  review_binding_payload_schema_sha + payload_sha + wrapper_sha
  review_binding_payload_jcs_bytes_sha256
  review_binding_payload/wrapper_file_physical

common detached wrapper body:
  payload_schema_sha + payload_sha256 + payload_file_physical
  semantic_domain + semantic_value_sha256
  publisher_actor_id + publisher_role + publisher_physical_sha
  verifier_actor_id + verifier_role + verifier_physical_sha + literal_argv
  wrapper_signing_domain + signature_algorithm + detached_signature
```

exact publisher/verifier role은 `N26/T1/X1`에 각각
`P3_B06_<OBJECT>_SERIALIZER`/`P3_B06_<OBJECT>_VERIFIER`,
`StageCReviewBinding/V1`에 각각
`P5_B06_<OBJECT>_SERIALIZER`/`P5_B06_<OBJECT>_VERIFIER`다. successor
allowlist가 각 role의 actor ID와 Physical SHA를 하나씩 freeze하고 같은
object의 serializer/verifier Physical은 distinct하다. 모든 SHA field는
exact 64 lower-hex string이고 ordinal/count는 JSON integer다.

```text
semantic_value_sha256 =
  SHA256(ASCII(exact_domain) || 0x00 || RFC8785_JCS(payload_without_value_sha))
wrapper_signing_digest =
  SHA256(ASCII("WS-WALKSAFE-R005-B06-WRAPPER-V1") || 0x00 ||
         HEXLOWER_DECODE32(payload_sha256) ||
         HEXLOWER_DECODE32(payload_schema_sha) ||
         HEXLOWER_DECODE32(publisher_physical_sha) ||
         HEXLOWER_DECODE32(semantic_value_sha256))
```

각 payload는 object type, branch=`CONSTRUCTIVE_H1`, literal path, schema SHA,
publisher actor/Physical/SHA/argv와 위 semantic value를 가진다. wrapper
verifier actor/Physical은 payload publisher와 distinct하다. V1은
ReviewBinding까지만 inward-reference하고 B04 receipt, future H2 transaction,
자기/future SHA를 포함하지 않는다. §8.3 `B06X001..010`만 legal edge다.

future actual subject는 같은 logical value topology를 쓰되 object별
schema role을 `B06_ACTUAL_<OBJECT>_{PAYLOAD|WRAPPER}_V1`, semantic domain을
`WS-WALKSAFE-R005-B06-ACTUAL-V1:<OBJECT>`, wrapper domain을
`WS-WALKSAFE-R005-B06-ACTUAL-WRAPPER-V1`로 바꾼 strict tagged variant를
다음 canonical templates에 적용한다.

```text
<stage-a-subject>/aggregate/candidate/
  {normative-exact26-target-table,t1-target-binding}.{payload,signature}.json
<stage-b-resolved-subject>/
  x1-stage-c-binding.{payload,signature}.json
<authority-root>/journal/attempts/stage-c/<literal-attempt-key>/
  {review-binding,v1-binding}.{payload,signature}.json
```

subject freeze가 모든 metavariable를 exact UTF-8 literal, schema SHA와
publisher Physical로 펼친다. constructive/actual path, schema role,
semantic/signing domain과 publisher set 교집합은 `0`이다.

#### 9.1.2 B04 constructive/actual C-recovery extension

`B04_C_RECOVERY_EXTENSION_CONTRACT_PAYLOAD_V1`은 다음 exact tagged union을
정의한다.

```text
receipt_variant = exact {
  ORDINARY_STAGE_C,
  RECOVERY_EXACT6_SUFFIX,
  RECOVERY_APPLICATION_FINALIZATION
}
common required:
  N26/T1/X1/V1 payload_sha + wrapper_sha + semantic_value_sha256
  StageCReviewBinding payload_sha + wrapper_sha
  ordered_capability_rows[] + capability_row_set_digest
  predecessor kind/payload/receipt/original_state/verification_result
  executor stage/kind/attempt/actor/Physical/authority receipt
  ApplicationReceiptTargetSpec exact RFC8785 JCS bytes
  application_receipt_target_spec_jcs_sha256
```

branch predicate:

```text
ORDINARY_STAGE_C:
  effective Stage-C consume + POSTCHECK_PASSED
RECOVERY_EXACT6_SUFFIX:
  disjoint recovery consume + durable complete exact6 prefix
  + never-dispatched suffix; application finalization forbidden
RECOVERY_APPLICATION_FINALIZATION:
  disjoint recovery consume + existing POSTCHECK_PASSED;
  exact6 reexecution forbidden
```

branch별 capability path/nonce/state set 교집합은 `0`이다. actual extension은
새 H2 evidence 파일을 만들지 않고 기존 receipt schema를 확장한다.

```text
<authority-root>/journal/attempts/stage-c/<literal-key>/
  authority-receipt.json
  schema = STAGE_C_AUTHORITY_RECEIPT_B04_EXTENSION_V1
<authority-root>/journal/attempts/recovery-stage-c/<literal-key>/
  authority-receipt.json
  schema = RECOVERY_STAGE_C_AUTHORITY_RECEIPT_B04_EXTENSION_V1
<literal-h2-root>/stage-c/application-receipt.json
  schema = STAGE_C_APPLICATION_RECEIPT_B04_EXTENDED_V1
```

`ApplicationReceiptTargetSpec` JCS bytes/SHA는 C/recovery receipt,
transaction manifest, POSTCHECK progress와 application receipt에서
byte-equal이다. H1의 contract, constructive fixture, verification pair는
각각 `B04_C_RECOVERY_EXTENSION_CONTRACT_*_V1`,
`B04_CONSTRUCTIVE_C_RECOVERY_FIXTURE_*_V1`,
`B04_EXTENSION_VERIFICATION_*_V1` schema와 distinct producer/checker
Physical을 쓴다. fixture는 H1 B06 objects와 M03 recovery contract만
소비하고 actual authority/H2 path를 소비하지 않는다. verification은
three-variant exhaustiveness, target byte equality, branch disjointness,
self/future SCC=`0`을 재계산한다.

### 9.2 H2 actual-only

```text
StageCCheckpointDurabilityReceipt
StageCLiveRootManifestPayload
StageCLiveRootManifestDetachedSignature
StageCLiveRootPhysicalPublicationReceipt
LiveRootPublishedProgressRef
ActualExact6Input
ActualExact6SandboxIntent
ActualExact6Result
StageCLiveRootIntegrationReceipt
PostcheckPassedProgressRef
StageCApplicationReceipt
StageCFinalizedProgressRef
StageCClosedSuccessReceipt
```

이 type들은 PRE_P_SUCCESSOR_READY와 별도 Stage-C one-use approval 뒤 H2에서만
생산한다. H1 evidence 또는 G6 predecessor로 사용할 수 없다.

### 9.3 B09 actual live-root publication contract

R005와 successor는 다음 13개 role/path suffix/schema-role/publisher-role만
정의한다. 아래 `<h2_root>`는 roadmap metavariable이며 actual artifact에
그 문자열이나 angle bracket가 남아서는 안 된다.

binding control pair path template:

```text
<h2_root>/stage-c/h2-literal-root-binding.payload.json
<h2_root>/stage-c/h2-literal-root-binding.signature.json
```

exact 13 evidence role path templates:

```text
<h2_root>/stage-c/checkpoint-durability-receipt.json
<h2_root>/stage-c/live-root-manifest.payload.json
<h2_root>/stage-c/live-root-manifest.signature.json
<h2_root>/stage-c/live-root-physical-publication-receipt.json
<h2_root>/stage-c/live-root-published.progress.json
<h2_root>/stage-c/actual-exact6-input.json
<h2_root>/stage-c/actual-exact6-sandbox-intent.json
<h2_root>/stage-c/actual-exact6-result.json
<h2_root>/stage-c/live-root-integration-receipt.json
<h2_root>/stage-c/postcheck-passed.progress.json
<h2_root>/stage-c/application-receipt.json
<h2_root>/stage-c/finalized.progress.json
<h2_root>/stage-c/closed-success-receipt.json
```

actual Stage-C write 전에 exact H2 subject와 approval이 하나의
`H2LiteralRootBinding` 값을 먼저 동결한다.

```text
binding_schema_sha
journal_bootstrap_close_receipt_sha
stage_b_validation_PASS_receipt_sha
stage_c_subject_payload_sha + publication_receipt_sha
stage_c_independent_review_receipt_sha[]
stage_c_approval_payload_sha + publication_receipt_sha
stage_c_approval_issued_at
stage_c_authority_consume_receipt_sha
stage_c_authority_consume_receipt_file_physical:
  literal_path, parent_anchor, dev, ino, mode, file_type, nlink, bytes, sha256
stage_c_consume_at + trusted_clock_source
stage_c_revocation_head_payload_sha + wrapper_sha + ordinal
stage_c_revocation_pre_consume_observation_receipt_sha
stage_c_hard_deadline
stage_c_attempt_id + fresh_stage_c_nonce
literal_h2_root_utf8
literal_binding_payload_path
literal_binding_wrapper_path
binding_payload_schema_sha + binding_wrapper_schema_sha
binding_payload_publisher_actor_id + publisher_physical_sha
binding_wrapper_publisher_actor_id + publisher_physical_sha
h2_parent_anchor_physical:
  literal_path, dev, ino, mode, file_type, nlink
h2_root_prewrite_physical:
  literal_path, dev, ino, mode, file_type, nlink
root_is_absolute_or_project_root_relative_literal = true
root_is_non_symlink_non_alias = true
transaction_recovery_subject_digest
exact_role_path_records[13]:
  role_id, literal_path, schema_sha, publisher_actor_id,
  publisher_physical_sha, write_phase, cardinality = 1
literal_path_set_digest
approved_read_write_exec_set_digest
frozen_at + trusted_clock_source
```

payload은
`stage_c_approval_issued_at <= stage_c_consume_at <= frozen_at
 <= stage_c_hard_deadline`, latest head=`UNREVOKED`, same attempt/nonce/scope를
직접 검증한다. consume SHA/time/head/deadline이 payload에 없거나 wrapper의
동일 field와 byte-equal하지 않으면 H2B003은 materialize되지 않는다.

proposed literal root와 13개 fully expanded path는 Stage-C subject에 있고
independent review와 one-use approval이 같은 bytes/digest를 승인한다.
`H2LiteralRootBinding` payload/wrapper publication은 그 approval의 write
set에 포함된 첫 H2 write다. 두 binding path도 subject에서 exact UTF-8
literal로 승인하며 13-role evidence cardinality에는 포함하지 않는다.
H2 root는 prewrite 시점에 존재하는 approved non-symlink directory여야
한다. binding은 predecessor approval을 참조하지만
approval이나 subject는 미래 binding SHA를 역참조하지 않는다. binding
뒤 root/path/schema/publisher/transaction/attempt 중 하나라도 바뀌면 새
subject, review, approval과 binding이 필요하다.

binding pair의 exact schema/publisher:

| artifact | exact schema role | required publisher |
|---|---|---|
| binding payload | `H2_LITERAL_ROOT_BINDING_PAYLOAD_V2` | Stage-C subject materializer Physical |
| binding wrapper | `H2_LITERAL_ROOT_BINDING_WRAPPER_V2` | independent H2 root-binding verifier Physical |

두 publisher actor/Physical은 distinct하다. binding payload는 자기 SHA,
wrapper SHA/signature 또는 미래 checkpoint SHA를 포함하지 않는다. detached
wrapper는 다음 body를 payload 밖 signature와 함께 결속한다.
R004의 plan-only V1 이름은 어떤 artifact도 materialize하지 않았으며
consume lineage가 직접 추가된 R005에서는 재사용하지 않는다.

```text
binding_payload_sha + binding_payload_schema_sha
binding_payload_file_physical:
  literal_path, parent_anchor, dev, ino, mode, file_type, nlink, bytes, sha256
journal_bootstrap_close_receipt_sha + stage_b_validation_PASS_receipt_sha
stage_c_subject/approval/consume receipt SHA
stage_c_authority_consume_receipt_file_physical
stage_c_consume_at + revocation head/wrapper/ordinal/observation SHA
stage_c_hard_deadline + binding_frozen_at
stage_c_attempt_id + nonce
literal_h2_root_utf8
h2_parent_anchor_pre/post_physical
h2_root_pre/post_physical
exact_binding_path_set_digest
exact_13_role_path_set_digest
payload_and_parent_fsynced = true
reopen_payload_sha_equal = true
non_symlink_non_alias_recheck = PASS
publisher_actor_id + publisher_physical_sha
independent_verifier_actor_id + verifier_physical_sha + literal_argv
status = FROZEN
signature_domain + detached_signature
```

bootstrap publication DAG:

```text
H2B001 StageCApprovalReceipt → StageCAuthorityConsumeReceipt
H2B002 StageCApprovalReceipt → H2LiteralRootBindingPayload
H2B003 StageCAuthorityConsumeReceipt → H2LiteralRootBindingPayload
H2B004 H2LiteralRootBindingPayload → H2LiteralRootBindingWrapper
H2B005 H2LiteralRootBindingWrapper → StageCCheckpointDurabilityReceipt
```

payload create 전 parent/root를 no-follow로 reopen해 subject/approval의
prewrite Physical과 비교한다. payload와 wrapper는 create-exclusive,
file fsync→parent fsync→reopen 순서로 발행하고 wrapper가 payload Physical과
parent/root pre/post equality를 독립 검증한다. existing path는 approved
payload/wrapper bytes와 Physical이 모두 exact하고 attempt가 unused일 때만
adopt하며, partial pair, collision, drift는 overwrite/rebuild 없이
Stage-C를 `NOT_RUN`으로 둔다. binding pair는 authority를 만들지 않고
consumed Stage-C approval의 root/path 경계만 물리화한다.

exact 13-role registry:

| role ID / suffix | exact schema role | required publisher role |
|---|---|---|
| `CHECKPOINT-DURABILITY` / `stage-c/checkpoint-durability-receipt.json` | `STAGE_C_CHECKPOINT_DURABILITY_RECEIPT_V1` | checkpoint durability verifier |
| `LIVE-ROOT-MANIFEST-PAYLOAD` / `stage-c/live-root-manifest.payload.json` | `STAGE_C_LIVE_ROOT_MANIFEST_PAYLOAD_V1` | live-root manifest serializer |
| `LIVE-ROOT-MANIFEST-WRAPPER` / `stage-c/live-root-manifest.signature.json` | `STAGE_C_LIVE_ROOT_MANIFEST_WRAPPER_V1` | live-root manifest signer |
| `PHYSICAL-PUBLICATION` / `stage-c/live-root-physical-publication-receipt.json` | `STAGE_C_LIVE_ROOT_PHYSICAL_PUBLICATION_RECEIPT_V1` | physical publication verifier |
| `LIVE-ROOT-PUBLISHED` / `stage-c/live-root-published.progress.json` | `STAGE_C_LIVE_ROOT_PUBLISHED_PROGRESS_V1` | Stage-C progress FSM publisher |
| `ACTUAL-EXACT6-INPUT` / `stage-c/actual-exact6-input.json` | `STAGE_C_ACTUAL_EXACT6_INPUT_V1` | exact6 input serializer |
| `ACTUAL-EXACT6-SANDBOX-INTENT` / `stage-c/actual-exact6-sandbox-intent.json` | `STAGE_C_ACTUAL_EXACT6_SANDBOX_INTENT_V1` | exact6 dispatcher |
| `ACTUAL-EXACT6-RESULT` / `stage-c/actual-exact6-result.json` | `STAGE_C_ACTUAL_EXACT6_RESULT_V1` | exact6 runner |
| `LIVE-ROOT-INTEGRATION` / `stage-c/live-root-integration-receipt.json` | `STAGE_C_LIVE_ROOT_INTEGRATION_RECEIPT_V1` | same-root integration verifier |
| `POSTCHECK-PASSED` / `stage-c/postcheck-passed.progress.json` | `STAGE_C_POSTCHECK_PASSED_PROGRESS_V1` | postcheck FSM publisher |
| `APPLICATION` / `stage-c/application-receipt.json` | `STAGE_C_APPLICATION_RECEIPT_B04_EXTENDED_V1` | application finalizer |
| `FINALIZED` / `stage-c/finalized.progress.json` | `STAGE_C_FINALIZED_PROGRESS_V1` | finalization FSM publisher |
| `CLOSED-SUCCESS` / `stage-c/closed-success-receipt.json` | `STAGE_C_CLOSED_SUCCESS_RECEIPT_V1` | Stage-C close verifier |

13개 actual artifact 각각은 자기 body에 다음 common binding을 직접 가진다.

```text
journal_bootstrap_close_receipt_sha
stage_b_validation_PASS_receipt_sha
stage_c_subject_payload_sha + publication_receipt_sha
stage_c_approval_payload_sha + publication_receipt_sha
stage_c_authority_consume_receipt_sha
stage_c_attempt_id + fresh_stage_c_nonce
h2_literal_root_binding_payload_sha + wrapper_sha
literal_h2_root_utf8
h2_root_physical:
  literal_path, parent_anchor, dev, ino, mode, file_type, nlink
own_role_id + own_literal_path + own_schema_sha
own_publisher_actor_id + own_publisher_physical_sha
exact_predecessor_role_id[] + exact_predecessor_receipt_sha[]
```

모든 13 body의 bootstrap close/Stage-B PASS, binding payload/wrapper SHA,
literal root bytes, `h2_root_physical`,
subject/approval/consume/attempt/nonce는 서로와
`H2LiteralRootBinding` wrapper에 exact equality여야 한다. 간접 manifest
상속이나 앞 artifact에서의 추론은 허용하지 않는다. actual cardinality는
각 role `1`이고 다음 full order를 직접 predecessor receipt로 잇는다.

```text
Stage-C approval + consume
→ H2 binding payload/wrapper
→ CHECKPOINT-DURABILITY
→ LIVE-ROOT-MANIFEST-PAYLOAD
→ LIVE-ROOT-MANIFEST-WRAPPER
→ PHYSICAL-PUBLICATION
→ LIVE-ROOT-PUBLISHED
→ ACTUAL-EXACT6-INPUT
→ ACTUAL-EXACT6-SANDBOX-INTENT
→ ACTUAL-EXACT6-RESULT
→ LIVE-ROOT-INTEGRATION
→ POSTCHECK-PASSED
→ APPLICATION
→ FINALIZED
→ CLOSED-SUCCESS
```

표의 schema role은 template 이름이다. `H2LiteralRootBinding`의 각 row는
non-empty exact schema SHA와 publisher actor/Physical SHA를 넣는다.
missing/extra/duplicate role, literal path collision/escape, symlink/alias,
empty schema/publisher, wrong phase 또는 angle bracket가 남은 path는
approval/consume을 금지한다.

`StageCLiveRootManifestPayload`의 strict field:

```text
checkpoint_durability_receipt_sha
checkpoint_payload_sha
checkpoint_sequence = 40
canonical_gap_target_revision
canonical_backlog_target_revision
live_root_physical:
  literal_path, parent_anchor, dev, ino, mode, file_type, nlink
ordered_member_records[]
member_count = 627
physical_set_digest
exact26_physical_identity[]
exact26_set_digest
U1_physical_identity
U1_digest
exclusion_manifest_sha
producer_physical + producer_sha + literal_argv
```

`StageCLiveRootPhysicalPublicationReceipt`는 manifest payload/signature
FilePhysical, live root Physical, checkpoint durability predecessor,
double-walk before/after equality, file+directory fsync, atomic publication,
reopen 뒤 physical identity/set digest equality를 결속한다.

actual root reference는 다음 exact triple이다.

```text
StageCLiveRootManifest FilePhysical
+ StageCLiveRootPhysicalPublicationReceipt FilePhysical
+ LIVE_ROOT_PUBLISHED ProgressRef
```

`ActualExact6Input`, `ActualExact6SandboxIntent`, 여섯 ordinal result,
`StageCLiveRootIntegrationReceipt`와 `StageCApplicationReceipt`는 이 triple의
세 SHA를 byte-equal로 직접 결속한다. common-field equality로 대체하지
않는다.

one-way order:

```text
Stage-C approval + consume
→ H2 literal-root binding payload/wrapper
→ checkpoint durable
→ manifest payload/signature
→ double-walk + fsync + reopen physical publication receipt
→ LIVE_ROOT_PUBLISHED
→ actual exact6 ordinal 1 → 2 → 3 → 4 → 5 → 6
→ StageCLiveRootIntegrationReceipt
→ POSTCHECK_PASSED
→ StageCApplicationReceipt
→ FINALIZED
→ CLOSED_SUCCESS
```

wrong checkpoint/sequence/root/set digest, invocation마다 다른 manifest,
manifest/receipt/ProgressRef triple 일부 누락, exact26/U1 mismatch, publish 전
exact6, application receipt의 역참조는 거부한다.

recovery capability는 disjoint하다.

```text
EXACT6_SUFFIX:
  durable exact prefix + never-dispatched suffix only
APPLICATION_FINALIZATION:
  POSTCHECK_PASSED 뒤 application/finalization only
```

두 capability의 path/nonce/state set 교집합은 `0`이어야 한다.

## 10. exact 18 BLOCKING closure contract

### B01 — canonical root와 grant/review-consume/prefix lifecycle

- owner:
  P2
- strict outputs:
  `canonical-root-spec.payload.json`,
  `canonical-root-spec.signature.json`, issue/consume/review-consume/close/
  failure-fixture payload와 각각의 detached wrapper; exact 12 files
- payload:
  unsigned `CanonicalRootSpecPayload` JCS bytes에 exact root/head/prefix,
  subject, deadline, publisher와 predecessor만 둔다.
- wrapper:
  §6.1 domain bytes로 payload SHA를 서명하고 signature/self SHA는 payload
  밖에 둔다.
- edge:
  §8.3 `BG001..BG014`; `previous_prefix_digest → next_prefix_digest`,
  grant/review-consume/failure-fixture/close가 exact one-use chain이다.
  failure fixture의 effect는 `NONE`이고 rejected branch만 기록한다.
- reject:
  signature-in-payload, self-hash, replay, fork, prefix mutation,
  review-consume 누락, wrong wrapper domain/publisher
- close:
  unsigned payload JCS, detached signature, prefix chain과 cardinality를
  independent checker가 재계산

### B02 — revocation과 common guard FSM

- owner:
  P2
- required:
  current head/time, original hard deadline, retry 상속 산식, renewal ordinal,
  one-use/unrevoked/consumed/closed/failure predecessor
- reject:
  deadline reset, expired/revoked/duplicate consume, wrong head, ordinal skip,
  fail 뒤 stale decision 재사용
- close:
  A/B/C/recovery legal·illegal edge exhaustiveness, unreachable state `0`

### B03 — literal physical capability row

- owner:
  P2
- exact row:
  `{operation,literal_path,parent_anchor,object_type,publisher,signature_domain}`
- required:
  signed bytes에서 exact row를 independent checker가 재열거
- reject:
  alias, glob, cross-product, parent/sibling expansion, root escape, broad
  `DirPhysical`
- close:
  declared row set과 traced requested capability set exact equality

### B04 — synthetic C/recovery receipt fixture와 actual contract

- owner:
  P5
- H1 output:
  §9.1.2 contract/fixture/verification exact three payload/wrapper pairs
- H2 contract:
  actual `StageCApplicationReceipt`와 §9 triple
- binding:
  §9.1.1 N26/T1/X1/ReviewBinding/V1 exact pairs, capability rows,
  byte-equal target spec, predecessor/executor와 exact three-variant branch
- reject:
  constructive/actual cross-use, common-field-only equality, wrong phase,
  self/future cycle
- close:
  B04 six physical files, §8.3 B04X001..010, strict schema/JCS/SCC/byte
  equality verification PASS만 row receipt에 결속; actual artifact는 G6
  predecessor가 아님

### B05 — D~G future activation artifact

- owner:
  P2
- literal role:
  `authority/future/D/activation-approval.json`부터 G까지 네 exact path
- field:
  stage tag, exact future subject SHA, same-SHA reviews, predecessor receipt,
  nonce/scope/deadline/revocation/one-use/publisher/signature domain
- reject:
  current V1 token, H1/H2 review, 다른 stage approval/receipt 재사용
- close:
  stage 간 implicit authority conversion path `0`

### B06 — N26/T1/X1/StageCReviewBinding/V1

- owner:
  P5
- milestones:
  P3 `B06a-HASH-TOPOLOGY`, P5 `B06b-SYNTHETIC-INSTANCE`
- required order:
  §9.1.1 inward-only `N26 → T1/X1 → StageCReviewBinding → V1`
- producer:
  topology producer와 synthetic instance producer Physical/argv/SHA
- reject:
  label-only, self/future reference, publisher mismatch, actual/fixture cross-use
- close:
  exact 10 physical files, `B06X001..010`, five semantic domain hash와
  payload/wrapper verification이 모두 PASS

### B07 — Stage-B exact10와 repeat oracle

- owner:
  P4
- scope:
  two environments × BEFORE/AFTER/regression-A/regression-B/AFTER-repeat
- field:
  raw/parsed literal path, extractor Physical, parser, expected/actual/result,
  signed wrapper/publisher
- reject:
  opaque evidence, partial invocation, wrong environment, repeat 누락
- close:
  raw exact10 aggregate와 repeat byte equality를 independent recomputation

### B08 — Stage-B input manifests와 SandboxIntent

- owner:
  P4
- field:
  ordered Physical member, alias, set digest, publisher/predecessor,
  literal argv/bind/environment/input/output/tracer
- order:
  actual reference → input manifest → command manifest
- reject:
  implicit alias, broad mount, undeclared environment, early publication
- close:
  actual sandbox argv와 signed intent byte equality

### B09 — constructive root와 actual live-root publication

- owner:
  P5
- milestones:
  `B09a-ACTUAL-LIVE-ROOT-CONTRACT →
  B09b-CONSTRUCTIVE-ROOT-DERIVATION →
  B09c-CONSTRUCTIVE-SAME-ROOT-INTEGRATION`
- H1:
  P0+allowed delta에서 exact 627, exact26/U1, seq40 shape를 결정론적으로
  유도하고 synthetic exact6가 같은 constructive manifest를 결속
- H2:
  §9.3의 checkpoint durability→manifest→double-walk/fsync/reopen physical
  receipt→LIVE_ROOT_PUBLISHED→exact6→application one-way chain
- reject:
  근거 없는 627, generic root, invocation별 root, U1 누락, triple 일부 누락,
  fixture의 actual 승격
- close:
  H1 derivation/integration receipts와 H2 contract schema receipt만 G6에
  결속; 미래 actual 실행 receipt는 요구하지 않음

### B10 — exact6 schema와 synthetic oracle

- owner:
  P5
- milestones:
  P3 `B10a-EXACT6-SCHEMA`, P5 `B10b-SYNTHETIC-CONSTRUCTION`
- command count:
  six ordinal의 command cardinality `1/1/1/1/1/2`
- field:
  typed raw source, parser, expected/actual/result, producer/path, framing digest
- reject:
  string role, untyped assertion, row006 command 혼합, B09c 선참조
- close:
  immutable synthetic raw evidence에서 six assertion independent recomputation

### B11 — generated scratch determinism

- owner:
  P1
- input:
  P0 `generated_inventory[]`
- required:
  literal policy, exclusion/collision order, empty/seed rule, pre/post digest
- reject:
  glob, source collision, stale scratch, mutable count 재사용
- close:
  clean isolated two-run digest equality

### B12 — BEFORE/AFTER projection single DAG

- owner:
  P1
- required:
  typed manifests/receipts와 ClosureDependencyEdgeManifest에 있는 유일한
  publication order
- reject:
  conflicting order, missing/extra field, noncanonical cast, second DAG
- close:
  topological order cardinality `1`, cycle `0`, graph digest equality

### B13 — RuntimeActual use chain

- owner:
  P4
- predecessor:
  B12 projection과 M02 late-bound enum
- required:
  resolved payload/hash, exhaustive consumer map, use receipt,
  normalized-output producer/path/schema
- consumers:
  local full19, hosted full19, synthetic exact6, future actual/recovery
- reject:
  exact6/recovery 누락, owner/hash-domain 누락, undeclared consumer
- close:
  trace consumer set과 declared map exact equality

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
- input:
  P0 Git inventory, B17 mixed-tree union과 B08 input/sandbox intent
- reject:
  broad prefix, external gitdir/executable 누락, path escape
- close:
  actual trace equality, undeclared read `0`

### B16 — FutureSealed exactly-one producer

- owner:
  P3
- exact milestones:
  `M04a → B16a → M04b → B16b`
- required:
  role별 producer Physical/SHA/argv, input manifest, output path, resolution
  barrier
- reject:
  duplicate/zero producer, unresolved role, premature consume, M04 역방향
- close:
  producer=1, resolved=1, duplicate=0, cycle=0과 four ordered receipt equality

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
  P0 tree/symlink/direct-origin 전 member의 union exhaustiveness

### B18 — source-only BEFORE spawn

- owner:
  P4
- predecessor:
  B08/B12와 debt D-A/D-B receipt
- required:
  current positional BEFORE와 successor AFTER의 literal isolated spawn 분리
- reject:
  live unassigned inventory 은폐, absent flag, AFTER overlay, stale runner count
- close:
  frozen current-control projection에서 current CLI spawn receipt와 successor
  CLI spawn receipt를 서로 다른 subject로 결속

## 11. exact 4 MAJOR closure contract

### M01 — row15 executable closure

- owner:
  P4
- milestones:
  `M01a-EXECUTABLE-DECLARATION`
- required:
  M01a가 runner subprocess executable Physical 전수를 먼저 선언하고,
  B07/B14/B18 실행 뒤 row completion verifier가 final trace와 비교
- include:
  `dirname`, `mktemp`, `chmod`, `rm`, `find`, `sort`와 trace의 전부
- reject:
  broad PATH, undeclared executable, 근거 없는 제거 주장
- close:
  executable trace와 declared closure exact equality

### M02 — exhaustive late-bound role

- owner:
  P1
- closed enum cardinality:
  exact `19`
- literal role ID set:

```text
F / FilePhysical:
  CONTINUATION_CHECKER
  CHECKPOINT
  AFTER_ROUTING
  GOAL_GRAPH_CHECKER
  TEST_LAYER_RUNNER

P / DirManifestPhysical | ordered FilePhysical:
  V24_CONTROL
  V241_CONTROL
  MANAGED_GOALS
  ROUTING_LITERAL132_SET
  ROW17_DIRECT3
  ROW17_V241_CONTROL_SET
  ROW17_MANAGED_GOALS_SET

LIVE_TRANSACTION_DATA:
  LIVE_ROOT_TRIPLE
  TRANSACTION_MANIFEST
  LIVE_EXACT26
  AUX_PARENT_001
  M_AFTER627_WORKTREE_INVENTORY
  DOUBLE_READ_GIT_INVENTORY_SET

RUNTIME_ACTUAL:
  RUNTIME_ACTUAL_BINDING
```

각 row는 다음 exact schema다.

```text
binding_role_id = "STAGE_C_INVOCATION::<logical_role_id>"
logical_role_id
constructor_enum
constructor_actor_id + constructor_physical_path + physical_sha + literal_argv
allowed_phase = STAGE_C_INTENT_ONLY
source_literal_path_or_exact_manifest_selector
output_tagged_type + output_schema_sha
expected_member_cardinality
carrier_role = ACTUAL-EXACT6-INPUT
carrier_literal_path + carrier_json_pointer
wrong_phase_predicate
```

`constructor_enum`과 expected output은 다음 19-row table로 고정한다.
모든 selector는 no-follow exact UTF-8이고 prefix는 glob가 아니라 sorted
recursive literal member manifest다. `manifest:<field>` cardinality는
Stage-C subject가 승인한 transaction manifest의 해당 integer field와
byte-equal인 한 개의 exact integer다.

| role ID | constructor enum | exact source path/selector | output tagged type / schema role | member cardinality |
|---|---|---|---|---:|
| `CONTINUATION_CHECKER` | `NOFOLLOW_FILE` | `/work/walksafe/scripts/check_walksafe_project_continuation_v2_4_1.py` | `FilePhysical` / `STAGE_C_LB_CONTINUATION_CHECKER_V1` | 1 |
| `CHECKPOINT` | `NOFOLLOW_FILE` | `/work/walksafe/docs/control/walksafe-project-continuation-checkpoint.json` | `FilePhysical` / `STAGE_C_LB_CHECKPOINT_V1` | 1 |
| `AFTER_ROUTING` | `NOFOLLOW_FILE` | `/work/walksafe/docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-after-seq40-v2.4.1.json` | `FilePhysical` / `STAGE_C_LB_AFTER_ROUTING_V1` | 1 |
| `GOAL_GRAPH_CHECKER` | `NOFOLLOW_FILE` | `/work/walksafe/scripts/check_walksafe_goal_graph_v2_4_1.py` | `FilePhysical` / `STAGE_C_LB_GOAL_GRAPH_CHECKER_V1` | 1 |
| `TEST_LAYER_RUNNER` | `NOFOLLOW_FILE` | `/work/walksafe/scripts/run_walksafe_test_layers_20260711.sh` | `FilePhysical` / `STAGE_C_LB_TEST_LAYER_RUNNER_V1` | 1 |
| `V24_CONTROL` | `SEALED_PREFIX_MANIFEST` | `/work/walksafe/docs/control/goals/walksafe-completion-graph-v2-4` + selector `V24_CONTROL` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_V24_CONTROL_V1` | `manifest:v24_control_count` |
| `V241_CONTROL` | `SEALED_PREFIX_MANIFEST` | `/work/walksafe/docs/control/goals/walksafe-completion-graph-v2-4-1` + selector `V241_CONTROL` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_V241_CONTROL_V1` | `manifest:v241_control_count` |
| `MANAGED_GOALS` | `SEALED_PREFIX_MANIFEST` | `/work/walksafe/docs/control/goals` + selector `MANAGED_GOALS` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_MANAGED_GOALS_V1` | `manifest:managed_goals_count` |
| `ROUTING_LITERAL132_SET` | `SEALED_LITERAL_SET` | `/work/walksafe` + selector `ROUTING_LITERAL132_SET` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_ROUTING_LITERAL132_SET_V1` | 132 |
| `ROW17_DIRECT3` | `SEALED_LITERAL_SET` | exact ordered three successor test paths from R007 Stage-C row 006 | `OrderedFilePhysicalManifest` / `STAGE_C_LB_ROW17_DIRECT3_V1` | 3 |
| `ROW17_V241_CONTROL_SET` | `SEALED_PREFIX_MANIFEST` | `/work/walksafe/docs/control/goals/walksafe-completion-graph-v2-4-1` + selector `ROW17_V241_CONTROL_SET` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_ROW17_V241_CONTROL_SET_V1` | `manifest:row17_v241_control_count` |
| `ROW17_MANAGED_GOALS_SET` | `SEALED_PREFIX_MANIFEST` | `/work/walksafe/docs/control/goals` + selector `ROW17_MANAGED_GOALS_SET` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_ROW17_MANAGED_GOALS_SET_V1` | `manifest:row17_managed_goals_count` |
| `LIVE_ROOT_TRIPLE` | `H2_ROOT_TRIPLE_BINDING` | H2 binding V2 + live-root manifest wrapper + physical-publication receipt | `LiveRootPhysicalTriple` / `STAGE_C_LB_LIVE_ROOT_TRIPLE_V1` | 1 |
| `TRANSACTION_MANIFEST` | `TRANSACTION_INPUT_FILE` | `/input/transaction-manifest.json` | `FilePhysical` / `STAGE_C_LB_TRANSACTION_MANIFEST_V1` | 1 |
| `LIVE_EXACT26` | `TRANSACTION_MANIFEST_FIELD` | transaction manifest field `ordered_applied_targets` | `OrderedTargetBinding` / `STAGE_C_LB_LIVE_EXACT26_V1` | 26 |
| `AUX_PARENT_001` | `TRANSACTION_MANIFEST_FIELD` | transaction manifest field `aux_parent_001` | `FilePhysical` / `STAGE_C_LB_AUX_PARENT_001_V1` | 1 |
| `M_AFTER627_WORKTREE_INVENTORY` | `TRANSACTION_MANIFEST_FIELD` | transaction manifest field `m_after627_worktree_inventory` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_M_AFTER627_V1` | 627 |
| `DOUBLE_READ_GIT_INVENTORY_SET` | `TRANSACTION_MANIFEST_FIELD` | transaction manifest field `double_read_git_inventory_set` | `OrderedGitPhysicalManifest` / `STAGE_C_LB_DOUBLE_READ_GIT_V1` | `manifest:double_read_git_inventory_count` |
| `RUNTIME_ACTUAL_BINDING` | `STAGE_B_RUNTIME_BINDING` | `/work/walksafe/projections/runtime-actual/full19-gate-event-id-binding.json` | `RuntimeActualBindingPhysical` / `WS_PRE_P_R007_RUNTIME_ACTUAL_V1` | 1 |

successor의 M02 schema bundle은 이 표의 canonical row order/JCS에서
`expected_role_registry_table_digest`를 한 번 계산해 freeze한다. registry
payload는 그 bundle SHA/digest와 각 row의 exact constructor Physical/SHA를
직접 결속하며 verifier는 supplied row를 신뢰하지 않고 이 표에서 19개를
재생성한다.

Stage A actual인 `PYTHON_RUNTIME`은 이 set에서 제외한다. 19 actual binding은
기존 `ActualExact6Input` 한 파일에 registry payload/wrapper SHA, ordered
19-row binding과 invocation별 selected subset으로 들어가므로 H2 evidence
file count 13은 바뀌지 않는다. registry digest는

```text
SHA256(
  ASCII("WS-WALKSAFE-R005-M02-LATE-BOUND-ROLE-REGISTRY-V1") ||
  0x00 || RFC8785_JCS(ordered exact 19 rows))
```

다. exact set 밖 role, constructor alias, Stage A/Stage B에서의 구성과
wrong-phase 사용은 금지한다.
- close:
  registry expected/actual `19↔19`, missing/extra/duplicate `0`,
  wrong-phase/wrong-constructor negative `19/19 PASS`

### M03 — disjoint recovery

- owner:
  P2
- branch:
  `EXACT6_SUFFIX`는 durable prefix+never-dispatched suffix만,
  `APPLICATION_FINALIZATION`은 actual `POSTCHECK_PASSED` 뒤만
- reject:
  cross-use, broad post-check state, common capability/nonce 재사용
- close:
  state/path/capability branch intersection `0`

### M04 — graph extraction, serialization과 independent digest

- owner:
  P3
- M04a producer:
  graph membership extractor Physical의 literal path/SHA/argv/environment와
  typed node/edge output
- M04a checker:
  producer와 다른 Physical/SHA가 source inputs에서 membership을 독립 추출
- M04b producer:
  canonical serializer/digest Physical의 literal path/SHA/argv/domain bytes
- M04b checker:
  producer와 다른 Physical/SHA가 canonical bytes와 digest를 독립 재계산
- order:
  `M04a extraction/membership → B16a assignment →
  M04b serialization/digest → B16b verification`
- reject:
  producer=checker, missing/duplicate node, implementation order, second edge
  manifest, B16↔M04 cycle
- close:
  T08 M04a receipt, T09 M04b receipt와 independent equality를 한 row에 결속

## 12. exact 22-row matrix와 four regression debt

### 12.1 finding matrix

| ID | severity | accountable owner | exact predecessor/milestone summary | planning state |
|---|---|---|---|---|
| B01 | BLOCKING | P2 | B17 → unsigned payload → detached wrapper/lifecycle | `REQUIRED_FUTURE_ROW` |
| B02 | BLOCKING | P2 | B01 → common guard FSM | `REQUIRED_FUTURE_ROW` |
| B03 | BLOCKING | P2 | B17/B01 → literal capability rows | `REQUIRED_FUTURE_ROW` |
| B04 | BLOCKING | P5 | M03/B09c/B10b/B06b → synthetic receipt fixture | `REQUIRED_FUTURE_ROW` |
| B05 | BLOCKING | P2 | B01/B02/B03/M03 → D/E/F/G activation | `REQUIRED_FUTURE_ROW` |
| B06 | BLOCKING | P5 | B10a → B06a → B09c/B10b → B06b | `REQUIRED_FUTURE_ROW` |
| B07 | BLOCKING | P4 | B08/B13/M01a → exact10/repeat | `REQUIRED_FUTURE_ROW` |
| B08 | BLOCKING | P4 | P0/B17 → input/SandboxIntent | `REQUIRED_FUTURE_ROW` |
| B09 | BLOCKING | P5 | B09a → B09b → B10b → B09c | `REQUIRED_FUTURE_ROW` |
| B10 | BLOCKING | P5 | B10a/B09b/B13 → B10b | `REQUIRED_FUTURE_ROW` |
| B11 | BLOCKING | P1 | P0/B17 → scratch two-run | `REQUIRED_FUTURE_ROW` |
| B12 | BLOCKING | P1 | B11/B17 → projection DAG | `REQUIRED_FUTURE_ROW` |
| B13 | BLOCKING | P4 | B12/M02 → RuntimeActual use chain | `REQUIRED_FUTURE_ROW` |
| B14 | BLOCKING | P4 | B13/B15/M01a → full19 framing | `REQUIRED_FUTURE_ROW` |
| B15 | BLOCKING | P4 | P0/B17/B08 → Git closure | `REQUIRED_FUTURE_ROW` |
| B16 | BLOCKING | P3 | M04a → B16a → M04b → B16b | `REQUIRED_FUTURE_ROW` |
| B17 | BLOCKING | P1 | P0 → physical union | `REQUIRED_FUTURE_ROW` |
| B18 | BLOCKING | P4 | B08/B12/M01a/D-A/D-B → BEFORE spawn | `REQUIRED_FUTURE_ROW` |
| M01 | MAJOR | P4 | M01a declaration → B07/B14/B18 → row trace equality | `REQUIRED_FUTURE_ROW` |
| M02 | MAJOR | P1 | P0/B17 → late-bound enum | `REQUIRED_FUTURE_ROW` |
| M03 | MAJOR | P2 | B02/B03 → recovery split | `REQUIRED_FUTURE_ROW` |
| M04 | MAJOR | P3 | B01/B06a → M04a → B16a → M04b → B16b | `REQUIRED_FUTURE_ROW` |

```text
row count = 22
unique finding ID = 22
BLOCKING = 18
MAJOR = 4
accountable owner phase/actor cardinality per row = 1/1
multi-owned = 0
unowned = 0
```

### 12.2 debt manifest

| ID | accountable owner | exact purpose | B18 predecessor | G4/G6 predecessor | current |
|---|---|---|---|---|---|
| D-A | P4 | backend/local-test/hosted lock epoch와 two-run generation | yes | yes | `OPEN` |
| D-B | P4 | runner current/historical/non-running inventory와 CLI spawn | yes | yes | `OPEN` |
| D-C | P4 | Gateway historical exact4/current exact5 typed validation | no | yes | `OPEN` |
| D-D | P4 | 20260722/current artifact baseline dual validation | no | yes | `OPEN` |

각 debt는 unique ID, accountable owner phase/actor cardinality `1/1`, immutable
input manifest, exact output path/schema/publisher, producer/checker Physical,
positive/negative fixture와 verified completion receipt를 가진다.

G6 debt predicate:

```text
debt IDs = exact {D-A,D-B,D-C,D-D}
unique debt ID = 4
debt accountable owner cardinality = 1 each
debt OPEN = 0
debt CLOSED_CANDIDATE = 4
verified debt completion receipts = 4
missing evidence = 0
duplicate evidence = 0
wrong task/debt mapping = 0
```

### 12.3 post-close completion spec/result DAG

successor의 `CompletionSpecRegistry`는 각 finding/debt의 expected predecessor
ID/type, supporting task ID, milestone ID, edge-manifest digest와 output
schema/path/publisher를 freeze한다. future actual receipt SHA는 넣지 않는다.

각 completion payload:

```text
completion_spec_registry_sha
completion_id + completion_type
tested_successor_sha
final_cas_receipt_sha
authority_execution_lineage_digest
post_close_finalization_lineage_prefix_digest
expected_predecessor_id[]       # registry bytes와 exact equality
expected_external_predecessor_role[]
expected_supporting_task_id[]   # registry bytes와 exact equality
expected_milestone_id[]         # registry bytes와 exact equality
actual_predecessor_receipt_ref[]:
  id, type, payload_sha, receipt_sha, observed_status
actual_external_predecessor_receipt_ref[]:
  role_id, payload_sha_or_na, receipt_sha, observed_status
actual_supporting_task_receipt_ref[]:
  task_id, result_sha, completion_receipt_sha, observed_status
actual_milestone_receipt_ref[]:
  milestone_id, payload_sha, receipt_sha, observed_status
expected_edge_set_digest
actual_edge_set_digest
owner_phase + owner_actor_id
verifier_physical + verifier_sha + literal_argv
status = CLOSED_CANDIDATE | FAIL
```

expected와 actual ID/cardinality/order가 one-to-one이고 모든 actual receipt가
같은 successor/attempt/authority, `PASS`일 때만 detached completion wrapper를
발행한다. 아래 predecessor cell에서 semicolon 앞은 completion/debt ID이고
뒤는 external tagged predecessor role이다. external role은 CR40에 포함하지
않고 별도 `PX001..PX012` edge로 materialize한다.

| completion | expected predecessor completion/debt IDs | milestone IDs | supporting task receipts |
|---|---|---|---|
| B17 | none; P0 freeze | B17a | T01 |
| B11 | B17; P0 freeze | B11a | T01 |
| B12 | B17,B11 | B12a | T02 |
| M02 | B17; P0 freeze | M02a | T01 |
| B01 | B17 | B01a | T07 |
| B02 | B01 | B02a | T07 |
| B03 | B17,B01 | B03a | T07 |
| M03 | B02,B03 | M03a | T07 |
| B05 | B01,B02,B03,M03 | B05a | T07 |
| B08 | B17; P0 freeze | B08a | T04 |
| B15 | B17,B08; P0 freeze | B15a | T05 |
| B13 | B12,M02 | B13a | T05 |
| B07 | B08,B13 | B07a | T04,T05 |
| B14 | B13,B15 | B14a | T05,T14 |
| D-A | none; P0 freeze | none | T10 |
| D-B | none; P0 freeze | none | T11 |
| D-C | none; P0 freeze | none | T12 |
| D-D | none; P0 freeze | none | T13 |
| B18 | B08,B12,D-A,D-B | B18a | T02,T03,T04,T05,T10,T11 |
| M01 | B07,B14,B18 | M01a,M01b | T03,T04,T05,T14 |
| B09 | none; P0 freeze + allowed-delta + synthetic-derivation | B09a,B09b,B09c | T06,T15 |
| B10 | B13 | B10a,B10b | T05,T06,T15 |
| B06 | B10 | B06a,B06b | T06,T15 |
| B04 | M03,B09,B10,B06 | B04a | T07,T15 |
| B16 | B01,B06 | M04a,B16a,M04b,B16b | T07,T08,T09 |
| M04 | B01,B06 | M04a,B16a,M04b,B16b | T07,T08,T09 |

`PX001..PX012`의 canonical order는 표의 row order와 각 semicolon suffix의
role order다: B17/B11/M02/B08/B15와 D-A~D-D는 각각 P0 freeze 한 edge,
B09는 P0 freeze/allowed-delta/synthetic-derivation 세 edge다. actual
external receipt ref 누락, payload-only ref, wrong P0 lineage 또는 B09
derivation order mismatch면 completion wrapper를 발행하지 않는다.

publication은 위 dependency의 topological order다. 특히 D-A/D-B completion
wrapper가 B18보다 먼저, B07/B14/B18 wrapper가 M01보다 먼저, B13이
B10보다 먼저 존재한다. parallel `CAS→all rows`로 이 edge를 생략할 수
없다. missing/wrong predecessor task receipt, milestone, row/debt receipt
또는 spec/actual edge digest mismatch는 current completion을 발행하지 않고
downstream을 `NOT_RUN`으로 둔다.

## 13. V0 authority provenance와 exact V1 15-task evidence

### 13.1 user 질문 전 successor와 bridge review

G5-SPEC PASS 뒤 exact successor payload/receipt에 대해 formal/skeptical
`SUCCESSOR_PLAN_REVIEW`를 수행하고 common identity pair가 `0/0/0`이어야
bridge를 freeze할 수 있다. 이 pre-authority pair는 P7의 post-G6 review를
대체하지 않는다.

`ConstructiveFixtureAuthorityBridgePayload`:

```text
technical_handoff_sha
p0_freeze_receipt_sha
tested_successor_sha + successor_publication_receipt_sha
g1_to_g5_spec_result_receipt_sha[]
pre_authority_successor_review_receipt_sha[2] + pair_receipt_sha
fixture_bundle_sha
verifier_bundle_sha
dependency_edge_manifest_sha
isolated_root_physical
exact_read_set[] + exact_write_set[] + exact_exec_set[]
task_id_set = exact T01..T15
attempt_scope_template
network = DENY
secrets = DENY
external_write = DENY
live_write = DENY
crash_recovery_truth_table
```

bridge formal/skeptical review pair도 같은 bridge payload/receipt에 대해
공통 identity/disjointness와 `0/0/0`을 통과해야 한다. 이 두 pair가
완료되기 전에는 user에게 실행 허용 여부를 질문하지 않는다.

### 13.2 mandatory governing decision provenance

`UserAuthorityRequestPayload`:

```text
request_id
requester_actor_id + requester_session_id
attempt_namespace_freeze_payload_sha + wrapper_sha
attempt_namespace_id + literal_attempt_root + attempt_ordinal
technical_handoff_sha
tested_successor_sha + receipt_sha
p0_freeze_receipt_sha
pre_authority_successor_review_pair_receipt_sha
bridge_sha + bridge_receipt_sha
formal_bridge_review_sha
skeptical_bridge_review_sha
bridge_review_pair_receipt_sha
requested_disposition = DENY | ALLOW_ONE_ISOLATED_ATTEMPT
requested_task_set = exact T01..T15
requested_execution_read/write/exec_set_digest
requested_post_close_finalization_scope_digest
requested_execution_close_recovery_scope_digest
requested_terminal_wrapper_recovery_scope_digest
requested_execution_deadline_watchdog_spec_digest
requested_execution_hard_deadline
requested_finalization_hard_deadline
requested_close_recovery_hard_deadline
requested_terminal_wrapper_recovery_hard_deadline
request_subject_digest
requested_at + trusted_clock_source
signature_domain
```

`ImmutableUserAuthorityResponseReceipt`:

```text
response_origin_system
immutable_origin_message_or_event_id
immutable_origin_artifact_sha
issuer_actor_id
canonical_issuer_actor_id
issuer_session_id
issued_at + trusted_clock_source
request_payload_sha + request_receipt_sha
request_subject_digest
attempt_namespace_freeze_payload_sha + wrapper_sha
attempt_namespace_id + literal_attempt_root + attempt_ordinal
disposition = DENY | ALLOW_ONE_ISOLATED_ATTEMPT
approved_execution_scope_digest
approved_post_close_finalization_scope_digest
approved_execution_close_recovery_scope_digest
approved_terminal_wrapper_recovery_scope_digest
approved_execution_deadline_watchdog_spec_digest
approved_execution_hard_deadline
approved_finalization_hard_deadline
approved_close_recovery_hard_deadline
approved_terminal_wrapper_recovery_hard_deadline
signature_domain
issuer_signature_or_origin_attestation
```

`AuthorityBoundaryDecisionEnvelope`은 request와 response를 그대로 결속하고
provenance verifier가 정규화한다.

```text
decision_schema_sha
request_payload_sha + request_receipt_sha
immutable_user_response_receipt_sha
issuer_actor_id + canonical_issuer_actor_id + issuer_session_id
issued_at + trusted_clock_source
request_subject_digest
tested_successor_sha
attempt_namespace_freeze_payload_sha + wrapper_sha
attempt_namespace_id + literal_attempt_root + attempt_ordinal
bridge_sha + two bridge review SHA + pair receipt SHA
disposition
attempt_id
fresh_execution_nonce
fresh_finalization_nonce
fresh_close_recovery_nonce
fresh_terminal_wrapper_recovery_nonce
execution_scope_digest
post_close_finalization_scope_digest
execution_close_recovery_scope_digest
terminal_wrapper_recovery_scope_digest
execution_deadline_watchdog_spec_digest
execution_hard_deadline
finalization_hard_deadline
close_recovery_hard_deadline
terminal_wrapper_recovery_hard_deadline
one_use = true
provenance_verifier_physical + verifier_sha + literal_argv
provenance_verifier_result = PASS
signature_domain
```

requester/roadmap/bridge author가 response를 자가발행하는 경로,
`issuer="explicit user"` 문자열만 있는 문서, mutable chat copy, actor/session
누락, wrong request digest, issuer/requester alias, expired response와
unverifiable origin은 거부한다. decision payload와 detached receipt가
모두 존재해야 한다.

네 scope와 네 deadline, execution deadline watchdog spec digest,
attempt namespace/root/ordinal은
request/response/decision 사이 exact equality다.
한 필드라도 빠지거나 다르면 execution/finalization/recovery grant와 consume
cardinality는 모두 `0`이다.

### 13.3 consume, execution, close와 full lineage

decision이 `DENY`이거나 없으면 V1부터 G7까지 `NOT_RUN`이다. ALLOW일 때도
15개 task spec과 exact input/fixture/verifier/write-set bundle이 decision
scope와 같아야 한 번 consume할 수 있다.

각 consume 직전에는 §7의 immutable ordinal version과 별도 signed
`RevocationLatestObservationReceipt`를 current-time CAS로 읽는다.

```text
AuthorityRevocationHeadPayload:
head_role =
  EXECUTION_CONSUME | FINALIZATION_CONSUME | CLOSE_RECOVERY_CONSUME |
  TERMINAL_WRAPPER_RECOVERY_CONSUME
literal_head_payload_path + literal_head_wrapper_path
head_schema_sha + wrapper_schema_sha
publisher_actor_id + publisher_physical_sha
tested_successor_sha + attempt_namespace_id + attempt_id
revocation_ordinal = exact 0 | 1 | 2
state =
  UNREVOKED_INITIAL | UNREVOKED_RENEWAL | REVOKED
original_hard_deadline + effective_hard_deadline
source_revocation_cas_token + source_revocation_cas_digest
predecessor_head_payload_sha + predecessor_revocation_ordinal
observed_at + trusted_clock_source
signature_domain

RevocationLatestObservationReceipt:
head_role + observation_phase = PRE_CONSUME | POST_CONSUME_TERMINAL
attempt_namespace_id + attempt_id
literal_version_member_ref[]
latest_head_payload_sha + wrapper_sha + latest_ordinal + state
source_revocation_cas_token + source_revocation_cas_digest
member_set_digest + latest_selection_result = PASS
observed_at + trusted_clock_source
signature_domain
```

version path ordinal set은 exact `{000000,000001,000002}`이고 publish된
member는 gap 없는 prefix다. ordinal 0만 predecessor=`NA`와
`UNREVOKED_INITIAL`이다. ordinal 1은 `UNREVOKED_RENEWAL` 또는 `REVOKED`,
ordinal 2는 ordinal 1이 renewal일 때 `REVOKED`만 허용한다. renewal은

```text
effective_hard_deadline(n)
= min(original_hard_deadline,
      predecessor.effective_hard_deadline,
      source_cas_not_after)
```

라서 deadline을 늘리지 못한다. overwrite, ordinal skip/fork,
`REVOKED→UNREVOKED`, second renewal과 version slot 3은 금지한다.

consume은 PRE observation receipt, selected head payload/wrapper SHA,
ordinal/state/source CAS와
`head.observed_at <= observation.observed_at <= consumed_at
 <= effective_hard_deadline`을 직접 결속하고 consume 직전 source CAS를 다시
읽어 same latest token/digest임을 비교한다. stale/missing/forked head 또는
latest `REVOKED`이면 `NOT_RUN`이다. PRE observation의
`effective_hard_deadline`은 그 consume role에 decision이 승인한 hard
deadline과 exact equality여야 하며 더 짧은 renewal/head이면 fresh authority
없이 consume하지 않고 `NOT_RUN`이다. 따라서 execution 후 `EXPIRED` cause는
항상 decision/consume의 exact `execution_hard_deadline` 하나다.
consume 뒤 larger `REVOKED` ordinal을
POST observation receipt로 관찰하면 새 task/success suffix dispatch를
중단한다. execution은 recovery FAILURE close-only로, finalization은
`FinalizationPrefixFailureCheckpoint` 뒤 failure-close로만 닫고 ready/Gate
credit은 `0`이다.

exact FSM:

```text
UNREVOKED_INITIAL
  → {UNREVOKED_RENEWAL, REVOKED, CONSUMED}
UNREVOKED_RENEWAL
  → {REVOKED, CONSUMED}
CONSUMED
  → {SELECTED_TERMINAL, POST_CONSUME_REVOKED_OBSERVED}
POST_CONSUME_REVOKED_OBSERVED
  → FAILURE_CLOSE_ONLY
SELECTED_TERMINAL | FAILURE_CLOSE_ONLY
  → SPENT_AND_CLOSED
```

표 밖 edge, revoked consume, consume/close 뒤 renewal, deadline reset과 stale
attempt namespace head adoption은 mandatory negative fixture다. 이 tail은
새 실행, 범위 확대 또는 retry 권한이 아니다.

`AuthorityConsumeReceipt`:

```text
decision_payload_sha + decision_receipt_sha
request_sha + immutable_user_response_receipt_sha
bridge_sha + bridge_review_receipt_sha[2] + pair_receipt_sha
attempt_id + fresh_execution_nonce
task_spec_payload_sha[15] + task_spec_bundle_digest
input_fixture_verifier_bundle_digest
read_write_exec_set_digest
execution_scope_digest
execution_revocation_head_payload_sha + wrapper_sha
execution_revocation_ordinal + source_revocation_cas_digest
execution_revocation_pre_observation_receipt_sha
consumed_at + trusted_clock_source
execution_hard_deadline
pre_consume_state = UNREVOKED_AND_UNSPENT
post_consume_state = CONSUMED
```

execution scope도 subset/superset 없이
`requested == approved == decision == canonical literal sets == consume`이다.
deadline/attempt/nonce/head CAS가 다르면 dispatch cardinality는 `0`이다.

decision 뒤 execution consume 전에
`CloseRecoveryAuthorityGrantPayload`/wrapper도 동결한다.

```text
grant_type = EXECUTION_TERMINAL_CLOSE_RECOVERY_ONLY
request/response/decision SHA + tested successor SHA
attempt_id + fresh_close_recovery_nonce
execution_close_recovery_scope_digest
execution_deadline_watchdog:
  spec_digest
  producer/verifier Physical + SHA + literal_argv
  config_digest + frozen_at
  execution_hard_deadline + trusted_clock_correlation
literal_read_set[] = exact consume/spec/observed raw-result/head/clock paths
literal_write_set[] = exact failure/crash/expired close receipt paths
literal_exec_set[] = exact close verifier only
deny_set[] = exact {
  task-dispatch,result-rewrite,success-close,finalization-success,retry,
  network,secrets,external,live,product,checkpoint,canonical
}
one_use = true
close_recovery_hard_deadline
```

scope equality는 다음과 같고 어느 항도 바뀔 수 없다.

```text
requested_execution_close_recovery_scope_digest
== approved_execution_close_recovery_scope_digest
== decision.execution_close_recovery_scope_digest
== close_recovery_grant.execution_close_recovery_scope_digest
== SHA256(canonical(
     literal_read_set,literal_write_set,literal_exec_set,deny_set,
     close_recovery_hard_deadline,attempt_id,fresh_close_recovery_nonce,
     execution_deadline_watchdog_spec_digest))
== close_recovery_consume.execution_close_recovery_scope_digest
```

watchdog spec digest는 producer/verifier Physical SHA, literal argv,
config digest, trusted-clock source/correlation과
`execution_hard_deadline`의 canonical JCS digest이고 `frozen_at`은 포함하지
않는다. grant는 approved spec digest를 byte-equal 재계산한 뒤 actual
`frozen_at`을 추가하며 terminal은 existing grant payload/wrapper SHA와
동일 watchdog fields를 직접 결속한다.

`CloseRecoveryAuthorityConsumeReceipt`는 grant payload/wrapper, original
execution consume, exact observed result set `0..15`, pre-close CAS tagged
state, recovery revocation version/observation/source CAS,
attempt/namespace/nonce/scope/deadline/consume time을 직접 결속한다. recovery
head 자체는 current `UNREVOKED`여야 한다.
execution 또는 finalization head가 이미 `REVOKED`여도 recovery consume은
허용되지만 FAILURE/CRASH/EXPIRED terminal 중 하나를 close하는 write만
가능하다.

`ExecutionTerminalState`는 Gate status와 다른 strict tagged enum이다.

| terminal kind / canonical status | finalization eligible | success suffix | failure tail |
|---|---:|---:|---:|
| `SUCCESS / CLOSED_SUCCESS` | true | true | false |
| `FAILURE / CLOSED_FAILURE` | true | false | true |
| `CRASH / CLOSED_CRASH` | true | false | true |
| `EXPIRED / CLOSED_EXPIRED` | true | false | true |

`GateStatus={NOT_RUN,FAIL,PASS}`와 위 enum 사이 implicit cast는 없다.
predecessor schema는 artifact kind별 exact expected state와 별도
`verification_result=PASS|FAIL`을 가진다. 따라서 `FROZEN`,
`COMPLETE_CANDIDATE`, `CLOSED_CANDIDATE`와 execution terminal kind는
자기 원본 enum을 보존하고, verifier 성공만 generic PASS field로 표현한다.

execution terminal actual cardinality는 다음 complete cut truth table에서
정확히 `1`이다.

| observed signed result count | CAS state before terminal | cause | selected action |
|---:|---|---|---|
| 15 | `CAS_PRESENT_UNWRAPPED` | all PASS, normal | `SUCCESS`, CAS sole wrapper |
| 15 | `CAS_PRESENT_UNWRAPPED` | any FAIL/NOT_RUN, normal | `FAILURE`, CAS sole wrapper |
| 0..15 | `CAS_ABSENT_BEFORE_PUBLICATION` | revocation | recovery `FAILURE` |
| 0..15 | `CAS_ABSENT_BEFORE_PUBLICATION` | process crash | recovery `CRASH` |
| 0..15 | `CAS_ABSENT_BEFORE_PUBLICATION` | execution deadline reached | recovery `EXPIRED` |
| 15 | `CAS_PRESENT_UNWRAPPED` | revocation/crash/expiry | selected recovery terminal becomes CAS sole wrapper |
| 15 | `CAS_WRAPPED_BY_SELECTED_TERMINAL` | restart/adoption | verify existing exact terminal; new terminal/recovery consume cardinality `0` |

result count `<15`에서 CAS present, result count `>15`, CAS wrapper와 terminal
mismatch, CAS state 둘 이상, close-recovery deadline을 EXPIRED cause로 사용,
또는 observed-set이 T01부터의 strict execution order와 다르면 거부한다.
15-result/no-CAS와 15-result/unwrapped-CAS cut을 mandatory fixtures로 둔다.

네 receipt는 모두 signed wrapper-only다. 공통 body는 consume lineage,
selected terminal role, observed `task_id/result SHA/status` ordered refs,
exact missing task ID set, missing의 projected status=`NOT_RUN`, first terminal
cause/time, `pre_close_cas_state_before/after`,
CAS payload SHA-or-`NA`, execution start/end/terminal trusted time,
recovery grant/consume SHA-or-`NA`, relevant revocation version/observation과
execution deadline watchdog identity/config/frozen_at,
`post_close_state=EXECUTION_SPENT_AND_CLOSED`를 직접 결속한다. CRASH와
EXPIRED가 모두 관찰되면 trusted time상 먼저 성립한 원인만 선택한다.
missing/extra/duplicate/reordered task, 두 terminal file, 또는 terminal
cardinality `0/2+`는 closed execution이 아니다.

`execution_authority_lineage_digest`는 request, immutable response, decision,
bridge와 두 review/pair, attempt, nonce, execution revocation head, consume,
selected execution terminal의 ordered exact SHA에, 사용된 경우
close-recovery grant/consume/recovery-head SHA를 더해 계산한다. downstream은
이 closed execution digest를 evidence로 결속하지만 실행 권한으로
재사용하지 않는다.

publication DAG:

```text
15 task specs
→ consume receipt
→ {exact 15 task result → pre-close CAS → SUCCESS XOR FAILURE terminal}
  XOR
  {observed strict result set 0..15 + optional unwrapped CAS
   → close-recovery consume → FAILURE XOR CRASH XOR EXPIRED terminal}
  XOR
  {CAS_WRAPPED_BY_SELECTED_TERMINAL → existing terminal adoption, new write 0}
```

task result와 pre-close CAS payload는 future terminal SHA를 선참조하지
않는다. selected execution terminal 뒤 durable write는 다음 §13.4의 별도
finalization grant 없이는 금지한다.

### 13.4 separate `POST_CLOSE_FINALIZATION_ONLY` authority

`PostCloseFinalizationAuthorityGrantPayload`는 governing decision 뒤,
execution consume 전에 freeze한다. decision이
`ALLOW_ONE_ISOLATED_ATTEMPT`가 아니면 grant cardinality는 `0`이다.

```text
grant_type = POST_CLOSE_FINALIZATION_ONLY
tested_successor_sha + receipt_sha
request/response/decision SHA
bridge/review pair SHA
attempt_namespace_freeze_sha + attempt_namespace_id + literal_attempt_root
attempt_id
execution_nonce
fresh_finalization_nonce
post_close_finalization_scope_digest
eligible_execution_terminal_role/path/status = exact tagged union {
  SUCCESS, FAILURE, CRASH, EXPIRED
}
literal_read_set[]
literal_write_set[] =
  finalization consume/close control receipts,
  dependency expansion result payload/wrapper,
  exact 15 task completion,
  success branch: CAS final, 29 milestones, 22+4 completion,
    G1E..G7, P7 reviews/pairs, ready payload/wrapper,
  failure branch: failure CAS or prefix-failure checkpoint,
    bounded evidence/disposition,
    failure terminal payload/wrapper
literal_exec_set[] = exact read-only finalization verifiers
deny_set[] = exact {
  network,secrets,external,live,product,checkpoint,canonical,
  v1-task-execution,retry
}
branch_cardinality_contract
one_use = true
finalization_hard_deadline
```

이 grant는 execution permission의 sibling artifact이며 selected execution terminal 뒤
eligible해진다. V1 task, H2, actual Stage-C, checkpoint, canonical, product,
network 또는 retry를 실행할 권한이 전혀 없다.

같은 decision 뒤 original finalization consume 전에
`FinalizationTerminalWrapperRecoveryGrantPayload`/wrapper도 동결한다.

```text
grant_type = FINALIZATION_TERMINAL_WRAPPER_RECOVERY_ONLY
attempt_namespace/attempt/request/response/decision SHA
finalization_grant_payload_sha + wrapper_sha
fresh_terminal_wrapper_recovery_nonce
terminal_wrapper_recovery_scope_digest
terminal_wrapper_recovery_revocation_initial_payload_sha + wrapper_sha
literal_read_set[] = exact selected terminal payload,
  finalization close and their file/parent Physical
literal_write_set[] = exact {
  ready/evaluation.signature.json,
  failure/terminal.signature.json
}
literal_exec_set[] = exact byte/Physical adoption verifier
deny_set[] = exact {
  new-or-rewritten-payload,new-or-rewritten-close,task,cas,milestone,
  finding,debt,gate,review,retry,network,secrets,external,live,
  product,checkpoint,canonical
}
one_use = true
terminal_wrapper_recovery_hard_deadline
```

scope는

```text
requested_terminal_wrapper_recovery_scope_digest
== approved_terminal_wrapper_recovery_scope_digest
== decision.terminal_wrapper_recovery_scope_digest
== wrapper_recovery_grant.terminal_wrapper_recovery_scope_digest
== SHA256(canonical(
     literal_read_set,literal_write_set,literal_exec_set,deny_set,
     terminal_wrapper_recovery_hard_deadline,attempt_id,
     fresh_terminal_wrapper_recovery_nonce))
== wrapper_recovery_consume.terminal_wrapper_recovery_scope_digest
```

이고
`finalization_hard_deadline < terminal_wrapper_recovery_hard_deadline`다.
grant가 finalization consume 뒤 freeze되거나 scope/deadline이 다르면 recovery
cardinality는 `0`이다.

`PostCloseFinalizationConsumeReceipt`:

```text
grant_payload_sha + grant_receipt_sha
selected_execution_terminal_role/path/receipt_sha
execution_authority_lineage_digest
attempt_id + fresh_finalization_nonce
post_close_finalization_scope_digest
finalization_revocation_head_payload_sha + wrapper_sha
finalization_revocation_ordinal + source_revocation_cas_digest
finalization_revocation_pre_observation_receipt_sha
consumed_at + trusted_clock_source
pre_state = UNREVOKED_AND_UNSPENT
post_state = FINALIZATION_CONSUMED
```

scope equality는 subset/superset 허용 없이 다음 exact 식이다.

```text
requested_post_close_finalization_scope_digest
== approved_post_close_finalization_scope_digest
== decision.post_close_finalization_scope_digest
== grant.post_close_finalization_scope_digest
== SHA256(canonical(
     literal_read_set,
     literal_write_set,
     literal_exec_set,
     deny_set,
     finalization_hard_deadline,
     attempt_id,
     fresh_finalization_nonce))
== consume.post_close_finalization_scope_digest
```

request/response/decision/grant/consume 어느 하나라도 값, canonical field
order, deadline, attempt 또는 nonce가 다르면 consume은 `NOT_RUN`이다. grant의
literal sets나 branch cardinality를 consume 뒤 늘리거나 줄일 수 없다.
§13.3의 current finalization revocation head도 `UNREVOKED`이고 fresh CAS와
일치해야 한다. consume 뒤 revocation이면 success suffix를 더 쓰지 않고
관찰된 exact prefix에서 아래 failure-close tail로만 닫는다.

selected execution terminal 뒤 `PostCloseFinalizationConsumeReceipt`는
branch와 무관하게 exact 한 번 먼저 발행한다. 재소비 edge는 `0`이다.
그 consume 뒤 selector가 terminal과 actual output prefix를 검사한다.
success branch DAG:

```text
V1ExecutionCloseReceipt(SUCCESS)
→ PostCloseFinalizationConsumeReceipt
→ ClosureDependencyExpansionResult payload/wrapper
→ exact 15 task final completion receipts(all PASS)
→ V1EvidenceCASReceipt
→ 29 milestone payload/detached receipts
→ 22 finding + 4 debt completion payload/detached receipts
→ G1E → G2E → G3E → G4E → G5E
→ G6 disposition/result
→ four P7 reviews + two pair receipts
→ G7 result
→ ReadyEvaluationPayload
→ PostCloseFinalizationCloseReceipt
→ ReadyEvaluation detached wrapper as the atomic terminal tail
```

`FAILURE`, `CRASH`, `EXPIRED`, success CAS 전 task completion non-PASS는
다음 early failure branch로 간다.

```text
PostCloseFinalizationConsumeReceipt(existing exact one)
→ ClosureDependencyExpansionResult payload/wrapper
→ exact 15 task completion receipts
   - observed result가 있으면 status를 그대로 보존
   - never-dispatched/missing result는 task_result_sha=NA, status=NOT_RUN
→ V1FailureCASReceipt
→ bounded failure tail
```

success CAS가 이미 존재한 뒤 milestone/row/Gate/review/G7/ready 중
FAIL/NOT_RUN, deadline 또는 post-consume revocation이 발생하면 success CAS를
부정하거나 failure CAS를 새로 만들지 않는다.

```text
PostCloseFinalizationConsumeReceipt(existing exact one)
→ ClosureDependencyExpansionResult payload/wrapper(existing exact one)
→ V1EvidenceCASReceipt(existing exact one)
→ exact durable success-prefix artifact set
→ exact first failed/non-runnable role or post-consume revocation observation
→ FinalizationPrefixFailureCheckpoint payload/detached wrapper
→ bounded failure tail
```

`FinalizationPrefixFailureCheckpointPayload`:

```text
tested_successor_sha + static_union_graph_digest
dependency_expansion_result_payload_sha + wrapper_sha
attempt_namespace_id + attempt_id
execution_terminal_receipt_sha + execution_authority_lineage_digest
finalization_grant/consume receipt SHA + prefix_lineage_digest
success_cas_receipt_sha
ordered_durable_prefix_records[]:
  role_id,literal_path,schema_sha,publisher_actor/Physical,
  payload_or_body_sha,wrapper_or_receipt_sha,
  original_terminal_state,verification_result
durable_prefix_member_set_digest
last_complete_role + first_failed_or_nonrunnable_role
first_failure_payload_or_receipt_sha_or_na
failure_code + cause_observed_at + trusted_clock_source
post_consume_revocation_observation_receipt_sha_or_na
missing_success_suffix_role_id[]
protected/current mutation = 0
producer/checker distinct Physical/SHA/argv
```

checkpoint payload는 future failure evidence/close/wrapper SHA를 포함하지
않는다. detached wrapper가 payload Physical, exact prefix의 missing/extra/
duplicate `0`, static-union subset과 first-failure boundary를 독립 검증한다.

failure tail의 checkpoint source는 다음 exact union 중 하나다.

```text
EARLY_FAILURE = V1FailureCASReceipt and success CAS cardinality 0
PREFIX_FAILURE = FinalizationPrefixFailureCheckpointWrapper
                 and success CAS cardinality 1
source cardinality = 1

selected checkpoint source
→ FinalizationFailureEvidencePayload + detached wrapper
→ FinalizationFailureDispositionPayload + detached wrapper
→ FailureTerminalPayload(ready=false)
→ PostCloseFinalizationCloseReceipt(terminal_kind=FAILURE)
→ FailureTerminalWrapper as the atomic terminal tail
```

`V1FailureCASReceipt`는 exact T01~T15 completion receipt, observed result-or-NA,
execution terminal role/status, first failure edge, actual bounded output-prefix digest와
missing role set을 canonical task order로 결속한다. success CAS가 이미
있으면 cardinality는 `0`이고
`FinalizationPrefixFailureCheckpoint`만 쓴다. 두 checkpoint source를 같은
attempt에서 함께 주장할 수 없고 `cas_kind=FAILURE_ONLY`다.

`FinalizationFailureEvidencePayload`:

```text
tested_successor/attempt/two lineage prefix
execution_terminal_role + execution_terminal_receipt_sha
failure_checkpoint_kind = EARLY_FAILURE | PREFIX_FAILURE
failure_checkpoint_payload_or_receipt_sha + wrapper_sha_or_na
ordered_actual_output_prefix_ref[]
missing_or_not_run_role[]
first_failure_role + failure_code
raw_failure_evidence_ref[]
protected/current mutation = 0
success_credit_claimed = false
producer_physical + producer_sha + literal_argv
```

`FinalizationFailureDispositionPayload`은 evidence payload/wrapper, selected
checkpoint source와 branch-cardinality 검증을 결속하고
`disposition=FAILED_CLOSED_CANDIDATE`, `ready=false`,
`retry_requires_fresh_whole_lineage=true`로 고정한다.
`FailureTerminalPayload`는 이 disposition까지 inward-reference하지만 future
finalization close SHA는 넣지 않는다. final wrapper가 payload SHA,
finalization close SHA, full execution/finalization lineage와
`PRE_P_SUCCESSOR_READY=false`를 결속한다.

execution terminal이 FAILURE/CRASH/EXPIRED이면 milestone, 22+4 completion,
G1E..G7, P7와 Ready payload/wrapper actual cardinality는 전부 `0`이다.
success branch 중간 실패이면 이미 durable인 exact prefix는 보존하되 그
실패 node의 downstream success-only cardinality와 Ready payload/wrapper는
`0`이다. failure evidence를 finding closure, Gate PASS, G6/P7/G7 또는 ready
credit으로 사용할 수 없다.

`PostCloseFinalizationCloseReceipt`는 grant/consume, exact ordered output
payload/receipt set, `terminal_kind=READY|FAILURE`, selected terminal payload
SHA, closed time/deadline과 branch-specific expected terminal state를
결속한다. close와 selected terminal wrapper는 하나의 two-member close
transaction이다. close receipt를 먼저 durable publish하되 expected terminal
state는 exact selected wrapper까지 fsync/reopen 검증된 때에만 effective하다.
READY close에는 Ready wrapper가 정확히 1이고 Failure wrapper는 0이며,
FAILURE close에는 그 반대다. 따라서 effective close 뒤의 write는 없고,
selected wrapper는 close SHA를 결속하는 유일한 terminal commit/tail이다.
close 뒤 wrapper 전 crash/expiry이면 ready=false인 partial state로 해석하고
다음 exact adoption만 허용한다.

```text
FinalizationTerminalWrapperRecoveryConsumeReceipt:
  recovery grant payload/wrapper SHA
  attempt_namespace_id + attempt_id
  original finalization grant/consume SHA
  terminal_wrapper_recovery_revocation_head_payload_sha + wrapper_sha
  terminal_wrapper_recovery_revocation_ordinal + source CAS digest
  terminal_wrapper_recovery_pre_observation_receipt_sha
  selected terminal payload SHA + file/parent Physical
  existing finalization close SHA + file/parent Physical
  close.terminal_kind + expected sole wrapper path/schema/publisher
  expected wrapper pre_state = ABSENT
  payload_and_close_byte_physical_reopen_equality = PASS
  recovery consumed_at + trusted clock
  terminal_wrapper_recovery_hard_deadline
  pre_state = UNREVOKED_AND_UNSPENT
  post_state = WRAPPER_RECOVERY_CONSUMED
```

consume 뒤 expected wrapper 한 파일만 create-exclusive 발행하고 fsync/reopen
한다. consume 직전 latest version/observation은 `UNREVOKED`이고 source CAS와
fresh해야 한다. wrapper는 payload+existing close와 recovery grant/consume,
optional post-consume revocation observation을
inward-reference한다. expected wrapper가 이미 exact하면 recovery consume을
만들지 않고 기존 terminal을 adopt한다. wrong close SHA/kind, changed
Physical, wrapper collision, 두 wrapper, 새 close/payload 또는 recovery
deadline 초과는 거부한다. recovery 성공 뒤 terminal exactly-one과 wrapper
뒤 write cardinality `0`을 다시 검증한다.

`finalization_lineage_prefix_digest`는 grant+consume까지, full
`finalization_authority_lineage_digest`는 close까지 계산한다. G1E~G7은
prefix와 closed execution digest를 결속하고, final ready wrapper는 full
두 lineage를 결속한다. recovery가 쓰였으면 full finalization lineage에
wrapper-recovery grant/consume도 ordered exact SHA로 추가한다. failure
wrapper도 같은 full 두 lineage를 결속하지만 ready=false다. allowlist만으로
이 authority를 대체할 수 없다.

### 13.5 retry는 항상 fresh whole lineage

다음 중 하나라도 발생하면 기존 decision은 `SPENT_AND_CLOSED`로 보존하고
재사용하지 않는다.

- V1 failure, crash, timeout, revocation 또는 명시적 retry
- successor/spec/input/fixture/verifier/producer Physical SHA 변경
- P0/protected snapshot, dependency edge manifest 또는 task set 변경
- bridge, review, scope, deadline, write set 또는 authority subject 변경

새 attempt에는 모두 새것이 필요하다.

```text
fresh P0/G0 if protected freshness changed
fresh successor revision root first if P0/protected freshness or
  dependency definition/manifest/schema/attempt template changed
fresh AttemptNamespaceFreeze and disjoint literal ATTEMPT_ROOT
fresh pre-authority successor reviews and pair receipt
fresh bridge payload and detached receipt
fresh formal/skeptical bridge reviews and pair receipt
fresh user request
fresh immutable explicit user response
fresh governing decision and receipt
fresh attempt_id, attempt namespace nonce, execution/finalization/
  close-recovery/terminal-wrapper-recovery nonce
fresh POST_CLOSE_FINALIZATION_ONLY grant and receipt
fresh execution-close recovery and terminal-wrapper recovery grant/receipt
fresh task specs
fresh consume
fresh execution/raw/result
fresh execution close
fresh finalization consume/close
fresh task completion receipts and CAS
fresh G1~G5-EVIDENCE/G6/P7/G7
```

하나라도 stale/replayed/spent/wrong-attempt이면 V1과 downstream은
`NOT_RUN`이다. 같은 subject의 단순 retry도 fresh whole lineage를 요구한다.
모든 fresh attempt-specific write path는 새 `ATTEMPT_ROOT` 아래이고 이전
attempt path와 exact set intersection=`0`이다. shared fixed `r001` path에
두 번째 request/result/close를 쓰거나 이전 namespace allowlist를 새
attempt에 재사용하면 `NOT_RUN`이다. successor revision이 바뀌면 새
`H1_ROOT`도 이전 root와 disjoint하고 old root는 immutable history다.

### 13.6 V1 task common contract

각 `V1TaskExecutionSpecPayload`은 consume 전에 freeze하고 successor,
P0, five SPEC PASS, bridge/review/decision과 task-specific input,
fixture/verifier/producer SHA를 결속한다. task spec은 아직 존재하지 않는
task result SHA를 포함하지 않으며 expected predecessor task ID/role만
선언한다.

```text
task_id
task_spec_registry_sha + task_dag_digest
tested_successor_sha + publication_receipt_sha
p0_freeze_receipt_sha
five_spec_pass_receipt_sha[]
authority_decision_sha + decision_receipt_sha
attempt_id + expected_execution_nonce
task_specific_input/fixture/verifier/producer_ref[]
literal_argv[] + literal_environment[]
literal_read/write/exec_set[]
expected_task_predecessor_edges[]:
  task_edge_id, predecessor_task_id,
  expected_result_role, expected_result_schema_sha,
  expected_status = PASS,
  expected_attempt_id = current attempt_id,
  expected_execution_lineage_prefix_role =
    same decision/attempt + future matching execution consume
expected_output_role/path/schema/publisher/cardinality[]
```

`V1TaskResultPayload` path는 이름과 무관하게
`SIGNED_RECEIPT_WRAPPER_ONLY` one-file body/signature다. dependent task가
실행되기 전에 이미 signature/domain/schema/publisher를 검증할 수 있다.

```text
body:
  task_id
  task_spec_payload_sha + publication_receipt_sha
  task_dag_digest
  tested_successor_sha + publication_receipt_sha
  attempt_id
  execution_consume_receipt_sha
  execution_lineage_prefix_digest
  expected_task_predecessor_edge_id[]
  verified_task_predecessor_ref[]:
    task_edge_id, predecessor_task_id,
    predecessor_result_sha, predecessor_result_schema_sha,
    predecessor_status, predecessor_attempt_id,
    predecessor_execution_consume_receipt_sha,
    predecessor_execution_lineage_prefix_digest,
    signature_verification_result
  actual_argv + environment_digest
  raw_stdout_sha + raw_stderr_sha
  execution_started_at + execution_ended_at
  semantic_assertion_result[]
  producer_physical + producer_sha
  status = NOT_RUN | FAIL | PASS
signature:
  signature_domain + signer_actor_id + detached_signature
```

expected와 verified predecessor edge ID/cardinality/order는 §13.7 TD25와
exact one-to-one다. 하나라도 missing/extra/duplicate, non-PASS, wrong
attempt/consume/lineage/signature이면 current producer command는 dispatch하지
않고 signed result status를 `NOT_RUN`으로 둔다. predecessor가 모두 PASS이고
producer를 실행했지만 assertion이 틀리면 `FAIL`, 모두 맞으면 `PASS`다.
`NOT_RUN` raw stdout/stderr는 guard가 발행한 canonical zero-byte RAW_BYTES고
producer execution timestamps는 explicit `NA`다. failure를 PASS로 바꾸거나
dependency-disjoint task의 결과를 현재 predecessor로 가장하지 않는다.

final `V1TaskCompletionReceipt`:

```text
task_id
task_spec_payload_sha + publication_receipt_sha
tested_successor_sha + publication_receipt_sha
p0_freeze_receipt_sha
five_spec_pass_receipt_sha[]
input_manifest_sha + fixture_sha
producer_physical_sha + verifier_physical_sha
attempt_id
bridge_sha + bridge_review_sha[2] + pair_receipt_sha
request_sha + immutable_user_response_receipt_sha
decision_sha + decision_receipt_sha
execution_consume_receipt_sha + execution_terminal_receipt_sha
execution_authority_lineage_digest
finalization_grant_sha + publication_receipt_sha
finalization_consume_receipt_sha
finalization_lineage_prefix_digest
execution_window
raw_stdout_sha + raw_stderr_sha
task_result_payload_sha
expected_task_predecessor_edge_id[]
verified_predecessor_task_completion_ref[]:
  task_edge_id, predecessor_task_id,
  predecessor_task_result_sha,
  predecessor_task_completion_receipt_sha,
  predecessor_status, predecessor_attempt_id,
  predecessor_execution_authority_lineage_digest,
  predecessor_finalization_lineage_prefix_digest
expected_produced_finding_id[]
expected_produced_debt_id[]
expected_produced_milestone_id[]
status = NOT_RUN | FAIL | PASS
```

observed task의 completion receipt는 task result와 같은 status를 가져야
한다. CRASH/EXPIRED missing task는 `task_result_payload_sha=NA`,
`status=NOT_RUN`과 selected terminal의 missing-set proof를 직접 가진다.
current가
PASS이면 모든 predecessor completion도 PASS이고 같은 attempt/closed
execution lineage/finalization prefix여야 한다. completion은 TD25
topological order로 발행하며 `TP001..TP025`가 predecessor completion에서
current completion으로 가는 exact edge다. failure/NOT_RUN도 원인 ref와
동일 status를 보존한 signed terminal receipt를 만들고 PASS evidence나
milestone producer로 소비하지 않는다.

`CASPreClosePayload`은 canonical T01~T15 순서의
`task_id/result_payload_sha/status/attempt_id` 15 row, task-set digest와
execution consume SHA를 가진다. 15-result cut에서 payload가 이미
`CAS_PRESENT_UNWRAPPED`이면 selected `SUCCESS|FAILURE|CRASH|EXPIRED`
execution terminal receipt가 그 sole deferred wrapper다. result count
`<15` 또는 `CAS_ABSENT_BEFORE_PUBLICATION` recovery이면 payload/wrapper
cardinality는 `0/0`이다. restart의
`CAS_WRAPPED_BY_SELECTED_TERMINAL`은 exact 15-result payload와 existing
terminal receipt `1/1`을 adopt하며 새 write는 `0`이다.

final `V1EvidenceCASReceipt`는 signed wrapper-only로 다음을 결속한다.

```text
tested_successor_sha + publication_receipt_sha
attempt_id
cas_pre_close_payload_sha
execution_terminal_receipt_sha
execution_authority_lineage_digest
finalization_grant_sha + grant_receipt_sha
finalization_consume_receipt_sha
finalization_lineage_prefix_digest
ordered_task_completion_members[15]:
  task_id, task_result_payload_sha, task_completion_receipt_sha, status
canonical_task_id_order = T01..T15
unique_task_id_count = 15
PASS/FAIL/NOT_RUN counts
task_member_set_digest
published_at + trusted_clock_source
signature_domain
```

missing/extra/duplicate/reordered member, 14/16 member, wrong result/completion
pair, attempt/lineage mismatch 또는 pre-close/final task-set digest mismatch는
CAS final 발행을 금지한다.

22 finding/4 debt completion payload는 supporting task receipt SHA,
final CAS receipt SHA, expected milestone receipt, owner, closed execution
lineage digest와 finalization prefix를 직접 결속한다.
crosswalk의 expected ID와 실제 payload ID가 다르거나 supporting task가
missing/duplicate이면 wrapper를 발행하지 않는다.

### 13.7 exact T01~T15 crosswalk와 task DAG

| task | exact task predecessor | evidence Gate | exact purpose | finding/debt/milestone support |
|---|---|---|---|---|
| T01 `MIXED-TREE-SCRATCH-LATE-BOUND` | none | G1 | P0 mixed tree, scratch repeat, late-bound negative | B11/B17/M02; B11a/B17a/M02a |
| T02 `PROJECTION-REPEAT` | T01 | G1 | BEFORE/AFTER typed projection repeat | B12; B12a |
| T03 `CURRENT-BEFORE-SUCCESSOR-AFTER-SPAWN` | T02,T04,T05,T10,T11 | G4 | source-only current BEFORE/successor AFTER spawn | B18; B18a |
| T04 `TWO-ENV-EXACT10-REPEAT` | T01,T02,T05,T11 | G4 | B08 input/intent와 exact10/repeat | B07/B08; B07a/B08a |
| T05 `RUNTIME-GIT-M01A-FOUNDATION` | T01,T02,T11 | G4 | RuntimeActual consumer map, Git closure, executable declaration | B13/B15; B13a/B15a/M01a |
| T06 `STAGEC-CONTRACT-FOUNDATION` | T01 | G5 | B09 actual contract, exact6 schema, hash topology | B09a/B10a/B06a |
| T07 `AUTHORITY-REPLAY-REVOKE-CRASH` | T01 | G2 | wrapper/FSM/capability/future activation/recovery negative | B01/B02/B03/B05/M03; B01a/B02a/B03a/B05a/M03a |
| T08 `M04A-EXTRACTION-MEMBERSHIP` | T06,T07 | G3 | independent graph extraction와 B16 assignment input | M04a/B16a |
| T09 `M04B-SERIALIZATION-DIGEST` | T08 | G3 | canonical serialization/digest와 exactly-one verify | M04b/B16b/B16/M04 |
| T10 `DEBT-D-A-LOCK-EPOCH-TWO-RUN` | none | G4 | lock epoch와 generation repeat | D-A |
| T11 `DEBT-D-B-RUNNER-INVENTORY-SPAWN` | none | G4 | runner inventory와 spawn foundation | D-B |
| T12 `DEBT-D-C-GATEWAY-EXACT4-EXACT5` | none | G4 | historical exact4/current exact5 | D-C |
| T13 `DEBT-D-D-ARTIFACT-BASELINE-DUAL` | none | G4 | 20260722/current artifact baseline | D-D |
| T14 `FULL19-M01B-FINAL` | T03,T04,T05 | G4 | full19 framing과 executable final trace equality | B14/M01; B14a/M01b |
| T15 `SYNTHETIC-STAGEC-FINAL` | T05,T06,T08,T09 | G5 | root derivation, exact6, same-root, object/receipt fixture | B04/B06/B09/B10; B04a/B06b/B09b/B09c/B10b |

task count와 TD predecessor는 바꾸지 않는다. T01은 M02 registry/verification
exact 4 files와 19-role verification을, T06은 B06 N26/T1/X1 pairs를, T15는
B06 ReviewBinding/V1 pairs와 B04 exact 6 files를 자기
`expected_output_role/path/schema/publisher/cardinality[]`에 직접 추가한다.
missing/extra/duplicate object, domain hash mismatch 또는 wrong task publisher면
해당 result는 PASS가 아니다.

task specs는 consume 전에 전부 freeze하지만, actual execution은 다음 exact
edge를 지킨다. task result는 각 expected predecessor task의 actual
result payload SHA/status/attempt를 one-to-one 기록한다.

```text
TD001 T01 → T02
TD002 T01 → T05
TD003 T02 → T05
TD004 T11 → T05
TD005 T01 → T04
TD006 T02 → T04
TD007 T05 → T04
TD008 T11 → T04
TD009 T02 → T03
TD010 T04 → T03
TD011 T05 → T03
TD012 T10 → T03
TD013 T11 → T03
TD014 T01 → T06
TD015 T01 → T07
TD016 T06 → T08
TD017 T07 → T08
TD018 T08 → T09
TD019 T03 → T14
TD020 T04 → T14
TD021 T05 → T14
TD022 T05 → T15
TD023 T06 → T15
TD024 T08 → T15
TD025 T09 → T15
```

exact gate membership:

```text
G1 task set = {T01,T02}
G2 task set = {T07}
G3 task set = {T08,T09}
G4 task set = {T03,T04,T05,T10,T11,T12,T13,T14}
G5 task set = {T06,T15}

unique task IDs = 15
PASS = 15
FAIL = 0
NOT_RUN = 0
final CAS task receipt members = exact 15
orphan/duplicate task = 0/0
finding row completion receipts = exact 22
debt completion receipts = exact 4
```

T08/T09, T05/T14와 T06/T15는 각각 foundation/final leaf로 합칠 수 없다.
이 분리가 M04, M01과 Stage-C 내부 선후행 cycle을 제거한다.

## 14. G1~G5-EVIDENCE, G6Disposition, P7, G7과 ready

### 14.1 post-close G1~G5-EVIDENCE chain

각 Gate는 `POST_CLOSE_READ_ONLY_VERIFY`이며 spent decision을 재사용해
실행하지 않는다. 여기서 read-only는 protected/current product input에
대한 mutation `0`을 뜻한다. Gate의 spec/raw/result와 이후 review/ready
control artifact는 consumed `POST_CLOSE_FINALIZATION_ONLY` grant의 exact
add-only write set으로만 발행한다.

| Gate | existing exact predecessor | evidence membership |
|---|---|---|
| G1-EVIDENCE | G5-SPEC PASS, final CAS, all 22+4 closure receipts | T01,T02와 B11/B12/B17/M02 |
| G2-EVIDENCE | G1-EVIDENCE PASS | T07와 B01/B02/B03/B05/M03 |
| G3-EVIDENCE | G2-EVIDENCE PASS | T08,T09와 B16/M04, edge digest |
| G4-EVIDENCE | G3-EVIDENCE PASS | T03/T04/T05/T10~T14, B07/B08/B13/B14/B15/B18/M01, D-A~D-D |
| G5-EVIDENCE | G4-EVIDENCE PASS | T06/T15와 B04/B06/B09/B10 |

각 spec은 표의 predecessor result payload/receipt가 존재한 뒤 freeze하고,
result는 같은 attempt의 closed execution lineage와
`PostCloseFinalizationConsumeReceipt`까지의 finalization prefix를
비교한다. exact result receipt order
`G1E→G2E→G3E→G4E→G5E`의 JCS digest를
`ordered_evidence_gate_chain_digest`로 발행한다.

G3-EVIDENCE spec/result는 §6.3/§6.4의 named field로 exact
`ClosureDependencyEdgeManifestPayload/Receipt`,
`ClosureDependencyExpansionResultPayload/Wrapper`와
`execution_projection_digest`/`finalization_projection_digest`를 결속한다.
G3 verifier는 static union의
branch condition/algorithm을 expansion result의 selected execution terminal,
observed result prefix, materialized execution set과 selected finalization
projection에 적용해 subset, cardinality, order와 digest를 독립 재계산한다.
이 여섯 named value가
하나라도 missing/extra/mismatch이거나 generic manifest 배열에서만 발견되면
G3-EVIDENCE는 PASS할 수 없다.

### 14.2 G6 strict paths와 named disposition

successor가 freeze할 literal roles:

```text
<ATTEMPT_ROOT>/g6/execution-spec.payload.json
<ATTEMPT_ROOT>/g6/execution-spec.signature.json
<ATTEMPT_ROOT>/g6/raw-stdout.bin
<ATTEMPT_ROOT>/g6/raw-stderr.bin
<ATTEMPT_ROOT>/g6/disposition.payload.json
<ATTEMPT_ROOT>/g6/disposition.signature.json
<ATTEMPT_ROOT>/g6/result.payload.json
<ATTEMPT_ROOT>/g6/result.signature.json
```

`G6ExecutionSpec`은 G5-EVIDENCE result/receipt와 그 ordered chain digest,
P0/successor/final CAS/closed execution lineage/finalization prefix/22+4
completion receipt를 existing expected predecessor set으로 결속한다.
또한 static dependency manifest payload/receipt, runtime dependency
expansion result payload/wrapper와 expected execution projection digest를
각각 named field로 결속하고 expected finalization projection digest도
별도 결속한다. 성공 후보이므로 prefix-failure checkpoint
payload/wrapper는 exact `NA`다.

`G6DispositionPayload`은 G6 result와 별도 immutable artifact다. G6
verifier가 raw recomputation 뒤 먼저 생산한다.

```text
type = G6_DISPOSITION
schema_sha
g6_execution_spec_sha + receipt_sha
tested_successor_sha + successor_receipt_sha
p0_freeze_receipt_sha
evidence_cas_sha + final_cas_receipt_sha
authority_decision_sha + decision_receipt_sha
execution_consume_receipt_sha + execution_terminal_receipt_sha
execution_authority_lineage_digest
finalization_grant_sha + grant_receipt_sha
finalization_consume_receipt_sha
finalization_lineage_prefix_digest
static_dependency_manifest_payload_sha + receipt_sha
dependency_expansion_result_payload_sha + wrapper_sha
execution_projection_digest
finalization_projection_digest
finalization_prefix_failure_checkpoint_payload_sha = NA
finalization_prefix_failure_checkpoint_wrapper_sha = NA
ordered_evidence_gate_chain_digest
predecessor_result_receipt_sha[]
finding_predicate_result
debt_predicate_result
owner_predicate_result
milestone_predicate_result
task_crosswalk_predicate_result
graph_predicate_result
negative_fixture_predicate_result
disposition = PASS_CANDIDATE | FAIL_CANDIDATE
generated_at + trusted_clock_source
producer_physical + producer_sha + literal_argv
```

payload 뒤 detached `G6DispositionPublicationReceipt`를 발행한다. disposition
payload는 future G6 result SHA를 포함하지 않는다.

`G6ExecutionResultPayload` named fields:

```text
gate_spec_payload_sha + gate_spec_receipt_sha
tested_successor_sha + successor_receipt_sha
evidence_cas_sha + final_cas_receipt_sha
authority_decision_sha + decision_receipt_sha
execution_consume_receipt_sha + execution_terminal_receipt_sha
execution_authority_lineage_digest
finalization_grant_sha + grant_receipt_sha
finalization_consume_receipt_sha
finalization_lineage_prefix_digest
static_dependency_manifest_payload_sha + receipt_sha
dependency_expansion_result_payload_sha + wrapper_sha
execution_projection_digest
finalization_projection_digest
finalization_prefix_failure_checkpoint_payload_sha = NA
finalization_prefix_failure_checkpoint_wrapper_sha = NA
ordered_evidence_gate_chain_digest
disposition_sha
disposition_publication_receipt_sha
predecessor_result_receipt_sha[]
actual_exit + raw_stdout_sha + raw_stderr_sha
semantic_assertion_result[]
status = NOT_RUN | FAIL | PASS
```

G6 result의 `disposition_sha`는 exact `G6DispositionPayload` SHA다. generic
`actual_output_sha[]`에서 추정하지 않는다.

### 14.3 G6 PASS exact predicate

```text
P0 freeze status = FROZEN
P0 protected snapshot drift = 0
G1-SPEC..G5-SPEC PASS receipts = 5
pre-authority successor review pair = 0/0/0
bridge review pair = 0/0/0
authority decision = ALLOW_ONE_ISOLATED_ATTEMPT
authority provenance verifier = PASS
execution consume/selected terminal receipts = exact 1/1
selected ExecutionTerminalState = SUCCESS
selected terminal verification_result = PASS
selected execution terminal signed receipt file = exact 1
finalization grant/consume receipts = exact 1/1
same closed execution lineage digest everywhere = true
same finalization lineage prefix digest everywhere = true
V1 unique task IDs = 15
V1 PASS/FAIL/NOT_RUN = 15/0/0
final CAS member task receipts = 15
G1-EVIDENCE..G5-EVIDENCE PASS receipts = 5
ordered evidence chain digest equality = PASS
static dependency manifest payload/receipt = exact 1/1
runtime dependency expansion result payload/wrapper = exact 1/1
G3/G6 static manifest named binding equality = PASS
G3/G6 expansion result named binding equality = PASS
runtime expansion static-union subset/cardinality/order/digest = PASS
G3/G6 execution projection digest equality = PASS
G3/G6 finalization projection digest equality = PASS
success suffix actual/projected node-edge set equality = PASS
finalization prefix-failure checkpoint cardinality = 0

finding rows = 22
unique finding IDs = 22
BLOCKING/MAJOR = 18/4
OPEN = 0
CLOSED_CANDIDATE = 22
verified row completion receipts = 22
accountable owner phase cardinality per row = 1
accountable owner actor cardinality per row = 1
multi-owned/unowned = 0/0
expected per-row milestone slots = 33
expected unique milestone receipt objects = 29
actual milestone ID/schema/count/order exact equality = PASS
missing/duplicate/reordered milestone = 0

debt IDs = exact {D-A,D-B,D-C,D-D}
unique debt IDs = 4
debt accountable owner cardinality = 1 each
debt OPEN/CLOSED_CANDIDATE = 0/4
verified debt completion receipts = 4
missing/duplicate debt evidence = 0

B04 constructive contract/fixture/verification files = exact 6
B04 exact three receipt variants and capability predicates = PASS
B04 target-spec JCS/hash and predecessor/executor binding = PASS
B06 N26/T1/X1/StageCReviewBinding/V1 files = exact 10
B06 semantic domain/value/wrapper hashes = PASS
B06 inward-only edge set B06X001..010 = PASS
M02 registry/verification files = exact 4
M02 closed late-bound role enum coverage = 19/19
M02 wrong/missing/extra/duplicate/wrong-phase negatives = 19/19 PASS

positive fixture failure = 0
negative fixture false-accept = 0
implicit alias/glob/path escape = 0
missing/duplicate producer = 0
producer/checker identity collision = 0
dependency graph missing/extra edge = 0
dependency graph cycle = 0
expanded node/edge cardinality or digest mismatch = 0
future/self reference = 0
constructive/actual Stage-C cross-use = 0
allowlist missing/undeclared role = 0
official delta = 0
```

milestone slot `33`은 §8.2의 per-row cardinality 합이다. B16과 M04가 같은
four-receipt chain을 각각 결속하므로 unique object는 `29`이고, 이를
중복 evidence로 오판하지 않는다. 한 row 안의 중복은 계속 거부한다.

predicate 하나라도 틀리면 disposition은 `FAIL_CANDIDATE`; G6 result는
실제 evaluation을 완료했으므로 `FAIL`이다. predecessor 자체가 missing,
wrong status/subject/attempt/lineage면 evaluation을 실행하지 않고
`NOT_RUN`이다.

### 14.4 P7 four class-specific reviews

1. formal `SUCCESSOR_PLAN_REVIEW`
2. skeptical `SUCCESSOR_PLAN_REVIEW`
3. formal `CONSTRUCTIVE_EVIDENCE_REVIEW`
4. skeptical `CONSTRUCTIVE_EVIDENCE_REVIEW`

plan review pair의 exact subject:

```text
reviewed_subject_type = SUCCESSOR_PLAN_REVIEW
reviewed_sha = {
  tested_successor_sha,
  successor_publication_receipt_sha,
  g6_disposition_sha,
  g6_disposition_publication_receipt_sha,
  g6_result_receipt_sha
}
```

evidence review pair의 exact subject:

```text
reviewed_subject_type = CONSTRUCTIVE_EVIDENCE_REVIEW
reviewed_sha = {
  tested_successor_sha,
  evidence_cas_sha,
  final_cas_receipt_sha,
  g6_disposition_sha,
  g6_disposition_publication_receipt_sha,
  g6_result_receipt_sha,
  execution_authority_lineage_digest,
  finalization_lineage_prefix_digest
}
```

네 review 모두 같은 successor와 G6 disposition을 결속하고, evidence pair만
같은 evidence CAS/full lineage를 subject로 가진다. 서로 다른 class를
“same subject”라고 합치지 않는다. §2 common identity와 pair actor/session/
author/producer disjointness를 모두 적용한다.

### 14.5 G7 named result

P7 네 review receipt와 두 pair receipt가 모두 존재한 뒤에만 G7 spec을
freeze한다. G7 spec의 expected predecessor set은 exact G6 result receipt,
four review receipt와 two pair receipt다.

`G7ExecutionResultPayload`:

```text
gate_spec_payload_sha + gate_spec_receipt_sha
tested_successor_sha + successor_receipt_sha
evidence_cas_sha + final_cas_receipt_sha
g6_disposition_sha + disposition_receipt_sha
g6_result_receipt_sha
formal_successor_review_sha
skeptical_successor_review_sha
successor_review_pair_receipt_sha
formal_evidence_review_sha
skeptical_evidence_review_sha
evidence_review_pair_receipt_sha
authority_decision_sha
execution_consume_receipt_sha + execution_terminal_receipt_sha
execution_authority_lineage_digest
finalization_grant_sha + grant_receipt_sha
finalization_consume_receipt_sha
finalization_lineage_prefix_digest
predecessor_result_receipt_sha[]
post_freeze_mutation_result
official_delta
status = NOT_RUN | FAIL | PASS
```

G7 PASS:

```text
G6 status = PASS
G6 disposition = PASS_CANDIDATE
all exact G6 named binding equality = PASS
four required reviews exist
all four findings = 0/0/0
review class/subject binding = PASS
review identity/disjointness = PASS
successor/evidence/disposition/G6/reviews mutation = 0
same closed execution lineage digest = true
same finalization lineage prefix digest = true
official delta = 0
```

### 14.6 유일한 ready predicate

`ReadyEvaluationPayload`가 다음 식을 계산한다. detached wrapper는 이
payload 뒤가 아니라 §13.4의 finalization close 뒤 terminal로 발행한다.

```text
PRE_P_SUCCESSOR_READY =
  G6 status = PASS
  AND exact G6 result receipt present
  AND G6 disposition = PASS_CANDIDATE
  AND exact G6 disposition payload/receipt present
  AND G7 status = PASS
  AND exact G7 result receipt present
  AND G7.tested_successor_sha = G6.tested_successor_sha
  AND G7.evidence_cas_sha = G6.evidence_cas_sha
  AND G7.g6_disposition_sha = G6.disposition_sha
  AND G7.g6_result_receipt_sha = exact G6 result receipt
  AND G7.four_review_sha = exact four P7 review receipts
  AND G7.execution_authority_lineage_digest =
      G6.execution_authority_lineage_digest
  AND G7.finalization_lineage_prefix_digest =
      G6.finalization_lineage_prefix_digest
  AND execution decision/consume/close full lineage = PASS
  AND finalization grant/consume prefix lineage = PASS
  AND post-G7 protected/subject mutation = 0
  AND official delta = 0
```

missing/ambiguous/duplicate named binding, wrong-class subject, wrong successor/
CAS/disposition/G6 receipt, review bypass, replay/spent wrong-attempt lineage와
post-freeze mutation을 negative fixture로 거부한다.

payload의 true는 아직 `READY_CANDIDATE`다.
`PostCloseFinalizationCloseReceipt`가 exact ordered finalization output set과
이 payload를 결속하고, 그 close SHA와 full
`finalization_authority_lineage_digest`를 결속한 detached
`ReadyEvaluationReceipt`가 발행돼야 `PRE_P_SUCCESSOR_READY=true`가
effective하다. close-before-payload, wrapper-before-close, 다른 payload/
lineage 결속과 wrapper 뒤 추가 write는 거부한다.

effective ready=true도 journal bootstrap, Stage A, H2, checkpoint, canonical,
product 또는 release authority가 아니다.

## 15. H2 bootstrap, Stage A→B→C와 D→E→F→G

### 15.1 bootstrap과 Stage A~C

ready=true 뒤에도 각 단계별 exact subject와 fresh one-use approval이
필요하다.

```text
ReadyEvaluationReceipt
→ journal-bootstrap design/review/approval
→ journal-bootstrap consume/apply/close receipt
→ Stage A exact subject/review/approval
→ candidate/environment/package build
→ immutable candidate freeze
→ candidate-bound independent review
→ Stage B exact command subject/review/approval
→ Stage B validation PASS receipt
→ Stage C exact transaction/recovery subject/review/approval
→ Stage C authority consume
→ H2LiteralRootBinding payload/wrapper
→ checkpoint durable
→ §9.3 actual live-root/exact6/application FSM
→ StageCClosedSuccessReceipt
```

각 approval은 stage, exact subject/payload/receipt, predecessor PASS receipt,
actor/session provenance, nonce, scope, deadline, revocation, consume와 close를
직접 결속한다. H1 decision/receipt는 H2에 재사용하지 않는다.

공통 stop:

- predecessor missing 또는 status/subject/nonce/attempt mismatch
- expired/revoked/consumed authority
- undeclared read/write/exec
- missing raw evidence/result/publication receipt
- undefined crash state
- checkpoint/live-root triple/same-root mismatch

H2 뒤 `H2_RESULT_VERIFICATION_ONLY / NO_REEXECUTION`은 exact
`StageCClosedSuccessReceipt`와 predecessor chain을 read-only 검증할 뿐
authority를 만들지 않는다. H2 approval, nonce, consume/close와 actual
apply를 재사용하거나 재실행하지 않는다.

### 15.2 Stage D~G

| Stage | exact predecessor | allowed action | completion |
|---|---|---|---|
| D | actual `StageCApplicationReceipt` + `StageCClosedSuccessReceipt` + exact D design subject + D fresh one-use approval | P-successor 설계·검수만 | D consume/close + design-review receipt |
| E | D receipts + exact E candidate subject + E fresh one-use approval | exact P17 candidate build·candidate review만 | E consume/close + candidate-review receipt |
| F | E receipts + exact F resolution attempt subject + F fresh one-use approval | reviewed candidate resolution만 | F consume/close + resolved-subject receipt |
| G | F receipts + exact G resolved subject + G fresh one-use approval | exact resolved P subject fenced apply만 | G consume/close + selector-ready application receipt |

`H2_RESULT_VERIFICATION_ONLY`이나 constructive receipt는 D authority가
아니다. D activation subject와 D output successor plan은 서로 다른 tagged
type이다. D output의 미래 review를 D 사전 approval로 순환 재사용하지
않는다.

Stage G durable selector-ready receipt 전에는 frontier, canonical, product와
artifact credit을 바꾸지 않는다.

## 16. main transition, 제품과 artifact

Stage G 뒤 별도 main-control successor를 설계·검수·승인한다.

- v2.5/r022 actual bytes, DAG와 recovery 재검증
- exact main-control subject와 fresh one-use authority
- checkpoint-last atomic apply
- stale pointer 제거
- 중간 실패 시 seq39/r021 또는 완전한 새 상태 중 하나만 유지

전환 뒤 live frontier를 다시 계산한다. 현재 focus `EPIC-03`, ready frontier
`EPIC-03/EPIC-12`, materialized leaf `none`은 참고값이다. FP-008이나
FP-048을 강행하지 않고 actual recomputation과 별도 review/authorization을
따른다.

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

현재 artifact 표현:

```text
artifact closed-equivalent = 126/257
open = 131
```

이는 seq39 projection 값이며 전체 artifact completion, 프로젝트 완료율,
R004/R005 또는 미래 constructive evidence의 새 credit이 아니다.

| lane | 수 | 병렬 준비 가능 | 실제 종료 조건 |
|---|---:|---|---|
| A internal-ready | 62 | 내용/trace/review | required content와 적격 승인 |
| B fact/owner/attest | 24 | request/owner map | actual fact/decision/attestation |
| C internal-run-required | 24 | env/command/receipt spec | actual raw output/receipt/review |
| D real-event-pending | 21 | target/authority/schedule | legitimate actual-event receipt |

## 17. H4 내부 five Gate, formal 279와 H5 release

### 17.1 H4 entry와 common candidate

H4 진입 조건:

- EPIC-04/05/06/08/09/10/11 완료
- 하나의 immutable candidate payload/receipt
- 승인된 environment와 provisional device/participant eligibility inventory
- `TEST_PLAN_APPROVAL_RECEIPT`

TEST_PLAN_APPROVAL은 planned formal row 279, actual device/event plan,
provisional device/participant eligibility inventory, five Gate plan과 exact
candidate SHA를 한 bundle로 승인한다. 이 approval은 지원 기기별 최종
user-test scope freeze나 actual device/participant dispatch 권한이 아니다.
approval body는 eligibility inventory payload/receipt SHA, file Physical,
canonical `(device_physical_id,participant_scope_id,actual_user_test_role_id)`
tuple set과 digest를 직접 결속해 immutable baseline으로 동결한다.
그 뒤 H4 안에서 다음 `must_close_before` edge를 지킨다.

### 17.2 exact five Gate internal edges

| Gate ID | required PASS edge in H4 |
|---|---|
| `GATE-SINGLE-ADMIN-RECOVERY-DRILL` | PASS(`waived=false`) → 첫 실제 사용자시험 시작 |
| `GATE-PHONE-QUEUE-BYTE-LIMIT` | PASS(`waived=false`) → `SupportedDeviceUserTestScopeFreezeReceipt` 발행 가능 |
| `GATE-SERVER-CAPACITY-STATE-CONTRACT` | PASS(`waived=false`) → 관련 기능 통합시험 completion |
| `GATE-RAW-COLLECTION-RELEASE-REVIEW` | PASS(`waived=false`) → TST-22 readiness 판단 및 actual deploy eligibility |
| `GATE-CLOUD-COST-MEASUREMENT` | PASS(`waived=false`) → 운영비 기준 freeze 및 TST-22 readiness 판단 |

모든 Gate receipt는 같은 candidate SHA, TEST_PLAN_APPROVAL, raw measurement/
drill/review evidence, executor와 independent reviewer identity를 결속한다.
Gate를 뒤늦게 H4 completion 뒤 실행하거나 다른 candidate receipt를 섞거나
`waived=true`로 바꾸면 H4는 PASS할 수 없다.

phone Gate PASS 뒤 별도 scope authorizer가 다음
`SupportedDeviceUserTestScopeFreezeReceipt`를 발행한다.

```text
candidate_sha + publication_receipt_sha
test_plan_approval_receipt_sha
immutable_eligible_inventory_payload_sha + receipt_sha
immutable_eligible_inventory_physical + tuple_set_digest
immediate_inventory_revalidation_receipt_sha
phone_queue_byte_limit_gate_result_sha + receipt_sha
phone_gate_status = PASS
phone_gate_waived = false
phone_gate_pass_coverage_tuple_set_digest
final_supported_scope_tuple_record[]
final_supported_scope_tuple_set_digest
exact_supported_device_physical_id[]
exact_participant_scope_id[]
actual_user_test_role_id[]
scope_authorizer_actor_id
scope_authority_receipt_sha
scope_frozen_at + trusted_clock_source
publisher_physical + publisher_sha + literal_argv
status = FROZEN
```

scope freeze 직전 발행하는 revalidation receipt는 approval 때의 inventory
payload/receipt SHA, Physical과 tuple digest가 여전히 exact함을 다시
검증한다. final scope의 유일한 식은 다음과 같다.

```text
final_supported_scope_tuple_set
= immutable_eligible_inventory_tuple_set_at_TEST_PLAN_APPROVAL
  ∩ phone_gate_PASS_coverage_tuple_set
```

두 operand, candidate 또는 revalidation이 다르면 scope receipt
cardinality는 `0`이다. provisional inventory, approval 또는 phone Gate
PASS 하나만으로 final scope를 추정하지 않는다.

actual user/device run은 이 receipt와 fresh run-specific authority를 모두
가진 뒤에만 dispatch한다. 각 run은 다음 순방향 tagged schema를 쓰며
앞 receipt에서 추론하거나 자기 SHA를 body에 넣지 않는다.

```text
COMMON_EXISTING_IDENTITY:
  candidate_sha + publication_receipt_sha
  test_plan_approval_receipt_sha
  supported_device_scope_freeze_receipt_sha
  admin_recovery_gate_PASS_receipt_sha
  phone_queue_gate_PASS_receipt_sha
  device_physical_id + participant_scope_id + actual_user_test_role_id
  run_authority_payload_sha + receipt_sha
  fresh_run_nonce
  authority_valid_from + authority_hard_deadline + trusted_clock_source

H4ActualRunAuthorityConsumeReceipt:
  COMMON_EXISTING_IDENTITY
  consumed_at + atomic_dispatch_started_at
  pre_state = UNREVOKED_AND_UNSPENT
  post_state = RUN_AUTHORITY_CONSUMED
  # own receipt SHA field = forbidden

H4ActualRunResultReceipt:
  COMMON_EXISTING_IDENTITY
  run_authority_consume_receipt_sha
  run_started_at + run_ended_at
  raw/result SHA + result status

H4ActualRunCloseReceipt:
  COMMON_EXISTING_IDENTITY
  run_authority_consume_receipt_sha
  run_result_receipt_sha
  run_started_at + run_ended_at + closed_at
  post_state = SPENT_AND_CLOSED
```

result와 close만 existing consume SHA를 inward-reference한다.
consume self-SHA, result future-close SHA, duplicate consume과 common tagged
variant 밖 field는 mandatory negative fixture다.
세 artifact equality와 다음 식이 모두 PASS여야 한다.

```text
(device_physical_id,participant_scope_id,actual_user_test_role_id)
  ∈ final_supported_scope_tuple_set
admin_gate.passed_at <= consumed_at <= run_started_at
run_started_at <= run_ended_at <= authority_hard_deadline
candidate/scope/admin/phone/nonce equality = exact
```

admin recovery Gate가 첫 actual 사용자시험 전에 PASS(`waived=false`)하지
않았거나 pre-scope/out-of-scope/post-deadline run이면 dispatch/credit은
`0`이다.

H4 내부 order:

```text
immutable candidate
→ TEST_PLAN_APPROVAL
→ immutable eligibility inventory 재확인
→ phone queue Gate PASS
→ immediate inventory revalidation receipt
→ SupportedDeviceUserTestScopeFreezeReceipt
→ admin recovery Gate PASS
→ 첫 actual user/device run
→ 나머지 Gate를 각 must_close_before 지점까지 수행
→ 해당 Gate PASS 뒤에만 관련 formal/device/event execution 또는 readiness
→ formal 279 aggregation
→ final scope-bound actual device/event aggregation
→ exact five Gate aggregation
→ H4CompletionReceipt
```

`H4CompletionReceipt`는 exact candidate/approval/inventory/revalidation/
`SupportedDeviceUserTestScopeFreezeReceipt`, 두 operand digest와 위
intersection 결과 digest, admin/phone Gate receipt, final tuple set과
actual consume/result/close receipt membership을 직접 결속한다. completion의
candidate/scope/run tuple digest와 각 run의 직접 field는 exact equality여야
한다. wrong candidate, pre-scope run, unsupported device, out-of-scope
participant/role, missing scope authority와 post-run scope mutation은 H4
PASS를 금지한다.

### 17.3 formal 279와 authorized N/A

```text
formal rows = 279
PASS + APPROVED_NOT_APPLICABLE = 279
all applicable rows = PASS
FAIL = 0
NOT_RUN = 0
```

`APPROVED_NOT_APPLICABLE`은 PASS가 아니며 다음 strict receipt가 있어야 한다.

```text
candidate_sha
test_case_row_id
applicability_basis
decision = APPROVED_NOT_APPLICABLE
authorized_approver_actor_id
approver_authority_receipt_sha
decided_at + trusted_clock_source
signature_domain
```

missing/stale/unauthorized N/A, 다른 candidate, 실행 뒤 소급 N/A, 단순
`N/A` 문자열은 거부한다. applicable row에는 N/A를 쓸 수 없다.

H4 completion:

```text
same immutable candidate SHA everywhere = true
formal PASS + APPROVED_NOT_APPLICABLE = 279
formal FAIL/NOT_RUN = 0/0
all applicable formal rows PASS = true
all N/A authority/timestamp receipts valid = true
supported-device user-test scope freeze receipt = exact 1
immutable inventory revalidation immediately before scope freeze = PASS
final scope = immutable eligible inventory ∩ phone PASS coverage = exact
phone/admin Gate before scope/first-run edges = PASS
pre-scope or out-of-scope actual run = 0
all run consume/result/close direct candidate/scope/Gate/tuple/nonce equality = PASS
all run start/end within consumed authority deadline = PASS
required actual device/event receipts complete = true
five Gate PASS = 5
five Gate waived = 0
must_close_before violations = 0
candidate SHA mismatch = 0
independent findings = 0/0/0
```

현재 formal `0/279`, actual device/event `0/0`, Gate `0/5`다.

### 17.4 H5 starts after H4

H5는 Gate 실행 단계가 아니다. exact H4 completion receipt 뒤 다음부터
시작한다.

```text
RELEASE_DECISION
→ eligibility = ELIGIBLE
→ signed candidate/config
→ deployment
→ canary/smoke
→ rollback/backup/restore/recovery verification
→ operation stabilization
→ handover closure
```

H5는 five Gate receipt를 read-only predecessor로 검증한다. Gate를 H5에서
처음 닫거나 H4 completion을 기다린 뒤 Gate를 수행하지 않는다. 현재
production `0`, release `NOT_ELIGIBLE`, project `NOT_COMPLETE`다.

## 18. 단계별 stop rule과 negative fixture

### 18.1 Roadmap, G0와 P0

- future session의 exact `DocumentationScopeProvenanceReceipt`가 없거나
  path/schema/publisher/session/deadline이 틀리면 review/G0/P0/successor
  durable write와 G0 probe 모두 `NOT_RUN`
- R005 formal/skeptical ROADMAP_REVIEW가 same exact SHA, common identity,
  findings `0/0/0`이 아니면 G0 spec 작성 금지
- G0 spec 두 review가 same spec SHA findings-zero가 아니면 G0 금지
- G0 `write_set=[]`; durable output 하나라도 있으면 FAIL
- G0 before/after drift, freshness 초과 또는 P0 recheck mismatch면 G0부터
  재시작
- P0 freeze 전 successor 작성 금지
- successor freeze 전 G1-SPEC 작성 금지

### 18.2 H1

- §7 literal allowlist 밖 write 금지; allowlist 자체는 authority가 아님
- G1~G5 SPEC과 EVIDENCE spec/result timing을 섞지 않음
- pre-authority successor review와 bridge review pair 전 user 질문 금지
- governing decision/consume 없이 V1 금지
- actual Stage-C/checkpoint/canonical/product/live root write 금지
- owner 중복, milestone mismatch, graph cycle, alias/glob, missing evidence면
  downstream `NOT_RUN`
- retry/subject change에 stale authority/evidence/review 재사용 금지

### 18.3 H2~H5

- exact predecessor PASS와 reviewed fresh one-use write set 안에서만 write
- R007/R001/R002/R003/R004/r021 history bytes 불변
- Stage-C actual triple과 ProgressRef state 순서 위반 시 즉시 중단
- H4 Gate `must_close_before` 위반, waived Gate, invalid N/A를 actual/formal
  credit으로 승격 금지
- 공식 credit은 해당 independent gate 뒤에만 허용

### 18.4 mandatory negative fixtures

```text
predecessor:
  missing/extra/duplicate, NOT_RUN/FAIL, wrong subject/attempt/lineage
time/publication:
  reversed time, untrusted clock, execute-before-consume,
  close-before-result, deadline breach, self-receipt, wrong wrapper domain
allowlist:
  missing mandatory role, undeclared path, wrong publisher/phase/cardinality
authority:
  fabricated/self-issued response, string-only issuer, replay/spent/revoked
  nonce, stale retry, wrong bridge/review/attempt
documentation scope:
  missing origin provenance, wrong session/path/schema/publisher, expired/replay,
  roadmap/allowlist-derived authority, execution-scope escalation
review:
  missing identity, same actor, session/credential alias, author self-review,
  wrong class/subject
G0/P0:
  durable file/dir, redirect/temp/cache, snapshot drift, stale P0,
  orphan/hand-filled 627 member
row/graph:
  owner 0/2, contributor-as-owner, milestone missing/duplicate/reversed,
  edge missing/extra/cycle, expansion count/digest mismatch,
  M04 producer=checker, B16↔M04 reverse
task/debt:
  CAS 14/16 members, orphan/duplicate task, wrong gate crosswalk,
  debt ID/owner/receipt missing/duplicate
G6/G7:
  named binding missing/ambiguous/duplicate/mismatch, wrong class, G7 bypass
B01:
  signature-in-payload, self-hash, wrapper-before-payload
B09:
  wrong checkpoint/root/triple, mixed manifest, exact6-before-publication,
  constructive/actual cross-use
H4:
  Gate-late execution, waived=true, wrong candidate, stale/unauthorized N/A
```

### 18.5 common external stop

사람, 기기, 외부 서비스, secret 또는 유료 자원이 필요한데 권한이 없으면
해당 lane을 미루고 독립 ready lane만 진행한다. mock/계획/내부 검사를
actual/formal/release 증거로 승격하지 않는다. blocker에는 missing input,
단일 owner와 exact resume predicate를 기록한다.

## 19. 사용자 질문 시점, 팀과 예상 범위

### 19.1 사용자에게 질문할 미래 시점

현재 요청할 실행 승인은 없다.

| 시점 | exact subject | 허용 가능한 답 |
|---|---|---|
| G5-SPEC PASS + pre-authority successor dual review + V0 bridge dual review 뒤 | request/successor/bridge/review/task-set SHA | `DENY` 또는 isolated V1 one attempt |
| ready=true 뒤 | ready/G6/G7/successor/evidence SHA와 bootstrap scope | journal bootstrap만 |
| bootstrap receipt 뒤 | Stage A subject/write set | build만 |
| candidate-bound review 뒤 | Stage B subject/commands | validation만 |
| Stage-B PASS 뒤 | Stage C transaction/recovery/root triple | apply/post-check/finalize만 |
| D/E/F/G 각각 | 해당 stage exact subject/nonce/write set | 해당 한 stage만 |
| H4 준비 뒤 | candidate/test plan/device/participant/five Gate scope | 승인된 시험/Gate만 |
| H4 completion 뒤 | H4 receipt와 release decision subject | 명시된 H5 release action만 |

### 19.2 역할과 병렬화

| 역할 | 책임 |
|---|---|
| root/merge | protected manifest, common schemas, edge manifest freeze, final merge |
| Z0/G0 lane | reviewed ephemeral probe spec와 transient observation |
| P0 lane | freshness recheck와 inventory freeze |
| P1 | physical union, scratch, projection, late-bound |
| P2 | authority lifecycle/capability/recovery/future boundary |
| P3 | graph type, M04a/B16a/M04b/B16b |
| P4 | Stage-B/runtime/full19/Git/runner/debt |
| P5 | constructive root/exact6/object/receipt fixture와 actual contract |
| V1 producer | approved isolated T01~T15 evidence |
| formal/skeptical reviewers | common identity 아래 서로 다른 actor로 반례 검수 |

충돌 없는 조사·fixture 초안만 병렬화한다. common schema, edge manifest,
successor freeze와 daylog는 root/merge 한 명이 수행한다.

### 19.3 계획용 예상

| 단계 | 성공 기준 | 예상 |
|---|---|---:|
| R005 review | same-SHA dual roadmap reviews `0/0/0` | 수시간~1일 |
| Z0/G0/P0 | reviewed spec, durable write 0, fresh P0 freeze | 수시간~1일 |
| P1~P2 | inventory/physical/authority contract draft | 2~5 작업일 |
| P3~P5/P6 | graph/raw oracle/Stage-C contract, successor freeze | 3~7 작업일 |
| G1~G5-SPEC + pre-authority reviews | sequential exact predecessor PASS | 1~3 작업일 |
| V0 decision | bridge reviews와 explicit user decision | 외부 결정 종속 |
| V1/G1E~G6/P7/G7 | 15 tasks, 29 milestones, 22+4 closure, four reviews | 3~7 작업일 이상 |
| H2/D~G | 각 별도 승인 뒤 | 환경/recovery별 재산정 |
| 제품/artifact | live frontier/dependency 기반 | 여러 작업일~수주 |
| H4/H5 | 사람/기기/외부 권한 기반 | 별도 일정 |

예상은 승인이나 deadline이 아니며 실제 P0 inventory, 환경과 외부 가용성
확인 뒤 재산정한다.

## 20. 다음 Codex용 즉시 지시문

```text
기술 인계서, rejected R007와 두 review, R001/R002/R003/R004와 각 review,
그리고 R005를 먼저 읽어라. 현재 continuation/review session의 explicit
user-request origin과 exact documentation add-only write set을
DocumentationScopeProvenanceReceipt로 검증하라. 없거나 stale/wrong이면
review/G0/P0/successor를 모두 NOT_RUN으로 두어라.

exact R005 SHA에 대한
formal/skeptical ROADMAP_REVIEW가 common identity/disjointness를 통과하고
둘 다 0/0/0일 때만 add-only G0 spec을 작성하라.

G0 spec은 exact R005와 두 actual review physical identity, §1 protected
inputs, absence/authority roles, literal verifier argv/environment를 결속하고
write_set=[]로 고정하라. 같은 G0 spec SHA에 dual G0_SPEC_REVIEW 0/0/0 뒤
G0를 durable output 없이 실행하라. transient PASS를 freshness window 안에
P0InventoryManifest가 처음 기록하고 end snapshot을 재확인해야 한다.

P0FreezeReceipt 뒤 closure contract를 작성하고 integrated successor를 먼저
freeze하라. successor receipt 뒤에만 G1-SPEC을 만들고, 각 prior PASS
result/receipt가 존재한 뒤 G2~G5 spec을 순서대로 freeze/execute하라.
각 result가 exact predecessor subject/attempt/lineage를 one-to-one
결속하는지 검사하라.

G5-SPEC 뒤 pre-authority successor dual review와 bridge dual review가
findings-zero이기 전에는 user에게 V1 승인을 질문하지 마라. explicit
immutable user response와 provenance-verified fresh decision이
ALLOW_ONE_ISOLATED_ATTEMPT일 때만 exact T01~T15를 한 번 실행하라.
execution close→finalization consume→task final receipts→final CAS
→29 milestones→22+4 completion receipts 순서를 지키고,
G1E→G2E→G3E→G4E→G5E를 post-close read-only로 순차 수행하라.

G6Disposition/G6 PASS→four class-specific P7 reviews→G7 PASS→ready 외
우회는 금지한다. ready=true도 H2 authority가 아니다. H2 이후도 각 stage
fresh approval을 받고, H4에서는 TEST_PLAN_APPROVAL 뒤 five Gate의
must_close_before를 지킨 동일 candidate 279+device/event+Gate bundle만
완료로 판정하라.
```

## 21. R003/R004 review required-correction 전수 crosswalk

아래 `CLOSED_DESIGN_IN_R005`는 R005에 실행 가능한 교정 계약이 존재한다는
roadmap 설계 판정뿐이다. R007 finding, V1 evidence, successor readiness
또는 어떤 official closure도 뜻하지 않는다.

### 21.1 formal `4 BLOCKING / 2 MAJOR`

| R003 formal finding | required correction | R005 contract | roadmap disposition |
|---|---|---|---|
| `ROADMAP-R003-BLOCKING-001` | exact predecessor result/receipt, status, subject, attempt/lineage one-to-one | §3, §6.2~§6.4, §14.1/§14.5 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-BLOCKING-002` | ten G1~G5 spec/evidence spec/raw/result/receipt allowlist | §7 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-BLOCKING-003` | named G6 disposition/successor/CAS/authority와 G7/ready equality | §14.2~§14.6 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-BLOCKING-004` | immutable user provenance와 retry마다 fresh whole one-use lineage | §13.1~§13.5 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-MAJOR-001` | owner cardinality와 exact milestone schema/count/order를 G6가 검사 | §8.1~§8.2, §12, §14.3 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-MAJOR-002` | ROADMAP/G0/BRIDGE/SUCCESSOR/EVIDENCE 공통 review identity/disjointness | §2, §13.1, §14.4 | `CLOSED_DESIGN_IN_R005` |

### 21.2 skeptical `7 BLOCKING / 7 MAJOR`

| R003 skeptical finding | required correction | R005 contract | roadmap disposition |
|---|---|---|---|
| `ROADMAP-R003-SK-BLOCKING-001` | G0 durable write `0`, transient observation, P0 first record | §3.1, §4, §5 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-SK-BLOCKING-002` | existing predecessor receipt와 trusted timeline/one-way publication | §6 전체, §14.1 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-SK-BLOCKING-003` | five SPEC와 five EVIDENCE output의 full H1 allowlist | §7 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-SK-BLOCKING-004` | spent decision retry 금지, bridge/reviews/decision/nonce/consume/close fresh | §13.2~§13.5 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-SK-BLOCKING-005` | task→gate→22 finding/4 debt exact crosswalk과 CAS 산술 | §12, §13.6~§13.7, §14.3 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-SK-BLOCKING-006` | B09 actual live-root physical publication/same-root contract | §9.2~§9.3, §10 B09, §15.1 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-SK-BLOCKING-007` | five Gate를 H4 내부 must-close-before로 이동 | §17 전체 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-SK-MAJOR-001` | ROADMAP/G0 review actor 독립성 | §2, §18.1 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-SK-MAJOR-002` | R004/R005/review 보호, G0 interval/snapshot freshness와 P0 equality | §1.1, §4.2, §5.1 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-SK-MAJOR-003` | strict P0 inventory와 P0+allowed-delta 627 derivation | §5 전체 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-SK-MAJOR-004` | 하나의 normative static graph와 signed runtime expansion | §8.3 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-SK-MAJOR-005` | B01 unsigned payload→detached signature wrapper | §6.1, §10 B01 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-SK-MAJOR-006` | M04a/M04b producer Physical과 independent checker 분리 | §8.1, §11 M04, §13 T08/T09 | `CLOSED_DESIGN_IN_R005` |
| `ROADMAP-R003-SK-MAJOR-007` | formal `PASS+APPROVED_NOT_APPLICABLE=279`와 authority receipt | §17.3 | `CLOSED_DESIGN_IN_R005` |

```text
formal required-correction rows = 6/6 mapped
skeptical required-correction rows = 14/14 mapped
mapping disposition = ROADMAP_DESIGN_ONLY
execution closure produced = 0
official credit delta = 0
```

### 21.3 R004 formal/skeptical union `10 BLOCKING / 3 MAJOR`

formal `8B/3M`은 아래 skeptical B001~B008/M001~M003과 같은 교정 축이고,
skeptical B009/B010이 독립 추가 축이다. 두 원본 report의 finding ID를
합치지 않으며 이 표의 한 row는 같은 required correction의 union 표현이다.

| R004 review finding union | required correction | R005 exact contract | roadmap disposition |
|---|---|---|---|
| `FORMAL-B001` / `SK-B001` | result count `0..15` 모든 cut에서 strict execution terminal과 CAS truth table | §6.1, §6.5, §8.3, §13.3 | `CLOSED_DESIGN_IN_R005` |
| `FORMAL-B002` / `SK-B002` | success CAS 뒤 intermediate failure의 durable prefix와 branch switch | §7, §8.3, §13.4 | `CLOSED_DESIGN_IN_R005` |
| `FORMAL-B003` / `SK-B003` | immutable ordinal revocation slots와 pre/post-consume latest observation | §7, §8.3, §13.3 | `CLOSED_DESIGN_IN_R005` |
| `FORMAL-B004` / `SK-B004` | close 뒤 missing terminal wrapper만 복구하는 bounded one-use tail | §6.1, §6.5, §7, §8.3, §13.4 | `CLOSED_DESIGN_IN_R005` |
| `FORMAL-B005` / `SK-B005` | H4 consume/result/close를 inward-only tagged schema로 분리 | §17.2 | `CLOSED_DESIGN_IN_R005` |
| `FORMAL-B006` / `SK-B006` | pre-G1 static union과 post-terminal signed runtime expansion 분리 | §6.3~§6.4, §7, §8.3, §14.1~§14.3 | `CLOSED_DESIGN_IN_R005` |
| `FORMAL-B007` / `SK-B007` | B04 exact three receipt variant bytes와 physical pair materialization | §7, §8.3, §9.1.2, §10 B04, §13.7, §14.3 | `CLOSED_DESIGN_IN_R005` |
| `FORMAL-B008` / `SK-B008` | B06 N26/T1/X1/ReviewBinding/V1 exact physical/hash contract | §7, §8.3, §9.1.1, §10 B06, §13.7, §14.3 | `CLOSED_DESIGN_IN_R005` |
| `SK-B009` | retry마다 deterministic fresh namespace/root와 disjoint literal path set | §7, §13.2, §13.5 | `CLOSED_DESIGN_IN_R005` |
| `SK-B010` | EXPIRED cause를 execution deadline으로 한정하고 later recovery ceiling과 분리 | §6.5, §13.3 | `CLOSED_DESIGN_IN_R005` |
| `FORMAL-M001` / `SK-M001` | execution terminal enum과 generic Gate status를 분리한 exact mapping | §6.2~§6.4, §13.3, §14.3 | `CLOSED_DESIGN_IN_R005` |
| `FORMAL-M002` / `SK-M002` | H2 binding payload 자체의 Stage-C consume/head/deadline lineage | §9.3 | `CLOSED_DESIGN_IN_R005` |
| `FORMAL-M003` / `SK-M003` | M02 19-role literal closed enum과 exact physical verification | §7, §8.3, §11 M02, §13.7, §14.3 | `CLOSED_DESIGN_IN_R005` |

```text
R004 formal required-correction rows = 11/11 mapped
R004 skeptical required-correction rows = 13/13 mapped
R004 union = 10 BLOCKING / 3 MAJOR mapped
R005 independent review executed = 0
R005 status = REVIEW_PENDING
mapping disposition = ROADMAP_DESIGN_ONLY
execution closure produced = 0
official credit delta = 0
```

## 22. R005 인계 완료조건

```text
R003 formal findings 4B/2M corrected = true
R003 skeptical findings 7B/7M corrected = true
R004 formal findings 8B/3M corrected in design = true
R004 skeptical findings 10B/3M corrected in design = true
R004 review union 10B/3M mapped = true
R007 mapping = 18 BLOCKING + 4 MAJOR
finding rows = 22 unique
accountable owner phase/actor cardinality = 1/1 per row
multi-owned/unowned = 0/0
expected milestone slots/unique objects = 33/29
debt rows = exact 4 unique
debt owner/verified receipts = 1 each/4

G0 durable output cardinality = 0
G0 before/after snapshot equality required = true
P0 first durable observation record = true
P0 categories and synthetic derivation specified = true
documentation scope/execution authority disjoint = true
future pre-successor write without exact documentation provenance = NOT_RUN
successor freezes before G1-SPEC = true
G1S→G5S and G1E→G5E sequential existing predecessor binding = true
Gate exact predecessor result/receipt/subject/attempt/lineage = true
Gate trusted-clock/publication DAG self-cycle = 0
H1 mandatory role allowlist missing = 0

fresh user provenance/one-use retry lineage = required
fresh attempt namespace/root per retry = required
retry literal path-set intersection = 0
V1 evidence tasks = exact 15
G1/G2/G3/G4/G5 task membership = 2/1/2/8/2
CAS task members = 15
observed result count domain = 0..15
ExecutionTerminalState = exact SUCCESS/FAILURE/CRASH/EXPIRED
execution terminal signed receipt file = exact 1
pre-close CAS state = exact ABSENT/PRESENT_UNWRAPPED/WRAPPED
EXPIRED cause = execution hard deadline only
revocation immutable ordinal slots = exact 0/1/2
revocation latest observations = pre-consume and post-consume
terminal-wrapper recovery = missing-wrapper-only, exact 0 or 1 consume
finalization prefix-failure checkpoint = branch-exclusive
row/debt publication hash cycle = 0
G6Disposition named artifact = defined
G6/G7 successor/evidence/disposition/authority binding = exact
G6/P7/G7/ready bypass = 0

ClosureDependencyEdgeManifest normative source count = 1
static manifest/runtime expansion source count = 1/1
future runtime actual in static manifest = 0
G3/G6 static/expansion named binding = exact
B16/M04 cycle = 0
B01 unsigned payload/detached wrapper = required
B04 exact files/variants = 6/3
B06 exact files/semantic domains = 10/5
M02 exact files/closed roles = 4/19
M04a/M04b producer/checker Physical = distinct
B09 actual live-root manifest/publication/ProgressRef triple = required
H2 binding schema = PAYLOAD_V2/WRAPPER_V2
H2 binding direct Stage-C consume/head/deadline = required
constructive/actual Stage-C cross-use = 0
Stage D fresh one-use approval = required

H4 five Gate internal must_close_before = required
H4 consume/result/close self-reference cycle = 0
formal PASS + APPROVED_NOT_APPLICABLE = 279
formal FAIL/NOT_RUN = 0/0
invalid N/A accepted = 0
H5 begins at release decision/eligibility/deployment = true

artifact status wording = closed-equivalent 126/257
old S1/R007/stale FP-048 execution = forbidden
current seq39/r021/r021 and execution authority ABSENT_DENY_ALL = stated
official delta = 0
R005 status = REVIEW_PENDING
formal ROADMAP_REVIEW on exact R005 SHA = REVIEW_PENDING
skeptical ROADMAP_REVIEW on exact R005 SHA = REVIEW_PENDING
R005 ROADMAP_REVIEW receipts produced = 0
```

이 완료는 R007 finding closure, successor readiness, authority,
checkpoint/canonical/product/artifact/formal/device/Gate/production/release
진척을 뜻하지 않는다.
