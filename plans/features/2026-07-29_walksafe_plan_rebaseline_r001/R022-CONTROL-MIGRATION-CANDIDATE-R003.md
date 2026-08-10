# r022 제어계약 전환 후보 R003

- 작성일: `2026-07-30`
- 상태: `DESIGN_ONLY_NOT_EFFECTIVE_NOT_APPROVED_NOT_APPLIED`
- 현재 활성 제어: v2.4 / checkpoint sequence 39 / Gap·Backlog r021
- 보존 대상: reviewed exact68 `r022-candidate-r002`
- 후속 구현 후보: 격리된 v2.5 R003 control candidate

이 문서는 설계만 추가한다. 정본, checkpoint, Goal/event, 제품 코드, 활성 문서와
daylog를 변경하지 않는다. R003 독립검수 findings가 모두 0이 되기 전에는 후보
구현·발행, 승인 요청 생성과 적용을 시작하지 않는다.

## 1. 선행 설계와 판정

R003은 다음 두 물리 문서를 입력으로 결속한다.

| 역할 | 경로 | SHA-256 | bytes |
|---|---|---:|---:|
| 실패한 선행 설계 | `R022-CONTROL-MIGRATION-CANDIDATE-R002.md` | `718e1c06549eb286d9b55abf8648b46b8831af4ef862b258de6290994987c47c` | 29,640 |
| 선행 실패 검수 | `R022-CONTROL-MIGRATION-CANDIDATE-R002-independent-review-r001.md` | `0da1597b0480bbde76b1bf58958d1c87018fe705423d77db9da9ed021028962d` | 8,252 |

공통 prefix는
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/`이다. R002의
`FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_DESIGN` 판정은 유지하며 구현 근거로 재사용하지
않는다.

R003은 R001의 content/application-route 분리와 R002 exact68 pair를 보존한다.
R002의 제어 구조는 이 문서가 전부 대체한다. 특히 다음 R002 문구는 폐기한다.

- transform member의 final physical hash를 사전승인 manifest에 기록
- 로컬 raw response equality만으로 production 사용자 승인을 주장
- 파일과 checkpoint 관찰만으로 same-transaction resume을 주장
- final path에 아직 없는 writer가 자기 자신을 최초 실행
- checkpoint commit 즉시 steady ACTIVE로 해석
- final17만으로 새 세션 discovery가 폐쇄됐다고 주장

## 2. 현재 불변식과 exact68

현재 값은 다음과 같으며 R003 설계 작성으로 변하지 않는다.

- branch `codex/walksafe-rc2-hardening-20260715`
- HEAD `a3ad7eead6b5d834d3e0675422475a9aad351e3d`
- v2.4 static:
  `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07`,
  `39,534` bytes
- active checkpoint:
  `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c`,
  `1,329,415` bytes
- sequence `39` tail:
  `c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a`
- r021 Gap:
  `f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a`,
  `488,160` bytes
- r021 Backlog:
  `bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0`,
  `59,266` bytes
- artifact complete `126/257`, open `131`
- formal `0/279`, actual-device `0`, closed release gate `0/5`
- release `NOT_ELIGIBLE`
- Goal topology/status와 ready frontier `EPIC-03`, `EPIC-12` 불변
- FP-008 Goal/leaf, canonical r022, physical v2.5 candidate/active files 부재

exact68 R002의 ledger/Gap/Backlog/pair/review/runbook은 다음 물리 binding을
그대로 쓴다.

| 역할 | SHA-256 | bytes |
|---|---:|---:|
| ledger | `82ecdfbfc9a35d39f1f8648971df62c18faf861745ccc9a28f5b24d8d354493f` | 189,900 |
| Gap | `3dee2cccad7fb264077dc8858ac3940821af6b4cda0e7664c3e8809b69dc8595` | 535,938 |
| Backlog | `8e2c9cc565ffdd1039f7e03fbfefed348e568d8b62a7750ff06f989a0b20f098` | 58,007 |
| pair manifest | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| independent review | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |
| R002 activation runbook | `71e322de393027d3cefaf20c2e7b20e64b94d0106b044f6f1cc4a26f0e5b8bd7` | 3,868 |

pair fingerprint는
`09fcf9cba249a777946fc90a9ee903f2e70143a431e68d0fda2564a7ea8ba67f`다.
changed31/carry37, status change8과 최종
`BLOCKED 5 / CONFLICTING 14 / EVIDENCE_MISSING 4 / MISSING 6 /
PARTIAL 39 / IMPLEMENTED 0`을 다시 계산하지 않는다.

R002 activation runbook은 R003 candidate/challenge generator가 실제로 읽는
`ADVISORY_PREDECESSOR_RUNBOOK` validation input이다. R003 request가 그
path/SHA-256/bytes를 결속한다. 단, R002 runbook의 승인 문구는 authority가 아니며
R003 challenge/provenance를 대체할 수 없다. R003 candidate에는 별도
`activation-approval-runbook-r003.md`를 만들고 그것도 검토·결속한다.

## 3. 사전승인 binding과 미래 hash 분리

R003 candidate root는 add-only
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r003/`
이다. 이하 이 root의 terminal slash를 뺀 경로를 `T`로 쓴다.

candidate-output manifest는 promotion을 두 종류로 분리한다.

### 3.1 COPY/CAS member

21개 COPY/CAS member는 사전승인 때 다음을 모두 확정한다.

- fixed promotion index, source path, source SHA-256/bytes
- final path, promotion mode
- expected final physical SHA-256/bytes
- expected type, uid/gid, mode, nlink
- before binding 또는 explicit absent tombstone
- serialization contract

COPY/CAS의 final content hash는 source bytes와 같고 승인·quick gate가 바꿀 수
없다.

### 3.2 TRANSFORM member

final history와 active checkpoint 두 TRANSFORM member는 사전승인 때 다음만
고정한다.

- promotion index와 final path/mode
- transform ID와 transform-spec physical SHA-256/bytes
- ordered input-slot name/schema/requiredness
- canonical serialization과 허용 JSON Pointer delta
- `final_physical_binding_state=UNRESOLVED_UNTIL_AUTHORIZATION_AND_QUICK_GATE`

사전 manifest/package/request에는 두 transform의 미래 physical SHA-256/bytes
필드를 두지 않는다. authorization와 fresh quick gate 뒤 같은 ordered inputs를
한 번 평가한 resolved-output manifest에서 실제 hash/bytes를 처음 확정한다.
history를 먼저 도출하고 그 exact physical binding을 checkpoint의 ordered input으로
쓴다.

`FINAL_HISTORY_R003` ordered slots는 다음 exact 순서다.

1. `SEQ1_PREFIX_RAW`
2. `CONTROL_CORE_REVIEW_RAW`
3. `AUTHORIZATION_RECEIPT_RAW`
4. `FRESH_QUICK_GATE_RECEIPT_RAW`
5. `R002_PAIR_MANIFEST_RAW`
6. `R002_INDEPENDENT_REVIEW_RAW`
7. `FINAL_HISTORY_EVENT_PROJECTION_SPEC_RAW`

`ACTIVE_CHECKPOINT_R003` ordered slots는 다음 exact 순서다.

1. `SOURCE_SEQ39_CHECKPOINT_RAW`
2. `FINAL_HISTORY_R003_RAW`
3. `R022_GAP_RAW`
4. `R022_BACKLOG_RAW`
5. `V25_STATIC_RAW`
6. `V25_PACKAGE_RAW`
7. `ACTIVE_DISCOVERY_RAW`
8. `FINAL23_COPY_BINDING_ARRAY_CANONICAL`
9. `AUTHORIZATION_RECEIPT_RAW`
10. `FRESH_QUICK_GATE_RECEIPT_RAW`
11. `REPOSITORY_PRODUCT_SNAPSHOT_CANONICAL`
12. `ACTIVE_CHECKPOINT_PROJECTION_SPEC_RAW`

slot array는 이름/schema/order가 exact하고 추가·누락·중복을 거부한다. 두 transform은
resolved manifest나 progress/post-check/receipt를 입력으로 읽지 않는다.

### 3.3 gate object와 미래 durable record

사전 package는 authorization policy의 reviewed source binding과 gate object의
role, path, schema version, transaction-ID/path 도출 규칙과 생성 선행조건을
선언한다. policy archive처럼 candidate에서 byte-exact copy할 정적 입력만
사전 physical hash/bytes를 가진다. authorization, progress, quick, runtime,
post-check와 post-commit 동적 record의 physical hash/bytes, server message ID,
timestamp와 결과는 미리 쓰지 않는다.

gate root `G`는 정확히 다음 경로다.

`docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-001`

