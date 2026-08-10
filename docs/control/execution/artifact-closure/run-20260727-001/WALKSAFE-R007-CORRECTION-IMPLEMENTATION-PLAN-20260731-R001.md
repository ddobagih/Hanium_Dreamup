# WALKSAFE R007 correction implementation plan R001

## 0. 문서 지위와 금지선

```text
artifact_class = NON_EXECUTABLE_CORRECTION_IMPLEMENTATION_PLAN
document_status = PRE_REVIEW
authority = NONE
official_progress_delta = 0
execution_authorized = false
checkpoint_mutation_authorized = false
canonical_mutation_authorized = false
product_mutation_authorized = false
release_or_production_authorized = false
```

이 문서는 rejected R006의 후속 문서를 작성할 author가 따라야 할
**구현계획**이다. 이 문서 자체는 R007 successor, authority grant, approval,
consume receipt, 실행 지시 또는 closure evidence가 아니다.

다음 행위는 이 문서로 허용되지 않는다.

1. PRE-P R007 또는 그 후속 단계를 실행하는 일
2. G0/P0, V1, H2~H5, Stage-C, checkpoint 또는 canonical root를 변경하는 일
3. request, grant, consume, lease, CAS, recovery 또는 terminal receipt를
   실제로 발행하는 일
4. product/source/test/runtime 파일을 변경하는 일
5. R006이나 그 독립 review의 exact bytes를 수정·대체·삭제하는 일
6. 독립 review 전 R007에 `ACCEPTED`, `CLOSED` 또는 findings-zero를 쓰는 일

R007 author는 기존 파일을 고치는 방식이 아니라 새 successor를 add-only로
작성해야 한다. R006과 두 review는 `REJECTED_HISTORY`로 유지한다.

## 1. exact source identity와 검수 ceiling

이 계획의 유일한 source set은 다음 세 regular file의 exact bytes다.

| Source ID | 파일 | SHA-256 | bytes | lines | disposition |
|---|---|---|---:|---:|---|
| `SRC-R006` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006.md` | `0795cff0703b85469b61f454cbfefd3d6351b94900aaa49025edca3231d35c0a` | 306496 | 6670 | `REJECTED_HISTORY` |
| `SRC-FORMAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-review-r001.md` | `ced8c9319996805ebcbb691dfa49cf8d5c6e38283c0f56254a1554952be2f374` | 20542 | 412 | `FAIL`, `6/0/0` |
| `SRC-SKEPTICAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-skeptical-review-r001.md` | `a4a3873589192fc09e0a6138aa1d76d0bb7aee1817f4655f2fabb88180332b2c` | 24902 | 465 | `FAIL`, `18/4/0` |

상대 경로 기준점은 다음과 같다.

```text
docs/control/execution/artifact-closure/run-20260727-001/
```

R007은 source identity 표를 그대로 inward-carry하고 다음을 명시해야 한다.

```text
R006 executable authority = NONE
R006 official progress delta = 0
R006 checkpoint/canonical/product mutation authorized = false
R006 formal review verdict = FAIL
R006 skeptical review verdict = FAIL
R007 initial status = PRE_REVIEW
```

이 계획이 정규화한 finding union은 다음과 같다.

```text
formal blocking = 6
skeptical blocking = 18
skeptical major = 4
merged blocker overlaps = 4
canonical union = 20 BLOCKING + 4 MAJOR + 0 MINOR
```

병합한 네 cluster는 다음과 같다.

```text
FORMAL-B003 + SK-B012 = PostG7 finalization authority
FORMAL-B004 + SK-B005 = signed CRASH source
FORMAL-B005 included by SK-B009 = wrapper publication totality
FORMAL-B006 + SK-B010 = 15-result CAS/GT contradiction
```

Formal B005는 Skeptical B009의 세 결함 중 late revoke 선형화 결함을
포함한다. 따라서 subsystem-totality 기준 canonical row는 하나지만,
R007 acceptance predicate는 outbox crash, late revoke, expiry settlement의
세 하위 조건을 각각 검사해야 한다.

## 2. 작성 목표와 범위

R007 author의 목표는 다음과 같다.

1. 아래 canonical 24개 finding을 하나도 누락하지 않는다.
2. 독립 four-key CAS를 부분 보수하지 않고 attempt당 단일
   `AttemptAuthorityAggregateV2`로 권위 모델을 통일한다.
3. constructor, authority context, deadline, event, transition, settlement와
   terminal seal을 같은 add-only lineage로 닫는다.
4. B06, B04, M02, H2와 H4를 strict schema, literal path, publisher,
   Physical, direct edge와 cardinality 수준으로 물리화한다.
5. static count와 runtime branch count를 서로 분리한다.
6. role/node/edge/scope digest를 결정론적 registry에서 재생성한다.
7. §18 fixture와 §22 predicate를 같은 stable ID로 결속한다.
8. exact R007 bytes를 freeze한 뒤 같은 SHA를 대상으로 formal/skeptical
   독립 review를 받는다.

이 계획은 R006 전체를 다시 서술하지 않는다. R007 author는 R006의 닫힌
계약을 유지하되, 이 계획에 열거한 affected section과 schema/DAG/count를
add-only successor에서 교정해야 한다.

## 3. canonical 24-row correction ledger

Canonical ID는 R007 author가 finding completion, fixture와 §22에서 공통으로
사용해야 한다. source ID는 원 review의 exact heading을 가리킨다.

| Canonical ID | exact source finding | root cause | primary batch | R006 affected surface |
|---|---|---|---|---|
| `R007-B001` | `R006-SK-BLOCKING-001` | G0 freshness digest self-preimage | `P1` | §5.1, constructor/P0, §18, §22 |
| `R007-B002` | `R006-SK-BLOCKING-002` | DENY·미실행 attempt seal 부재 | `P2` | §7.1, §13.2, §13.8, namespace DAG, §18, §22 |
| `R007-B003` | `R006-SK-BLOCKING-003` | four-role CAS genesis 부재 | `P2` | §7.1, §13.8, authority schemas/DAG, §18, §22 |
| `R007-B004` | `R006-SK-BLOCKING-004` | deadline이 atomic CAS 밖에 있음 | `P2` | §13.8 consume/selector/deadline, §18, §22 |
| `R007-B005` | `R006-FORMAL-BLOCKING-001` | finalization grant→execution consume 결속 누락 | `P3` | §8.3, §13.2~§13.4, execution consume, §18, §22 |
| `R007-B006` | `R006-FORMAL-BLOCKING-002` | wrapper grant→finalization consume 결속 누락 | `P3` | §8.3, §13.3~§13.4, finalization consume, §18, §22 |
| `R007-B007` | `R006-FORMAL-BLOCKING-004`; `R006-SK-BLOCKING-005` | CRASH signed source 부재 | `P3` | §8.3 ET003, §13.8 selector/source, §18, §22 |
| `R007-B008` | `R006-SK-BLOCKING-006` | non-wrapper PRE_CONSUME revoke terminal outcome 부재 | `P2` | §7.1, §13.8 revoke/disposition, §18, §22 |
| `R007-B009` | `R006-SK-BLOCKING-007` | wrapper pre-close revoke가 key를 소진 | `P3` | §13.3, §13.8 wrapper revoke/FSM, §18, §22 |
| `R007-B010` | `R006-SK-BLOCKING-008` | consumed recovery/finalization pre-close crash 미정산 | `P4` | §13.3~§13.4, §13.8 outbox/deadline, §18, §22 |
| `R007-B011` | `R006-FORMAL-BLOCKING-005`; `R006-SK-BLOCKING-009` | wrapper output/revoke/expiry totality 부재 | `P4` | §13.3, §13.8 FSM/outbox/settlement, §18, §22 |
| `R007-B012` | `R006-FORMAL-BLOCKING-006`; `R006-SK-BLOCKING-010` | 15-result CAS의 GT branch 모순 | `P5` | §8.3 GT/EO/ET, §13.8.3, §18, §22 |
| `R007-B013` | `R006-SK-BLOCKING-011` | expansion lineage와 direct edge 누락 | `P5` | §8.3 expansion/EP/EXC/EXF, §13.4, §18, §22 |
| `R007-B014` | `R006-FORMAL-BLOCKING-003`; `R006-SK-BLOCKING-012` | PostG7 pair가 finalization scope에 없음 | `P5` | §8.3, §13.4, §14.6~§14.7, §18, §22 |
| `R007-B015` | `R006-SK-BLOCKING-013` | B04 issuance/application 계약 구성 불가 | `P6` | §8.3, §9.1.2, §9.2~§9.3, §18, §22 |
| `R007-B016` | `R006-SK-BLOCKING-014` | B06 semantic/signature/DAG 충돌 | `P6` | §8.3, §9.1.1, §18, §22 |
| `R007-B017` | `R006-SK-BLOCKING-015` | H2 exact6/current authority closure 부재 | `P6` | §8.3, §9.2~§9.3, §13.8, §18, §22 |
| `R007-B018` | `R006-SK-BLOCKING-016` | H4 scope/run authority chain 구성 불가 | `P7` | §8.3, §17, §18, §22 |
| `R007-B019` | `R006-SK-BLOCKING-017` | H4 five-Gate가 count-only | `P7` | §8.3, §17, §18, §22 |
| `R007-B020` | `R006-SK-BLOCKING-018` | wrapper zero-tail과 root seal final write 충돌 | `P4` | §7.1, §8.3 WR009, §13.3, §18, §22 |
| `R007-M001` | `R006-SK-MAJOR-001` | full plane binding이 strict schema에 없음 | `P1` | §7.1, §13.2~§13.4, §13.8, §18, §22 |
| `R007-M002` | `R006-SK-MAJOR-002` | attempt constructor encoding 불명확 | `P1` | §7, §7.1, constructor fixtures, §22 |
| `R007-M003` | `R006-SK-MAJOR-003` | M02 registry/type/edge 불일치 | `P6` | §8.3, §9.3, §11, §18, §22 |
| `R007-M004` | `R006-SK-MAJOR-004` | G3 timing prose가 future nodes를 참조 | `P5` | §6.4, §8.3, §14.1, §14.6~§14.7, §18, §22 |

Ledger invariant:

```text
canonical BLOCKING IDs = exact 20
canonical MAJOR IDs = exact 4
canonical MINOR IDs = exact 0
duplicate canonical IDs = 0
source BLOCKING findings covered = exact 24 references before dedupe
source MAJOR findings covered = exact 4 references
unmapped source finding = 0
```

기존 R005 `22+4` closure topology와 위 R006-review `20+4` correction ledger는
서로 다른 namespace다. 기존 `GC001..GC022` 또는 다른 historical edge ID를
새 24-row ledger에 재사용하지 않는다.

## 4. 공통 authority architecture

### 4.1 단일 aggregate key

R007은 R006의 독립 role key와 새 aggregate를 병행해서는 안 된다. authority
state의 유일한 정본은 다음 key 하나다.

```text
AttemptAuthorityAggregateV2.key = RFC8785_JCS({
  "successor_revision_id": <exact string>,
  "attempt_namespace_id": <lowercase sha256>,
  "attempt_id": <lowercase sha256>
})
```

Role slot은 다음 ordered exact four다.

```text
0 EXECUTION
1 CLOSE_RECOVERY
2 POST_CLOSE_FINALIZATION
3 TERMINAL_WRAPPER_RECOVERY
```

각 role row의 state enum은 다음 exact closed set이다.

```text
RoleSlotStateV2 =
  UNINITIALIZED
  | UNSPENT_UNREVOKED
  | PRE_REVOKED
  | PRE_CLOSE_BLOCK_PENDING
  | CONSUMED_OPEN
  | OUTCOME_SELECTED
  | SETTLED
  | NOT_NEEDED
