# WALKSAFE R007 correction implementation plan R002 독립 Stage-C 검수 R001

문서 ID:
`WS-WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R002-INDEPENDENT-STAGEC-REVIEW-R001`

검토일:
`2026-07-31`

검토 유형:
`READ_ONLY_STAGE_C_CONSTRUCTIBILITY_AND_MECHANICAL_REVIEW`

검토 권한:
`NONE / ABSENT_DENY_ALL`

판정:
`FAIL_REQUIRES_ADD_ONLY_CORRECTION_IMPLEMENTATION_PLAN_R003`

Finding:
`BLOCKING=7 / MAJOR=1 / MINOR=0`

## 1. exact target identity와 review ceiling

| 항목 | exact 값 |
|---|---|
| target | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R002.md` |
| start SHA-256 | `5c7fd7482557325817b05dca2638eaf13a4451a22ab83d6255d4bd6b1f8aa4c0` |
| end SHA-256 | `5c7fd7482557325817b05dca2638eaf13a4451a22ab83d6255d4bd6b1f8aa4c0` |
| bytes / LF lines | `299396 / 6338` |
| owner | `uid=1000 / gid=1000` |
| mode / type / nlink | `0664 / regular non-symlink / 1` |
| target declared status | `PRE_REVIEW / IMPLEMENTATION_PLAN_ONLY` |
| official progress delta | `0` |

검수 시작과 보고서 작성 직전에 target identity를 독립 재계산했다. 시작과
종료 SHA가 같으므로 이 review가 target bytes를 변경하지 않았음이 확인된다.

이 review의 범위는 target R002만으로 다음 Stage-C/constructibility 계약을
추측 없이 구현하고 검증할 수 있는지다.

1. graph/GT/EO/ET, expansion, PostG7와 G3;
2. B06, B04 issuance/consume/application;
3. H2 exact-six guarded publication과 M02 late-bound registry;
4. H4 scope/run/exact-five completion;
5. closed registries, closure order와 fixed cardinality.

Authority lifecycle와 seal은 위 Stage-C artifact의 source order 또는 registry
constructibility에 직접 영향을 주는 부분만 읽었다. 별도 authority review를
대체하거나 그 finding 수를 합산하지 않는다.

이 review는 target R002, 일곱 source, checkpoint, canonical state, product,
daylog와 memory를 수정하지 않았다. 이 파일 하나만 add-only로 추가한다.

## 2. exact source identities와 source boundary

상대 경로 기준점은 다음 exact directory다.

```text
docs/control/execution/artifact-closure/run-20260727-001/
```

Target §1의 일곱 source identity를 모두 재계산했다.

| Source ID | exact 파일 | SHA-256 | bytes | lines | disposition |
|---|---|---|---:|---:|---|
| `SRC-R006` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006.md` | `0795cff0703b85469b61f454cbfefd3d6351b94900aaa49025edca3231d35c0a` | 306496 | 6670 | `REJECTED_HISTORY` |
| `SRC-R006-FORMAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-review-r001.md` | `ced8c9319996805ebcbb691dfa49cf8d5c6e38283c0f56254a1554952be2f374` | 20542 | 412 | `FAIL`, `6/0/0` |
| `SRC-R006-SKEPTICAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-skeptical-review-r001.md` | `a4a3873589192fc09e0a6138aa1d76d0bb7aee1817f4655f2fabb88180332b2c` | 24902 | 465 | `FAIL`, `18/4/0` |
| `SRC-PLAN-R001` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001.md` | `bcf67b07c5d2a0454b6a2af325964e62d55f39e04c8c88684b2006738f027944` | 61353 | 2024 | `REJECTED_HISTORY` |
| `SRC-PLAN-R001-LEDGER` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001-independent-ledger-review-r001.md` | `1ed21a96908e5a9300ca433c259c12ffab0f2ba16fec3014fcaa1e76a9294c17` | 25582 | 581 | `FAIL`, `12/8/0` |
| `SRC-PLAN-R001-STAGEC` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001-independent-stagec-review-r001.md` | `448d8e7cd31ce7d1207d2426955c20c4a51ef65897f85b324ce8e314bfe2746c` | 26466 | 592 | `FAIL`, `6/4/0` |
| `SRC-PLAN-R001-AUTHORITY` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001-independent-authority-review-r001.md` | `c1098e45a4e8eaddc33ca7be6a795d8f01daa999cf3e75ff08f8a7f9d6096cf3` | 31791 | 650 | `FAIL`, `16/3/0` |