| role | path under `G` | schema |
|---|---|---|
| gate bootstrap | `gate-bootstrap.json` | `walksafe.control-gate-bootstrap.v1` |
| authorization policy archive | `authorization/authority-policy.json` | `walksafe.control-plane-authority-policy.v1` |
| challenge issuance attestation | `authorization/challenge-issuance-attestation.json` | `walksafe.control-plane-challenge-issuance-attestation.v1` |
| challenge core | `authorization/challenge.json` | `walksafe.v2.5-r022-authorization-challenge.v1` |
| authorization request | `authorization/request.json` | `walksafe.v2.5-r022-authorization-request.v1` |
| outgoing message attestation | `authorization/control-plane-request-attestation.json` | `walksafe.control-plane-message-attestation.v1` |
| response message attestation | `authorization/control-plane-response-attestation.json` | `walksafe.control-plane-message-attestation.v1` |
| authorization receipt | `authorization/receipt.json` | `walksafe.v2.5-r022-production-authorization-receipt.v1` |
| consumption probe | `authorization/consume-attempts/<attempt-id>/probe.json` | `walksafe.authorization-consumption-probe.v1` |
| consumption-time attestation | `authorization/consume-attempts/<attempt-id>/attestation.json` | `walksafe.authorization-consumption-time-attestation.v1` |
| single-use claim | `authorization/single-use-claim.json` | `walksafe.authorization-single-use-claim.v1` |
| quick attempt intent | `quick/attempt-000001/intent.json` | `walksafe.fresh-quick-attempt-intent.v1` |
| quick check state | `quick/attempt-000001/<check-index>-(started\|result-intent).json` | `walksafe.fresh-quick-check-state.v1` |
| quick raw streams | `quick/attempt-000001/raw/<check-index>.(stdout\|stderr).raw` | `OPAQUE_RAW_BYTES` |
| fresh quick receipt | `quick/fresh-quick-gate-receipt.json` | `walksafe.v2.5-r022-fresh-quick-gate-receipt.v1` |
| execution-isolation attestation | `runtime/execution-isolation-attestation.json` | `walksafe.execution-isolation-attestation.v1` |
| tool/environment inventory | `runtime/tool-environment-inventory.json` | `walksafe.control-tool-environment-inventory.v1` |
| resolved outputs | `resolved-output-manifest.json` | `walksafe.v2.5-r022-resolved-output-manifest.v1` |
| progress record | `transaction-progress/<transaction-id>/<sequence>-<phase>.json` | `walksafe.control-transaction-progress-record.v1` |
| post-check attempt | `postcheck/attempt-000001/intent.json` | `walksafe.control-postcheck-attempt-intent.v1` |
| post-check result intent | `postcheck/attempt-000001/<check-index>-result-intent.json` | `walksafe.control-postcheck-result-intent.v1` |
| post-check raw streams | `postcheck/attempt-000001/raw/<check-index>.(stdout\|stderr).raw` | `OPAQUE_RAW_BYTES` |
| post-check outcome | `postcheck/attempt-000001/outcome.json` | `walksafe.control-postcheck-outcome.v1` |
| terminal incident | `postcommit/incident.json` | `walksafe.control-transition-terminal-incident.v1` |
| post-commit receipt | `postcommit/receipt.json` | `walksafe.v2.5-r022-post-commit-receipt.v1` |

`transaction-id`, `candidate-id`, `attempt-id`는 package가 고정한 lowercase ASCII
`[a-z0-9][a-z0-9-]{0,62}`이고 `.`, `/`, `\\`, NUL, whitespace와 percent
encoding을 금지한다. nonce/hash는 lowercase 64-hex다. server opaque ID는
1~256 bytes의 valid UTF-8 원본 bytes를 canonical base64url-no-padding으로만
response line에 넣고 decode/re-encode가 동일해야 한다. path component는
validated ID에서만 만들며 secure dirfd 아래
`openat2(RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS)`로 descendant를 재확인한다.

## 4. 무순환 authorization DAG

승인 1의 순서는 다음과 같고 역방향 reference를 금지한다.

1. v2.4/r021/R002/R003 pins와 deterministic candidate
2. candidate core independent review findings `0/0/0`
3. gate directory bootstrap, reviewed authority-policy archive와 fixed lock
4. control-plane challenge-time issuance attestation
5. challenge core
6. authorization request physical bytes
7. 완성 prompt의 control-plane outgoing message attestation
8. 그 prompt에 대한 user response message attestation
9. production authorization receipt
10. fresh quick attempt와 receipt
11. fixed lock 안의 fresh consumption probe/attestation, runtime/tool attestations
12. single-use claim
13. resolved-output manifest와 transform final bytes
14. progress journal과 final23 promotion
15. read-only transition post-check
16. terminal incident XOR post-commit receipt

challenge issuance attestation은 완성 prompt 작성 전에 control plane이 발급한
CSPRNG nonce,
channel/conversation/session epoch, issued/expires, expected user principal과 signer를
결속한다. challenge core는 그 attestation과 candidate-output manifest, 21개
COPY hash, 두 transform spec/slot, source checkpoint/repository identity, review와
transaction ID를 결속한다.
request는 challenge physical SHA-256/bytes와 response derivation rule을 결속한다.
request는 자기 physical SHA를 내용에 쓰지 않는다.

request physical hash가 확정된 뒤 control plane에 보낼 exact response line을
도출한다. 따라서 response가 request hash를 직접 포함해도 self-cycle이 없다.
authorization receipt는 challenge/request/message provenance까지만 결속하고 아직
없는 quick/consumption/resolved/final/post-check/receipt를 참조하지 않는다.
consumption-time attestation은 authorization와 quick을 순방향으로 결속하고,
claim이 그 attestation을 결속한다.

final event는 authorization와 quick binding을 포함하지만 resolved manifest와
post-commit receipt를 참조하지 않는다. resolved manifest는 final23 hash를
포함하지만 자기 hash와 미래 post-check/receipt hash를 포함하지 않는다.
post-commit receipt만 final event/checkpoint, resolved manifest, final23,
post-check PASS와 progress tail을 단방향으로 결속한다.

## 5. 승인 1의 실제 사용자 provenance

### 5.1 challenge와 exact response

nonce는 control-plane challenge issuer가 만든 CSPRNG 256-bit이고
transaction/challenge namespace에서 단 한 번만 쓴다. issuer의 signed server
timestamp를 `issued_at`으로 삼고 `expires_at = issued_at + 15 minutes`로 고정한
issuance attestation을 challenge core보다 먼저 만든다. local clock만으로
발급·유효성을 주장하지 않는다. outgoing request는 이미 확정된 issued/expires와
request hash를 포함한 exact response line을 사용자에게 제시한다.

승인 1 response prefix는
`WALKSAFE_APPROVAL1_V25_R022`이며 완성된 한 줄은 정확히 다음 field와 순서다.

```text
WALKSAFE_APPROVAL1_V25_R022 request_sha256=<64hex> challenge_sha256=<64hex> authority_policy_sha256=<64hex> transaction_id=<safe-id> candidate_id=<safe-id> nonce=<64hex> repository_identity_sha256=<64hex> channel_id_b64u=<base64url-no-padding> session_epoch_b64u=<base64url-no-padding> issued_at=<RFC3339-UTC-seconds> expires_at=<RFC3339-UTC-seconds> scope=CONTROL_PLUS_R022_ONLY
```

field는 각각 정확히 한 번, separator는 ASCII space 하나다. timestamp는
`YYYY-MM-DDTHH:MM:SSZ`, 전체 line은 ASCII이고 leading/trailing space나 LF가
없다. placeholder, 추가·누락 field/prefix/suffix, 공백·개행·대소문자·Unicode
normalization 차이는 거부한다.

사용자에게 답장을 요구하는 것은 이 line을 정확히 한 번 포함한 완성 prompt 한
건뿐이다. prompt 작성 전에 별도 signed challenge-time token으로 issued/expires를
확정한다. preliminary/placeholder message에 답한 것은 무효다. outgoing
attestation은 완성 prompt raw SHA-256/bytes, exact line offset, request/challenge/
policy binding과 server가 부여한 message ID/time을 결속한다. response의
`in_reply_to`는 바로 그 message ID여야 한다.

### 5.2 production provenance

reviewed authority policy source는
`T/authority/control-plane-authority-policy.candidate.json`, durable archive는
`G/authorization/authority-policy.json`이다. candidate package와 challenge는 두
path의 byte-exact SHA-256/bytes 및 copy equality를 직접 결속한다. policy는
repository identity와 scope별 허용 human principal/role, issuer, audience,
purpose, signature algorithm/key ID/key usage, root public-key bytes, key
validity/revocation revision, signed-byte canonicalization과 server-ID encoding을
고정한다. OS trust store, 환경변수, network 응답이 root/policy를 대체할 수 없다.

production verifier는 외부 control plane이 policy에 따라 서명한 attestation으로
제공한 다음을 모두 확인한다.

- conversation/channel ID와 session epoch
- outgoing request message ID, author/service identity, server timestamp와 raw
  content SHA-256/bytes
- incoming response message ID와 exact `in_reply_to` outgoing message ID
- expected human author principal ID
- incoming raw content SHA-256/bytes와 exact response bytes
- issued/expires 사이의 response server timestamp
- challenge/request/transaction/repository binding
- issuer/audience/purpose/scope와 repository별 principal ACL revision
- attestation signer/key ID/algorithm/key usage/validity/revocation, signature와
  archived trust root

로컬 transcript, 복사한 문자열, screenshot, 임의 JSON, synthetic fixture 또는
서명 없는 MCP 결과는 production evidence가 아니다. 연결된 control plane이 위
author/message/server-time/signature provenance를 제공하지 않으면 상태는
`PRODUCTION_APPLY_UNAVAILABLE_WRITE_ZERO`다. 이 상태에서는 quick receipt,
single-use claim, resolved manifest와 final write를 만들지 않는다.

production verifier는 test fixture verifier와 다른 module/schema/path다.
production import closure에는 synthetic generator, `TEST` mode, fixture key와
환경변수 bypass가 존재하지 않는다.

### 5.3 single-use claim

fresh quick PASS 뒤 external trusted supervisor는 fixed lock을 잡은 상태에서 새
CSPRNG probe nonce와 monotonic send tick을 만들고 control plane에 보낸다. signed
consumption-time attestation은 probe nonce/attempt, challenge/request/response,
authorization와 quick receipt physical binding, transaction/repository, current
ACL/revocation revision, server `consumed_at`과 one-use token을 결속한다.
supervisor는 echoed nonce, signed raw bytes와
`issued_at <= prompt_server_time <= response_server_time <= consumed_at <= expires_at`,
왕복 monotonic 60초 이하, policy가 허용한 bounded skew를 검증한다. local wall
clock이나 과거 attestation은 현재 시각의 대체물이 아니다.

pre-claim crash에서는 이전 attempt를 보존하고 새 safe attempt ID로 다시 probe할
수 있지만 control plane이 이전 attempt가 아직 server-side consume되지 않았음을
확인해야 한다. signed attestation 발행은 control plane의 one-use token을 해당
transaction/attempt에 원자적으로 consume한다. 그 뒤에는 새 attempt를 금지하고
same attempt ID 조회는 동일 signed bytes만 멱등 반환한다. 따라서 server consume
뒤 local publish crash도 exact attestation을 복구해 같은 transaction claim만
계속한다. forged/stale/다른 transaction에 이미 소비된 token이면
`PRODUCTION_APPLY_UNAVAILABLE_WRITE_ZERO`다. supervisor는 attestation을 durable
publish한 뒤 network를 폐쇄하고 같은 lock/lease를 유지한 채 writer로 전환한다.