```

금지 invariant:

```text
legacy independent role key accepted = 0
legacy-key/aggregate mixed transition accepted = 0
aggregate role row count other than 4 accepted = 0
one role in two live states accepted = 0
OUTCOME_SELECTED 뒤 state-changing consume/revoke/expire accepted = 0
```

### 4.2 lifecycle와 aggregate activation

Namespace commit 직후 request outcome보다 먼저 lifecycle key를 exact 한 번
만든다.

```text
NAMESPACE_COMMITTED
├─ DENIED
├─ REQUEST_EXPIRED
├─ ABANDONED
└─ ALLOW
   → REQUIRED_GRANTS_FROZEN
   → AUTHORITY_ACTIVATED(role rows=4)
```

권고 lifecycle edge namespace:

```text
NL001 NAMESPACE-HEAD-OBSERVATION → LIFECYCLE-GENESIS
NL002 DENY-DECISION → NONEXECUTION-TERMINAL-SELECTION
NL003 REQUEST-DEADLINE → REQUEST-EXPIRED-SELECTION
NL004 ABANDON-RECEIPT → ABANDONED-SELECTION
NL005 selected nonexecution outcome → ATTEMPT-TERMINAL-SEAL
NL006 ATTEMPT-TERMINAL-SEAL → NEXT-NAMESPACE-MEMBER
```

`DENIED`, `REQUEST_EXPIRED`, pre-dispatch `ABANDONED`는 data-plane write가
exact 0이다. Execution pre-consume revoke는
`ABANDONED(reason=EXECUTION_PRE_REVOKED)`로 seal한다. Dispatch 뒤의 failure,
recovery unavailable 또는 finalization unavailable은 `EXECUTED` seal의
closed failure disposition이다.

### 4.3 AuthorityContextV2

모든 request/response/decision, 세 grant, activation, consume, revoke,
deadline, cause selection, terminalization transaction과 receipt는 다음
객체를 digest가 아니라 full embedded object로 직접 가진다.

```text
AuthorityContextV2 = {
  successor_revision_id,
  tested_successor_sha,

  attempt_namespace_id,
  attempt_id,
  attempt_ordinal,
  attempt_namespace_constructor_digest,

  namespace_member_payload_sha,
  namespace_member_wrapper_sha,
  namespace_append_receipt_sha,
  namespace_head_receipt_sha,
  namespace_head_observation_sha,
  namespace_cas_token,

  AuthorityConcreteAllowlistBinding {
    ordered_entries[],
    entry_count,
    ordered_entries_digest
  },

  AttemptPlaneSetBindingV2 {
    ordered_control_entries[],
    control_count,
    control_digest,
    ordered_data_entries[],
    data_count,
    data_digest,
    ordered_union_entries[],
    union_count,
    union_digest,
    ordered_intersection_entries[],
    intersection_count,
    intersection_digest
  },

  authority_aggregate_key,
  lifecycle_genesis_receipt_sha,
  aggregate_activation_receipt_sha,

  granting_artifact_kind,
  granting_artifact_payload_sha,
  granting_artifact_wrapper_or_decision_sha,
  role_scope_digest,

  RoleDeadlineBindingV2 {
    operation_not_after,
    settlement_not_after,
    trusted_clock_source_id,
    trusted_clock_source_physical,
    trusted_clock_correlation_sha
  }
}
```

Plane intersection이 비어 있으면 다음 exact pair를 사용한다.

```text
intersection_count = 0
intersection_digest = SHA256(RFC8785_JCS([]))
```

request/response/decision/grant/consume 사이 identity, ordered entries,
counts와 digests는 byte-equal이어야 한다. 후행 prose는 strict field
목록을 암묵적으로 확장할 수 없다.

### 4.4 constructor와 G0 digest separation

`AttemptNamespaceConstructorV2`는 domain-tagged RFC 8785 JCS object다.

```text
constructor_domain = "WS-WALKSAFE-R007-ATTEMPT-NAMESPACE-CONSTRUCTOR-V2"
attempt_namespace_id =
  SHA256(
    ASCII(constructor_domain) || 0x00 ||
    RFC8785_JCS(exact_constructor_object)
  )
```

Author가 R007에 고정할 encoding:

```text
ordinal/time = canonical decimal string
SHA/nonces = lowercase hexadecimal string
predecessor absent = tagged literal "GENESIS_NA"
null/empty string/omitted field substitution = forbidden
raw bytes와 hex string implicit cast = forbidden
path normalization과 locale-dependent serialization = forbidden
```

G0 fields:

```text
g0_after_protected_snapshot_digest
g0_freshness_observation_payload_sha
g0_freshness_observation_wrapper_sha
```

P0는 snapshot digest를 snapshot source와, payload/wrapper SHA를 각각의
artifact bytes와 비교한다. snapshot digest와 observation object SHA 사이
equality는 금지한다.

### 4.5 named authority artifacts

R007은 최소 다음 strict artifact를 정의해야 한다.

| Artifact | cardinality | 최소 역할 |
|---|---:|---|
| `G0ProtectedSnapshotObservationPayloadV2/WrapperV2` | pair `0/2` by branch | snapshot digest와 observation artifact SHA 분리 |
| `AttemptLifecycleGenesisReceiptV2` | committed attempt당 1 | namespace 직후 lifecycle state/token 생성 |
| `AuthorityAggregateActivationReceiptV2` | ALLOW당 1 | exact four role row atomic activation |
| `AuthorityAggregateTransitionReceiptV2` | accepted transition당 1 | pre/post token/state, CAS time, deadline, event, outcome/outbox |
| `ExecutionCrashObservationReceiptV2` | attempt당 `0/1` | signed process-loss source |
| `RolePreConsumeRevocationDispositionPayloadV2/WrapperV2` | selected non-wrapper role당 pair 1 | pre-revoke closed outcome |
| `TerminalWrapperPreCloseGuardReceiptV2` | pending cause당 1 | partial close 전 revoke/expiry 보존 |
| `SettlementObligationV2` | accepted consume/selection당 1 | exact output allowlist와 later deadline |
| `TerminalOutboxBatchV2` | selected outcome당 1 | immutable ordered outputs와 content key |
| `OutboxSettlementAttestationV2` | settled batch당 1 | reopened bytes/Physical와 aggregate state 증명 |
| `AttemptTerminalSealReceiptV2` | committed attempt당 1 | exact lifecycle terminal union |

`OutboxSettlementAttestationV2`는 terminal output 뒤 별도 project file로
발행하지 않는다. CAS service가 서명한 bytes/SHA를
`AttemptTerminalSealReceiptV2` 안에 embed한다.

권고 path macro는 다음과 같다.

```text
<AUTH_AGG_ROOT> := <ATTEMPT_ROOT>/authority-aggregate-v2