Source boundary는 다음과 같다.

- 일곱 source는 immutable historical evidence다. 아래 remediation은 source를
  고치는 명령이 아니라 add-only successor plan/R007 authoring 요구다.
- Target의 inherited canonical ledger `20B/4M/0m`과 R001-review correction
  cluster `18`은 변경하거나 재번호화하지 않는다.
- 아래 `7B/1M/0m`은 frozen R002의 독립 review finding이다. inherited source
  finding 수에 더하거나 source review를 소급 수정하지 않는다.
- 기존 finding이 요구한 surface에서 새 internal contradiction을 발견한 경우,
  각 finding의 `source boundary`에 기존 요구와 이번 판정의 경계를 명시한다.

## 3. 독립 판정 요약

R002는 R001의 여러 결함을 실질적으로 교정했다. B06 `10/26`, B04 H1
`6/23`, application materialized `52/69`, H2 `50/25/75`, M02
`19/24/3`, H4 Gate `5/5/5/1`과 target `7/6`의 열거 산술은 재현된다.
PostG7 exact-two와 G3 four-predicate scope도 이전보다 명확하다.

그러나 다음 여덟 constructibility finding이 남는다.

| ID | severity | surface | 요약 |
|---|---|---|---|
| `R002-STAGEC-B001` | BLOCKING | GT/EO/ET | unavailable role 분기를 full vector가 표현하지 못한다 |
| `R002-STAGEC-B002` | BLOCKING | expansion | obligation projection digest의 closed preimage가 없다 |
| `R002-STAGEC-B003` | BLOCKING | B04 application | branch별 endpoint가 하나의 closed Edge row와 충돌한다 |
| `R002-STAGEC-B004` | BLOCKING | H2 | guard co-output의 idempotency digest가 자기 content SHA로 순환한다 |
| `R002-STAGEC-B005` | BLOCKING | H4 run | DISPATCH/RECORD_RESULT same-store CAS 증거가 없다 |
| `R002-STAGEC-B006` | BLOCKING | H4 completion | required-run universe가 signed predecessor에 고정되지 않는다 |
| `R002-STAGEC-B007` | BLOCKING | closure registry | fixed path registry가 C1→C4로 add-only 전진할 수 없다 |
| `R002-STAGEC-M001` | MAJOR | M02 | heterogeneous source/cardinality fields가 tagged union이 아니다 |

따라서 R002는 self-contained R007 authoring plan으로 수용할 수 없다.
R002 bytes는 그대로 보존하고 아래 최소 교정을 반영한 add-only successor가
필요하다.

## 4. BLOCKING findings

### R002-STAGEC-B001 — GT full vector가 unavailable role 분기를 표현하지 못한다

근거:

- `ET002`는 `close-recovery-consume→selector`, `ET005`는
  `selected-terminal→finalization-consume`으로 정의된다(target
  2644~2648행).
- 모든 CAS-absent/unwrapped/adopted recovery vector는 `ET002=1`과
  `ET005=1`을 고정한다(target 2677~2714행). Target은 새 terminal selection에서
  `ET004/ET005`가 항상 exact `1`이라고 다시 선언한다(target 2755~2762행).
- 그러나 `FSM013`은 close-recovery가 이미 unavailable인 `[C,S,f,w]`에서
  recovery consume 없이 fail-closed terminal을 고른다(target 1123~1125행).
- `FSM017`은 finalization이 이미 unavailable인 `[S,q,S,w]`에서 original
  finalization consume 없이 terminal을 고른다(target 1129~1130행).
