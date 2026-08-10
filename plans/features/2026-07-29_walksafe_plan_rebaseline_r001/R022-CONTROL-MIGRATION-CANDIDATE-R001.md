# r022 제어계약 전환 후보 R001

- 작성일: 2026-07-30
- 상태: `DESIGN_ONLY_NOT_EFFECTIVE_NOT_APPROVED`
- 현재 정본: v2.4, checkpoint sequence 39, Gap/Backlog r021
- 대상: exact68 재평가 기반 Gap/Backlog r022 atomic pair
- 권고 경로: v2.5 versioned successor control package

이 문서는 제어계약 후보만 정의한다. v2.4 manifest/checker/history/checkpoint, r021
정본, Goal 상태와 제품 코드를 변경하지 않는다.

## 1. content 판정과 적용 경로를 구분하는 이유

r022는 단일 leaf 결과가 아니라 68개 전체 재검토와 31개 assessment delta,
Backlog role-global normalization을 포함한다.

changed31/carry37 assessment content 자체는 v2.4의 successor scope 검사를
통과한다. 그러나 Gap/Backlog 전체 pair에는 Backlog 운영 delta와 role-global
normalization이 함께 있으므로 현 v2.4에서 이를 원자적으로 적용할 경로가 없다.
따라서 후보 판정은 다음처럼 분리한다.

- content classification:
  `V2_4_CONTENT_SCOPE_COMPATIBLE_CHANGED31_CARRY37`
- application route:
  `VERSIONED_SUCCESSOR_CONTROL_REQUIRED_FOR_OPERATIONAL_DELTA`

현 v2.4 계약에서 전체 pair 적용 경로가 막히는 이유는 다음과 같다.

- `POLICY_GAP_WORK` 생산자는 정확히 한 policy/Gap pair만 소유한다.
- 생산자 경로는 소유한 assessment가 허용 terminal outcome에 도달해야 한다.
- r022의 `GAP-055`는 새 wire-level 충돌 때문에 `CONFLICTING`이다.
- Backlog 운영 필드는 `POLICY_GAP_WORK` 생산자 없이 바꿀 수 없다.
- producer-less `CANONICAL_BINDINGS_UPDATED`도 이 Backlog delta를 허용하지 않는다.
- role-global `*` 영향과 changed31을 단일 생산자 결과로 꾸미면 실제 근거 소유권과
  event 원자성 계약을 위반한다.

v2.4 checker나 seq39 checkpoint를 제자리 수정해 우회하면 locked static contract와
append-only history를 훼손한다. 따라서 이를 선택하지 않는다.

## 2. 권고 전환 모델

### 2.1 predecessor 보존

- v2.4 static manifest, checker와 sequence 0~39 history는 byte-exact로 보존한다.
- v2.5 초기 trust anchor가 v2.4 package ID, static manifest SHA-256, checkpoint
  file/content SHA-256, tail event SHA-256과 r021 canonical pair를 결속한다.
- v2.4의 작업 전 quick-check PASS receipt를 predecessor 기준선으로 보존한다.
- 현재 staged 파일 때문에 발생한 v2.4 live working-snapshot drift를 과거 history
  실패나 r021 변경으로 해석하지 않는다.

### 2.2 새 event

v2.5에 provisional event type `BULK_REBASELINE_APPLIED`를 추가한다. 정확한 이름은
후보 schema/checker/test가 함께 고정할 때 확정한다.

이 event는 다음 조건을 모두 만족할 때만 유효하다.

- 후보별 `control_transition_authorization_binding`으로 명시적 사용자 승인
  receipt 결속
- 승인 직후 실행한 fresh quick gate receipt 결속
- R002 독립검수와 v2.5 core 독립검수를 서로 다른 binding으로 결속하고 양쪽
  findings `BLOCKING/MAJOR/MINOR = 0/0/0`