<AUTH_AGG_ROOT>/lifecycle/genesis-receipt.json
<AUTH_AGG_ROOT>/activation/receipt.json
<AUTH_AGG_ROOT>/transitions/<literal-transition-id>.receipt.json
<AUTH_AGG_ROOT>/events/execution-crash-observation.receipt.json
<AUTH_AGG_ROOT>/dispositions/<literal-role-id>/
  pre-consume-revocation.payload.json
  pre-consume-revocation.signature.json
<AUTH_AGG_ROOT>/wrapper/pre-close-guard.receipt.json
<AUTH_AGG_ROOT>/terminal/attempt-terminal-seal.receipt.json
```

위 `<literal-*>`는 plan macro다. Frozen R007의 literal path registry에는
angle bracket나 wildcard를 남기지 않고 branch별 exact entry로 펼쳐야 한다.

### 4.6 pre-frozen grant order와 direct edges

ALLOW branch의 유일한 권위 순서는 다음이다.

```text
ALLOW decision
→ watchdog/heartbeat spec
→ close-recovery grant pair
→ post-close-finalization grant pair
→ terminal-wrapper-recovery grant pair
→ AuthorityAggregateActivationReceiptV2(role rows=4)
→ execution consume
```

필수 direct edge:

```text
AG001 FINALIZATION-GRANT-WRAPPER
  → EXECUTION-CONSUME-INTENT

AG002 TERMINAL-WRAPPER-RECOVERY-GRANT-WRAPPER
  → FINALIZATION-CONSUME-INTENT

AG003 WATCHDOG-WRAPPER
  → EXECUTION-CONSUME-INTENT

AG004 CLOSE-RECOVERY-GRANT-WRAPPER
  → EXECUTION-CONSUME-INTENT

AG005 DECISION + WATCHDOG + THREE-GRANT-WRAPPERS
  → AUTHORITY-AGGREGATE-ACTIVATION

AG006 AUTHORITY-AGGREGATE-ACTIVATION
  → EVERY-CONSUME-REVOKE-DEADLINE-TERMINALIZATION-INTENT
```

`AG006`은 frozen R007 graph에서 하나의 fan-out 약식 edge로 남겨서는 안 된다.
각 literal intent target으로 direct edge를 펼치고 count/digest를 registry에서
계산한다.

Activation role row는 최소 다음을 가진다.

```text
role_id
grant_or_decision_payload_sha
grant_or_decision_wrapper_sha_or_NA
AuthorityContextV2
role_scope_digest
operation_not_after
settlement_not_after
initial_state = UNSPENT_UNREVOKED | NOT_NEEDED
initial_role_token
```

ALLOW attempt의 activation/role-row cardinality는 `1/4`다.
DENY와 request-expired branch는 `0/0`이다.

### 4.7 atomic deadline와 CRASH source

모든 authority CAS는 trusted `linearized_at`을 transaction 안에서 비교한다.

```text
normal consume 또는 NORMAL terminal selection:
  linearized_at < operation_not_after

deadline outcome materialization:
  operation_not_after <= linearized_at < settlement_not_after

outbox settlement:
  selected obligation exists
  and linearized_at < settlement_not_after
```

NORMAL은 execution deadline과 같거나 늦으면 candidate가 아니다. Pre-check
결과는 CAS predicate를 대체하지 못한다.

Deadline order:

```text
execution.operation_not_after
< execution.settlement_not_after
<= close_recovery.operation_not_after
< close_recovery.settlement_not_after
<= post_close_finalization.operation_not_after
< post_close_finalization.settlement_not_after
<= terminal_wrapper_recovery.operation_not_after
< terminal_wrapper_recovery.settlement_not_after
```

`ExecutionCrashObservationReceiptV2` strict body:

```text
dispatch_lease_id
process_instance_id
watchdog_payload_sha
watchdog_wrapper_sha
watchdog_physical
heartbeat_head_sha
heartbeat_store_token
last_heartbeat_at
lost_process_effective_at
observed_at
trusted_clock_source_id
trusted_clock_correlation_sha
source_event_digest
publisher_actor_id
publisher_physical
signature_domain
detached_signature
AuthorityContextV2
```

필수 direct edge:

```text
CR001 HEARTBEAT/WATCHDOG → EXECUTION-CRASH-OBSERVATION
CR002 EXECUTION-CRASH-OBSERVATION → EXECUTION-TERMINAL-SELECTION-CAS
```

CRASH source role 정의는 exact 1이고 runtime cardinality는 `0/1`이다.
CRASH가 선택되면 valid source instance는 exact 1이다.

### 4.8 pre-consume revoke

Non-wrapper role:

| role | selected pre-revoke disposition |
|---|---|
| `EXECUTION` | no-dispatch `ABANDONED(reason=EXECUTION_PRE_REVOKED)` |
| `CLOSE_RECOVERY` | `RECOVERY_AUTHORITY_UNAVAILABLE` |
| `POST_CLOSE_FINALIZATION` | `FINALIZATION_AUTHORITY_UNAVAILABLE` |

각 accepted pre-revoke transaction은 state change와 disposition outbox를 같은
aggregate CAS에 저장한다. 원 consume/dispatch는 exact 0이다.

Terminal-wrapper role은 partial close 전에 key를 소진하지 않는다.

```text
UNSPENT_UNREVOKED
→ PRE_CLOSE_BLOCK_PENDING(REVOKE | EXPIRE)
→ exact partial close available
→ OUTCOME_SELECTED(REVOKED | EXPIRED)
→ SETTLED
```

Pre-close pending transition은 cause를 잃지 않지만 terminal lease, wrapper,
disposition output은 아직 만들지 않는다. Partial close가 생긴 뒤 exact
Physical equality를 확인하고 outcome을 고른다.

### 4.9 transactional outbox

Outbox slot은 다음 ordered exact eight다.

```text
0 EXECUTION_DISPATCH
1 EXECUTION_TERMINAL
2 EXECUTION_PRE_REVOKE
3 CLOSE_RECOVERY_PRE_REVOKE
4 FINALIZATION_PRE_REVOKE
5 FINALIZATION_WORK
6 FINALIZATION_TERMINAL
7 WRAPPER_TERMINAL
```

`SettlementObligationV2`:

```text
aggregate_key
aggregate_transition_sha
outbox_slot
ordered_output_entries[] {
  entry_id,
  role_id,
  literal_path,
  schema_sha,
  publisher_actor_id,
  publisher_physical_sha,
  constructor_id,
  content_sha,
  predecessor_set_digest
}
branch_id
settlement_not_after
idempotency_key
```

`TerminalOutboxBatchV2`는 같은 ordered entries와 selected terminal variant,
pre/post aggregate token, event set digest를 inward-carry한다.

Publisher idempotency key:

```text
(aggregate_key, outbox_slot, entry_id, content_sha)
```

Settlement rule:

1. consume CAS는 state와 `SettlementObligationV2`를 함께 영속화한다.
2. terminal-selection CAS는 exact output identity와
   `TerminalOutboxBatchV2`를 같은 transaction에 저장한다.
3. exact existing bytes와 Physical은 adopt할 수 있다.
4. 같은 path의 다른 bytes, 두 번째 physical output 또는 다른 constructor는
   거부한다.
5. operation deadline 뒤에는 이미 committed된 obligation의 exact output만
   settlement deadline 전까지 쓸 수 있다.
6. reopen equality 뒤 aggregate state를 `SETTLED`로 CAS한다.
7. signed settlement attestation은 terminal seal에 embed한다.
8. `OUTCOME_SELECTED` 뒤 revoke/expire/consume은
   `effect=NONE`, token/receipt/lease/write=`0`이다.

Close recovery는 consume과 terminal selection을 분리하지 않는다.

```text
execution role = CONSUMED_OPEN
+ close-recovery role = UNSPENT_UNREVOKED
+ selected cause/TD25 cut
→ one aggregate CAS
→ execution OUTCOME_SELECTED
+ EXECUTION_TERMINAL outbox
```

Finalization consume은 `FINALIZATION_WORK` obligation/outbox를 동시에 만든다.
Worker crash는 같은 content key로 resume한다. Success 완료 또는 bounded
prefix/deadline failure는 `FINALIZATION_TERMINAL` outbox를 고정한다.

Wrapper recovery는 중간 `CONSUMED_OPEN`을 만들지 않는다.

```text
partial close exact binding
+ consume/revoke/expire contender
→ one aggregate CAS
→ WRAPPER_PUBLICATION_SELECTED
  | REVOKED_DISPOSITION_SELECTED
  | EXPIRED_DISPOSITION_SELECTED