writer는 fixed lock 안에서 첫 application mutation 전에 authorization, quick,
consumption attestation을 다시 검증하고 공통 §14 atomic-publish primitive로
`G/authorization/single-use-claim.json`을 만든다. claim temp 생성이 첫
application mutation이다. claim은 challenge/request/outgoing/response
attestations, authorization/quick/consumption receipts, transaction ID, nonce,
repository identity, authority-policy revision, execution-isolation attestation과
tool/environment inventory를 결속한다.

claim이 absent면 정확히 한 transaction만 생성할 수 있다. exact same claim은 그
transaction recovery에만 쓴다. 다른 bytes, nonce 또는 transaction의 occupant는
`DIVERGED_FAIL_CLOSED`다.
정상 claim이 한번 durable해진 뒤에는 authorization expiry가 same-transaction
crash recovery를 취소하지 않지만, 새 transaction이나 새 claim 권한으로
재해석할 수 없다.

## 6. 승인 2 — FP-008 전용

승인 1은 FP-008 권한이 아니다. 승인 2는 다음처럼 별도 namespace를 쓴다.

- challenge schema:
  `walksafe.fp008-materialization-authorization-challenge.v1`
- request schema:
  `walksafe.fp008-materialization-authorization-request.v1`
- receipt schema:
  `walksafe.fp008-materialization-production-authorization-receipt.v1`
- response prefix: `WALKSAFE_APPROVAL2_FP008`
- 별도 transaction ID, CSPRNG nonce, issued/expires와 single-use claim

승인 2 gate root는
`docs/control/execution/goal-gates/WS-GOAL-EPIC-03-FP-008-MATERIALIZATION-20260730-001`
이고 challenge/request/attestation/receipt/claim을 승인 1과 같은 상대경로 구조지만
다른 schema/transaction namespace에 둔다. exact response는 다음 field를 정확히
한 번, 표시 순서와 ASCII-space 하나로 잇는 한 줄이다.

```text
WALKSAFE_APPROVAL2_FP008 request_sha256=<64hex> challenge_sha256=<64hex> authority_policy_sha256=<64hex> transaction_id=<safe-id> candidate_id=<safe-id> nonce=<64hex> fp008_manifest_sha256=<64hex> fp008_review_sha256=<64hex> approval1_postcommit_receipt_sha256=<64hex> v25_active_checkpoint_sha256=<64hex> full19_contract_sha256=<64hex> full19_receipt_sha256=<64hex> repository_identity_sha256=<64hex> channel_id_b64u=<base64url-no-padding> session_epoch_b64u=<base64url-no-padding> issued_at=<RFC3339-UTC-seconds> expires_at=<RFC3339-UTC-seconds> scope=FP008_MATERIALIZATION_ONLY
```

승인 2 challenge는 exact FP-008 materialization manifest/review, canonical r022,
승인 1 post-commit receipt와 `V25_ACTIVE`, full19 contract digest와 fresh full19
receipt를 직접 결속한다. full19은 `V25_ACTIVE` 뒤 승인 2 prompt를 만들기 전에
실행한다. receipt exact path는 승인 2 gate root 아래
`preauthorization/full19-receipt.json`, schema는
`walksafe.fp008-full19-preauthorization-receipt.v1`이다.
`approval1_postcommit_receipt_sha256`은 정확히
`G/postcommit/receipt.json`,
`walksafe.v2.5-r022-post-commit-receipt.v1`의 path/schema/hash/bytes를 뜻하며
승인 1 authorization receipt로 치환할 수 없다.

승인 2는 §5.1의 pre-prompt challenge-time token/exact prompt grammar와 §5.2
authority policy/message provenance, §5.3의 lock 안 fresh consumption token을
전부 별도 nonce/transaction으로 반복한다. 같은 repository-wide lock 아래
승인 2 전용 claim을 공통 §14 atomic-publish로 만든다. claim 상태는
`ABSENT | EXACT_SAME_TRANSACTION | DIVERGED`뿐이며 exact recovery만 허용한다.
동시 claim은 하나만 성공하고 승인 1 response/nonce/reply/receipt와 승인 2
consumption token 재사용을 거부한다. provenance가 없으면
`FP008_MATERIALIZATION_AUTHORIZATION_UNAVAILABLE_WRITE_ZERO`다.

승인 2 consumption attestation은 full19 receipt age가 policy 한도 안이고
event-scoped repository-state가 그 receipt snapshot과 여전히 exact임을 다시
결속한다. 승인 2 claim 뒤에도 `GOAL_STARTED` durable event 전에는 제품 write를
허용하지 않는다.

## 7. trusted launcher와 staged writer

최종 writer가 아직 없다는 bootstrap 순환은 별도 trusted launcher로 끊는다.

R003 candidate review 전에 add-only preparation tool
`scripts/launch_walksafe_v2_5_r022_authorized_20260730.py`를 만들고 독립검수한다.
이 Python 파일은 final23 member가 아닌 사전승인 validation input이며 스스로를
root of trust라고 주장하지 않는다. challenge는 그 exact path/SHA-256/bytes/review,
fixed FD-bootstrap source bytes, 그리고 repository 밖 trusted execution
supervisor의 absolute path/ELF SHA-256/bytes/build ID/signer를 결속한다. supervisor
identity와 signer는 §5.2 archived authority policy가 허용한 것만 쓴다.

repository Python 한 줄도 실행되기 전에 external supervisor가 다음 순서를
완료한다.

1. signed host execution attestation과 candidate가 고정한 supervisor identity를
   검증하고 pinned interpreter ELF, ELF loader, shared libraries, stdlib/extension
   import closure의 opened-FD bytes를 검증한다.
2. repository/candidate를 secure dirfd로 열고 Python launcher, staged writer,
   production verifier와 read-only core를 `O_NOFOLLOW`로 연다. read 전후
   `fstat`과 exact path/hash/bytes/type/uid/gid/mode/nlink를 검증한다.
3. fixed repository lock과 fd-backed host-exclusive lease를 획득한다.
4. fixed local broker socketpair를 만들고 execution child를 생성한다. supervisor
   parent는 exact operation broker가 되고 parent와 child가 같은 lock/lease
   open-file descriptions를 함께 보유한다.
5. child environment를 `env -i` 의미로 비우고 dynamic-loader/Python startup 입력을
   제거한다. inherited FD를 close-range하고 exact source/lock/lease/root/broker
   FD만 번호까지 고정해 남긴다.
6. child에 `no_new_privs`, network namespace with no interface/route, egress deny,
   reviewed seccomp filter와 filesystem confinement를 설치한다.
7. supervisor가 child의 actual kernel/FD 상태를 검증·attest한 뒤 child는 pinned
   interpreter를 exact
   `-I -S -B -c <reviewed-fixed-FD-bootstrap>` argv로 exec한다. bootstrap은 path로
   launcher를 import하지 않고 inherited source FD를 read 전후 `fstat`/hash한
   뒤 그 memory bytes만 compile한다. 검증 전에 repository top-level/import/
   callback side effect는 0이다.

Python launcher와 staged writer는 같은 exec process이고 전달받은 lock/lease FD를
receipt 또는 incident 마지막 parent fsync까지 보유한다. exact module map만
memory compile/load하며 filesystem `sys.path` discovery, bytecode, user-site,
`.pth`, `sitecustomize`, undeclared native extension와 import를 거부한다. audit
hook은 선언되지 않은 module/file open을 fail-closed한다.

환경 allowlist는 `LANG=C.UTF-8`, `LC_ALL=C.UTF-8`, `TZ=UTC`와 manifest가 선언한
읽기 전용 identity 값뿐이다. `PYTHONPATH`, `PYTHONHOME`, `WALKSAFE_*TEST*`,
`PYTEST_*`, 임의 `GIT_*`, proxy, credential와 `LD_*` 등 loader injection
환경변수는 금지한다.

writer process에는 raw filesystem mutation syscall을 허용하지 않는다. pre-opened
local operation broker만 §14의 canonical mutation request를 받고 resolved
allowlist의 exact dirfd/name/temp/operation/hash/metadata와 현재 descriptor
identity를 재검증해 수행한다. broker 밖의 open-for-write, hardlink, unlink,
rename, mount, `/proc/self/fd`, device와 product write는 seccomp user-notify
deny + Landlock/RO mount로 차단한다. inherited socket은 broker endpoint 하나
외에 0이고 broker protocol도 network address나 임의 path를 표현할 수 없다.

execution-isolation attestation은 로컬 임의 JSON이 아니다. archived policy가
허용한 supervisor가 signed raw bytes로 발행하며 fresh nonce, repository/
transaction, opened identities, exact inherited FD table, lease token, mount/network
namespace IDs, `no_new_privs`, seccomp/Landlock policy digest와 kernel verification
result를 결속한다. tool/environment inventory도 같은 producer policy와 actual
kernel/tool evidence를 결속한다. resolved manifest와 claim이 두 physical record를
재검증한다. 이 사전 실행·격리를 제공하지 못하면
`UNSUPPORTED_EXECUTION_ISOLATION_WRITE_ZERO`다.

## 8. threat model, fixed lock와 descriptor 경계

지원 환경은 단일 Linux host의 local filesystem이며 다음 기능을 모두 요구한다.

- `flock`, `openat`/`mkdirat`, `O_NOFOLLOW`, `O_EXCL`, `fsync`
- `renameat2` no-replace와 atomic replace
- file와 ancestor directory `fsync` durability
- stable `st_dev/st_ino`, uid/gid/mode/nlink
- NFS/FUSE/지원이 검증되지 않은 overlay가 아닌 filesystem

