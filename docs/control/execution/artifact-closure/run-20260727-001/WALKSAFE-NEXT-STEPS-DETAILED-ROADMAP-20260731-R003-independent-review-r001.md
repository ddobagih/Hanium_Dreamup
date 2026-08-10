# WalkSafe 다음 단계 상세 로드맵 20260731 R003 독립검수 R001

- 문서 ID:
  `WS-WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R003-INDEPENDENT-REVIEW-R001`
- 검토일:
  `2026-07-31`
- 판정:
  `FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR`
- findings:
  `BLOCKING=4 / MAJOR=2 / MINOR=0`
- 검수 subject type:
  `ROADMAP_REVIEW`
- 검수 범위:
  `NONCANONICAL_ROADMAP_ONLY`
- review authority:
  `NONE / ABSENT_DENY_ALL`

## 1. exact 검수 대상

- path:
  `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R003.md`
- SHA-256:
  `9f72ac89504ed2f3fdee5d50a53713acbb31068cbf4e43a1dce2a000ad618a62`
- bytes:
  `40,836`
- lines:
  `1,235`
- mode:
  `0664`
- type:
  regular file, non-symlink
- hard-link count:
  `1`
- line ending:
  LF-only, terminal LF 있음
- CR / NUL:
  `0 / 0`

검수 시작과 종료 시 target physical identity를 독립 재계산했다. 두 관찰의
SHA-256, bytes, lines, mode, type, hard-link count와 LF/CR/NUL 값은 위
값으로 동일했다. 이 review는 target을 수정하지 않았다.

## 2. 판정과 review ceiling

R003는 R002 formal `3B/1M`과 skeptical `7B/4M`의 핵심 구조 결함을
대부분 실제 field, edge, tagged subject와 predicate로 교정했다. 특히 다음은
확인됐다.

- Z0 `G0_SPEC_ONLY`가 successor보다 먼저 작성·검수되고, G0 actual result와
  분리돼 R002의 자기선행 순환이 없다.
- `GateExecutionSpec`과 `GateExecutionResult`는 expected/actual field를
  분리한다.
- `G1~G5-SPEC`은 설계 단계, `G1~G5-EVIDENCE`는 V1 뒤로 분리된다.
- `M04a → B16a → M04b → B16b`는 단방향이고 B16/M04는 각각 R007 한
  finding row만 차지한다.
- R007 mapping은 B01~B18 18개와 M01~M04 4개, 합계 22개 unique row이며
  matrix의 accountable owner는 각 row 정확히 하나다.
- debt는 D-A~D-D 네 개 unique row이고 모두 G4/G6 predecessor다.
- constructive H1 Stage-C type과 actual H2 Stage-C type은 disjoint하다.
- Stage D는 exact subject와 D 전용 one-use approval을 요구한다.
- 공식 artifact 표현은 `closed-equivalent=126/257`이고 새 closure나 credit을
  주장하지 않는다.

그러나 아래 네 BLOCKING과 두 MAJOR 때문에 exact R003는 successor를 끝까지
모순 없이 실행·검증할 findings-zero roadmap이 아니다.

```text
BLOCKING=4
MAJOR=2
MINOR=0
ROADMAP_REVIEW_FINDINGS_ZERO=false
ROADMAP_HANDOFF_COMPLETE=false
R007_FINDINGS_CLOSED=false
PRE_P_CLOSURE_SUCCESSOR_EXISTS=false
PRE_P_SUCCESSOR_READY=false
AUTHORITY_BOUNDARY_DECISION_EXISTS=false
CONSTRUCTIVE_FIXTURE_AUTHORIZED=false
JOURNAL_BOOTSTRAP_AUTHORIZED=false
STAGE_A_TO_G_AUTHORIZED=false
CHECKPOINT_OR_CANONICAL_WRITE_AUTHORIZED=false
PRODUCT_OR_RELEASE_AUTHORIZED=false
OFFICIAL_CREDIT_DELTA=0
```

이 review의 ceiling은 frozen R003 roadmap의 구성 가능성·완전성 판정뿐이다.
required correction을 설명하는 것은 R007의 `18/4/0`을 닫거나 successor,
G0, authority bridge, V1, H2~H5 또는 어떤 실제 write를 승인하는 행위가
아니다.