- exact68 ledger 68건, changed31, byte-exact carry37, assessment status change8
- Gap/Backlog pair manifest의 physical hash·bytes·logical seal·pair fingerprint 일치
- r021 before binding과 r022 after binding 동시 결속
- 사전 봉인된 application transaction plan이 before/after pair와 허용
  checkpoint projection을 결속
- 변경 가능한 subject는 manifest가 선언한 exact 집합뿐
- policy↔Gap mapping, 24-edge hard dependency와 기존 Goal topology 변화 0
- Goal status, formal/actual test, release gate, artifact 수치와 제품 코드 변화 0

이 event는 제품 leaf의 결과가 아니므로 `produced_by_goal_id`를 가장하지 않는다.
대신 `rebaseline_review_receipt_binding`과
`control_transition_core_review_binding`,
`control_transition_authorization_binding`,
`fresh_quick_gate_receipt_binding`,
`application_transaction_plan_binding`을 필수로 둔다.

event가 post-commit receipt를 직접 결속하면 event/checkpoint hash와 receipt 사이에
순환이 생긴다. 따라서 event는 pre-commit transaction plan만 참조하고,
post-commit receipt가 적용된 event·checkpoint hash를 단방향으로 참조한다.
transaction plan과 authorization request/receipt에는 최종 event/checkpoint hash를
넣지 않는다.

### 2.3 후보 event chain

격리된 v2.5 후보 history는 다음 최소 순서를 검증한다.

1. `PACKAGE_PREPARED` — predecessor import/trust anchor를 설치하고
   사전승인 candidate checkpoint의 tail이 된다.
2. `PACKAGE_ACTIVATED` — 승인된 package lifecycle만 전환하고 Goal·정본 delta 0.
   기존 `package_activation_authorization_binding`과
   `activation_quick_gate_binding`을 모두 필수로 유지한다.
3. `BULK_REBASELINE_APPLIED` — r022 Gap/Backlog와 허용 checkpoint projection

실제 사전승인 candidate checkpoint에는 seq1만 존재한다. seq1~3 전체 chain은
synthetic authorization을 쓰는 시험 fixture에서만 미리 검증한다. 실제 승인과
fresh quick gate 뒤 seq2와 seq3을 인접하게 같은 최종 checkpoint에 commit한다.
seq2를 생략하거나 seq3보다 뒤에 두거나, seq2에서 정본을 바꾸거나, active tail이
seq2인 fixture는 fail-closed한다.

### 2.4 허용 delta

허용 범위는 다음으로 제한한다.

| 역할 | 허용 범위 |
|---|---|
| `IMPLEMENTATION_GAP` | metadata successor identity, exact68 scope/snapshot/evidence, changed31 assessment, 파생 summary |
| `IMPLEMENTATION_BACKLOG` | metadata successor identity, Gap logical binding, assessment status projection, 검토된 action refresh, EPIC-02/03 reason, FP-008 next pointer |
| checkpoint | 새 canonical binding, event, `implementation_gap_snapshot`, `current_work`, `session_handoff`, 새 working snapshot |
| static topology | 변화 금지 |
| product/formal/device/gate/release | 변화 금지 |

application transaction plan은 허용 checkpoint 변경을 필드 이름만으로 열어 두지
않고 JSON Pointer별 before/after 값과 hash를 exact 목록으로 봉인한다. r022
counts/evidence snapshot, FP-008 `current_work`와 `session_handoff`는 그 목록대로만
갱신한다. `status_by_goal`, topology, focus/ready frontier, blocker/artifact queue,
`126/257`·open `131`, formal/device/gate/release는 byte 또는 의미상 불변이다.
working snapshot도 임의 재계산이 아니라 봉인된 exact content-set delta만 허용한다.

Backlog action은 r021 Backlog가 r021 Gap remediation과 달리 이미
post-internal-completion action으로 특수화한 행을 그대로 보존한다. 나머지만
r022 remediation으로 갱신한다.