fixed repository-wide lock은
`<realpath(git-common-dir)>/walksafe-control-transition.lock`이다. 현재 worktree의
git-common-dir resolved path/dev/ino를 challenge에 봉인한다. lock file은 challenge
발행 전에 trusted preparation 단계가 secure common-dir dirfd 아래
`O_RDWR|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC`, mode `0600`으로 한 번 만들고
file/common-dir `fsync`한다. 이미 있으면 같은 보안 속성만 허용한다. challenge가
그 exact dev/ino/uid/gid/mode/nlink를 pin하므로 apply 시 새 lock inode를
즉석에서 신뢰하지 않는다.

supervisor는 pinned lock을 `O_RDWR|O_NOFOLLOW|O_CLOEXEC`로 열고 regular file,
owner `1000`, group `1000`, nlink `1`, expected dev/ino/mode를 확인한 뒤 하나의
FD로 `flock(LOCK_EX)`한다. 검증 뒤 reserved inherited FD에 explicit dup하고
`FD_CLOEXEC`를 해제해 Python launcher/writer가 같은 open-file description과
flock을 직접 보유하게 한다. parent와 name-to-inode를 lock 직후와 해제 직전에
다시 확인한다.

이 lock은 이를 따르는 WalkSafe control writer만 직렬화한다. advisory lock을
무시하는 같은 UID process를 kernel CAS가 차단한다고 주장하지 않는다.
production apply는 외부 orchestrator의 host-exclusive writer lease와 “다른
write-capable process 없음” attestation이 함께 있을 때만 지원한다. 해당 격리,
filesystem 또는 lease를 검증하지 못하면
`UNSUPPORTED_EXECUTION_ISOLATION_WRITE_ZERO`다.

lease도 bearer JSON이 아닌 writer가 상속해 보유하는 fd-backed exclusive
capability다. supervisor/broker가 죽으면 broker channel의 모든 mutation은 즉시
fail하고, writer가 보유한 lock은 writer 종료까지 풀리지 않는다. writer는
`pidfd`, cgroup kill-on-supervisor-loss와 `PR_SET_PDEATHSIG`/parent-race check로
종료한다. 어느 순서에서도 “lock은 풀렸지만 writer가 계속 쓸 수 있는” 상태가
없다. broker/lease/lock 중 하나가 소실되면 추가 write 0이다.

lock 아래에서 repository root, source/target/parent를 dirfd와 열린 FD로 유지한다.
각 read는 before/after `fstat`으로 dev/ino/size/mtime/ctime/uid/gid/mode/nlink를
확인한다. checkpoint switch 직전 source checkpoint, 22개 선행 final, product/
control CAS와 모든 parent name-to-inode를 마지막으로 double-read한다.

## 9. exact final23와 promotion metadata

모든 final file의 after type은 regular, owner는 `uid:gid=1000:1000`, nlink는
`1`이다. 표의 `0644` 외에는 registry만 `0755`다. 기존 CAS target의 before
uid/gid/mode/nlink/dev/ino/hash/bytes는 challenge에 실제 값으로 고정하며 nlink가
1이라고 가정하지 않는다.