## 3. R002 required-correction 전수 crosswalk

### 3.1 formal `3 BLOCKING / 1 MAJOR`

| R002 formal finding | R003 근거 | 판정 |
|---|---|---|
| B001 — G0~G7 PASS propagation과 ready 우회 | §3.2, §5, §12 | PARTIAL; DAG와 ready 순서는 닫혔지만 exact predecessor result binding은 R003-BLOCKING-001, G6/G7 binding은 R003-BLOCKING-003 |
| B002 — B16↔M04 cycle | §6.2~§6.3, §8 B16, §9 M04, §10 | CLOSED |
| B003 — single owner와 multi-owner 모순 | §6.1~§6.2, §10 | PARTIAL; row ownership은 닫혔지만 약속한 G6 owner/milestone assertion은 R003-MAJOR-001 |
| M001 — successor 전 G0 spec 자기선행 | §3.1, §4 | CLOSED |

### 3.2 skeptical `7 BLOCKING / 4 MAJOR`

| R002 skeptical finding | R003 근거 | 판정 |
|---|---|---|
| B001 — G0 self-predecessor | §3.1, §4 | CLOSED |
| B002 — B16↔M04 cycle | §6.2~§6.3 | CLOSED |
| B003 — multi-owner row | §6.1~§6.2, §10 | PARTIAL; matrix는 CLOSED, G6 검증 누락은 R003-MAJOR-001 |
| B004 — V0/V1 authority provenance | §11.2~§12.4 | PARTIAL; governing decision과 G6/ready lineage는 생겼지만 기계적 provenance와 새 attempt authority는 R003-BLOCKING-004, G7 named binding은 R003-BLOCKING-003 |
| B005 — G7 bypass와 subject-class 혼동 | §2, §12 | PARTIAL; 순서와 class 분리는 CLOSED, G6/G7 receipt binding schema는 R003-BLOCKING-003 |
| B006 — Gate spec의 미래 result SHA | §5.1~§5.2 | CLOSED |
| B007 — Stage D approval 누락 | §13~§14 | CLOSED |
| M001 — H1 allowlist의 bridge 누락 | §11.1 | 원 finding은 CLOSED; mandatory gate-result write 누락은 별도 R003-BLOCKING-002 |
| M002 — reviewer actor identity/disjointness | §4.2, §11.2, §12.2, §19 | PARTIAL; P7 pair는 개선됐지만 G0/bridge의 동일한 identity·author exclusion은 R003-MAJOR-002 |
| M003 — changed subject의 evidence 재사용 | §11.3~§11.4 | PARTIAL; reuse 금지 자체는 명시됐지만 one-use decision을 갱신하지 않은 재실행은 R003-BLOCKING-004 |
| M004 — artifact “complete” 표현 | §15 | CLOSED |

## 4. BLOCKING findings

### ROADMAP-R003-BLOCKING-001 — gate별 exact predecessor PASS receipt가 result에 결속되지 않는다

R003 §3.2 165~173행은 predecessor가 `NOT_RUN/FAIL`이면 downstream을
`NOT_RUN`으로 전파한다고 선언한다. 그러나 §5.1의 `GateExecutionSpec`은
`predecessor_spec_sha[]`와 상수
`predecessor_required_status=PASS`만 가진다(257~273행). 실행 뒤 actual
관찰을 기록하는 §5.2 `GateExecutionResult`에는 다음이 없다(279~291행).

- exact predecessor result/receipt SHA 배열
- 각 predecessor의 observed `status=PASS`
- predecessor와 current result의 subject SHA equality
- predecessor attempt/authority lineage equality

따라서 spec에 “PASS 필요”라고 적은 것과 실제 어느 PASS receipt를 소비했는지
증명하는 것이 분리된다. 예를 들어 G1 result가 `FAIL`이거나 없는데도 G2
result가 자신의 status를 `PASS`로 발행하는 것을 schema가 거부하지 못한다.
§12.1의 G6 cardinality와 “모든 predecessor PASS receipt 결속”이라는
926~927행의 집계 문장은 각 중간 edge가 실행 시점에 올바른 predecessor를
소비했다는 것을 소급 증명하지 못한다.