`BULK_REBASELINE_APPLIED`는 exact R002 pair의 이 transaction에만 적용되는 좁은
예외다. 기존 `POLICY_GAP_WORK` 생산자·terminal outcome 규칙을 다른 event나
일반 successor에 대해 완화하지 않는다.

## 3. v2.5 후보 산출물

실제 후보를 만들 경우 모두 별도 versioned/add-only 경로를 사용한다.

- `static-plan-manifest-v2.5.0.candidate.json`
- `walksafe-project-continuation-checkpoint-v2.5.candidate.json`
- `check_walksafe_project_continuation_v2_5.py`
- `check_walksafe_goal_graph_v2_5.py`
- v2.5 전환·negative regression tests
- candidate checker와 active checker가 함께 import하는 동일 `validate()` core
- `candidate-output-manifest.json`
- 승인 뒤 write 전에 만드는 `resolved-output-manifest.json`
- `r022-canonical-application-authorization-request.candidate.json`
- 승인 후 별도 `r022-canonical-application-authorization-receipt.json`
- `v2.5-application-transaction-plan.candidate.json`
- 적용 후에만 만드는 `r022-canonical-application-receipt.json`
- 결정론적 builder와 `--check`
- 독립 physical/semantic review receipt

후보 파일 이름은 실제 생성 전 기존 versioning pattern과 충돌 여부를 다시 확인한다.
`.candidate` 파일은 승인 전 checker의 활성 discovery 대상에 넣지 않는다.
`candidate-output-manifest.json`은 각
`candidate_path → intended_final_path → promotion_mode`를 봉인한다.
sealed core/static/package와 seq1 history prefix의 `BYTE_EXACT_COPY` entry는
candidate SHA-256/bytes를 final expected 값으로 기록한다.
`SEALED_DETERMINISTIC_TRANSFORM` entry는 아직 생성할 수 없는 final hash를
가장하지 않고 source candidate SHA-256/bytes, `transform_spec_sha256`, exact
선행 입력 binding, canonicalization 규칙과
`final_hash_state=DERIVED_AFTER_AUTHORIZATION`을 기록한다.
sealed core/static/package와 seq1 history prefix는
`BYTE_EXACT_COPY`이며 승인 후 재생성하지 않는다. seq1-only candidate
checkpoint와 실제 seq3 final checkpoint는 byte-exact 승격 대상이 아니다.
checkpoint와 seq2/3 event는 검토된 projection 함수·고정 activation envelope·
승인 receipt와 fresh quick-gate receipt를 동적 입력으로 하는
`SEALED_DETERMINISTIC_TRANSFORM`으로 분리하고,
transform 입력/출력 path와 허용 delta를 manifest에 봉인한다. core candidate
검토가 findings 0으로 끝난 뒤에만 transaction plan과 authorization request를
만들고, 그 exact 요청에 대한 사용자 응답으로 authorization receipt를 만든다.
output manifest 자체는 자기 SHA/bytes member에서 제외해 해시 순환을 피하고,
core 독립검수 receipt가 manifest의 physical SHA/bytes를 외부에서 결속한다.
authorization request에는 candidate-output manifest와 transform spec을 결속하고,
사용자에게 둘의 exact hash를 함께 제시한다. authorization receipt도 request,
candidate-output manifest와 응답을 함께 결속한다.

승인 뒤 final write 전에는 authorization receipt를 입력으로 transform을 한 번
도출하고 add-only `resolved-output-manifest.json`에 transaction ID,
authorization receipt와 fresh quick-gate receipt hash, candidate-output
manifest가 선언한 exact promotion member set의 final path/SHA-256/bytes를
기록한다. resolved manifest 자기 자신, temp/failure/incident/post-check record와
post-commit receipt는 이 member set에서 명시적으로 제외한다. 이
manifest는 temp `O_EXCL` → file `fsync` → no-replace rename → parent directory
`fsync`로 먼저 내구화한다. final event는 resolved manifest를 역참조하지 않고
post-commit receipt가 resolved manifest의 physical hash/bytes를 단방향으로 결속해
해시 순환을 피한다.