`T`는 정확히
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r003`
이다.

| index | source 또는 ordered inputs | final path | promotion | after |
|---:|---|---|---|---|
| 1 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/implementation-gap-r022.candidate.json` | `docs/control/audits/walksafe-implementation-gap-analysis-20260726-r022.json` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, pinned opaque bytes |
| 2 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/implementation-backlog-r022.candidate.json` | `docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r022.json` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, pinned opaque bytes |
| 3 | `docs/control/walksafe-project-continuation-checkpoint.json` at pinned seq39 | `docs/control/goals/walksafe-completion-graph-v2-5/superseded-v2.4.0-active-checkpoint.json` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, pinned opaque bytes |
| 4 | `T/static-plan-manifest-v2.5.0.candidate.json` | `docs/control/goals/walksafe-completion-graph-v2-5/static-plan-manifest-v2.5.0.json` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, canonical JSON |
| 5 | `T/v2.5-application-transaction-plan.candidate.json` | `docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-001/application-transaction-plan.json` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, canonical JSON |
| 6 | `T/transition-history-v2.5.candidate.json` | `docs/control/goals/walksafe-completion-graph-v2-5/transition-history-seq1-prefix-v2.5.json` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, canonical JSON |
| 7 | `T/v2.5-control-package-manifest.candidate.json` | `docs/control/goals/walksafe-completion-graph-v2-5/control-package-manifest-v2.5.json` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, canonical JSON |
| 8 | `T/staged/scripts/walksafe_v2_5_validation.py` | `scripts/walksafe_v2_5_validation.py` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, reviewed UTF-8 bytes |
| 9 | `T/staged/scripts/check_walksafe_project_continuation_v2_5.py` | `scripts/check_walksafe_project_continuation_v2_5.py` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, reviewed UTF-8 bytes |
| 10 | `T/staged/scripts/check_walksafe_goal_graph_v2_5.py` | `scripts/check_walksafe_goal_graph_v2_5.py` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, reviewed UTF-8 bytes |
| 11 | `T/staged/scripts/apply_walksafe_v2_5_r022_authorized.py` | `scripts/apply_walksafe_v2_5_r022_authorized.py` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, reviewed UTF-8 bytes |
| 12 | `T/active-control-discovery.v2.5.candidate.json` | `docs/control/walksafe-active-control-discovery.json` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, canonical JSON |
| 13 | `T/staged/AGENTS.md` | `AGENTS.md` | `BYTE_EXACT_CAS_REPLACE` | `0644`, reviewed UTF-8 bytes |
| 14 | `T/staged/README.md` | `README.md` | `BYTE_EXACT_CAS_REPLACE` | `0644`, reviewed UTF-8 bytes |
| 15 | `T/staged/docs/control/README.md` | `docs/control/README.md` | `BYTE_EXACT_CAS_REPLACE` | `0644`, reviewed UTF-8 bytes |
| 16 | `T/staged/docs/control/goals/README.md` | `docs/control/goals/README.md` | `BYTE_EXACT_CAS_REPLACE` | `0644`, reviewed UTF-8 bytes |
| 17 | `T/staged/docs/control/walksafe-project-resumption-runbook.md` | `docs/control/walksafe-project-resumption-runbook.md` | `BYTE_EXACT_CAS_REPLACE` | `0644`, reviewed UTF-8 bytes |
| 18 | `T/staged/scripts/README.md` | `scripts/README.md` | `BYTE_EXACT_CAS_REPLACE` | `0644`, reviewed UTF-8 bytes |
| 19 | `T/staged/scripts/run_walksafe_test_layers_20260711.sh` | `scripts/run_walksafe_test_layers_20260711.sh` | `BYTE_EXACT_CAS_REPLACE` | `0755`, reviewed UTF-8 bytes |
| 20 | `T/staged/docs/control/goals/walksafe-completion-graph-v2-5/README.md` | `docs/control/goals/walksafe-completion-graph-v2-5/README.md` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, reviewed UTF-8 bytes |
| 21 | `T/staged/tests/test_walksafe_v2_5_active_control_20260730.py` | `tests/test_walksafe_v2_5_active_control_20260730.py` | `BYTE_EXACT_COPY_NOREPLACE` | `0644`, reviewed UTF-8 bytes |
| 22 | transform `FINAL_HISTORY_R003` ordered slots | `docs/control/goals/walksafe-completion-graph-v2-5/transition-history-v2.5.json` | `SEALED_TRANSFORM_NOREPLACE` | `0644`, canonical JSON |
| 23 | transform `ACTIVE_CHECKPOINT_R003` ordered slots | `docs/control/walksafe-project-continuation-checkpoint.json` | `SEALED_TRANSFORM_CAS_REPLACE_COMMIT_POINT` | `0644`, canonical JSON |

JSON canonicalization은 UTF-8, recursively sorted keys, compact `,`/`:`, duplicate와
NaN/infinity 금지, terminal LF 정확히 하나다. reviewed UTF-8와 pinned opaque
member는 candidate physical bytes를 재직렬화하지 않는다.

index 12 active discovery의 before state와 index 20/21 등 존재하지 않는 target은
zero-byte hash로 가장하지 않는다. 두 번의 `lstatat`가 `ENOENT`이고 parent
dev/ino가 같은 explicit
`{"state":"ABSENT","errno":"ENOENT","parent_dev":...,"parent_ino":...}`
tombstone으로 봉인한다.
모든 `NOREPLACE` target은 같은 explicit tombstone을 가져야 하며 하나라도 이미
있으면 exact same-transaction progress가 없는 한 후보 준비·apply를 거부한다.

## 10. resolved manifest와 validation input closure

resolved-output manifest는 authorization, quick, consumption/runtime attestations와
single-use claim 뒤, 첫 final23 promotion 전에 만든다. 다음을 분리한다.

- `copy_members`: 21개 preauthorized physical bindings
- `transform_members`: ordered actual inputs와 처음 확정한 final hash/bytes
- `validation_inputs`: 실제 읽은 모든 path/hash/bytes/role
- `runtime_preconditions`: tool/env/host/filesystem/lease identity
- `future_durable_records`: role/path/schema와 현재 `ABSENT` 상태

validation inputs에는 v2.4/r021/R002, R001~R003 설계와 검수, R002 activation
runbook, R003 activation runbook, builder/core/wrappers/writer/launcher/production
verifier/tests, candidate bundle, authority-policy source/archive, external
supervisor/FD-bootstrap/interpreter/runtime closure, 23개 before/after,
control-plane/consumption/runtime attestations, quick intent/raw/receipt,
authorization/claim, tool/environment inventory, product/control snapshot과
transform inputs가 모두 들어간다. duplicate role/path와 undeclared open을
거부한다.

resolved manifest는 self physical hash, 미래 progress/post-check/incident/receipt
hash를 포함하지 않는다. §14 공통 atomic-publish로 내구화하고 progress journal이
physical binding을 기록한다.

preauth package는 23개 index의 membership/mode/path/role을 고정하되 transform
22/23에는 transform spec/ordered slots와
`UNRESOLVED_UNTIL_AUTHORIZATION_AND_QUICK_GATE`만 둔다. actual transform
SHA-256/bytes를 package에 넣는 것은 schema error다. resolved manifest만 actual
22/23 bindings를 처음 제공하고 ACTIVE checker가 package + resolved +
post-commit receipt를 join해 final23 exact physical closure를 만든다. 서로 다른
유효 authorization/quick 입력에서도 package bytes는 같고 resolved bytes만
달라진다.

## 11. Git, tool, environment와 product inventory

product pathspec은 `apps`, `backend`, `configs`, `contracts`, `deploy`, `model`,
`product`, `voice`, `docker-compose.yml`이다. tracked, non-ignored untracked,
ignored와 tracked deletion을 구분해 모두 결속한다.

Git/tool closure는 다음을 포함한다.

- resolved `/usr/bin/git` physical SHA-256/bytes/version/build identity
- pinned Python interpreter와 full19에서 쓰는 node/npm/gradle/bash tool identity
- worktree `.git` pointer, git-common-dir/worktree config, local config
- repository와 product roots 아래 모든 applicable `.gitignore`
- git-common-dir `info/exclude`, `.gitmodules`
- global/system exclude를 비활성화한 exact config
- allowed environment와 exact `PATH`
- 각 product path의 tracked/untracked/ignored/deleted classification

Git subprocess 환경은 `env -i`를 기준으로
`LC_ALL=C`, `LANG=C`, `TZ=UTC`, `GIT_CONFIG_NOSYSTEM=1`,
`GIT_CONFIG_GLOBAL=/dev/null`, `GIT_OPTIONAL_LOCKS=0`,
`GIT_TERMINAL_PROMPT=0`, `PATH=/usr/bin:/bin`만 허용한다. 다른 `GIT_*`,
alias, pager, external diff, filter, hook와 credential 환경은 거부한다.

repository-state가 두 번 실행하는 exact Git argv bundle은 다음 순서다.
`<ROOT>`는 challenge가 pin한 absolute Git top-level이다. 모든 argv에서
`/dev/null` core excludes override를 직접 둔다.

```text
["/usr/bin/git","-C","<ROOT>","-c","core.excludesFile=/dev/null","rev-parse","--show-toplevel"]
["/usr/bin/git","-C","<ROOT>","-c","core.excludesFile=/dev/null","rev-parse","HEAD^{commit}","HEAD^{tree}"]
["/usr/bin/git","-C","<ROOT>","-c","core.excludesFile=/dev/null","status","--porcelain=v2","-z","--branch","--untracked-files=all","--ignored=matching","--","apps","backend","configs","contracts","deploy","model","product","voice","docker-compose.yml"]
["/usr/bin/git","-C","<ROOT>","-c","core.excludesFile=/dev/null","ls-files","-z","--cached","--stage","--","apps","backend","configs","contracts","deploy","model","product","voice","docker-compose.yml"]
["/usr/bin/git","-C","<ROOT>","-c","core.excludesFile=/dev/null","ls-files","-z","--others","--exclude-standard","--","apps","backend","configs","contracts","deploy","model","product","voice","docker-compose.yml"]
["/usr/bin/git","-C","<ROOT>","-c","core.excludesFile=/dev/null","ls-files","-z","--others","--ignored","--exclude-standard","--","apps","backend","configs","contracts","deploy","model","product","voice","docker-compose.yml"]
["/usr/bin/git","-C","<ROOT>","-c","core.excludesFile=/dev/null","ls-files","-z","--deleted","--","apps","backend","configs","contracts","deploy","model","product","voice","docker-compose.yml"]
["/usr/bin/git","-C","<ROOT>","-c","core.excludesFile=/dev/null","config","--local","--null","--show-origin","--list"]
```

path-bearing 결과는 NUL-framed, scalar `rev-parse` 결과는 exact ASCII LF-framed로
선언하고 서로 바꾸지 않는다. 모든 command는 exit `0`, stderr empty를 요구한다.
첫 bundle 뒤 어떤 projection도 쓰지 않고 같은 FD/input identity로 둘째 bundle을
실행해 raw stdout/stderr/exit가 전부 byte-exact인지 확인한다.

repository-state/product inventory는 정확한 Git argv와 NUL-framed raw
stdout/stderr/exit code를 보존한다. raw bytes를 해시한 뒤 strict NUL parser로
canonical JSON projection을 만든다. inventory와 모든 config/ignore/tool
descriptor를 연 채 raw snapshot을 두 번 읽고 두 bundle이 byte-exact이며
descriptor identity가 같을 때만 CAS PASS다. newline/non-UTF8 path는 임의
line decoding하지 않는다.

Git projection만으로 content를 대신하지 않는다. 각 snapshot은 Git 결과의
tracked/non-ignored-untracked/ignored/deleted union과 product root의 secure
recursive dirfd walk를 exact set으로 비교한다. 각 entry는 raw path bytes,
classification, type, dev/ino/uid/gid/mode/nlink/size/mtime-ns/ctime-ns를 기록한다.
regular file은 열린 FD에서 전 raw bytes를 stream hash해 SHA-256/bytes를
기록하고 read 전후 `fstat`이 같아야 한다. tracked deletion과 실제 부재는 parent
dev/ino를 포함한 `ABSENT` tombstone으로 기록한다. directory는 sorted raw child
name set을, 허용된 executable은 ELF/script raw physical binding을 기록한다.
symlink, gitlink, device, socket, FIFO, path escape, duplicate raw path와
classification/set 불일치는 실패다.

snapshot A의 Git raw bundle + filesystem raw-content inventory + index/config/
ignore/tool bytes를 모두 완성한 뒤 아무 write 없이 snapshot B를 독립 재수집한다.
두 snapshot의 raw command outputs, exact path set, 모든 metadata와 content
SHA-256/bytes가 byte-exact여야 한다. 따라서 이미 dirty인 tracked file,
untracked/ignored file의 same-path content 변경도 검출한다. checkpoint 직전
같은 절차를 한 번 더 수행해 resolved snapshot과 비교한다.

ignore/config/tool/env/classification 하나의 drift, double-read 사이 drift,
symlink/gitlink/non-regular product path는 checkpoint write 0이다.

## 12. quick와 full19 exact command contract

contract canonicalization은
`{"contract_version":...,"checks":[{"check_id":...,"command":...},...]}`을
UTF-8, sorted keys, compact separators, `allow_nan=false`, terminal LF 없이
직렬화한 SHA-256이다.

quick version은 `2026-07-30.4`, ordered digest는
`0261f6574b6bc279df6e41ce927477b0582271a381af2a4443f05c831a487d14`다.

```text
CONTINUATION_QUICK_V2_5	/usr/bin/python3 -I -S -B scripts/check_walksafe_project_continuation_v2_5_candidate.py --mode QUICK_PRECHECK
GOAL_GRAPH_QUICK_V2_5	/usr/bin/python3 -I -S -B scripts/check_walksafe_goal_graph_v2_5_candidate.py --mode QUICK_PRECHECK
```

quick runner는 candidate review가 별도로 0/0/0인 validation input이다. shell이나
`PATH` resolution 없이 위 각 command를 exact argv로 direct exec한다. cwd는
pinned repository root, environment는
`LANG=C.UTF-8`, `LC_ALL=C.UTF-8`, `TZ=UTC`, `PATH=/usr/bin:/bin`,
`PYTHONDONTWRITEBYTECODE=1`, `PYTHONHASHSEED=0`만, umask `0077`, stdin
`/dev/null`, stdio 외 inherited FD는 0개다. 각 timeout은 300초, stdout/stderr
한도는 각각 16 MiB이고 timeout/한도 초과 시 process group을 kill해 FAIL한다.
`/usr/bin/python3` physical identity와 opened candidate scripts/import closure는
§7 supervisor가 실행 전에 검증한다.

quick intent는 authorization/challenge/request, source checkpoint,
candidate/package/review, contract digest, tool/env/cwd와 pre-run repository/product
snapshot을 결속한다. 각 raw stream/exit/signal/timeout은 §15와 같은
result-intent → common atomic-publish 순서로 보존한다. receipt는 두 PASS,
per-check raw SHA-256/bytes, runner/isolation identity, pre/post snapshot equality,
server/monotonic checked interval을 결속한다. 임의 JSON, fixture producer,
다른 transaction/old snapshot/command/env/output와 partial attempt는 transform
입력으로 인정하지 않는다. unknown attempt는 새 authorization transaction 없이는
재실행하지 않는다.

full version은 `2026-07-30.3`, exact ordered digest는
`8d92a521980e67a06e5712dbad62f76ff56519debae5be7b2c74664e7f0e00d5`다.

```text
CONTINUATION	python3 -B scripts/check_walksafe_project_continuation_v2_5.py
GOAL_GRAPH	python3 -B scripts/check_walksafe_goal_graph_v2_5.py
BASELINE_MATERIALIZATION	python3 -B scripts/materialize_walksafe_artifact_baseline_approval_20260722.py --check
ANDROID_GATEWAY_BOUNDARY	python3 -B scripts/check_walksafe_android_gateway_boundary_20260723.py --root .
NODE_TOOLCHAIN_PRE	: "${WALKSAFE_NODE_BIN_DIR:?required}" && WALKSAFE_NODE_ROOT="$(/usr/bin/dirname -- "${WALKSAFE_NODE_BIN_DIR}")" && test "${WALKSAFE_NODE_BIN_DIR}" = "${WALKSAFE_NODE_ROOT}/bin" && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root "${WALKSAFE_NODE_ROOT}" --lock configs/walksafe_node_toolchain_lock_20260715.json
GATEWAY_TYPECHECK	: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway run typecheck
GATEWAY_TEST	: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway test
GATEWAY_BUILD	: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway run build
WEB_TEST	: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web test
WEB_LINT	: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run lint
WEB_TYPECHECK	: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run typecheck
WEB_BUILD	: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run build
NODE_TOOLCHAIN_POST	: "${WALKSAFE_NODE_BIN_DIR:?required}" && WALKSAFE_NODE_ROOT="$(/usr/bin/dirname -- "${WALKSAFE_NODE_BIN_DIR}")" && test "${WALKSAFE_NODE_BIN_DIR}" = "${WALKSAFE_NODE_ROOT}/bin" && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root "${WALKSAFE_NODE_ROOT}" --lock configs/walksafe_node_toolchain_lock_20260715.json
ANDROID_UNIT_ASSEMBLE_LINT	(cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon)
TEST_LAYER_REGISTRY_VALIDATE	WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && PYTHON_BIN="${WALKSAFE_LOCKED_TEST_PYTHON}" bash scripts/run_walksafe_test_layers_20260711.sh validate
FIELD_AND_RELEASE_PYTEST	WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && "${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest tests/test_android_field_session_summary.py tests/test_release_evidence_gate.py -q
GOAL_CONTROL_PYTEST	WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && "${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest tests/test_walksafe_v2_5_active_control_20260730.py tests/test_walksafe_goal_graph_v2_3_history.py tests/test_walksafe_goal_graph_v2_2_history.py -q
CONTROL_AND_TRACE_PYTEST	WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && "${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest tests/test_walksafe_epic01_phase_b_trace_20260722.py tests/test_walksafe_epic01_phase_c_trace_20260722.py tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py tests/test_walksafe_epic02_trace_v2_2_history.py tests/test_walksafe_epic02_trace_v2_3_history.py tests/test_walksafe_android_gateway_boundary_20260723.py tests/test_walksafe_artifact_baseline_materialization_20260722.py --deselect=tests/test_walksafe_epic01_phase_b_trace_20260722.py::WalkSafeEpic01PhaseBTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_c_trace_20260722.py::WalkSafeEpic01PhaseCTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py::WalkSafeEpic01PhaseETraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py::WalkSafeEpic01PhaseFTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py::WalkSafeEpic01PhaseGTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py::WalkSafeEpic02PhaseATraceTest::test_generated_files_are_current_and_deterministic -q
REPOSITORY_STATE	: "${WALKSAFE_GATE_EVENT_ID:?required}" && python3 -B scripts/check_walksafe_project_continuation_v2_5.py --root . --checkpoint docs/control/walksafe-project-continuation-checkpoint.json --print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"
```

full19은 transition post-check가 아니다. `V25_ACTIVE`가 durable해진 뒤 승인 2
prompt를 만들기 전 Goal-start preparation gate가 별도 sandbox에서 실행한다.
실패해도 v2.5 ACTIVE를 되돌리지 않고 승인 2 request, FP-008 `GOAL_STARTED`와
제품 write만 차단한다. 따라서 npm/Gradle/build write는 transition writer
allowlist와 섞이지 않는다.

각 command string은 pinned `/usr/bin/bash`의 exact argv
`["/usr/bin/bash","--noprofile","--norc","-e","-u","-o","pipefail","-c",COMMAND]`
마지막 element다. cwd는 active repository lower layer를 read-only로 bind하고
declared build/cache/temp path만 disposable upper layer에 둔 exact sandbox root다.
host repository bytes에는 write하지 않는다. stdin은 `/dev/null`, stdio 외
inherited FD는 0개, umask는 `0077`, stdout/stderr 최대치는 check별 각각 256 MiB,
network namespace/egress는 0이고 timeout이면 새 process group 전체를 kill한다.
timeout은 다음 exact seconds다.

| checks | timeout |
|---|---:|
| 1–4, 19 | 300 |
| 5, 13 | 120 |
| 6–12 | 1,200 |
| 14 | 3,600 |
| 15 | 600 |
| 16–18 | 1,800 |

runner는 `env -i`에서 `LANG=C.UTF-8`, `LC_ALL=C.UTF-8`, `TZ=UTC`,
`PYTHONDONTWRITEBYTECODE=1`, `PYTHONHASHSEED=0`, exact
`PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin"`, `WALKSAFE_NODE_BIN_DIR`,
`WALKSAFE_LOCKED_TEST_PYTHON`, `WALKSAFE_GATE_EVENT_ID`, pinned `JAVA_HOME`,
`ANDROID_HOME=ANDROID_SDK_ROOT`, isolated `HOME`, `GRADLE_USER_HOME`,
`NPM_CONFIG_CACHE`, `NPM_CONFIG_USERCONFIG=/dev/null`,
`NPM_CONFIG_GLOBALCONFIG=/dev/null`, `CI=1`만 전달한다. 각 non-literal path/value는
승인 2 manifest가 absolute path/raw value/hash/bytes로 preauthorize한다.
`BASH_ENV`, `ENV`, proxy, credential, user/global npm/Gradle config와 daemon은
금지한다.