Required correction:

1. 모든 `GateExecutionResult`에
   `predecessor_result_receipt_sha[]`, 각 observed status, predecessor
   subject SHA와 attempt/authority lineage를 직접 넣는다.
2. exact predecessor result set이 해당 `GateExecutionSpec`의
   `predecessor_spec_sha[]`와 일대일이며 전부 `PASS`인지 verifier가
   재계산한다.
3. 하나라도 missing, `NOT_RUN`, `FAIL`, wrong-subject 또는 wrong-attempt이면
   current result는 `PASS`를 발행할 수 없고 `NOT_RUN`이어야 한다.
4. 이 edge 검사를 G1-SPEC부터 G7까지 같은 schema와 negative fixture로
   적용한다.

### ROADMAP-R003-BLOCKING-002 — H1 write allowlist가 필수 G1~G5 gate result를 금지한다

§5.2는 실행 뒤 각 `GateExecutionResult`를 add-only로 발행한다. §3.2와
§5.3은 V1 전 G1-SPEC~G5-SPEC 다섯 PASS result, V1 뒤
G1-EVIDENCE~G5-EVIDENCE 다섯 PASS result를 요구한다(142~159,
298~307행).

그러나 §11.1의 폐쇄적 H1 write allowlist에는 G0 result, V1
raw evidence/result/CAS, G6/G7 result만 있고 이 열 개 gate result와
receipt가 없다(789~800행). §17은 allowlist 밖 write를 금지한다(1122~1127행).
따라서 정상 경로는 필수 result를 쓰면 stop rule을 위반하고, 쓰지 않으면
G6에 도달할 수 없다.

Required correction:

1. H1 allowlist에 G1-SPEC~G5-SPEC과 G1-EVIDENCE~G5-EVIDENCE 각각의
   exact spec/result/raw-output/receipt role을 명시한다.
2. 각 role의 path, publisher, write phase와 authority ceiling을 고정한다.
3. allowlist verifier가 mandatory role의 누락과 undeclared role을 모두
   negative fixture로 거부하게 한다.

### ROADMAP-R003-BLOCKING-003 — G6→P7→G7→ready가 정의되지 않은 named binding에 의존한다

§12.1은 G6 result가 successor SHA, evidence CAS SHA, authority decision
SHA와 predecessor PASS receipt를 결속한다고만 한다(926~927행).
`GateExecutionResult` 공통 schema에는 `tested_successor_sha`는 있지만
`evidence_cas_sha`나 `disposition_sha`가 없다(279~291행).

그런데 후속 predicate는 다음 named value를 직접 요구한다.

- P7 네 review의 같은 `G6 disposition SHA`(936~953행)
- G7의 successor/evidence/G6 disposition binding(964~979행)
- ready의 G7 successor/evidence/disposition과 G6 값 equality
  (981~995행)

`G6Disposition`의 artifact type/path/schema/SHA가 정의되지 않았고, G7
result가 위 세 named SHA를 직접 발행하는 schema도 없다. 이를
`actual_output_sha[]` 중 임의 member나 G6 receipt 자체로 추정하면 verifier별
해석이 달라진다. 따라서 올바른 P7 review subject와 ready equality를
기계적으로 산출할 수 없다.

Required correction:

1. `G6Disposition`을 별도 immutable artifact로 둘지 G6 result 자체를
   disposition으로 쓸지 하나를 선택하고 exact type/path/schema/SHA를
   정의한다.
2. G6 result에 named `tested_successor_sha`, `evidence_cas_sha`,
   `authority_decision_sha`, `disposition_sha`와 exact predecessor receipt
   set을 직접 둔다.
3. 네 P7 review receipt가 class별 subject와 같은 exact successor/G6
   disposition을, evidence pair가 같은 evidence CAS를 결속하게 한다.
4. G7 result에 named successor/evidence/disposition SHA와 four-review SHA,
   G6 receipt SHA를 직접 넣고 ready가 그 필드를 비교하게 한다.
5. missing/ambiguous/duplicate named binding과 wrong-class subject를
   fail-closed negative fixture로 검증한다.

### ROADMAP-R003-BLOCKING-004 — governing decision provenance와 one-use 재실행 lineage가 닫히지 않는다