## 4. 필수 negative tests

- 승인 receipt 없음
- findings가 하나라도 0이 아님
- ledger 67/69건, duplicate Gap/policy, changed/carry 중첩
- evidence JSON Pointer 부재, path escape, symlink, source hash drift
- GAP-055를 근거 없이 `PARTIAL` 또는 `IMPLEMENTED`로 변경
- specialized FP-047 등 Backlog action을 raw Gap remediation으로 회귀
- mapping, dependency 또는 Goal topology 1비트 변경
- Gap만 또는 Backlog만 적용
- pair manifest와 실제 파일 hash/bytes/seal 불일치
- 승인된 changed31 밖 assessment 변경
- Goal status·artifact count·formal/device/gate/release credit 증가
- 제품 파일 변경을 canonical application receipt에 섞음
- 다른 transaction의 event/receipt 재사용, 또는 다른 transaction ID·상이 bytes로
  partial apply를 재시도
- 동일 transaction ID가 아닌 blind retry, 또는 partial apply 잔존 파일의
  path/hash/bytes가 expected와 다름
- event가 post-commit receipt를 참조해 hash 순환을 만듦
- `PACKAGE_ACTIVATED` 생략·순서 반전·no-op 경계 위반
- seq2의 `package_activation_authorization_binding` 또는
  `activation_quick_gate_binding` 누락·변조
- active checkpoint tail이 `PACKAGE_ACTIVATED`
- candidate checker와 active checker의 동일 fixture 판정 불일치
- `BYTE_EXACT_COPY` 대상의 candidate→final path, timestamp, event ID 또는 bytes
  1개 변경
- `SEALED_DETERMINISTIC_TRANSFORM` 대상에 미봉인 입력·함수·delta 사용
- resolved-output manifest 누락·상이 authorization/transaction 결속·final
  path/hash/bytes 불일치
- resolved-output manifest가 자기 자신 또는 post-check/incident/post-commit
  receipt를 member로 포함
- 허용 JSON Pointer 밖 checkpoint leaf 변경
- r022 canonical binding은 바뀌었지만 checkpoint의
  `implementation_gap_snapshot`, `current_work` 또는 `session_handoff`가 r021 잔존
- 사용자 승인 없는 write/apply 진입
- lock 해제, source CAS drift 또는 final no-replace 충돌 뒤 write 계속
- checkpoint projection과 live canonical binding 불일치
- manifest/event/transaction plan/authorization/receipt 중 하나에 duplicate JSON
  key, `NaN`, `Infinity`, `-Infinity` 또는 비정상 UTF-8 삽입

모든 negative fixture는 fail-closed하고 canonical 파일·checkpoint를 쓰지 않아야 한다.
candidate/active 공용 strict loader가 위 비정상 JSON을 parsing 단계에서 거부한다.

## 5. 적용과 rollback

승인 후 적용도 다음 두 단계로 분리한다.

### 승인 1 — 제어 전환과 r022 적용

한 application transaction에서:

1. lock 획득 전 r021, staged r022, 두 review, 승인, fresh quick gate와
   transaction plan binding을 재검증한다.
2. 모든 최종 file bytes와 seq1~3 checkpoint를 메모리/격리 임시경로에서 먼저
   완성하고 projected filesystem에 대해 candidate와 active가 공유하는 전체
   checker를 통과시킨다.
3. 단일 transaction lock을 획득하고 post-check와 receipt durable commit까지
   유지한다.
   lock 아래에서 source checkpoint/tail, r021, R002 pair·review,
   authorization·fresh quick gate·transaction plan CAS를 다시 검증한다.