실행 전 physical inventory는 Bash와 `/usr/bin/dirname`, 모든 Python
interpreter/stdlib/site-package, node/npm과 lockfile/node_modules executable
closure, Gradle wrapper script/JAR/distribution, JVM/JDK binaries/modules,
Android SDK/platform/build-tools/lint/aapt, Kotlin과 subprocess executable/config/
cache input 전체의 path/hash/bytes/version/build ID를 고정한다. caches는
read-only sealed base + disposable upper이고 command 1 전/19 뒤 base identity를
비교한다. 실제 resolved executable/import/config가 inventory와 다르면 command
1도 시작하지 않는다. 특히 command 내부 `python3`과 `bash`는 각각
`/usr/bin/python3`, `/usr/bin/bash`, `npm`은
`${WALKSAFE_NODE_BIN_DIR}/npm`으로만 resolve되어야 한다.

각 check는 start intent, raw stdout/stderr, exit/signal/timeout, duration과
tool/env/cwd identity를 add-only receipt에 결속하고 첫 실패에서 멈춘다. sandbox
lower repository의 §11 snapshot은 전후 byte-exact여야 하고 upper-layer outputs는
별도 manifest로만 보존한다. full19 receipt는 command contract digest, runner
policy physical digest, 19개 ordered result와 event-scoped repository-state를
결속한다.

static manifest, five staged new-session documents, active discovery와 checker가 같은
ordered digest를 검증한다. ID/order/command 한 byte 차이는 fail-closed한다.

repository-state CLI는 read-only다. exact Git argv/env inventory를 manifest에서
읽고 §11의 raw double-read를 수행한다. stdout에는 canonical JSON 한 건만 쓰며
event ID, raw command별 SHA-256/bytes/exit, repository/product projection,
checkpoint와 discovery binding을 포함한다. 파일 생성은 gate runner가 담당한다.

## 13. 3-way discovery와 mode별 closure

공개 discovery 상태는 정확히 세 가지다.

| public state | 판정 |
|---|---|
| `V24_STEADY` | source checkpoint와 v2.4 bindings exact, application progress/single-use claim/final-after 없음 |
| `TRANSITION_RECOVERY_REQUIRED` | single-use claim/progress/resolved/partial final 중 하나가 있거나 target checkpoint인데 durable receipt가 없음·incident·divergence |
| `V25_ACTIVE` | target checkpoint, exact final23, exact receipt와 `RECEIPT_PARENT_FSYNC_CONFIRMED` progress tail, incident 없음 |

gate bootstrap/policy, challenge/request/attestation/authorization/quick와
pre-claim consumption/runtime evidence만 있고 single-use claim과 application
progress가 아직 없으면 v2.4 bytes가 유효하므로 `V24_STEADY`다. 승인 사실 자체가
적용을 가장하지 않는다.
`V24_STEADY`는 authority/discovery 분류일 뿐 quick-check PASS나 실행 준비 상태를
뜻하지 않는다. 기존 staged working-snapshot drift가 있으면 readiness는 계속
fail-closed한다.

internal detail은 source clean, pre-checkpoint partial,
`COMMITTED_RECOVERY_REQUIRED`, terminal incident와 diverged를 구분하지만 외부
실행 권한은 위 세 상태로만 결정한다. target checkpoint는 receipt 전까지
`COMMITTED_RECOVERY_REQUIRED`를 선언하고 `TRANSITION_RECOVERY_REQUIRED`로
노출한다. 이때 r022 target bytes가 있어도 steady ACTIVE, Goal materialization,
full start gate와 제품 write는 기계적으로 금지한다. target checkpoint 이후
rollback이나 v2.4 재해석도 금지한다.

각 mode는 disjoint exact closure를 가진다.

- `V24_STEADY_NATIVE`: 현재 v2.4 static/checker/checkpoint/r021과 seq39 package
- `V24_STEADY_PROTECTED`: frozen predecessors와 exact seq39 managed snapshot
- `TRANSITION_RECOVERY_NATIVE`: trusted launcher, staged writer/core/verifier,
  authorization/progress/resolved와 관찰된 promotion prefix
- `TRANSITION_RECOVERY_PROTECTED`: source/target pins, final23 mapping, journal chain,
  product/control CAS
- `V25_ACTIVE_NATIVE`: r022 pair, v2.5 static/package/history/checkpoint/core/wrappers/
  discovery와 exact durable records
- `V25_ACTIVE_PROTECTED`: v2.4 archive, transaction plan/seq1 prefix/writer와
  final23 new-session/gate surfaces
- `V25_ACTIVE_MANAGED_SNAPSHOT`: resolved manifest가 고정한 exact path set,
  content set와 product inventory
- `V25_ACTIVE_DYNAMIC_RECORDS`: gate bootstrap/authority policy, challenge/request/
  message·consumption attestations, authorization/quick intent·raw·receipt,
  isolation/tool-environment inventory, claim/resolved/progress/post-check/
  post-commit receipt

최종 resolved mode 배열은 path/hash/bytes/role로 물리 고정되고 서로 중복 없이
해당 mode dependency를 전부 덮는다. preauth package의 transform 22/23만 §10의
2-layer `UNRESOLVED` 표현을 쓰고 ACTIVE에서는 resolved와 join한다. ACTIVE
wrapper는 candidate builder/root, R001~R003 설계·review, candidate-only
wrapper/core와 fixture를 열거나 import하지 않는다.