- Consolidated registry도 두 pre-revoke branch의 original consume을 `0`으로
  고정한다(target 5264~5266행).

영향:

동일한 reachable branch가 FSM에서는 consume edge `0`, GT full vector에서는
동일 edge `1`을 요구한다. `FX-R007-B012-GT-EO-TRUTH`, static/materialized/
new-write digest와 §11 cardinality를 동시에 만족하는 runtime registry를 만들
수 없다. Recovery 또는 finalization availability가 cut ID에 없으므로 checker가
두 결과 중 하나를 선택할 normative discriminant도 없다.

최소 수정:

1. `GtCutIdV2`와 runtime condition에 close-recovery/finalization availability를
   closed tagged discriminant로 추가한다.
2. `FSM013` branch는 `ET002=0`, `FSM017` branch는 `ET005=0`으로 열거한다.
3. Consume 없는 branch의 direct fail-closed/unavailability edge에 stable ID를
   주고 ordered edge-set digest를 재생성한다.
4. Availability cross-product의 positive/negative fixture를 추가한다.

Source boundary:

`SRC-R006-FORMAL`의 `R006-FORMAL-BLOCKING-006`과
`SRC-R006-SKEPTICAL`의 `R006-SK-BLOCKING-010`은 15-result/unwrapped-CAS
recovery cut에서 `GT016..GT030`을 보존하라는 요구다. 이 finding은 그 요구를
뒤집지 않는다. R002가 새로 도입한 unavailable-role FSM과 R002 full vector
사이의 추가 모순만 판정한다. Canonical `R007-B012`의 범위 안에서 고쳐야
하며 source finding 수는 변하지 않는다.

### R002-STAGEC-B002 — expansion obligation projection digest가 정의되지 않았다

근거:

- `OutputEntryV2`는 final `content_sha`와 `content_bytes`를 포함한다(target
  1470~1489행).
- `SettlementObligationV2`는 ordered full `OutputEntryV2` array와 그 digest를
  포함하는 closed schema다(target 1509~1523행).
- `DependencyExpansionPayloadV2`는
  `finalization_work_obligation_digest`와 `CasRecordPhysicalV2`를 직접
  요구한다(target 2781~2800행).
- 이어지는 설명은 그 digest가 expansion output content SHA를 제외한
  “causally prior obligation projection”을 hash한다고 하지만(target
  2802~2804행), 그 projection의 schema, field list, order, domain과 digest
  공식이 문서 어디에도 없다.

영향:

구현자는 full `SettlementObligationV2` digest를 사용해 output/self cycle을
만들거나, 문서에 없는 projection을 임의 발명해야 한다. 두 구현자가 같은
R002를 따라도 다른 preimage/digest를 만들 수 있으므로 EP006 lineage,
`FX-R007-B013-EXPANSION-LINEAGE`, registry digest와 G3 input을 결정적으로
검증할 수 없다.

최소 수정:

1. 예를 들어 `FinalizationWorkObligationProjectionV2`라는 exact closed
   predecessor-only object를 정의한다.
2. 포함/제외 fields, ordered output-spec projection, normalization과 literal
   SHA-256 domain을 명시한다.
3. Full obligation digest와 projection digest를 다른 field 이름/type으로
   구분하고 EP006 및 fixture가 어느 것을 비교하는지 고정한다.
4. Expansion content SHA를 projection에 넣은 negative vector를 추가한다.

Source boundary:

`SRC-R006-SKEPTICAL`의 `R006-SK-BLOCKING-011`은 consume→expansion과
expansion→completion direct lineage를 요구한다. R002는 EP005/EP006과 EXC
15개를 추가해 그 방향은 보존했다. 이 finding은 그 correction을 철회하지
않고, R002가 cycle 회피를 위해 새로 언급한 projection의 미정의 preimage만
다룬다. Canonical `R007-B013`의 source boundary 밖 artifact를 추가하지 않는다.

### R002-STAGEC-B003 — B04 branch-polymorphic APPIN edge를 closed registry로 표현할 수 없다

근거:

- Ordinary와 recovery subject/approval/issuance/consume은 서로 다른 role
  instance ID와 path를 가진다(target 3587~3611행).