→ immutable WRAPPER_TERMINAL outbox
```

## 5. graph, branch와 terminal-tail correction

### 5.1 15-result CAS/GT truth table

`GT016..GT030`은 terminal kind가 아니라 CAS producer input profile에
따른다. `GT031..GT045`는 NORMAL selector의 direct result inputs다.

| runtime cut | `GT016..GT030` | `GT031..GT045` | `EO001..EO015` |
|---|---:|---:|---:|
| `<15`, CAS absent recovery | 0 | 0 | observed `k`, `0..14` |
| `15`, CAS absent recovery | 0 | 0 | 15 |
| `15`, CAS present-unwrapped recovery | 15 | 0 | 15 |
| `15`, CAS present-unwrapped NORMAL | 15 | 15 | 0 |
| `15`, CAS already wrapped adoption | stored producer profile 15 | stored profile과 exact equality | stored profile과 exact equality |

Additional invariant:

```text
result count <15 and CAS present accepted = 0
result count >15 accepted = 0
CAS state ambiguity accepted = 0
CAS wrapper/terminal mismatch accepted = 0
ET004 selected terminal output cardinality = 1
ET005 selected terminal→finalization cardinality = 1
```

`ET001..ET003`은 실제 candidate source가 존재하는 branch에만 materialize한다.
Recovery라는 이유로 `GT016..GT030`을 blanket 0으로 만들지 않는다.

### 5.2 expansion lineage

Expansion payload는 기존 필드 외에 다음을 직접 가진다.

```text
finalization_grant_payload_sha
finalization_grant_wrapper_sha
finalization_consume_or_activation_transaction_sha
finalization_work_outbox_digest
finalization_lineage_prefix_digest
```

필수 edge:

```text
EP005 FINALIZATION-WORK-OUTBOX → EXPANSION-PAYLOAD
EXC001..EXC015 EXPANSION-WRAPPER → T01..T15-COMPLETION
EXF001 EXPANSION-WRAPPER → FINALIZATION-PREFIX-FAILURE-CHECKPOINT
```

기존 `EP001..EP005`를 유지한다면 expansion-local edge namespace는
`EP001..EP005 + EXC001..EXC015 + EXF001`이다. 기존 이름을
`EP006..EP021`로 연속 재명명하지 않아도 되지만 두 방식을 섞어 duplicate
edge를 만들면 안 된다.

Cardinality:

```text
finalization consumed branch EXC = 15
prefix failure branch EXF = 1
success 또는 early pre-prefix failure EXF = 0
```

### 5.3 PostG7 exact-two authority

Finalization grant의 `SUCCESS_SUFFIX_CANDIDATE` write set에 다음 two roles를
추가한다.

```text
ready/post-g7-full-projection-check.payload.json
ready/post-g7-full-projection-check.signature.json
```

두 role의 exact path/schema/publisher/Physical/cardinality는
request/response/decision/grant/consume scope에 모두 있어야 한다.

```text
success branch PostG7 pair = 2
failure branch PostG7 pair = 0
allowlist-only authority substitution = 0
scope digest/branch write count mismatch accepted = 0
```

`GE039..GE041`과 finalization close는 동일 payload/wrapper SHA를
predecessor로 검증한다.

### 5.4 G3 timing profile

두 profile만 허용한다.

```text
G3 profile = CURRENT_RUNTIME_EXPANSION_VALIDATION_V2
post-G7 profile = THROUGH_READY_EVALUATION_PAYLOAD_V2
```

G3는 current expansion의 subset/cardinality/order/digest만 재계산한다.
G4/G5/G6/P7/G7/Ready 또는 full projected equality를 참조하지 않는다.
Through-ready full equality는 PostG7 pair에서만 검사한다.

### 5.5 terminal tail

Terminal wrapper 또는 disposition은 마지막 **data/finalization-plane**
write다. 그 뒤 허용되는 project control write는
`AttemptTerminalSealReceiptV2` exact 1뿐이다.

```text
functional terminal output = 1
functional terminal 뒤 data/finalization write = 0
functional terminal 뒤 allowed project control set =
  {ATTEMPT_TERMINAL_SEAL}
AttemptTerminalSeal final write = 1
seal 뒤 project write = 0
```

## 6. B06 constructive H1 correction

### 6.1 exact paths와 role count

Root:

```text
<B06_ROOT> := <ATTEMPT_ROOT>/v1/stage-c/b06
```

Frozen R007은 다음 five detached pairs를 exact literal path로 펼친다.

```text
<B06_ROOT>/n26.payload.json
<B06_ROOT>/n26.signature.json
<B06_ROOT>/t1.payload.json
<B06_ROOT>/t1.signature.json
<B06_ROOT>/x1.payload.json
<B06_ROOT>/x1.signature.json
<B06_ROOT>/stage-c-review-binding.payload.json
<B06_ROOT>/stage-c-review-binding.signature.json
<B06_ROOT>/v1-binding.payload.json
<B06_ROOT>/v1-binding.signature.json
```

```text
B06 output roles = 10
B06 payload/wrapper pairs = 5
B06 direct edges = 26
```

### 6.2 strict semantic contract

`N26`:

- protected ordered exact 26 target rows만 담는다.
- count/order/schema/set digest를 담는다.
- protected row에 없던 `target_schema_sha` 또는 `target_value_sha`를
  임의 추가하지 않는다.

`T1` direct inputs:

```text
N26 payload
N26 wrapper
candidate target map
aggregate equality receipt
```

`X1` direct inputs:

```text
N26 payload/wrapper
T1 payload/wrapper
U1 DirPhysical spec
M03 recovery contract
exact6 contract
ApplicationReceiptTargetSpec
```

`StageCReviewBinding` direct inputs:

```text
X1 payload/wrapper
resolved subject
formal review receipt
skeptical review receipt
review-pair binding receipt
```

`V1` semantic value:

```text
SHA256(
  ASCII("R007_RESOLVED_REVIEW_BINDING_V1") || 0x00 ||
  RFC8785_JCS(StageCReviewBindingPayload)
)
```

V1 semantic preimage에는 wrapper SHA나 payload/wrapper Physical을 넣지 않는다.
그 값들은 V1 strict body의 provenance field로 semantic value 바깥에 둔다.

모든 pair는 하나의 detached-signature profile만 사용한다.

```text
signature input =
  exact domain || 0x00 || full RFC8785_JCS(payload)
partial-field signing digest = forbidden
```

### 6.3 exact edge derivation

| Edge group | count | derivation |
|---|---:|---|
| each own payload→wrapper | 5 | five pairs |
| protected exact26 source→N26 | 1 | protected N26 table source |
| N26 pair/map/equality→T1 | 4 | `2+1+1` |
| N26 pair/T1 pair/U1/M03/exact6/TargetSpec→X1 | 8 | `2+2+1+1+1+1` |
| X1 pair/subject/formal/skeptical/review-pair→Review | 6 | `2+1+1+1+1` |
| Review semantic/wrapper validity→V1 | 2 | semantic payload와 valid wrapper |
| **total** | **26** | no alias/duplicate |

`B06X001..010=10`이라는 R006 stale count는 폐기한다. R007 graph registry에서
위 26개 edge를 각각 stable ID와 literal source/target으로 물리화한다.

## 7. B04 H1과 actual application correction

### 7.1 H1 exact roles

Root:

```text
<B04_ROOT> := <ATTEMPT_ROOT>/v1/stage-c/b04
```

H1 output은 다음 three detached pairs다.

```text
<B04_ROOT>/c-recovery-extension-contract.payload.json
<B04_ROOT>/c-recovery-extension-contract.signature.json
<B04_ROOT>/constructive-fixture.payload.json
<B04_ROOT>/constructive-fixture.signature.json
<B04_ROOT>/extension-verification.payload.json
<B04_ROOT>/extension-verification.signature.json
```

```text
B04 H1 output roles = 6
B04 H1 direct edges = 22
```

Exact derivation:

| Edge group | count |
|---|---:|
| own payload→wrapper | 3 |
| protected application schema + neutral TargetSpec + M03→contract | 3 |
| contract wrapper→fixture | 1 |
| B06 five pairs→fixture | 10 |
| contract pair + fixture pair→verification | 4 |
| verification wrapper→T15 | 1 |
| **total** | **22** |

`B04X001..010=10`은 폐기한다.

### 7.2 issuance와 consume을 future receipt에서 분리

Actual authority journal roots:

```text
<AUTHORITY_ROOT>/journal/attempts/stage-c/<LITERAL_ATTEMPT_KEY>/
<AUTHORITY_ROOT>/journal/attempts/recovery-stage-c/<LITERAL_ATTEMPT_KEY>/
```

`StageCAuthorityIssuanceReceiptV2` 또는 recovery tagged variant는 issuance
시점에 이미 존재하는 값만 가진다.

```text
existing B06 payload/wrapper refs
resolved subject/spec
ordered capability set + digest
current predecessor
path-only ApplicationReceiptTargetSpec
issuer actor/Physical/signature
authority aggregate key/token
operation/settlement deadline
full AuthorityContextV2
```

다음 future value는 issuance body에서 금지한다.

```text
future consume receipt SHA
future POSTCHECK receipt SHA
future application receipt SHA
future transition token
```

후속 consume/postcheck/application이 issuance receipt를 inward-reference한다.

Recovery consume은 다음 exact XOR를 선택한다.

```text
RECOVERY_EXACT6_SUFFIX(k), k ∈ {0,1,2,3,4,5}
XOR
RECOVERY_APPLICATION_FINALIZATION
```

Suffix branch는 application finalization을 금지하고, application-finalization
branch는 exact6 재실행을 금지한다.

### 7.3 canonical application

Canonical application role은 하나다.

```text
<GOAL_GATE_PRE_P_VALIDATION_CONVERGENCE_APPLIED_ROOT>/
  application-receipt.json