final23의 exact 분류는 `V25_ACTIVE_NATIVE={1,2,4,7,8,9,10,12,22,23}`,
`V25_ACTIVE_PROTECTED={3,5,6,11}`,
`V25_ACTIVE_MANAGED_SNAPSHOT={13,14,15,16,17,18,19,20,21}`이다. dynamic record는
final23과 겹치지 않는다. package는 각 index의 분류를 한 집합에 정확히 한 번만
배치하되 22/23 actual physical hash를 포함하지 않는다.

ACTIVE checker는 `G`의 expected raw directory-entry set도 검증한다. manifest에
없는 temp, partial, second post-check attempt, 추가 consumption attempt after
claim, progress chain보다 높은 sequence, duplicate/lower fork, unknown file/
directory가 하나라도 있으면 ACTIVE가 아니다.

index 21 regression은 candidate root를 rename하고 mode `000`, import cache를
비운 subprocess에서 final ACTIVE wrapper 두 개와 repository-state를 실행한다.
undeclared open/import는 audit hook/syscall trace로 실패시킨다.

다섯 shared documents와 v2.5 graph README는 동일 3-way truth table, recovery
launcher와 quick/full digest를 가리킨다. checkpoint 전 shared 문서 일부만 after면
v2.4 steady PASS로 가장하지 않고 `TRANSITION_RECOVERY_REQUIRED`다.

## 14. parent directory와 append-only progress journal

candidate core review 전에는 candidate static files와 authority/supervisor policy
source만 존재한다. `G`의 challenge/request/attestation/quick/runtime dynamic
file을 만들거나 physical identity를 검수에 넣지 않는다. findings 0 뒤 trusted
gate initializer가 다음 fixed container lattice를 순서대로 secure
`mkdirat`/no-follow하고 child부터 ancestor까지 `fsync`한다.

```text
G
G/authorization
G/authorization/consume-attempts
G/quick
G/quick/attempt-000001
G/quick/attempt-000001/raw
G/runtime
G/transaction-progress
G/postcheck
G/postcheck/attempt-000001
G/postcheck/attempt-000001/raw
G/postcommit
```

initializer는 reviewed authority policy를 byte-exact archive하고 마지막에
`G/gate-bootstrap.json`을 내구화한다. bootstrap은 각 directory
dev/ino/uid/gid/mode와 exact entry set, 모든 아직 없는 dynamic final file의
explicit `ABSENT` tombstone을 결속하고 challenge가 그 physical bytes를 결속한다.
review는 schema/order/tool만 승인하고 동적 결과 hash를 승인하지 않는다.

bootstrap crash에서는 위 ordered directory prefix의 `ABSENT` 또는 exact empty
directory만 허용하고 다시 `fsync`한 뒤 계속한다. partial prefix에 unknown entry,
wrong metadata/symlink가 있으면 write 0이다. consumption attempt directory만
pre-claim에 append-only로 추가할 수 있으며 gate-bootstrap이 고정한 directory
publisher가 safe monotonic attempt ID, prior-attempt terminal state와 exact entry
set을 결속한다. claim 뒤 새 attempt directory는 금지한다.

### 14.1 공통 atomic-publish primitive

claim, policy/dynamic records, progress, resolved, raw output, outcome, incident,
receipt와 promotion은 한 공통 primitive를 쓴다. mutation 전에 preceding durable
intent가 final path, deterministic temp name, expected bytes/hash/metadata,
before binding과 operation `NOREPLACE | CAS_REPLACE`를 결속한다. claim만
consumption attestation이 preceding intent 역할을 하며 그 temp 생성이 첫
application mutation이다.

temp는 exact parent dirfd 아래 `O_CREAT|O_EXCL|O_NOFOLLOW`, mode `0600`으로 만들고
expected bytes를 쓴다. crash 후 temp가 expected bytes의 exact prefix이고
dev/ino/owner/temp-mode/nlink가 same transaction intent와 맞으면 열린 같은
inode에 남은 suffix만 append한다. full bytes가 된 뒤 expected final
uid/gid/mode를 적용·`fstat`하고 `fsync`한다. prefix가 아닌 partial/corrupt bytes,
다른 inode/metadata/transaction 또는 둘 이상의 temp는 `DIVERGED`이며
truncate/unlink/overwrite하지 않는다. raw output도 result intent가 expected
hash/bytes를 먼저 내구화한 뒤에만 이 규칙을 적용한다.

완성 temp는 file `fsync` 뒤 `NOREPLACE`면 `renameat2(RENAME_NOREPLACE)`,
CAS면 target descriptor/before bytes를 다시 확인한 뒤 atomic replace한다.
그 다음 exact target descriptor와 name-to-inode를 확인하고 target parent
`fsync`한다. state는 다음 중 정확히 하나다.

- `BEFORE_TEMP_ABSENT`: intent와 exact before/tombstone — temp 생성
- `BEFORE_TEMP_EXACT_PREFIX`: same temp exact prefix — append/fsync 계속
- `BEFORE_TEMP_EXACT_FULL`: same temp complete — file fsync/name switch 계속
- `EXACT_AFTER_SAME_INTENT`: target exact after, temp absent — parent fsync/confirm
- `DIVERGED`: 위 외 모든 조합 — 추가 write 0

final target이 exact bytes라도 same durable intent가 없으면 `DIVERGED`다.
filesystem capability attestation은 file-fsync 뒤 name-switch, parent-fsync 전
power loss가 위 BEFORE+same-temp 또는 EXACT_AFTER 중 하나로만 복구되고
neither/both/data-loss 상태를 만들지 않는다는 tested filesystem contract를
결속한다. 이를 증명하지 못하는 mount/filesystem은 지원하지 않는다.
atomic-publish 완료 phase는 `INTENT_DURABLE → TEMP_FILE_FSYNCED →
NAME_SWITCH_OBSERVED → TARGET_PARENT_FSYNCED`다. phase record 자체도 expected
canonical bytes를 temp-prefix resume 방식으로 publish하며 direct
O_EXCL-write로 partial final occupant를 만들지 않는다. candidate package의 fixed
transition function과 직전 durable progress tail이 다음 phase record의 expected
bytes/temp를 유일하게 도출하므로 별도 무한 intent chain은 만들지 않는다.

### 14.2 progress root와 promotion

`G/transaction-progress` container는 gate bootstrap에서 이미 durable/pinned다.
claim exact-after 뒤 apply가 만들 수 있는 유일한 unjournaled directory는
`G/transaction-progress/<transaction-id>` 하나다. crash state는 그 directory의
`ABSENT` 또는 exact empty/dev/ino/metadata뿐이고 parent fsync를 멱등 반복한다.
그 안의 첫 atomic record `000000-BOOTSTRAP.json`이 claim, container identity와
empty-directory observation을 결속한다.

이후 record filename은 zero-padded monotonic sequence와 phase이고 각 canonical
record가 transaction ID, previous record physical SHA-256/bytes와 observed
object state를 결속한다. gap, fork, duplicate sequence, unexpected temp나 declared
tail보다 높은 record는 `DIVERGED`다. 새 v2.5 final parent가 필요하면
`MKDIR_INTENT → DIR_CREATED → CHILD_FSYNCED → PARENT_FSYNCED`를 먼저 기록하며
expected owner `1000:1000`, mode `0755`, no symlink와 stable dev/ino를 검증한다.

resolved manifest는 promotion보다 먼저
`RESOLVED_MANIFEST_INTENT → RESOLVED_FILE_FSYNCED →
RESOLVED_NAME_SWITCH_OBSERVED → RESOLVED_PARENT_FSYNCED`로 내구화한다.

각 promotion intent는 source/ordered inputs와 expected after hash/bytes,
before `ABSENT` tombstone 또는 target dev/ino/hash/bytes/uid/gid/mode/nlink,
target parent dev/ino, operation/temp와 expected after metadata를 결속한다.
promotion phase는 정확히
`PROMOTION_INTENT_DURABLE → TEMP_FILE_FSYNCED → NAME_SWITCH_OBSERVED →
TARGET_PARENT_FSYNCED`다. index `n+1` intent는 index `n` parent fsync 뒤에만
가능하다. index 23 checkpoint는 index 1~22 parent fsync와 마지막
descriptor/product/control double-read 뒤의 유일한 commit point다.

blind retry, temp 삭제, rollback과 새 transaction ID resume은 금지한다.

## 15. checkpoint, post-check, incident와 receipt

index 23 checkpoint는 target bytes 안에
`TARGET_COMMITTED_REQUIRES_DURABLE_POSTCOMMIT_RECEIPT`를 선언한다. ACTIVE 여부는
checkpoint를 다시 고쳐 쓰지 않고 외부 durable receipt/journal과 함께 도출한다.

checkpoint parent fsync 뒤 post-check 실행 전, exact argv/env/input snapshot과
attempt ID를 담은 `intent.json`을 §14 atomic-publish한다. post-check contract
version은 `2026-07-30.1`, canonical digest는
`85c3548f2aee314f3db309e164927aa5319db86198470da3f63a81672ff76971`다.
canonical object는 sorted keys/compact separators/no terminal LF의 다음 값이다.

```json
{"checks":[{"argv":["/usr/bin/python3","-I","-S","-B","scripts/check_walksafe_project_continuation_v2_5.py","--root",".","--mode","POSTCHECK_PENDING"],"check_id":"CONTINUATION_POSTCHECK_V2_5","timeout_seconds":300},{"argv":["/usr/bin/python3","-I","-S","-B","scripts/check_walksafe_goal_graph_v2_5.py","--root",".","--mode","POSTCHECK_PENDING"],"check_id":"GOAL_GRAPH_POSTCHECK_V2_5","timeout_seconds":300},{"argv":["/usr/bin/python3","-I","-S","-B","scripts/check_walksafe_project_continuation_v2_5.py","--root",".","--mode","POSTCHECK_PENDING","--print-gate-repository-state","--gate-event-id","WS-V25-R022-POSTCHECK-20260730-001"],"check_id":"REPOSITORY_STATE_POSTCHECK_V2_5","timeout_seconds":300}],"contract_version":"2026-07-30.1","cwd":"PINNED_REPOSITORY_ROOT","environment":{"LANG":"C.UTF-8","LC_ALL":"C.UTF-8","PATH":"/usr/bin:/bin","PYTHONDONTWRITEBYTECODE":"1","PYTHONHASHSEED":"0","TZ":"UTC"}}
```

