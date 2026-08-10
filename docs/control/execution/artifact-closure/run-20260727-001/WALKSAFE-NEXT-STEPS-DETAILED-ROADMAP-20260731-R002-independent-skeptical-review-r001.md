# WalkSafe 다음 단계 상세 로드맵 20260731 R002 독립 공격검수 R001

## 1. exact target과 판정

| 항목 | exact 값 |
|---|---|
| review ID | `WS-WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R002-INDEPENDENT-SKEPTICAL-ROADMAP-REVIEW-R001` |
| review subject type | `ROADMAP_REVIEW` |
| 검수일 | `2026-07-31` |
| target | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R002.md` |
| target SHA-256 | `a8a4a11505a3652fff051e78aafa56a2f15828b398e80b24d0280b3f31bdc77e` |
| target bytes / lines | `49,604 / 1,327` |
| target physical | `regular, non-symlink, mode=0664, nlink=1` |
| terminal LF / CR / NUL | `true / 0 / 0` |
| target declared status | `NONCANONICAL_PLAN_ONLY / NOT_EXECUTABLE / ROADMAP_REVIEW_PENDING` |
| verdict | `FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR` |
| findings | `BLOCKING=7 / MAJOR=4 / MINOR=0` |
| review authority | `NONE / ABSENT_DENY_ALL` |

검수 시작 시 target identity는 요청값과 exact 일치했다. 이 review는 target,
daylog 또는 다른 파일을 수정하지 않고 위 frozen bytes만 검수했다.

R001 roadmap formal `3/1/0`, R001 roadmap skeptical `6/5/0`, R007 target과
formal·skeptical `18/4/0`, 기술 인계서를 읽기 전용으로 교차 대조했다.
R002가 R007의 22개 finding ID와 주요 physical remediation field를 누락 없이
가져온 점은 확인했다. 그러나 아래 별도 roadmap 결함 때문에 다음 successor
지시가 아직 fail-closed가 아니다.

```text
VERDICT=FAIL_REQUIRES_ADD_ONLY_ROADMAP_SUCCESSOR
BLOCKING=7
MAJOR=4
MINOR=0
ROADMAP_REVIEW_FINDINGS_ZERO=false
R002_HANDOFF_COMPLETE=false
R007_FINDINGS_CLOSED=false
R007_FORMAL_18_4_0_REMAINS=true
R007_SKEPTICAL_18_4_0_REMAINS=true
PRE_P_CLOSURE_SUCCESSOR_EXISTS=false
PRE_P_SUCCESSOR_READY_PREDICATE=false
CONSTRUCTIVE_FIXTURE_AUTHORIZED=false
JOURNAL_BOOTSTRAP_AUTHORIZED=false
STAGE_A_TO_G_AUTHORIZED=false
CHECKPOINT_OR_CANONICAL_WRITE_AUTHORIZED=false
OFFICIAL_CREDIT_DELTA=0
```

## 2. R001 correction 재검수 요약

| R001 review 축 | R002 재검수 |
|---|---|
| all-open G6 bypass | `CLOSED`: 790~826행의 `OPEN=0`, `CLOSED_CANDIDATE=22`, debt 4와 evidence predicate |
| constructive spec/evidence/actual Stage-C type | `PARTIAL`: 833~851행에서 type은 분리했으나 V0 authority lineage가 G6/G7/readiness에 결속되지 않음 |
| H2 old-S1 A~C 재실행 | `CLOSED`: 984~998행의 `NO_REEXECUTION`과 replay 금지 |
| D→E→F→G 분리 | `PARTIAL`: E/F/G는 분리됐으나 D 전용 approval이 exact predecessor에서 누락 |
| phase별 stop rule | `PARTIAL`: H1/H2~H5는 분리됐으나 V0 bridge/review가 H1 write allowlist에서 누락 |
| direct B09→B10→B09b 순서 | `CLOSED`: 199~235행과 458~495행 |
| R007 누락 physical field | `CLOSED`: B01/B03/B05/B07/B09/B14/B17/M02/M04 세부 field 보완 |
| G0/G1~G7 exactness | `PARTIAL`: 공통 형식은 추가됐으나 G0 자기선행과 spec/result 시간 역전이 남음 |
| regression debt 4건 | `CLOSED`: 762~788행에서 owner와 G4/G6 predecessor 지정 |
| external evidence freeze | `PARTIAL`: CAS/G6 SHA는 추가됐으나 evidence가 tested successor를 결속하고 재실행돼야 한다는 조건이 없음 |
| subject-type 오인 방지 | `PARTIAL`: type 표는 추가됐으나 G7/ready predicate가 다시 서로 다른 review class를 합치고 G7을 우회 |
| reviewer independence | `PARTIAL`: 역할 배제는 있으나 formal/skeptical actor 불일치가 기계적으로 강제되지 않음 |
| mutable inventory | `CLOSED`: 241~257행에서 미래 P0 actual inventory freeze 요구 |
| formal phase DAG | `FAIL`: B16↔M04 cycle과 row owner cardinality 모순이 새로 남음 |

## 3. BLOCKING findings

### ROADMAP-R002-SK-BLOCKING-001 — G0가 successor 작성의 자기선행조건이다

Target 140~142행은 G0를 새 successor 작성 전 gate로 둔다. 그러나
144~162행의 literal 명령은 checkpoint, r021 두 파일과 runner 네 SHA만
검사한다. §1.1의 기술 인계서, R007 3종, R001 roadmap, DOC-01/05 및
164~176행의 v2.5/r022/P/M candidate 부재, authority, mutation predicate를
재현할 명령은 없다. R001의 두 roadmap review는 읽기 입력인데 immutable
manifest에도 없다.

더 치명적으로 178~181행은 나머지 `G0InputManifest`, stat/hash/absence,
result와 raw-output path를 미래 successor가 정의하게 한다. 즉 successor가
없으면 G0는 `NOT_RUN`이고, G0가 PASS하지 않으면 successor를 쓸 수 없다.
1289~1292행의 다음 Codex 지시는 이 순환을 그대로 실행한다.

Required correction:

- R002 자체 또는 별도 선행 read-only verifier에 모든 보호 path/SHA/stat,
  candidate absence, authority와 mutation 검사를 literal argv와 expected
  result로 고정한다.
- R001 formal/skeptical roadmap review path와 SHA도 immutable manifest에
  추가한다.
- G0가 future successor 없이 독립적으로 `PASS/FAIL/NOT_RUN`을 산출하게
  하거나, successor보다 먼저 작성 가능한 별도 `G0_SPEC_ONLY` subject와
  그 비권한 효과를 명시한다.

### ROADMAP-R002-SK-BLOCKING-002 — B16과 M04가 서로를 predecessor로 요구한다

Target 582~596행과 matrix 734행은 B16의 predecessor로 M04 graph
foundation을 요구한다. 반대로 694~710행과 matrix 740행은 M04 predecessor로
B16 node schema를 요구한다. 207~209행에서 둘을 같은 P3b에 나열해도
완료조건의 `B16 ↔ M04` cycle은 사라지지 않는다. G3와 G6의 cycle 0
predicate는 이 roadmap대로면 통과할 수 없다.

Required correction:

```text
M04a canonical graph type/extraction foundation
→ B16 exactly-one producer assignment
→ M04b complete membership/digest construction and verification
```

M04를 위처럼 분리하거나 B16에서 full M04 predecessor를 제거하고, matrix와
normative DAG를 동일한 단방향 edge로 고친다.

### ROADMAP-R002-SK-BLOCKING-003 — single-owner G6와 세 개의 multi-owner row가 모순이다

Target §8은 22행의 단일 소유권을 고정한다고 선언하고 actual row field도 단수
`owner_phase`를 요구한다(712~760행). G6는 `multi-owned=0`을 요구한다
(795~805행). 그러나 B06은 `P3b/P5d`, B09는 `P5a/P5c`, B10은
`P3a/P5b`를 owner로 기록한다(402~418, 458~495, 724, 727~728행).
따라서 세 row는 구조상 영구 `OPEN`이거나 G6 FAIL이다.

Required correction:

- finding별 `accountable_owner_phase`를 정확히 하나만 둔다.
- schema/instance/publication/integration 단계는 `contributor_phase_ids[]`와
  ordered `execution_steps[]`로 분리한다.
- subreceipt 전부를 결속하는 row-level completion receipt 한 개만 최종
  disposition을 발행하게 한다.

### ROADMAP-R002-SK-BLOCKING-004 — V0/V1 권한 provenance가 readiness에서 빠졌다

Target 828~875행은 기술 인계서가 successor 두 final review 전에는 실행
승인 문구를 준비하지 말라고 한 경계와 충돌하면서, bridge review 뒤
constructive fixture 승인을 질문할 수 있다고 한다. 873~875행은 충돌이면
선택지를 보고하라고 하지만 이미 존재하는 충돌을 mandatory stop으로
고정하지 않는다.

또한 879~900행은 “정확한 bridge approval”을 요구하지만 bridge target SHA,
formal/skeptical bridge review SHA, exact user approval artifact,
one-use consume/close receipt가 `ConstructiveEvidenceCASManifest`, G6, G7
또는 123~136행의 ready predicate에 필수 binding으로 없다. 따라서 무권한으로
만든 V1 output도 내용만 맞으면 evidence로 세탁될 수 있다.

Required correction:

- 현 기술 인계서와 V0의 충돌을 명시적 `BLOCKED_PENDING_GOVERNING_DECISION`으로
  고정하고, 새 exact 사용자 결정 없이는 V1 approval 질문과 실행을 금지한다.
- 별도 결정으로 bridge가 허용된 경우 `V0GateSpec/V0GateResult`를 만들고
  bridge SHA, 두 same-SHA review receipt, exact user approval artifact,
  unexpired/unrevoked 확인, one-use consume/close receipt를 결속한다.
- 위 lineage를 evidence manifest, G6, G7과 ready predicate 모두의 필수
  predecessor로 둔다.
- V1을 일곱 개 exact task row로 만들고 `unique=7`, `PASS=7`,
  `FAIL/NOT_RUN=0`, manifest membership `=7`을 G6에 요구한다.

### ROADMAP-R002-SK-BLOCKING-005 — G7을 실행하지 않고도 ready predicate가 true가 된다

Target 123~136행의 `PRE_P_SUCCESSOR_READY_PREDICATE`는 네 review의 finding과
일부 SHA equality를 검사하지만 `G7 PASS`, G7 receipt SHA, 네 review가 같은
G6 disposition SHA를 결속했는지, G7 뒤 mutation 0인지 요구하지 않는다.
Normative DAG도 199~220행에서 `G6 → P7`로 끝나며 `P7 → G7 → READY` edge가
없다. H2는 953행에서 이 predicate를 진입 기준으로 쓴다.

또한 219행과 289행은 “four same-subject reviews”라고 하지만 §2는
`SUCCESSOR_PLAN_REVIEW`와 `CONSTRUCTIVE_EVIDENCE_REVIEW`가 서로 다른 tagged
subject라고 명시한다. P7의 실제 binding 규칙과 gate 요약이 모순이다.

Required correction:

```text
G6 PASS
→ four class-specific P7 reviews
→ G7 verifier and immutable PASS receipt
→ PRE_P_SUCCESSOR_READY_PREDICATE
```

Ready predicate에 exact G7 receipt SHA, same G6 disposition SHA,
post-G7 target/evidence/review mutation 0과 class-aware binding을 추가한다.
“four same subject”는 공통 successor/G6 SHA와 evidence-review 전용
evidence SHA predicate로 바꾼다.

### ROADMAP-R002-SK-BLOCKING-006 — GateExecutionSpec이 미래 result SHA를 미리 요구한다

Target 259~276행은 successor가 freeze할 `GateExecutionSpec`의 모든 cell을
actual 값으로 채우라고 하면서 raw stdout/stderr/result SHA와 receipt를 필수
field로 둔다. 이 값은 877~890행의 V1을 실행한 뒤에만 존재한다. 따라서
pre-execution successor spec을 freeze할 수 없거나 미래 hash를 추측해야 한다.

Required correction:

- immutable `GateExecutionSpec`에는 input/spec SHA, expected output path/schema,
  argv/env/read-write set과 expected result만 둔다.
- add-only `GateExecutionResult`와 receipt에 actual exit, raw output SHA,
  semantic result와 execution metadata를 둔다.
- result가 exact spec SHA와 approval/attempt를 결속하고, spec/result 간
  future/self reference가 없음을 검사한다.

### ROADMAP-R002-SK-BLOCKING-007 — Stage D exact predecessor에 D 전용 approval이 없다

Target 984~998행의 H2 result verification은 read-only 확인이며 canonical
output receipt, publisher, schema와 authority effect를 정의하지 않는다.
그런데 1007행은 정의되지 않은 `H2 result-verification receipt`만 Stage D
predecessor로 쓰고 D 전용 approval을 생략한다. E/F/G에는 각각 전용
approval이 있고 1230행도 D approval을 전제한다. 기술 인계서 역시 D를 별도
승인 단계로 고정한다.

Required correction:

- H2 read-only result는 비권한 verification evidence로만 두고, 그 receipt가
  Stage D authority를 만들지 못하게 한다.
- Stage D predecessor를 exact Stage-C application receipt, exact D design
  subject와 D 전용 one-use approval artifact로 고정한다.
- D activation subject와 Stage D가 산출할 P-successor plan을 다른 tagged
  type으로 구분해, output plan의 미래 reviews를 D 사전 approval로 순환
  재사용하지 못하게 한다.

## 4. MAJOR findings

### ROADMAP-R002-SK-MAJOR-001 — H1 write allowlist가 V0 bridge와 그 review를 금지한다

Target 859행과 868행은 add-only authority bridge와 formal/skeptical bridge
review를 필수로 만든다. 그러나 1195~1200행의 H1 write allowlist는 roadmap,
successor plan, isolated fixture/verification bundle만 허용하고 별도 subject인
bridge와 bridge review를 포함하지 않는다.

Required correction: add-only bridge target과 두 비권한 review artifact를
H1 허용 목록에 명시하되, actual V1 execution은 governing decision과 exact
bridge approval 전 계속 금지한다.

### ROADMAP-R002-SK-MAJOR-002 — reviewer 독립성이 actor identity로 검증되지 않는다

Target 927~932행은 author/merge와 evidence producer의 review 참여를 일부
금지하지만 formal reviewer와 skeptical reviewer가 서로 다른 행위자인지
요구하지 않는다. “결론을 복사하지 않음”은 같은 actor가 두 review를 쓰는
것을 기계적으로 막지 못한다. Bridge reviewer의 author/merge 분리도 없다.
G7 predicate에도 reviewer identity와 disjointness 검사가 없다.

Required correction:

- 모든 review receipt에 reviewer identity, actor/session, tool/version 또는
  commit, timestamp와 signature domain을 둔다.
- formal actor `!=` skeptical actor를 plan/evidence/bridge 각 pair에
  강제한다.
- author, merge, fixture/verifier author, evidence producer와 해당 reviewer
  집합의 disjointness를 G7/V0 verifier가 검사한다.

### ROADMAP-R002-SK-MAJOR-003 — successor가 바뀌어도 old V1 evidence를 재사용할 수 있다

Target 902~914행은 successor/evidence/G6 SHA 중 하나가 바뀌면 reviews를
다시 수행한다고만 한다. 877~891행의 `ConstructiveEvidenceCASManifest`와
각 execution receipt에 `tested_successor_sha`, gate-spec SHA와 input-manifest
SHA가 필수라는 규칙이 없고, successor SHA 변경 시 V1 재실행과 새 evidence
manifest를 요구하지 않는다. 새 successor에 old evidence를 붙여 새 review만
받는 경로가 남는다.

Required correction: evidence manifest와 모든 task receipt가 exact
`tested_successor_sha`, spec SHA, input SHA와 attempt ID를 결속하게 한다.
Successor 또는 executable fixture/verifier/input SHA가 바뀌면 V1을 다시
실행하고 새 CAS manifest를 만들기 전까지 G6/G7을 금지한다.

### ROADMAP-R002-SK-MAJOR-004 — 공식 artifact 상태명을 completion으로 과장한다

Target 1108~1113행은 현재 공식 값을 `artifact complete = 126/257`로 쓴다.
기술 인계서 130~142행의 exact 표현은
`artifact closed-equivalent = 126/257`이며 전체 artifact completion이나
프로젝트 완료율이 아니다. R002 작성으로 공식 artifact credit은 0이므로
상태명을 강화할 수 없다.

Required correction: `artifact closed-equivalent = 126/257`로 복원하고
seq39 projection 값일 뿐 실제 artifact completion 또는 새 credit이 아니라는
ceiling을 인접 문장에 명시한다.

## 5. R007 crosswalk와 확인된 폐쇄

R007 formal·skeptical의 canonical finding ID set은 모두
`B001~B018`, `M001~M004`이며 각 `18/4/0`이다. R002 §6~§8에는 같은 22개 ID가
각각 한 번 있고 이전에 누락됐던 다음 field도 들어갔다.

- B01 review-consume, physical artifact field와 prefix successor
- B03 six physical capability-row fields
- B05 future activation path, reviewed SHA, nonce와 signature domain
- B07 raw/parsed path, extractor, wrapper와 publisher
- B09 U1 physical identity와 publication/integration receipt 분리
- B14 command raw path, offset/length/hash와 framing digest
- B17 `MemberOriginMap`, lock/archive/direct-origin 적용
- M02 U1/repository inventory를 포함한 closed enum
- M04 canonical digest-input serialization과 domain bytes

다음 R001 결함도 실질적으로 폐쇄됐다.

- all-open G6 bypass
- H2 A~C/old S1 재실행
- phase-global stop rule 충돌
- direct B09a→B10→B09b sequencing
- 네 regression debt의 owner와 G4/G6 predecessor
- mutable 7-target/29-symlink literal 재사용
- roadmap/successor/bridge/evidence subject type의 기본 분리

이 PASS는 roadmap이 필요한 remediation을 적었다는 뜻뿐이다. B16/M04
cycle, owner cardinality, V0/G7 authority gate와 위 findings 때문에 R007
finding closure 또는 future successor readiness는 아니다.

## 6. review ceiling과 disposition

이 review의 최대 효력은 exact R002 roadmap bytes에 대한 비권한
`ROADMAP_REVIEW` 품질 판정이다. 이 review는 다음 중 어느 것도 아니다.

- R007 finding closure evidence
- `SUCCESSOR_PLAN_REVIEW`
- `AUTHORITY_BRIDGE_REVIEW`
- `CONSTRUCTIVE_EVIDENCE_REVIEW`
- V0/V1, journal bootstrap 또는 Stage A~G approval/receipt
- checkpoint, canonical, Goal, artifact, formal, device/event, Gate,
  production 또는 release credit

따라서 R002 §23의 skeptical `ROADMAP_REVIEW=0/0/0` 조건은 충족되지 않는다.
R002 target과 이 review는 history로 보존하고, 위 findings를 닫는 add-only
roadmap successor를 exact 새 경로에 작성한 뒤 formal/skeptical
`ROADMAP_REVIEW`를 처음부터 다시 수행해야 한다.

```text
TARGET_UNCHANGED=true
ROADMAP_FINDINGS_ZERO=false
R002_USABLE_AS_EXECUTION_SUCCESSOR=false
R007_REMAINS_REJECTED_DEFERRED=true
CURRENT_AUTHORITY=ABSENT_DENY_ALL
NEXT_ACTION=ADD_ONLY_ROADMAP_SUCCESSOR_AND_NEW_DUAL_ROADMAP_REVIEWS
```