```

R007은 위 macro를 actual goal-gate literal root로 한 번 정의한 뒤 path
registry에서 완전히 펼쳐야 한다. `<h2_root>/stage-c/application-receipt.json`
복제본이나 별도 B04 copy를 만들지 않는다.

Application strict body:

```text
protected application fields
exact LIVE_ROOT_TRIPLE members and digest
issuance receipt SHA
effective consume receipt SHA
POSTCHECK_PASSED receipt SHA
finalizing executor actor/Physical
terminal claim
full AuthorityContextV2
full-payload detached signature input
```

금지:

```text
H2 application copy = 0
self SHA reference = 0
future receipt reference = 0
LIVE_ROOT_TRIPLE member inference = 0
```

Actual direct-edge counts:

```text
application original inbound = 48
LIVE_ROOT_TRIPLE direct members = 3
application total inbound = 51

ordinary issuance lineage = 14
ordinary consume = 1
application inbound = 51
ordinary terminal tail = 2
ordinary actual edge total = 14 + 1 + 51 + 2 = 68
```

Recovery actual total은 선택된 `k`와 XOR variant에서 materialize한 literal
suffix edge set으로 계산한다. `68`을 recovery branch에 복사하지 않는다.

## 8. H2 exact6/current-authority correction

### 8.1 root와 49-role file registry

```text
<H2_ROOT> :=
  <AUTHORITY_ROOT>/journal/transactions/<H2_TRANSACTION_ID>
```

File role registry:

| Group | count |
|---|---:|
| H2 literal-root binding payload/wrapper | 2 |
| fixed non-exact6 Stage-C roles | 10 |
| actual exact6 input | 1 |
| six invocation groups × six files | 36 |
| **file role total** | **49** |

Fixed non-exact6 roles exact 10:

```text
checkpoint-durability-receipt.json
live-root-manifest.payload.json
live-root-manifest.signature.json
live-root-physical-publication-receipt.json
live-root-published.progress.json
live-root-integration-receipt.json
postcheck-passed.progress.json
application-receipt reference/binding
finalized.progress.json
closed-success-receipt.json
```

`application-receipt`은 §7.3의 canonical application을 참조하는 binding
role이며 별도 application bytes를 복제하지 않는다. Role registry와 physical
write registry는 이 alias를 구분해야 한다.

Six invocation IDs:

```text
001-live-exact26
002-activation-seal
003-continuation-quick
004-goal-quick
005-routing-validate
006-control-and-state
```

각 invocation은 다음 six files를 가진다.

```text
intent.json
stdout.raw
stderr.raw
access-trace.raw
access-trace.json
result.json
```

Command cardinality:

```text
[1, 1, 1, 1, 1, 2]
command total = 7
```

### 8.2 raw framing

각 raw aggregate는 다음 deterministic frame을 사용한다.

```text
magic
frame_kind
member_count
for each ordered member:
  ordinal
  byte_length
  sha256
  raw payload bytes