- `APPIN003`, `APPIN004`, `APPIN009`, `APPIN010`, `APPIN011`은 각각 하나의
  stable edge ID이지만 ordinary/recovery branch에 따라 source binding과
  literal path가 바뀐다(target 3802~3813, 3855~3885행).
- Target은 다섯 source node가 selected branch의 path로 “resolve”된다고
  선언한다(target 3881~3885행).
- 반면 `EdgeRegistryRowV2`에는 exact `source_node_id` 하나와
  `source_role_instance_id_or` 하나만 존재한다(target 5585~5607행).
- 한 edge ID가 두 tuple을 나타내는 것은 명시적 rejection이다(target
  5609~5614행).

영향:

Static EDGE registry에 ordinary와 recovery 가능성을 모두 넣으면 같은 edge
ID가 두 endpoint tuple을 가져 schema/rejection rule을 위반한다. 한 branch만
넣으면 다른 legal branch의 static topology가 사라진다. 현재
`application inbound=52`와 `ordinary total=69`의 materialized 산술은 맞아도,
그 산술을 생성해야 할 closed static/runtime edge registry를 만들 수 없다.

최소 수정:

1. 다섯 edge를 ordinary/recovery별 distinct stable IDs로 분리하고 각 row에
   exact endpoint와 `branch_ast`를 준다. 또는
2. Endpoint tagged union을 허용하는 별도 `EdgeTemplateRegistryRowV2`를
   정의하고 runtime EDGE row는 선택된 concrete endpoint 하나만 갖게 한다.
3. Static count와 branch-materialized `52/69`를 분리해 재생성한다.
4. Same-ID/two-tuple, mixed-journal, undefined generic endpoint negatives를
   모두 실행한다.

Source boundary:

`SRC-R006-SKEPTICAL`의 `R006-SK-BLOCKING-013`과 R001 Stage-C review는
ordinary/recovery issuance/application의 exact direct Physical/hash edge를
요구했다. R002의 detached pair, strict bodies와 materialized `52/69`은
유지한다. 이 finding은 새 §13 single-endpoint Edge schema와 branch-union
APPIN rows 사이의 representability만 다루며 B04 H1 `6/23`을 재판정하지
않는다.

### R002-STAGEC-B004 — H2 guard co-output의 idempotency digest가 자기순환한다

근거:

- 각 output idempotency key는 해당 output의 `content_sha`를 preimage에
  포함한다(target 1491~1493행).
- Batch obligation은 ordered output entries와 `idempotency_set_digest`를
  포함한다(target 1509~1523행).
- `H2BatchGuardReceiptV2` body도 `outbox_transition_id`,
  `outbox_core_digest`, `idempotency_set_digest`를 직접 포함한다(target
  4360~4398행).
- Guard는 동일 batch의 co-output이며 CAS가 target identities와 guard
  co-output identity를 함께 commit한다(target 4401~4413, 4424~4427행).
- Guard가 자신의 `ordered_target_specs`에서는 제외되지만(target
  4401~4404행), full batch idempotency set에서 guard를 제외하는 별도 schema,
  projection 또는 digest domain은 없다.

결과 dependency는 다음 cycle이다.

```text
guard body
→ guard content_sha
→ guard idempotency_key
→ batch idempotency_set_digest
→ guard body
```

영향:

Guard bytes와 full batch obligation/outbox를 CAS 전에 동시에 계산할 수 없다.
따라서 25개의 guard co-output을 포함한 immutable outbox, settlement
attestation과 `H2 guard recursion=0` predicate가 constructible하지 않다.
Target이 raw evidence `24/24`와 guard-of-guard target exemption을 고친 사실은
이 digest-level cycle을 제거하지 않는다.

최소 수정:

1. Guard body에서 full `idempotency_set_digest`를 제거하고 predecessor-only
   target-set digest만 넣거나,
2. Guard output을 명시적으로 제외한 exact tagged
   `H2TargetIdempotencyProjectionV2`와 domain을 정의한다.