runner는 shell 없이 exact argv를 direct exec하고 cwd token을 challenge가 고정한
repository root dirfd로 resolve한다. environment는 위 object 외 0개, umask
`0077`, stdin `/dev/null`, stdio 외 inherited FD 0개, stdout/stderr 각각 16 MiB
한도다. timeout/limit은 process group 전체를 kill하고 FAIL한다. pinned
interpreter와 active scripts/core만 열며 세 command 모두 read-only다. filesystem
write 시도는 broker가 거부하고 incident다. 세 번째 command가 §11 raw
double-read와 product/control CAS를 수행한다. 이 contract는 §12 full19과
별개이며 full19을 transition receipt에 사용할 수 없다.

attempt는 `attempt-000001` 하나뿐이고 순서는 다음과 같다.

1. attempt intent가 contract/tool/env/cwd, final23, resolved manifest와 current
   progress tail을 결속한다.
2. 각 command 직전 `CHECK_<n>_STARTED`를 durable progress로 남긴다.
3. child exit 뒤 supervisor가 raw bytes를 완전히 보유하고
   `CHECK_<n>_RESULT_INTENT`에 exit/signal/timeout, stdout/stderr expected
   SHA-256/bytes와 pre-result tail을 결속한다.
4. zero-byte stream도 포함해
   `raw/<check-index>.stdout.raw`, `.stderr.raw`를 §14 atomic-publish하고 각각
   parent fsync한다.
5. `CHECK_<n>_RAW_DURABLE`이 result intent와 두 raw physical binding을 결속한
   뒤에만 다음 command를 시작한다.
6. 세 raw result가 모두 durable하면 outcome을 계산·atomic-publish하고
   `POSTCHECK_PASS_COMPLETE` 또는 failure tail을 남긴다.

`CHECK_STARTED` 전 crash는 같은 attempt에서 시작 가능하다. started 뒤
result-intent 전 crash는 실행 결과 불명이므로 terminal incident다.
result-intent 뒤 crash는 command를 다시 실행하지 않고 expected raw의 exact
temp-prefix/final 상태만 resume한다. 모든 raw가 durable한데 outcome만 없으면
deterministic outcome을 publish한다. complete FAIL/nonzero/signal/timeout/
semantic mismatch는 terminal incident이고 PASS만 receipt 단계로 간다.

incident DAG는 정확히
`PRE_INCIDENT_PROGRESS_TAIL → POSTCHECK_INCIDENT_INTENT →
INCIDENT_PHYSICAL_BYTES → INCIDENT_PARENT_FSYNC_CONFIRMED_PROGRESS`다.
incident bytes는 intent 이전 tail과 failure observation만 참조하고 자기 expected
hash를 담은 intent나 이후 confirm progress를 역참조하지 않는다. intent가
deterministic expected incident hash/bytes를 결속하고 §14 상태
`BEFORE_TEMP_* | EXACT_AFTER_SAME_INTENT | DIVERGED`로 publish한다. incident가
있으면 receipt는 반드시 absent이고 terminal incident 뒤 새 attempt/PASS/receipt는
영구 금지한다.

post-commit receipt 상태는 다음처럼 처리한다.

- `ABSENT`: PASS outcome과 incident 부재 시 deterministic receipt를 먼저 계산하고
  `POST_RECEIPT_INTENT`가 expected hash/bytes를 결속한 뒤 write
- `EXACT`: same intent의 exact bytes면 빠진 parent fsync와 progress만 완성
- `DIVERGED`: valid receipt로 인정하지 않고 terminal divergence; write 금지

receipt는 §14 common atomic-publish를 쓴다. 그 뒤
`RECEIPT_PARENT_FSYNC_CONFIRMED` progress record와 progress parent fsync가
끝나야 `V25_ACTIVE`다. receipt intent/rename 뒤 어느 crash에서도 같은 transaction
exact resume만 허용한다.

receipt content가 결속하는 progress tail은
`POSTCHECK_PASS_COMPLETE`까지다. 그 뒤의 `POST_RECEIPT_INTENT`가 deterministic
receipt hash/bytes를 결속하고, 마지막 progress가 receipt를 결속한다. receipt가
자기 이후 progress hash를 역참조하지 않으므로 cycle이 없다.

valid incident와 valid receipt는 상호 배타적이다. 둘 다 있거나 receipt가
diverged면 `TRANSITION_RECOVERY_REQUIRED`이고 Goal/product는 차단된다. receipt
뒤 ACTIVE dependency가 drift하면 과거 receipt를 삭제·수정하지 않고 현재 ACTIVE
검사를 fail-closed한다.

single fixed lock과 fd-backed lease는 single-use claim temp부터
receipt/incident/confirm-progress의 마지막 parent fsync까지 writer process가
직접 유지한다.

## 16. 필수 positive/negative/crash 검증

후속 구현은 최소 다음을 통과해야 한다.

### 결정성·DAG

- fixed candidate inputs에 hash seed `0/1/42/8675309` byte-exact
- preauth manifest에 transform/future record physical hash가 있으면 실패
- package의 transform22/23 actual hash가 있으면 실패
- 서로 다른 유효 auth/quick slot에서 package/preauth bytes 동일, resolved만 다름
- 같은 resolved ordered inputs는 history/checkpoint byte-exact
- DAG topological sort와 역참조/cycle fixture 실패

### authorization

- signed control-plane provenance 없는 동일 문자열은 write 0
- wrong principal/root/algorithm/key-use/issuer/audience/purpose/ACL/revoked·expired
  key와 runtime trust-root 치환은 write 0
- author/channel/session/reply-to/outgoing raw prompt/repository/request/nonce/
  expiry/signature 한 항목 drift도 write 0
- placeholder/preliminary prompt reply, 추가 field, noncanonical ID/base64url,
  slash/dot/NUL/path escape 실패
- expired/future server timestamp, stale-at-consume, replayed consume token과 local
  synthetic fixture 실패
- 같은 nonce 동시 claim은 하나만 성공, 다른 transaction/replay 실패
- 승인 1을 승인 2로 재사용하거나 FP-008 manifest/review,
  approval1 post-commit receipt, active checkpoint/full19 contract·receipt drift 시
  materialization 0
- 승인 2 concurrent claim, auth-receipt 치환, 승인 1 nonce/reply 재사용 실패

### launcher·lock·isolation

- final writer가 absent인 source state에서 external pinned supervisor가 opened-FD
  launcher/staged writer를 실행
- pre-exec path swap, malicious interpreter/loader/sitecustomize/bytecode와
  launcher/core/writer open 뒤 path swap 실패
- undeclared import/env/network/inherited socket/procfd/hardlink/rename/ctypes
  syscall/write 실패
- TEST flag/fixture production injection 실패
- lock symlink/wrong owner/mode/nlink/inode와 second cooperating writer 실패
- supervisor/broker 사망 모든 race에서 child write 0이고 child 보유 lock은 종료까지
  유지
- forged isolation/lease attestation, wrong seccomp/Landlock/mount namespace/FD table
  실패
- unsupported filesystem/host lease/isolation은 prewrite 실패
- checkpoint 직전 descriptor/product/control drift는 checkpoint write 0

### final23·discovery·ACTIVE

- exact 23 indices/source/mode/owner/serialization과 checkpoint index 23
- absent tombstone을 zero-byte file로 바꾸면 실패
- 각 promotion crash가 세 public states 중 정확히 하나로 분류
- source+partial은 recovery, target-no-receipt/incident는
  `COMMITTED_RECOVERY_REQUIRED`
- recovery 상태의 Goal/full19/product command 전부 실패
- candidate root 제거/deny 뒤 final ACTIVE와 repository-state PASS
- ACTIVE undeclared candidate/design/review/test-fixture open 실패

### journal·directory·receipt

- gate/bootstrap, mkdir/child/ancestor fsync와 promotion 네 phase 모든 경계 crash
- claim/progress/resolved/promotion/raw/outcome/incident/receipt temp의 모든 byte
  offset crash: exact prefix append-resume, non-prefix/extra temp fail-closed
- journal 없는 exact after, fork/higher sequence/다른 transaction/metadata mismatch
  실패
- post-check start/result-intent/raw/outcome 각 crash의 단일-attempt monotonic 판정
- 한 번 FAIL/unknown outcome 뒤 PASS receipt 영구 금지
- receipt file fsync/rename/parent fsync crash exact resume
- pre-incident-tail → intent → incident → confirm DAG cycle test
- incident+receipt, mismatched receipt와 dependency drift fail-closed

### command·inventory

- quick/full 각 ID/order/command 한 byte drift와 digest mismatch 실패
- quick runner/tool/cwd/env/timeout/raw/exit/snapshot/checked-at drift와 forged/old
  receipt 실패
- Git alias/config/ignore/env/binary/PATH/classification drift 실패
- dirty tracked/untracked/ignored raw content와 metadata drift, raw NUL framing/
  invalid path와 double-read 사이 drift 실패
- full19 Bash argv/BASH_ENV/npm config/Gradle cache/JDK/SDK/cwd/env/umask/FD/timeout/
  raw output drift 실패
- full19 sandbox upper write는 허용하되 host repository write는 실패
- repository-state event ID 부재, stdout 외 write와 malformed raw projection 실패

현재 상태는
`R002_REVIEW_FAILED_R003_CONTROL_DESIGN_ONLY_NON_EFFECTIVE`다. 다음 허용 행동은 이
R003 exact bytes의 독립검수뿐이다. findings 0 전에는 R003 candidate/launcher,
authorization challenge/request, progress record, canonical/Goal/product write를
만들지 않는다.