```

`006-control-and-state`의 두 command는 별도 ordinal과 length boundary를
가진다. Concatenation만 하고 boundary를 생략하면 invalid다. `result.json`은
각 assertion을 raw bytes와 trace에서 다시 계산하며 status 문자열을
신뢰하지 않는다.

### 8.3 25 CAS guard receipts

각 protected file transition은 current authority를 다시 검사하고 CAS guard
receipt를 남긴다.

| Guarded group | count |
|---|---:|
| binding payload/wrapper | 2 |
| checkpoint durability | 1 |
| manifest payload/wrapper | 2 |
| physical publication | 1 |
| live-root published progress | 1 |
| exact6 input | 1 |
| six invocation intent/result pairs | 12 |
| integration | 1 |
| postcheck | 1 |
| canonical application transition | 1 |
| finalized | 1 |
| closed | 1 |
| **guard total** | **25** |

각 guard는 같은 transaction에서 다음을 검사한다.

```text
current aggregate token
current unrevoked authority head
authority consume Physical
role scope and full plane binding
prior transition receipt
exact write entries
event_at <= transition_hard_deadline
```

성공하면 token을 rotate하고 다음 transition용 one-use lease exact 1을
반환한다.

```text
H2 file roles = 49
H2 CAS guards = 25
H2 protected writes = 49 + 25 = 74
```

### 8.4 H2 edge derivation과 count caveat

R006의 13-role 축약 graph를 유지하지 않는다. 기계적 재계산의 seed는
다음과 같다.

```text
base expanded H2 edges with minimal application inbound 7 = 262
remove minimal application inbound = -7
add full canonical application inbound = +51
intermediate subtotal = 262 - 7 + 51 = 306
```

`306`은 최종 normative total이 아니다. 다음을 적용하기 전 중간 산술이다.

1. M02 named source edges와 triple-constructor edge를 추가한다.
2. canonical application alias가 H2 file-role registry에 있으나 physical
   application write는 하나뿐임을 반영한다.
3. 동일 `(source,target,branch predicate)` edge를 stable ID 기준으로
   unique-dedupe한다.
4. alias가 다른 source identity를 가리키면 dedupe하지 않는다.
5. 최종 edge table과 prefix subtotal에서 count/digest를 다시 계산한다.

Frozen R007은 단순히 `H2 edges=306`이라고 쓰면 안 된다.

## 9. M02 correction

M02 static role count는 exact 19다.

```text
M02 roles = 19
M02X001..M02X023 = 23
M02P001 = 1
M02 local direct edges = 24
```

`LIVE_ROOT_TRIPLE`:

```text
1 StageCLiveRootManifestPayload FilePhysical
2 StageCLiveRootPhysicalPublicationReceipt
3 LIVE_ROOT_PUBLISHED ProgressRef
```

Manifest wrapper는 triple member가 아니다.

`U1/AUX_PARENT_001`:

```text
physical_type = DirPhysical
path_type = directory
uid = 1000
gid = 1000
mode = 0775
nlink = 2
```

`FilePhysical`로 대체할 수 없다. Normative edge 이름은 `M02P001`만
사용하며 undefined `M02X-LIVE-ROOT-BINDING-PREDECESSOR`를 금지한다.

H1 static M02 registry는 future actual runtime SHA를 담지 않는다. Runtime
H2가 exact triple을 물리화한 뒤 M02 source edges가 그 세 concrete member를
inward-reference한다.

## 10. H4 exact scope/run/Gate design

### 10.1 root와 scope authority

R007은 `h4_id`와 literal root를 먼저 freeze한다.

```text
<H4_ROOT> := <H4_AUTHORITY_ROOT>/<LITERAL_H4_ID>
```

Scope authority는 exact two signed receipts다.

```text
<H4_ROOT>/scope/scope-authority-receipt.json
<H4_ROOT>/scope/scope-freeze-receipt.json
```

Freeze receipt는 preceding authority receipt를 참조한다. 자기 SHA나 undefined
`scope_authority_receipt_sha`를 참조하지 않는다.

Scope plane:

```text
read entries = 8
control entries = 2
data entries = 0
union entries = 10
```

### 10.2 per-run C=11

각 literal run ID는 다음 exact eleven control roles를 가진다.

```text
<H4_ROOT>/runs/<RUN_ID>/grant.payload.json
<H4_ROOT>/runs/<RUN_ID>/grant.signature.json
<H4_ROOT>/runs/<RUN_ID>/state-init-receipt.json
<H4_ROOT>/runs/<RUN_ID>/consume-intent.payload.json
<H4_ROOT>/runs/<RUN_ID>/consume-intent.signature.json
<H4_ROOT>/runs/<RUN_ID>/consume-receipt.json
<H4_ROOT>/runs/<RUN_ID>/dispatch-receipt.json
<H4_ROOT>/runs/<RUN_ID>/close-intent.payload.json
<H4_ROOT>/runs/<RUN_ID>/close-intent.signature.json
<H4_ROOT>/runs/<RUN_ID>/close-receipt.json
<H4_ROOT>/runs/<RUN_ID>/result-receipt.json
```

```text
per-run control role count C = 11
```

Same-store state:

```text
ABSENT
→ UNSPENT
→ CONSUMED_UNDISPATCHED
→ DISPATCHED
→ SPENT_CLOSED
```

Consume receipt에는 `consume_committed_at`만 둔다.
`dispatch_started_at`은 후행 dispatch receipt에 둔다.

Result는 closed discriminant/enum, raw evidence members와 digest, publisher,
signature domain을 가진다. Close는 exact result와 final token을 검증하는
terminal receipt다.

Per-run plane:

```text
read entries = 9
control entries = 11
data entries = D_i
union entries = 20 + D_i
```

Scope와 run plane count는 서로 대체하지 않는다.

### 10.3 exact five Gate

Gate ID는 ordered exact five로 freeze한다. 각 Gate는 다음 three artifacts를
가진다.

```text
raw evidence
Gate result
Gate receipt
```

```text
Gate count = 5
raw evidence count = 5
Gate result count = 5
Gate receipt count = 5
exact-five closure receipt = 1
```

`H4CompletionReceipt`는 다음을 직접 embed한다.

```text
ordered exact-five {
  gate_id,
  raw_evidence_sha,
  result_sha,
  receipt_sha,
  reviewer_actor_id,
  candidate_sha,
  status
}[]
ordered_exact_five_digest
exact_five_closure_receipt_sha
literal_target_shas[]
all_required_run_close_receipt_shas[]
```

Count-only `five Gate PASS=5`는 금지한다.

Must-close edge target multiplicity:

```text
per-Gate edge counts = [1, 1, 1, 2, 2]
must-close direct edges = 7
unique literal targets = 6
```

세 비-literal concept edge는 exact artifact/path target으로 교체한다.

H4 DAG:

```text
candidate
→ phone result
→ revalidation
→ scope authority
→ scope freeze
→ admin result
→ run grants/consumes/dispatch/results/closes
→ remaining Gate results/receipts
→ six must-close literal targets
→ exact-five closure
→ H4 completion
```

### 10.4 H4 negative registry exact 16

| ID | rejection class |
|---|---|
| `H4-N01` | scope authority missing |
| `H4-N02` | scope authority wrong signature/publisher |
| `H4-N03` | freeze precedes or mismatches scope authority |
| `H4-N04` | scope plane `8/2/0/10` mismatch |
| `H4-N05` | run grant wrong candidate/scope |
| `H4-N06` | run state init missing/duplicate |
| `H4-N07` | consume wrong token or at/after operation deadline |
| `H4-N08` | consume contains future dispatch-start time |
| `H4-N09` | dispatch without consumed-undispatched state |
| `H4-N10` | result uses open/unknown discriminant |
| `H4-N11` | raw evidence/result digest mismatch |
| `H4-N12` | close before result or wrong final token |
| `H4-N13` | run plane `9/11/D_i/(20+D_i)` mismatch |
| `H4-N14` | Gate raw/result/receipt missing, duplicate or wrong candidate |
| `H4-N15` | seven must-close edges or six targets mismatch |
| `H4-N16` | completion uses count-only PASS or misses run close |

## 11. consolidated branch cardinality

| Branch/cut | required cardinality |
|---|---|
| committed namespace | lifecycle genesis/seal `1/1` |
| `DENIED` | selected variant 1, activation 0, data-plane 0, seal 1 |
| `REQUEST_EXPIRED` | selected variant 1, activation 0, data-plane 0, seal 1 |
| pre-dispatch `ABANDONED` | selected variant 1, data-plane 0, seal 1 |
| ALLOW | aggregate activation 1, role rows 4 |
| execution pre-revoke | consume/dispatch `0/0`, disposition pair 1, seal 1 |
| close-recovery pre-revoke | original consume 0, unavailable disposition pair 1 when selected |
| finalization pre-revoke | original consume 0, unavailable disposition pair 1 when selected |
| wrapper pre-close revoke/expiry | pending guard 1, early key consumption 0 |
| normal execution terminal | normal selection/outbox/terminal `1/1/1`, close-recovery consume 0 |
| recovery execution terminal | recovery selection/outbox/terminal `1/1/1`, normal selection 0 |
| finalization consumed | consume/work-outbox `1/1`, expansion pair 2, completion inputs 15 |
| success finalization | PostG7 pair 2, final close 1 |
| failure/pre-revoked finalization | PostG7 pair 0, selected failure/unavailable disposition 1 |
| existing wrapper adoption | new selection/outbox/write `0/0/0` |
| wrapper consume winner | selection/outbox/wrapper `1/1/1`, disposition 0 |
| wrapper revoke/expire winner | selection/outbox 1/1, wrapper 0, disposition pair 1 |
| selected outbox entry | physical output 1, settlement transition 1 |
| same outbox retry | retry unbounded, additional physical output 0 |
| terminal selection 뒤 late revoke | accepted/effective revoke 0 |
| functional terminal 뒤 | data/finalization write 0, seal control write 1 |
| seal 뒤 | all project writes 0 |
| B06 H1 | roles/edges `10/26` |
| B04 H1 | roles/edges `6/22` |
| B04 ordinary actual | application inbound 51, total edges 68 |
| H2 | file/guard/protected writes `49/25/74` |
| M02 | roles/edges `19/(23+1)` |
| H4 scope | signed receipts 2, plane `8/2/0/10` |
| H4 per run | control roles 11, plane `9/11/D_i/(20+D_i)` |
| H4 Gates | raw/result/receipt/closure `5/5/5/1`, must-close edges/targets `7/6` |

## 12. patch batches와 dependency order

### P0 — add-only successor skeleton

Depends on: none.

Affected R006 surface:

```text
front matter and status
§0.1 identity/history
finding completion ledger
§22 self-audit
```

Author actions:

1. 새 R007 successor path를 만든다.
2. §1의 세 source SHA/bytes/lines를 inward-carry한다.
3. R006/reviews를 `REJECTED_HISTORY`로 둔다.
4. canonical 24-row ledger와 source crosswalk를 추가한다.
5. `PRE_REVIEW`, authority none, official delta 0을 고정한다.

Validation:

```text
historical file edits = 0
canonical IDs = 24 unique
source finding coverage = 28 references before dedupe
status = PRE_REVIEW
```

### P1 — constructor, G0와 full plane binding

Depends on: `P0`.

Affected R006 sections:

```text
§5.1
§7
§7.1
§13.2
§13.3
§13.4
§13.8
§18
§22
```

Closes: `R007-B001`, `R007-M001`, `R007-M002`.

Author actions:

- `AttemptNamespaceConstructorV2`와 golden bytes 정의
- G0 snapshot/object digest 분리
- `AuthorityContextV2`와 full `AttemptPlaneSetBindingV2`를 모든 strict
  schema에 명시

### P2 — aggregate CAS lifecycle, deadline, pre-revoke와 nonexecution seal

Depends on: `P1`.

Affected R006 sections:

```text
§7.1
§8.3 namespace/authority/ET DAG
§13.2
§13.3
§13.8
§18
§22
```

Closes: `R007-B002`, `R007-B003`, `R007-B004`, `R007-B008`.

Author actions:

- lifecycle genesis receipt
- single aggregate key, exact four role rows와 transition schema
- trusted deadline compare inside CAS
- three non-wrapper pre-revoke dispositions
- four seal variants
- zero-data-plane proof
- `NL001..NL006`

P2는 ALLOW activation schema와 initial state/token을 정의한다. 실제 grant
SHA/scope를 결속한 activation instance와 activation fan-out은 P3에서
완성한다.

### P3 — pre-frozen grants, CRASH source와 wrapper pre-close guard

Depends on: `P1`, `P2`.

Affected R006 sections:

```text
§8.3 GE/WR/authority/ET DAG
§13.2
§13.3
§13.4
§13.8
§18
§22
```

Closes: `R007-B005`, `R007-B006`, `R007-B007`, `R007-B009`.

Author actions:

- pregrant order
- `AG001..AG006` literal expansion
- aggregate activation exact four grant/decision-bound rows
- execution/finalization consume named grant binding
- signed CRASH source
- wrapper pre-close pending guard

### P4 — transactional outbox, settlement와 terminal tail

Depends on: `P3`.

Affected R006 sections:

```text
§7.1 seal attestation
§8.3 transition/outbox/WR DAG
§13.3
§13.4
§13.8
§18
§22
```

Closes: `R007-B010`, `R007-B011`, `R007-B020`.

Author actions:

- exact eight outbox slots
- consume/selection transaction atomicity
- idempotent publisher/adoption
- later settlement deadline
- selection 뒤 late revoke effect 0
- data terminal→seal→zero-tail

### P5 — GT, expansion, PostG7와 G3

Depends on: `P4`.

Affected R006 sections:

```text
§6.4
§8.3 GT/EO/ET/EP/GE
§13.4
§13.8.3
§14.1
§14.6
§14.7
§18
§22
```

Closes: `R007-B012`, `R007-B013`, `R007-B014`, `R007-M004`.

Author actions:

- five-cut GT truth table
- expansion lineage and 16 conditional direct edges
- PostG7 exact-two scope
- two timing profiles

P5 종료 시 전체 static/runtime graph를 처음으로 재생성한다. 아직 B04/B06,
H2/M02/H4가 남았으므로 global total을 freeze하지 않는다.

### P6 — B06, B04, M02와 H2

Depends on: `P5`.

Internal order:

```text
B06 → B04 H1 → B04 actual → M02 → H2
```

Affected R006 sections:

```text
§8.3 B04X/B06X/M02X/M02P/H2 graph
§9.1.1
§9.1.2
§9.2
§9.3
§11
§13.8 current-authority transitions
§18
§22
```

Closes: `R007-B015`, `R007-B016`, `R007-B017`, `R007-M003`.

Author actions:

- B06 `10 roles/26 edges`
- B04 H1 `6 roles/22 edges`
- canonical application inbound 51, ordinary actual total 68
- M02 `19 roles/(23+1) edges`
- H2 `49 files+25 guards=74 writes`
- H2 final edges를 §13 기계 규칙으로 재계산

### P7 — H4

Depends on: `P3`, `P4`, `P6`.

Affected R006 sections:

```text
§8.3 H4 graph
§13.8 shared CAS/plane rules
§17
§18
§22
```

Closes: `R007-B018`, `R007-B019`.

Author actions:

- scope exact two
- per-run C=11
- same-store state machine
- exact five Gate closure
- seven must-close edges to six targets
- negative registry exact 16

### P8 — global synchronization와 PRE_REVIEW freeze

Depends on: `P0..P7`.

Affected R006 surface:

```text
all role/path/schema/publisher/Physical tables
all required-node and direct-edge registries
all static/runtime branch counts
all scope/count/order digests
§18 fixture registry
§22 self-audit
history/crosswalk/status
```

Author actions:

1. §13의 deterministic calculation을 실행한다.
2. stale prose count와 generated table을 대조한다.
3. all macros를 literal path로 펼친다.
4. UTF-8/LF, fence/table/ID/source coverage를 검사한다.
5. R007 exact SHA/bytes/lines를 freeze한다.
6. 같은 SHA를 formal/skeptical reviewer에게 각각 전달한다.

P8 뒤에도 status는 `PRE_REVIEW`다.

## 13. deterministic mechanical recalculation

### 13.1 source registries

R007 author는 최소 다음 ordered registries를 한 곳의 count authority로 둔다.

```text
RoleRegistryV2[]
NodeRegistryV2[]
EdgeRegistryV2[]
BranchCardinalityRegistryV2[]
AuthorityScopeRegistryV2[]
FixtureRegistryV2[]
FindingCompletionRegistryV2[]
```

`RoleRegistryV2` row:

```text
role_id
literal_path
schema_role
schema_sha
publisher_actor_role
publisher_physical_sha
physical_kind
branch_predicate
runtime_cardinality
alias_of_or_NA
```

`EdgeRegistryV2` row:

```text
edge_id
source_node_id
target_node_id
branch_predicate
source_role_id_or_NA
target_role_id_or_NA
```

### 13.2 alias rules

1. Alias는 `alias_of`를 명시해야 한다.
2. Alias와 target이 같은 literal path, schema, bytes와 Physical을 가리킬
   때 physical write count에는 한 번만 센다.
3. Logical role count는 contract가 요구하는 경우 alias row를 포함할 수
   있으나 `logical_role_count`와 `physical_write_count`를 분리한다.
4. 다른 path, schema, publisher, Physical 또는 branch predicate면 alias가
   아니며 별도 node다.
5. Alias 때문에 두 edge tuple이 같아지면 stable canonical edge 하나만
   유지한다.
6. 같은 edge ID가 다른 tuple을 가리키거나 다른 edge ID가 동일 tuple을
   중복 표현하면 거부한다.

H2의 canonical application binding이 이 규칙의 대표 사례다.

### 13.3 ordering과 digest

```text
role order = explicit registry ordinal
node order = explicit registry ordinal
edge order = explicit registry ordinal
branch order = closed BranchEnum ordinal
```

Digest:

```text
registry_digest =
  SHA256(
    ASCII(<registry-domain>) || 0x00 ||
    RFC8785_JCS(ordered_rows)
  )