3. Guard content SHA까지 포함한 full batch digest는 post-commit
   transition receipt/settlement attestation에서만 inward-bind한다.
4. Dependency SCC fixture가 guard/content/idempotency nodes를 포함해 cycle
   count `0`을 재검증하게 한다.

Source boundary:

R001 Stage-C review의 raw `24` coverage와 guard recursion finding은 source
boundary다. R002는 exact-six result를 six atomic five-file batches로 묶고
guard target exemption을 명시해 그 두 표면을 개선했다. 이 finding은 그
결론을 반복하지 않고 R002에서 새로 추가한 `idempotency_set_digest`의
self-preimage만 판정한다. Canonical `R007-B017`과 R002 cluster
`R002-PLAN-C013/C017` 안에서 교정한다.

### R002-STAGEC-B005 — H4 DISPATCH/RECORD_RESULT에 same-store CAS 증거가 없다

근거:

- Target은 initialization과 모든 후속 CAS가 같은 key, grant pair, scope,
  deadlines, head/token과 expected state token을 byte-compare한다고 선언한다
  (target 5031~5037행).
- Total FSM은 `CONSUMED_UNDISPATCHED→DISPATCHED→RESULT_RECORDED`를
  same-store state transition으로 요구한다(target 5039~5049행).
- Consume와 close receipt는 `authority_store_id`와
  `run_state_record_physical`을 포함하고 CAS service가 서명한다(target
  4942~4954, 5002~5015행).
- Dispatch receipt는 pre/post state/token을 주장하지만 authority store,
  current CAS-record Physical과 CAS-service attestation을 갖지 않는다(target
  4956~4969행).
- Result receipt도 같은 값들을 주장하지만 store/record Physical 또는
  CAS-service attestation 없이 independent reviewer가 발행한다(target
  4971~4987, 5022~5029행).

영향:

Dispatcher/reviewer의 project-file signature만으로는 authority-state CAS의
token 전이가 실제 linearize됐음을 증명할 수 없다. 이를 state transition으로
인정하면 임의 token claim을 허용하고, 인정하지 않으면 close가 요구하는
`RESULT_RECORDED` pre-state에 도달할 source order가 없다. H4 run FSM과
`R007-B018` fixture를 동시에 구현할 수 없다.

최소 수정:

1. DISPATCH와 RECORD_RESULT 각각에 CAS-internal signed transition
   attestation을 정의한다.
2. Exact key, pre/post token, state, trusted time/head, store identity와
   `CasRecordPhysicalV2`를 attestation에 결속한다.
3. Project dispatch/result receipt와 다음 transition은 attestation SHA와
   Physical을 direct predecessor로 사용한다.
4. Forged project receipt, stale CAS generation과 reviewer-only token-change
   negatives를 추가한다.

Source boundary:

`SRC-R006-SKEPTICAL`의 `R006-SK-BLOCKING-016`과 R001 Stage-C
`R007-STAGEC-BLOCKING-004`는 strict run authority와 same-store FSM을
요구했다. R002가 result-before-close 순서와 state enum을 추가한 부분은
유지한다. 이 finding은 중간 두 전이의 CAS evidence가 closed payload에 없는
점만 판정하며 H4 count 산술을 변경하지 않는다.

### R002-STAGEC-B006 — H4 completion의 required-run universe가 고정되지 않는다

근거:

- 모든 `run_id`가 H4 activation 전에 frozen ordered list로 정해진다는 prose
  요구가 있다(target 4686~4687행).
- Scope freeze에는 `ordered_final_supported_scope_tuple_rows[]`가 있지만 그
  row의 closed schema, `run_id` projection과 required-run digest 공식이 없다
  (target 4764~4790행).
- `H4CompletionReceiptV2`는 자체
  `ordered_required_run_close_refs[]`, count와 digest만 가진다(target
  5202~5221행).
- “all required runs”가 close돼야 한다고 선언하지만(target 5223~5226행),
  completion array가 어느 predecessor-frozen run set과 byte-equal해야 하는지
  비교 규칙이 없다.