§11.3은 decision subject에 handoff, successor, bridge와 두 bridge review
SHA를 넣고 issuer를 `explicit user`라고 적으며 one-use consume/close를
요구한다(825~848행). 그러나 decision schema에는 그 결정을 만든 exact
user-decision request/response artifact SHA, issuer actor/session identity,
발행 timestamp/signature domain과 검증 결과가 없다. 문자열
`issuer=explicit user`만으로는 roadmap author가 만든 파일과 실제 사용자
결정을 구분할 수 없다.

또한 각 V1 task/CAS는 decision과 consume receipt만 직접 결속하고 close
receipt와 bridge/review lineage를 직접 결속하지 않는다(879~891행).
successor/spec/input/fixture/verifier가 바뀌면 §11.4는 새 attempt로 일곱 task를
재실행하라고 하지만 새 bridge review, 새 governing decision와 새
consume/close를 요구하지 않는다(893~895행). 기존 decision은 이미
one-use로 소비됐거나 바뀐 bridge subject와 불일치하므로 두 번째 attempt를
승인할 수 없다.

Required correction:

1. governing decision에 exact request artifact SHA, immutable user response
   또는 승인 receipt SHA, issuer actor/session, timestamp, signature domain,
   subject digest와 provenance verifier result를 직접 결속한다.
2. decision verifier는 requester/author가 user approval을 자가발행하는 경로와
   문자열-only issuer를 거부한다.
3. 각 V1 task와 CAS가 bridge, 두 bridge review, governing decision,
   consume/close receipt 전체 exact SHA lineage를 직접 결속한다.
4. 재실행을 허용하려면 변경된 exact subject에 대한 bridge와 두 review,
   새 explicit user decision, 새 nonce와 새 consume/close를 모두 다시
   요구한다. 그렇지 않으면 V1/G1-EVIDENCE~G7은 `NOT_RUN`이다.
5. G6, G7과 ready가 같은 authority lineage를 직접 비교하고 replay,
   consumed decision, wrong subject/attempt와 fabricated issuer fixture를
   거부한다.

## 5. MAJOR findings

### ROADMAP-R003-MAJOR-001 — G6 exact predicate에 owner cardinality와 milestone 완전성 검사가 없다

§6.2는 actual row의 단수 `accountable_owner_phase`,
`contributor_phase_ids[]`, `ordered_milestone_receipt_sha[]`를 정의하고 G6가
owner cardinality와 milestone 완전성을 따로 검사한다고 한다(336~349행).
§10도 `accountable owner cardinality per row=1`,
`multi-owned=0`, `unowned=0`을 명시한다(765~773행).

하지만 §12.1의 G6 PASS 목록은 row count/unique ID/completion receipt만
요구하고 owner cardinality, multi/unowned count, ordered milestone
cardinality·순서·완전성을 포함하지 않는다(899~924행). 따라서 22개 row가
존재해도 contributor를 owner로 중복 승격하거나 B06/B09/B10/B16/M04의
필수 milestone receipt를 빼고 G6 PASS가 될 수 있다.

Required correction:

1. G6 exact predicate에 `owner cardinality=1 per row`,
   `multi-owned=0`, `unowned=0`을 넣는다.
2. 각 row의 required ordered milestone schema와 expected cardinality를
   고정하고 actual receipt set/order exact equality를 검사한다.
3. 특히 B06, B09, B10, B16과 M04의 contributor/milestone 누락·중복·역순
   negative fixture를 둔다.

### ROADMAP-R003-MAJOR-002 — reviewer identity와 disjointness가 G0·bridge까지 동일하게 검증되지 않는다

§12.2는 P7 review에 actor/session/tool/version/timestamp/signature-domain
identity와 formal/skeptical pair disjointness를 둔다(942~962행). 그러나
G0 spec review는 “두 reviewer”만 요구하고 identity schema, 서로 다른 actor,
G0 spec author와의 disjointness가 없다(217~223행). Bridge review도 pair가
서로 다른 actor라고만 하며 동일한 receipt identity/provenance schema가
없다(822~823행). P7의 `bridge_author/merge not in bridge_reviewers` 문장만으로
V0 시점에 이를 누가 어떤 receipt에서 검증하는지도 정의되지 않는다.