```

Locale sort, filesystem enumeration order와 hash-map iteration order는 금지한다.

### 13.4 count algorithm

```text
logical_role_count =
  count(unique role_id satisfying registry predicate)

physical_write_count =
  count(unique resolved
    (literal_path, schema_sha, publisher_physical_sha, branch_instance))

node_count =
  count(unique resolved node_id satisfying branch predicate)

edge_count =
  count(unique
    (source_node_id, target_node_id, normalized_branch_predicate))
```

Prefix count는 edge ID string 범위를 손으로 계산하지 않고 registry filter로
계산한다.

```text
prefix_count(P) =
  count(edge where edge_namespace == P)
```

### 13.5 branch projection

Static graph와 runtime projection을 분리한다.

```text
static edge exists = registry row exists
runtime edge materializes =
  branch_predicate(static row, exact runtime cut) == true
```

각 fixture는 다음을 함께 기록한다.

```text
static_node_set_digest
static_edge_set_digest
runtime_node_set_digest
runtime_edge_set_digest
scope_digest
expected cardinality vector
```

### 13.6 known fixed and derived counts

다음은 closed enumeration으로 고정한다.

```text
B06 roles/edges = 10/26
B04 H1 roles/edges = 6/22
B04 application inbound = 51
B04 ordinary actual total edges = 68
H2 file/guard/write = 49/25/74
M02 roles/local edges = 19/(23+1)
H4 scope receipts = 2
H4 per-run control roles = 11
H4 Gate raw/result/receipt/closure = 5/5/5/1
H4 must-close edges/unique targets = 7/6
```

다음은 hardcode하지 않는다.

```text
H2 final edge total
global node total
global edge total
branch-projected total after alias resolution
scope union total containing runtime D_i
recovery B04 total for suffix k
```

H2의 `306`은 M02 source 추가와 alias unique-dedupe 전 intermediate subtotal다.

### 13.7 stale-total detector

R007 author는 문서의 모든 `count`, `cardinality`, edge prefix range와 digest를
registry output에 역매핑해야 한다.

```text
prose numeric claim without registry source = 0
registry count/prose count mismatch = 0
edge prefix gaps presented as materialized edges = 0
undefined edge ID = 0
unexpanded alias counted twice = 0
branch predicate omitted from conditional edge = 0
```

## 14. §18 fixture registry

아래 ID는 §3 canonical ledger와 one-to-one이다. 각 fixture set은 positive와
negative case, expected counts와 expected digest를 가진다.

| Fixture ID | Canonical finding | required predicate |
|---|---|---|
| `FX-R007-B001-G0-DIGEST-SPLIT` | `R007-B001` | snapshot/object digest 분리 통과; self-preimage와 alias 거부 |
| `FX-R007-B002-NONEXECUTION-SEAL` | `R007-B002` | four seal variants, zero-data-plane, next namespace edge |
| `FX-R007-B003-AGGREGATE-ACTIVATION` | `R007-B003` | activation 1, role rows 4; missing/duplicate/3/5 rows 거부 |
| `FX-R007-B004-DEADLINE-BOUNDARY` | `R007-B004` | one tick before/equal/after와 precheck→commit tick advance |
| `FX-R007-B005-FINALIZATION-GRANT` | `R007-B005` | grant-before-execution edge 1; missing/late/wrong scope consume 0 |
| `FX-R007-B006-WRAPPER-GRANT` | `R007-B006` | wrapper grant-before-finalization edge 1; late/mismatch downstream 0 |
| `FX-R007-B007-CRASH-SOURCE` | `R007-B007` | forged/stale/wrong-process/untrusted clock 거부; source 0/1 |
| `FX-R007-B008-NONWRAPPER-PREREVOKE` | `R007-B008` | three role disposition, original consume 0, terminal exact 1 |
| `FX-R007-B009-WRAPPER-PRECLOSE` | `R007-B009` | early revoke/expiry pending, key unconsumed, close 뒤 outcome 1 |
| `FX-R007-B010-PRECLOSE-CRASH` | `R007-B010` | consume 뒤 각 write boundary crash와 outbox resume |
| `FX-R007-B011-WRAPPER-TOTALITY` | `R007-B011` | lease crash, late revoke, expiry settlement 세 cut exact-one |
| `FX-R007-B012-GT-EO-TRUTH` | `R007-B012` | five GT/EO cuts와 ET selected edge `1/1` |
| `FX-R007-B013-EXPANSION-LINEAGE` | `R007-B013` | expansion→15 completion과 conditional checkpoint |
| `FX-R007-B014-POSTG7-AUTHORITY` | `R007-B014` | success pair 2, failure 0, allowlist-only 대체 거부 |
| `FX-R007-B015-B04-CONSTRUCTIBILITY` | `R007-B015` | future reference 0, application inbound 51, ordinary total 68 |
| `FX-R007-B016-B06-SEMANTIC` | `R007-B016` | roles/edges 10/26, full payload signature, semantic wrapper exclusion |
| `FX-R007-B017-H2-EXACT6-AUTHORITY` | `R007-B017` | roles/guards/writes 49/25/74, command vector와 current token |
| `FX-R007-B018-H4-RUN-AUTHORITY` | `R007-B018` | scope 2, C=11, state order, consume/dispatch time separation |
| `FX-R007-B019-H4-EXACT5` | `R007-B019` | Gate 5, artifact 5/5/5, closure 1, edges/targets 7/6 |
| `FX-R007-B020-SEAL-LAST-WRITE` | `R007-B020` | terminal 뒤 data 0/seal 1, seal 뒤 write 0 |
| `FX-R007-M001-PLANE-BINDING` | `R007-M001` | full object presence/equality, count/digest/intersection mismatch 거부 |
| `FX-R007-M002-CONSTRUCTOR-GOLDEN` | `R007-M002` | NA/type/order/encoding/nonce/path normalization mismatch 거부 |
| `FX-R007-M003-M02-TRIPLE` | `R007-M003` | roles 19, edges 23+1, triple 3, U1 DirPhysical |
| `FX-R007-M004-G3-TIMING` | `R007-M004` | G3 future refs 0, full equality PostG7 only |

Additional aggregate fixture groups:

```text
FX-AGG-CAUSE-PAIR-TRIPLE-QUADRUPLE
  REVOCATION/EXPIRY/CRASH/NORMAL pair, triple, quadruple tie