4. promotion mode별 expected bytes만 temp `O_EXCL` → file `fsync` →
   no-replace rename → parent directory `fsync` 순서로 쓴다.
   `BYTE_EXACT_COPY`는 candidate hash/bytes와 final이 같아야 한다.
   `SEALED_DETERMINISTIC_TRANSFORM`은 봉인된 함수·입력·delta에서 승인 후 한 번
   생성해 transaction 동안 고정한 사전검증 output hash/bytes와 같아야 하며
   commit 중 재계산하지 않는다. write 전에 내구화한 resolved-output manifest의
   final path/hash/bytes와도 모두 일치해야 한다.
5. checkpoint 교체 직전에 3번의 모든 CAS를 다시 수행하고 pre-commit checker를
   통과한다.
6. checkpoint를 마지막 commit point로 원자 교체하고 parent directory를
   `fsync`한다.
7. post-commit checker를 통과한 뒤에만 최종 event/checkpoint hash를 단방향으로
   결속하는 post-commit receipt를 추가한다.

checkpoint commit 전 실패에서는 v2.5 activation을 선언하지 않는다. r021/v2.4가
계속 유효한 predecessor이고, 불완전한 candidate/application 산출물은 비활성
append-only 실패 receipt로 격리한다. 재개는 같은 transaction ID에서 이미 존재하는
각 final 파일이 expected path/hash/bytes와 byte-exact일 때만 이를 재사용한다.

checkpoint가 exact target으로 commit된 뒤에는 v2.5와 r022가 이미 유효하다.
checkpoint는 target인데 durable post-check PASS/receipt가 아직 없으면
`COMMITTED_POSTCHECK_PENDING`으로 도출하고, 같은 transaction recovery가 target
checkpoint·resolved manifest·receipt 부재를 재검증한 뒤 post-check를 반드시 다시
실행한다. post-commit checker가 실제 finding으로 실패하면
`COMMITTED_POSTCHECK_FAILED` incident로 기록하고 receipt 발행을 금지한다.
post-check가 PASS했지만 receipt 생성만 중단됐으면
`COMMITTED_RECEIPT_PENDING`으로 기록하고, 같은 transaction ID와 target
checkpoint를 검증한 recovery만 허용해 receipt를 멱등 완성한다. 이 상태에서는
일반 writer를 금지한다. recovery는 sealed transform을 다시 도출해
resolved-output manifest와 모든 잔존 파일에 byte-exact인지 확인한다.
어느 경우도 v2.4 활성으로
되돌려 해석하거나 canonical/checkpoint를 rollback·overwrite하지 않는다.
checkpoint가 예상 source/target 어느 쪽에도 정확히 속하지 않거나 잔존 파일 하나라도
expected와 다르면 fail-closed incident로 중지한다. 모든 상태에서 blind retry와
overwrite를 금지한다.

### 승인 2 — FP-008 leaf

r022 적용만으로 제품 구현을 시작하지 않는다. 별도 승인 뒤:

1. `WS-GOAL-EPIC-03-FP-008-R001` 후보를 materialize한다.
2. r022 Backlog canonical binding과 FP-047 completion event를 결속한다.
3. `GOAL_READY`와 fresh 19-check implementation-start gate를 통과한다.
4. 그 뒤에만 HEAVY single-writer 제품 구현을 시작한다.

## 6. 검증 판정

v2.5 후보 준비 완료조건:

- v2.4 predecessor bytes와 seq39 tail 불변
- r021 정본 불변
- r022 staged R002 독립검수 0/0/0
- v2.5 core 독립검수 0/0/0
- v2.5 builder `--check` 결정성
- positive 전환 fixture PASS
- 위 negative fixtures 전부 fail-closed PASS
- frozen v2.4 history와 기존 EPIC trace adapter 회귀 PASS
- 실제 canonical r022 경로 없음
- active checkpoint/Goal/product 변화 0

현재 상태는 `R002_REVIEWED_BUILDING_NON_EFFECTIVE_V2_5_CANDIDATE`이다.
후보가 검토된 뒤에만 승인 1을 사용자에게 요청한다.