따라서 §21의 `review actor identity/disjointness specified=true`는 모든
mandatory review class에 대해 성립하지 않는다.

Required correction:

1. G0 spec, bridge, successor plan과 constructive evidence review 모두에
   하나의 공통 review-receipt identity schema를 적용한다.
2. 각 formal/skeptical pair의 actor inequality와 subject author/merge,
   fixture/verifier author, evidence producer의 class별 exclusion을 명시한다.
3. G0/V0/P7 각 단계 verifier가 해당 disjointness를 다음 단계 전에 검사하고,
   missing identity, same actor, same session alias와 author self-review를
   negative fixture로 거부한다.

## 6. 독립 확인한 PASS 축

다음 항목에서는 별도 finding을 발견하지 않았다.

- §1.1 보호 입력 manifest의 열거 SHA는 검수 시 실제 파일과 일치했다.
- Quick2 continuation/goal graph check는 각각 `PASS/PASS`였다.
- R007 mapping은 B01~B18, M01~M04 각각 정확히 한 상세 절과 matrix row를
  가지며 `18 + 4 = 22 unique`다.
- D-A~D-D는 네 개 unique debt이고 B18 사용 여부와 별개로 모두 G4/G6
  predecessor로 표시된다.
- Z0→two same-spec-SHA reviews→G0 result→successor 순서에는
  self-predecessor가 없다.
- `GateExecutionSpec`에는 미래 actual output SHA가 없고 actual field는
  `GateExecutionResult`로 분리됐다.
- 설계 단계의 G1~G5-SPEC과 V1 뒤 G1~G5-EVIDENCE timing은 문서상 분리됐다.
- M04a→B16a→M04b→B16b edge와 B09/B10/B06 milestone은 문서상
  단방향이다.
- 22-row matrix의 accountable owner 값은 각 행 하나이고 phase 합계는
  `18 BLOCKING + 4 MAJOR`와 일치한다.
- constructive Stage-C fixture와 actual Stage-C object의 tagged type,
  사용 시점과 authority ceiling이 분리됐다.
- D→E→F→G는 D를 포함해 각 단계 exact subject와 별도 one-use approval을
  요구한다.
- H2 actual apply는 한 번이며 이후
  `H2_RESULT_VERIFICATION_ONLY / NO_REEXECUTION`은 read-only다.
- artifact 상태는 `closed-equivalent=126/257`, open `131`로 표현되고
  전체 완료나 새 official credit을 주장하지 않는다.

이 PASS 축은 위 BLOCKING/MAJOR를 상쇄하지 않으며, 실제 R007 finding closure
receipt나 execution evidence를 뜻하지 않는다.

## 7. 최종 disposition

```text
ROADMAP_R003_EXACT_SHA_REVIEWED=true
ROADMAP_R003_TARGET_MUTATED=false
FORMAL_ROADMAP_REVIEW=FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR
FORMAL_FINDINGS=4/2/0
R002_FORMAL_3B_1M_CROSSWALKED=true
R002_SKEPTICAL_7B_4M_CROSSWALKED=true
R007_MAPPING_18B_4M_22_UNIQUE_VERIFIED=true
REGRESSION_DEBT_4_UNIQUE_VERIFIED=true
ROADMAP_HANDOFF_COMPLETE=false
R007_FINDINGS_CLOSED=false
PRE_P_SUCCESSOR_READY=false
AUTHORITY_BOUNDARY_DECISION_EXISTS=false
V1_OR_H2_TO_H5_AUTHORIZED=false
CHECKPOINT_CANONICAL_PRODUCT_RELEASE_AUTHORIZED=false
OFFICIAL_CREDIT_DELTA=0
```

다음 단계는 이 review와 별도 skeptical ROADMAP_REVIEW를 history로 보존하고,
위 required correction을 닫는 새 add-only roadmap successor를 작성한 뒤
그 exact SHA에 대해 formal/skeptical review를 다시 받는 것이다. 이 review
자체는 G0 spec 작성, successor 작성, authority 요청, evidence 실행,
checkpoint/canonical/product 변경 또는 R007 closure authority가 아니다.