FX-AGG-OUTBOX-IDEMPOTENCY
  consume→crash→resume
  drain 중 crash
  duplicate worker
  same path wrong bytes

FX-AGG-DEADLINE-SETTLEMENT
  operation deadline 뒤 committed outbox allowed
  settlement deadline 같거나 뒤 write forbidden

FX-AGG-MIXED-KEY-REJECT
  legacy independent role key와 aggregate key 혼용 거부
```

H4 fixture는 §10.4 `H4-N01..H4-N16`을 모두 포함한다.

## 15. §22 self-audit predicates

Frozen R007 §22에는 최소 다음 machine-readable predicate를 둔다.

```text
source identities exact = PASS
historical source mutation = 0
official progress delta = 0
execution authority = NONE

canonical finding union = 20/4/0
canonical finding IDs unique = 24
source finding unmapped = 0

G0 snapshot/object digest named separation = PASS
self-containing freshness preimage accepted = 0

attempt constructor = domain-tagged RFC8785 JCS
alternate NA/type/order/encoding accepted = 0

committed attempt lifecycle genesis/seal = 1/1
nonexecution seal variants = exact 4
nonexecution data-plane writes = 0
next namespace direct predecessor = exact terminal seal

authority aggregate activation/role rows = 1/4
legacy-key/aggregate mixed authority accepted = 0
uninitialized role transition accepted = 0
AuthorityContextV2 missing/mismatch accepted = 0

finalization-grant→execution-consume direct edge = 1
wrapper-grant→finalization-consume direct edge = 1
late/missing grant consume/dispatch = 0

deadline checked inside aggregate CAS = true
NORMAL selected at/after execution deadline = 0
CRASH selected source cardinality = 1
attempt terminal selected cardinality = 1

non-wrapper pre-revoke disposition per selected role = 1
wrapper pre-close revoke consumes terminal slot = 0
wrapper pre-close pending cause preserved = true

accepted consume creates settlement obligation = 1
terminal selection and outbox same transaction = true
same-outbox replay additional physical output = 0
selected role unresolved at attempt seal = 0
terminal selection 뒤 accepted revoke = 0
settlement write at/after settlement deadline = 0

GT/EO five-cut truth table = PASS
expansion-wrapper→completion edges = 15
expansion-wrapper→prefix-checkpoint conditional edge = 1

PostG7 full-check pair in finalization scope = 2
failure branch PostG7 pair = 0
scope/branch cardinality equality = PASS

G3 profile = CURRENT_RUNTIME_EXPANSION_VALIDATION_V2
post-G7 profile = THROUGH_READY_EVALUATION_PAYLOAD_V2
G3 future-node/full-equality claim = 0

B06 roles/edges = 10/26
B04 H1 roles/edges = 6/22
B04 canonical application inbound = 51
B04 ordinary actual edges = 68

H2 file/guard/write counts = 49/25/74
H2 final edge count = generated registry value
H2 hardcoded intermediate 306 accepted as final = 0

M02 roles/edges = 19/(23+1)
M02 LIVE_ROOT_TRIPLE members = 3
M02 U1 physical type = DirPhysical

H4 scope receipts = 2
H4 per-run control roles = 11
H4 Gate raw/result/receipt/closure = 5/5/5/1
H4 must-close edges/targets = 7/6
H4 negative classes = 16

functional terminal post-data-write count = 0
post-terminal project control set = {ATTEMPT_TERMINAL_SEAL}
AttemptTerminalSeal final-write cardinality = 1
post-seal write count = 0

undefined role/path/schema/publisher/Physical = 0
undefined edge ID = 0
duplicate resolved edge tuple = 0
unexpanded path macro in frozen registry = 0
stale prose/generated count mismatch = 0
```

## 16. plan acceptance criteria

이 implementation plan의 설계 수용조건:

1. exact source identities 세 개가 §1과 일치한다.
2. canonical ledger가 `20B/4M`, 24 unique ID이며 모든 source finding을
   coverage한다.
3. aggregate state, artifacts, context, transitions, outbox와 deadline이
   단일 authority model로 연결된다.
4. pregrant direct edges와 CRASH source가 구성 가능하다.
5. nonexecution, pre-revoke, crash, expiry와 wrapper race가 모두 finite
   terminal outcome을 가진다.
6. GT truth table과 expansion/PostG7/G3 timing이 모순되지 않는다.
7. B06 `10/26`, B04 H1 `6/22`, application `51/68`, H2 `49+25=74`,
   M02 `19/(23+1)`, H4 scope/C/Gate/edge 수치가 derivation과 함께 있다.
8. H2 `306`이 intermediate subtotal로 명시되고 final hardcoded total로
   오인되지 않는다.
9. §18 fixture ID와 §22 predicate가 canonical ledger에 추적 가능하다.
10. R006과 review는 rejected history로 남고 execution/official delta가 0이다.

이 조건은 future R007의 acceptance가 아니다. Future R007 acceptance에는
추가로 다음이 필요하다.

```text
R007 exact bytes frozen = true
formal review target SHA = R007 frozen SHA
skeptical review target SHA = R007 frozen SHA
formal findings = 0/0/0
skeptical findings = 0/0/0
reviewer independence = PASS
```

어느 review든 nonzero이면 그 R007은 `REJECTED_HISTORY`로 보존하고 수정하지
않는다. 다음 add-only successor에서 교정한다.

## 17. R007 author instructions

1. R006 또는 review를 편집하지 않는다.
2. 이 계획의 §12 순서로만 작성한다.
3. 각 batch마다 해당 fixture와 registry recalculation을 먼저 통과시킨다.
4. aggregate와 legacy independent role CAS를 병행하지 않는다.
5. schema 이름만 선언하지 말고 closed field list, discriminant, signature
   input과 publisher/Physical을 함께 쓴다.
6. graph prose의 `all`, `matching`, `related`를 literal direct edge table
   대신 사용하지 않는다.
7. final R007 role/path registry에는 angle bracket, wildcard, ellipsis 또는
   unresolved macro를 남기지 않는다.
8. aliases는 `alias_of`로 선언하고 logical count와 physical write count를
   구분한다.
9. B04/B06 fixed count와 H2 derived count를 혼동하지 않는다.
10. branch cardinality는 static graph row 수와 runtime materialization 수를
    분리한다.
11. `AuthorityContextV2`를 digest-only shortcut으로 축약하지 않는다.
12. deadline을 pre-check prose로 대체하지 않는다.
13. terminal output 뒤 settlement attestation을 별도 project file로
    발행하지 않고 seal에 embed한다.
14. 모든 fixture expected result에 count뿐 아니라 ordered set digest를
    기록한다.
15. R007 frozen SHA가 생기기 전 review target hash를 예약하거나 추정하지
    않는다.
16. 두 independent review가 끝나기 전 status를 `PRE_REVIEW`에서 올리지
    않는다.
17. 이 계획을 근거로 실제 PRE-P, H2/H4 또는 product 작업을 실행하지 않는다.

## 18. 최종 non-authority statement

```text
this document = implementation plan only
this document status = PRE_REVIEW
this document grants authority = false
R006 remains rejected history = true
R006/review bytes may be mutated = false
official progress delta = 0
execution allowed now = false
```