영향:

Completion producer가 required run 일부를 누락하고 남은 배열의 count/digest를
정상 재계산해도 이를 거부할 signed oracle이 없다. Empty/subset completion과
정확한 full completion을 schema 수준에서 구별할 수 없어 H4 completion과
run cardinality가 count-only claim으로 되돌아간다.

최소 수정:

1. `H4RequiredRunSpecV2`의 exact fields, ordering과 digest domain을 정의한다.
2. Scope freeze에 ordered full run specs/count/digest를 넣는다.
3. 각 run grant는 자신의 spec membership proof를, completion은 full frozen
   list와 exact one-to-one close-ref equality를 결속한다.
4. Missing/extra/duplicate/reordered run negative fixtures를 추가한다.

Source boundary:

기존 H4 source finding은 scope/run plane과 exact-five Gate를 닫으라고 요구했다.
R002의 exact eight/nine/eleven rows와 Gate five/target seven은 유지한다. 이
finding은 Gate identity가 아니라 R002가 새로 선언한 frozen `run_id` list와
completion의 연결만 다룬다. Canonical `R007-B018`의 required-run authority
surface 안에서 교정하고 `R007-B019` Gate count를 바꾸지 않는다.

### R002-STAGEC-B007 — completion registry가 C1→C4로 add-only 전진할 수 없다

근거:

- Canonical closure는 C1, C2, C3, C4에서 FindingCompletion rows를 순차
  materialize하고, C2~C4는 직전 batch의 durable receipt를 요구한다(target
  5325~5349행).
- 일곱 registry payload/signature는 registry kind마다 고정된 exact 14 paths만
  가진다(target 5358~5391행).
- `RegistryPayloadEnvelopeV2`의 `registry_version`은 상수 `2`이고 generation,
  closure batch 또는 predecessor version identity가 없다(target 5397~5421행).
- Open finding에는 completion-registry row가 없고 row는 evidence가 생긴 뒤
  materialize된다(target 5931~5937행). 따라서 C1과 C2의 row arrays는 서로
  다른 bytes다.
- `FindingCompletionRegistryRowV2`는 predecessor closure receipt를 SHA와
  `FilePhysicalV2`로 요구하지만(target 5912~5914행), C1/C2/C3 receipt의 exact
  role, path, schema, body, publisher, signature와 publication edge는 없다.

영향:

C1 registry를 fixed path에 publish하면 C2 rows를 같은 path에 add-only로
publish할 수 없다. C4까지 기다려 한 번만 publish하면 C2/C3가 요구하는 durable
predecessor registry/receipt가 존재하지 않는다. Undefined closure receipt는
그 간극도 메우지 못한다. 따라서 `C1→C2→C3→C4`와 immutable registry
publication을 동시에 만족할 수 없다.

최소 수정:

1. Registry path/envelope에 immutable `registry_generation` 또는
   `closure_batch_id` token을 추가한다.
2. 각 generation이 직전 payload/signature/publication binding SHA와 Physical을
   inward-bind하게 한다.
3. C1/C2/C3/C4 `ClosureBatchReceiptV2`의 exact path, schema, ordered completion
   refs, publisher/Physical, signature와 direct edges를 정의한다.
4. Final aggregate/index가 필요하면 C4 이후 별도 add-only artifact로 두고
   이전 generation을 덮어쓰지 않는다.
5. Same-path changed-bytes, skipped generation, missing receipt와 premature row
   negatives를 추가한다.

Source boundary:

이 finding은 R006 canonical finding을 새로 만들거나 C1~C4 grouping을
재배치하지 않는다. R001 reviews의 registry constructibility/closure-order
correction을 적용하려고 R002가 추가한 progressive registry와 fixed
publication path 사이의 target-internal 충돌이다. R002 clusters
`R002-PLAN-C017/C018` 범위에서 add-only versioning과 receipt schema만 닫는다.

## 5. MAJOR finding

### R002-STAGEC-M001 — M02 heterogeneous fields가 closed tagged union이 아니다

근거:

- `M02LateBoundRoleV2`는 `source_literal_path_or_selector`와
  `expected_member_cardinality`를 field 이름만 열거하고 exact type/variant를
  정의하지 않는다(target 4520~4543행).
- 19-row table은 이 두 fields에 absolute file path, root+selector enum,
  ordered literal set, transaction-manifest field, constructor node,
  literal integer, manifest-field reference와 “1 composite with 3 members”를
  혼용한다(target 4545~4565행).
- Runtime에서 selector를 literal members로 확장하라는 규칙은 있지만(target
  4567~4570행), frozen JSON row에서 각 variant를 어떻게 encode하고 reject할지
  결정하는 tagged union이 없다.
- Target 자체는 모든 JSON schema를 `additionalProperties=false`와 exact
  tagged union으로 닫는 것을 작성 목표로 둔다(target 60~64행).

영향:

19라는 row 수와 M02/H2 edge `24/3`은 재현되지만 두 independent generator가
동일 table을 서로 다른 JSON shape로 serialize할 수 있다. Registry digest,
wrong-phase/cardinality rejection과 exact row regeneration이
implementation-dependent다. U1 `DirPhysicalV2`와 triple member order 자체는
이 finding의 대상이 아니다.

최소 수정:

1. Source binding을 예를 들어 `NOFOLLOW_FILE`, `PREFIX_MANIFEST`,
   `LITERAL_SET`, `TRANSACTION_FIELD`, `H2_CONSTRUCTOR`,
   `STAGE_B_RUNTIME_BINDING`의 exact tagged union으로 정의한다.
2. Cardinality를 `EXACT`, `MANIFEST_FIELD`, `COMPOSITE_MEMBERS`의 exact tagged
   union으로 정의한다.
3. 각 variant의 required/forbidden fields와 constructor enum을 닫고 19 rows를
   canonical JSON 형태로 재생성한다.
4. Untagged selector, wrong variant field와 manifest-field/literal confusion
   negatives를 추가한다.

Source boundary:

`SRC-R006-SKEPTICAL`의 `R006-SK-MAJOR-003` 및 R001 Stage-C review가 요구한
M02 live-root triple member 세 개, wrapper 비-member 원칙과 U1 directory type은
R002에서 유지된다. 이 finding은 role count나 topology가 아니라 새 19-row
schema의 serialization closure만 다루므로 `MAJOR`로 분류한다.

## 6. fixed cardinality와 통과한 surface

다음 수치는 displayed rows에서 독립 재계산되며 위 finding과 분리해 보존해야
한다.

| surface | target claim | 독립 재계산 | 판정 경계 |
|---|---:|---:|---|
| §1 source identity | `7` | `7/7` exact SHA/bytes/lines | PASS |
| inherited canonical ledger | `20B/4M/0m` | `20/4/0` | PASS, 이 review 수와 분리 |
| B06 output roles / edges | `10/26` | `10/26` | PASS |
| B04 H1 roles / edges | `6/23` | `6/23` | PASS |
| B04 materialized application inbound | `52` | `48+3+1=52` | arithmetic PASS; static endpoint schema FAIL |
| B04 ordinary materialized total | `69` | `14+1+52+2=69` | arithmetic PASS; static endpoint schema FAIL |
| H2 files / batches / guards / outputs | `50/25/25/75` | `50/25/25/75` | arithmetic PASS; guard digest DAG FAIL |
| H2 result batches | `6×5` | six exact five-file rows | PASS |
| M02 roles / local edges / member edges | `19/24/3` | `19/24/3` | arithmetic PASS; row schema MAJOR |
| H4 scope | `8/2/0/10` | exact eight/two rows, union ten | row arithmetic PASS |
| H4 run | `9/11/D_i/(20+D_i)` | nine/eleven plus exact runtime `D_i` | formula PASS; CAS/run-universe FAIL |
| H4 Gate raw/result/receipt/closure | `5/5/5/1` | exact five paths per artifact class | PASS |
| H4 must-close edges / targets | `7/6` | seven literal rows / six unique targets | PASS |
| H4 Gate multiplicity | `[1,1,1,2,2]` | sum `7` | PASS |
| PostG7 success/failure | `2/0` | two exact role rows / failure zero | PASS |
| G3 predicates | `4` | subset/cardinality/order/digest | PASS |

이 PASS 목록은 R002 acceptance, R007 authoring authority 또는 finding closure를
뜻하지 않는다. Fixed number가 맞아도 그 row를 serialize하거나 source order로
생성할 수 없으면 constructibility는 FAIL이다.

## 7. add-only successor 최소 작성 순서

1. GT cut에 recovery/finalization availability를 추가하고 full vectors와
   digests를 먼저 재생성한다.
2. Finalization obligation projection의 exact schema/domain을 고정한다.
3. B04 static edge template와 runtime concrete edge를 분리한다.
4. H2 guard의 predecessor-only preimage를 닫고 SCC `0`을 확인한다.
5. H4 DISPATCH/RECORD_RESULT CAS attestation과 frozen required-run registry를
   정의한다.
6. M02 source/cardinality unions를 닫는다.
7. Versioned immutable registry generation과 C1~C4 closure receipts를
   정의한다.
8. 위 변경 뒤 모든 fixed cardinality와 registry digest를 source rows에서
   다시 생성한다.
9. Frozen successor bytes를 대상으로 ledger, Stage-C와 authority review를
   같은 SHA에서 다시 수행한다.

Successor가 최소 만족해야 할 추가 predicates:

```text
GT unavailable-recovery consume edge = 0
GT unavailable-finalization consume edge = 0
expansion obligation projection schema/domain ambiguity = 0
B04 one edge ID to multiple resolved endpoint tuples = 0
H2 guard/content/idempotency SCC count = 0
H4 DISPATCH CAS attestation cardinality = 1
H4 RECORD_RESULT CAS attestation cardinality = 1
H4 completion required-run omission/extra/duplicate = 0/0/0
M02 untagged source/cardinality variants accepted = 0
completion registry immutable generations = C1,C2,C3,C4 exact four
undefined predecessor closure receipt = 0
```

## 8. mechanical self-check contract

이 review 파일 자체는 다음 검사를 통과해야 한다. Report 자체의 SHA/bytes/LF
lines는 self-reference를 피하기 위해 이 파일 안에 넣지 않고 외부 반환값으로
보고한다.

| check | expected |
|---|---|
| strict UTF-8 decode | PASS |
| CR bytes / NUL bytes | `0 / 0` |
| terminal LF | exactly one |
| unmatched fenced blocks | `0` |
| malformed Markdown table rows | `0` |
| target start/end SHA equality | PASS |
| target bytes / LF lines | `299396 / 6338` |
| source identity match | `7/7` |
| finding heading count | `7 BLOCKING / 1 MAJOR / 0 MINOR` |
| edits outside this report | `0` |

## 9. 최종 verdict와 non-authority statement

```text
review verdict = FAIL
review findings = BLOCKING 7 / MAJOR 1 / MINOR 0

target R002 start SHA = 5c7fd7482557325817b05dca2638eaf13a4451a22ab83d6255d4bd6b1f8aa4c0
target R002 end SHA = 5c7fd7482557325817b05dca2638eaf13a4451a22ab83d6255d4bd6b1f8aa4c0
target R002 bytes / LF lines = 299396 / 6338

target R002 accepted = false
target R002 bytes may be mutated = false
R007 authoring gate passed = false
required correction vehicle = add-only successor plan

this review grants authority = false
PRE-P execution authority = false
H2/H4 execution authority = false
checkpoint mutation authorized = false
canonical mutation authorized = false
product mutation authorized = false
Gate/formal/release credit authorized = false
official progress delta = 0
```

같은 frozen successor SHA를 대상으로 한 독립 ledger, Stage-C와 authority
review가 모두 `0B/0M/0m`을 보고하기 전에는 R007 authoring, authority 발행,
PRE-P/H2/H4 실행, checkpoint/canonical/product 변경 또는 공식 진행률 승격을
해서는 안 된다.
