# WalkSafe 다음 단계 상세 로드맵 20260731 R001 독립 공격검수 R001

## 1. 대상과 최종 판정

| 항목 | exact 값 |
|---|---|
| review ID | `WS-WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R001-INDEPENDENT-SKEPTICAL-REVIEW-R001` |
| 검수일 | `2026-07-31` |
| target | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R001.md` |
| target SHA-256 | `77672dc0ecc592b2225acd3c247a91f54d4f2d33fb03126121c5b3d54d15e6ef` |
| target bytes / lines | `33,228 / 928` |
| target physical | `regular, non-symlink, mode=0664, nlink=1` |
| target terminal LF / CR / NUL | `true / 0 / 0` |
| target declared status | `NONCANONICAL_PLAN_ONLY / NOT_EXECUTABLE / OPEN` |
| verdict | `REVISION_REQUIRED_NON_EFFECTIVE_ROADMAP_ONLY` |
| findings | `BLOCKING=6 / MAJOR=5 / MINOR=0` |
| review authority | `NONE` |

이 검수는 위 target bytes만 대상으로 하며 target을 수정하지 않았다. R007
target과 두 review 및 기술 인계서도 읽기 전용으로 대조했다.

| 보호 입력 | 확인한 SHA-256 | 판정 |
|---|---|---|
| 기술 인계서 | `f5adb13001ba61bd44998415ff4af0e267dcec4bc7515a18ff8ad0a73830b6bb` | target 기록과 일치 |
| PRE-P R007 target | `02766312b1bbb00eb05e2789fe4d054cbf749407e6c5bd26dce62250e6dd98ef` | rejected history와 일치 |
| R007 formal review | `c2e6c226204d977db9706a58935125b472c829e5790c91ef83e3c866dde51269` | `18/4/0`과 일치 |
| R007 skeptical review | `0bc4fb67ab80acaae69ae8024b7f39156bc5c98d6f6df2e9bfa508fb2c91fe44` | `18/4/0`과 일치 |

R007 formal·skeptical finding ID를 전수 대조한 결과, 로드맵에는
`B01~B18`, `M01~M04`가 각각 정확히 한 번 등장한다. ID 누락·중복은
`0/0`이다. 그러나 번호가 있다는 사실과 required remediation, 실행 가능한
DAG 및 fail-closed acceptance가 닫혔다는 사실은 다르다. 아래 finding
때문에 이 로드맵의 §21 완료조건은 충족되지 않는다.

```text
VERDICT=REVISION_REQUIRED_NON_EFFECTIVE_ROADMAP_ONLY
BLOCKING=6
MAJOR=5
MINOR=0
TARGET_UNCHANGED=true
ROADMAP_ONLY=true
R007_FINDINGS_CLOSED=false
R007_FORMAL_18_4_0_REMAINS=true
R007_SKEPTICAL_18_4_0_REMAINS=true
SUCCESSOR_ACCEPTED=false
CURRENT_AUTHORITY=ABSENT_DENY_ALL
PLAN_EXECUTION_AUTHORIZED=false
JOURNAL_BOOTSTRAP_AUTHORIZED=false
STAGE_A_TO_G_AUTHORIZED=false
CHECKPOINT_OR_CANONICAL_WRITE_AUTHORIZED=false
PRODUCT_OR_OFFICIAL_CREDIT_DELTA=0
```

## 2. BLOCKING findings

### ROADMAP-SK-BLOCKING-001 — G6/G7이 `OPEN` 22행도 수용할 수 있다

Target 475~488행은 disposition에 `OPEN` 또는 `CLOSED_CANDIDATE`를 허용하고,
490~503행의 G6 산술에는 `OPEN=0`이나 `CLOSED_CANDIDATE=22`가 없다. 505행은
현재 22건 전부가 `OPEN`이라고 확인한다. 그런데 539~546행의 P7 수용조건은
단지 “22행 closure matrix exact”라고만 해 all-open matrix를 명시적으로
거부하지 않는다.

이는 22개 ID와 두 `0/0/0` review가 있다는 형식만으로 successor closure를
오독할 수 있는 fail-open gate다.

Required correction:

- G6에 `OPEN=0`, `CLOSED_CANDIDATE=22`, `failed_or_missing_evidence=0`을
  exact predicate로 추가한다.
- 각 row의 required remediation, positive·negative fixture와 independent
  verifier가 모두 PASS한 receipt 없이는 `CLOSED_CANDIDATE`가 될 수 없게 한다.
- G6 PASS 전에는 target freeze와 P7 review를 시작하지 않는 normative edge를
  추가한다.

### ROADMAP-SK-BLOCKING-002 — constructive verification 권한이 수용조건과 순환한다

Target 507~520행과 539~546행은 P7 수용 전에 실제 materialization, spawn,
exact10/full19/exact6 재계산 증거를 요구한다. 521~524행은 필요하면 사용자에게
실행 범위를 질문할 수 있다고 한다. 그러나 기술 인계서 377~394행과
434~441행은 successor의 두 review가 모두 `0/0/0`이 되기 전 실행 승인 문구를
준비하지 말라고 고정한다. 현재 권한은 `ABSENT_DENY_ALL`이다.

또한 288~299행의 actual post-checkpoint live-root receipt와 518행의
“checkpoint 이후 live-root”를 pre-P7 constructive fixture와 구분하지 않는다.
실제 Stage C 결과를 요구하면 H2는 accepted successor 뒤에만 시작하므로
`H1 acceptance → H2 execution → H1 acceptance` 순환이 생긴다.

Required correction:

- `CONSTRUCTIVE_FIXTURE_EVIDENCE`와 실제
  `STAGE_C_LIVE_ROOT/APPLICATION_RECEIPT`를 서로 다른 tagged type으로 분리한다.
- pre-P7 실행이 필요하다면 exact frozen subject, read/write/exec set, 격리
  root, nonce, 부작용 0, 만료·회수·one-use와 raw receipt를 가진 별도
  add-only authority bridge를 설계·검수·승인 대상으로 둔다.
- 그 bridge가 없으면 fixture specification까지만 허용하고 실제 실행이나
  checkpoint/application receipt 주장을 금지한다.
- fixture 권한·receipt가 bootstrap 또는 Stage A~G 권한으로 변환되지 않는
  negative invariant를 둔다.

### ROADMAP-SK-BLOCKING-003 — H2와 H3-1이 같은 selector-preflight를 두 번 정의한다

Target 555~571행은 H2에서 bootstrap→A→B→C→post-check/application receipt를
완료한다. H3는 H2 완료 전 금지인데, 640~651행의 H3-1은 다시
`selector-preflight transaction`으로 명명되고 Stage A/B/C 승인·receipt를
진입조건으로 하면서 post-check와 application receipt를 종료조건으로 둔다.
H2 receipt의 단순 검증인지 같은 transaction 재실행인지 구분되지 않는다.

이는 기술 인계서 34~38행이 history-only로 고정한 old Master S1 경로를
부활시키거나, H2에서 소비된 승인·nonce·receipt를 H3-1에서 재사용할 수 있는
경로다.

Required correction:

- H3-1을 H2에 병합하거나 `H2_RESULT_VERIFICATION_ONLY / NO_REEXECUTION`으로
  바꾸고 exact H2 application-receipt SHA를 단일 입력으로 고정한다.
- H3-1에서 journal, Stage A/B/C, post-check 또는 application receipt를 다시
  실행·발행할 수 없음을 명시한다.
- H2에서 소비된 approval, nonce, grant와 receipt의 cross-stage/replay를
  negative fixture로 거부한다.

### ROADMAP-SK-BLOCKING-004 — H3-2가 D→E→F→G의 별도 권한을 한 승인으로 축약한다

Target 653~663행은 “별도 successor 설계·검수·승인” 하나로 v2.5/r022 후보
검증부터 checkpoint-last apply까지 묶는다. 829~840행의 일반 표가 “각 D~G”를
언급하지만 각 stage의 predecessor, 산출물과 별도 review/approval 순서를
복원하지 않는다.

기술 인계서 346~364행과 396~413행은 D plan/review, E candidate
build/review, F attempt-scoped resolution, G exact resolved apply를 서로
대체할 수 없는 별도 단계로 고정한다. 현재 로드맵은 D 승인 하나가 E~G로
확대되거나 future approval을 재사용할 수 있는 fail-open 표현이다.

Required correction:

- H3-2를 `D → E → F → G` 네 transaction으로 분리한다.
- 각 단계마다 서로 다른 exact subject, frozen SHA, predecessor receipt,
  nonce, scope, expiry/revocation, one-use approval과 result receipt를 둔다.
- D는 설계·검수만, E는 candidate build와 candidate-bound review만, F는
  attempt-scoped resolution만, G는 exact resolved subject apply만 허용한다.
- G의 durable receipt 전에는 H3-3 frontier 재계산과 어떤 canonical·product
  credit도 금지한다.

### ROADMAP-SK-BLOCKING-005 — 전역 중단조건이 정상적인 downstream apply도 금지한다

Target 842~855행은 전역 중단조건으로 checkpoint·r021·제품 파일 수정 발생을
열거한다. 반면 570행은 fenced apply, 660~663행은 checkpoint-last apply,
681~689행은 최소 제품 구현과 canonical transition을 요구한다. phase 또는
authorized write set 예외가 없어 H2/H3의 합법적 미래 작업도 즉시 중단된다.

Required correction:

- immutable 보호는 H1과 승인 전 상태에 한정한다고 명시한다.
- H2~H5에서는 predecessor를 보존하면서 해당 단계의 exact reviewed
  write-set만 허용하는 별도 stop rule을 둔다.
- R007·review·r021 bytes는 계속 immutable history로 보존하되 r022 같은
  add-only successor와 exact 승인된 checkpoint transition을 구분한다.
- 범위 밖 수정은 계속 즉시 중단하고 공식 credit은 각 별도 gate 뒤에만
  허용한다.

### ROADMAP-SK-BLOCKING-006 — P5의 B09/B10 완료관계가 순환한다

Target 288~299행의 B09 완료는 모든 exact6 intent/input/result가 같은
live-root manifest를 결속해야 한다. 301~315행의 B10이 그 exact6
input/result를 정의·생산하며, B10은 다시 B09 live root를 입력으로 필요로
한다. 두 finding은 같은 P5이고, B04도 231~232행에서 B09와 B10을 모두
선행조건으로 둔다. 현재 문서에는 이 intra-phase cycle을 끊는 publication
순서가 없다.

Required correction:

```text
B09a live-root manifest/physical receipt publish
→ B10 exact6 typed input/intent/result/oracle
→ B09b same-root integration binding verification
→ B04 C/recovery receipt construction
```

위 순서를 normative DAG로 고정하고 각 edge의 exact artifact와 verifier를
명시한다. B09a의 publish receipt와 B09b의 integration receipt를 같은
“B09 완료” 한 상태로 선행 참조하지 않는다.

## 3. MAJOR findings

### ROADMAP-SK-MAJOR-001 — 여러 R007 required remediation이 이름만 매핑되고 물리 필드가 빠졌다

ID 산술은 맞지만 다음 필수 내용은 roadmap의 해당 절에서 누락됐다.

- B01 168~185행: formal review 122~142행이 요구한 review-consume canonical
  artifact와 `previous prefix digest → next prefix digest` append-only
  successor relation
- B05 234~244행: formal review 178~186행이 요구한 future approval artifact의
  strict schema/path/predecessor/signature domain 및 exact reviewed target SHA
- B09 288~299행: formal review 225~235행의 exact26과 함께 actual root를
  구성하는 `U1` physical identity/digest
- B14 358~371행: formal review 293~303행의 multi-command raw framing에 필요한
  command별 path, offset/length와 hash
- B17 398~411행: formal review 330~352행의 `MemberOriginMap`,
  lock/archive/direct-system-origin branches에 같은 tagged union을 적용하는
  완료조건

G6의 generic `schema/path/producer/hash/signature` 열만으로 위 field의 존재를
보장할 수 없다. 각 finding 절에 exact required output과 negative fixture를
추가하고 G6 row가 이를 직접 참조해야 한다.

### ROADMAP-SK-MAJOR-002 — G0와 G1~G7이 재현 가능한 gate가 아니다

Target 71~90행의 두 Quick2 명령은 보호 입력 전체의 SHA/stat, r022·v2.5·P/M
candidate 부재, authority와 target mutation 0을 모두 증명하지 않는다.
126~135행의 G1~G7은 gate 이름만 있고 input SHA, output path, verifier
Physical, argv/environment, expected exit/output과 raw receipt가 없다.

Required correction: deterministic G0 verifier 또는 exact
`sha256sum/stat/absence/diff` 명령과 기대값을 추가하고, G1~G7 각각에 frozen
input manifest, canonical output, verifier command, expected exit/result와
add-only receipt role을 정의한다.

### ROADMAP-SK-MAJOR-003 — 네 regression debt lane이 H1 수용 DAG에서 고아다

Target 585~633행은 네 debt를 successor 안에서 처리한다고 하지만 P1~P5와
G6는 R007 22행만 소유한다. 특히 R-A, R-C, R-D에는 owner phase, strict output,
fixture/verifier와 G7 predecessor가 없다. 기술 인계서 251~269행은 이 네
원인이 PRE-P lineage의 입력임을 명시한다.

Required correction: 네 lane을 mandatory G6/G7 row로 넣거나 H2 진입을 막는
별도 exact debt gate로 분리하고, 각 lane의 owner, immutable input, output,
command/oracle, receipt와 disposition을 둔다.

### ROADMAP-SK-MAJOR-004 — 별도 fixture/evidence가 frozen review subject에 완전히 결속되지 않는다

Target 509~524행은 frozen successor와 “별도 add-only fixture/verifier”를
말하지만 526~537행의 P7 freeze는 target SHA만 기록한다. 545행의
“evidence 결속”은 fixture/verifier/raw-output bundle의 exact SHA와 변경 시
재검수 규칙을 정의하지 않는다. 외부 bundle이 바뀌어도 target SHA와 기존
`0/0/0` review가 그대로 남을 수 있다.

Required correction: fixture, verifier executable, inputs, raw outputs와
receipts를 한 CAS evidence manifest로 묶고 두 review가 successor target SHA와
evidence-bundle SHA를 함께 결속하게 한다. 어느 하나라도 바뀌면 target과
review를 새 revision으로 다시 수행한다.

### ROADMAP-SK-MAJOR-005 — roadmap review `0/0/0`과 successor acceptance 상태가 type-safe하지 않다

Target 120~122행은 두 successor review를 “설계 수용 후보”라고 부르지만
539~546행은 generic “수용조건”, 555~562행은 별도 disposition 없이
`accepted successor`, 925행은 다시 roadmap 자체 review `0/0/0`을 사용한다.
38~39행과 927~928행의 설명상 부인은 올바르지만, 같은 unqualified
`target/review/0/0/0/accepted` 어휘가 서로 다른 subject class에 재사용돼
receipt나 자동화에서 roadmap review를 successor acceptance로 오독할 수 있다.

Required correction:

- `ROADMAP_REVIEW`, `SUCCESSOR_PLAN_REVIEW`, `CONSTRUCTIVE_EVIDENCE_REVIEW`를
  별도 subject type과 exact path/ID/SHA로 구분한다.
- roadmap review receipt가 successor review predicate를 만족할 수 없다는
  negative invariant를 추가한다.
- `accepted successor`를 별도 권한 상태로 쓰지 말고
  `same frozen successor SHA + two 0/0/0 reviews + exact evidence bundle`
  predicate로 치환한다.
- 이 predicate도 journal bootstrap이나 Stage A~G authority 또는 공식
  credit을 만들지 않는다고 명시한다.

## 4. 확인된 비결함과 claim ceiling

다음 항목은 공격 검토에서 target과 대조 문서가 일치했다.

- R007 target 및 formal·skeptical review의 physical SHA와 `18/4/0`
- roadmap의 finding ID 집합 `B01~B18`, `M01~M04`와 ID 누락·중복 `0/0`
- current v2.4 sequence `39`, canonical `r021/r021`
- R007 실행 금지, old Master S1 exact path history-only 선언
- 과거 approval·nonce·receipt의 일반 재사용 금지 문구
- formal `0/279`, device/event `0/0`, Gate `0/5`, production `0`,
  release `NOT_ELIGIBLE`

이 일치는 finding closure나 실행 가능성을 뜻하지 않는다. 특히 이 review가
`0/0/0`이 아니므로 roadmap target은 §21의 인계 완료조건을 충족하지 않는다.
후속 revision은 add-only 경로로 작성하고 exact 새 target에 대해 formal과
skeptical review를 다시 수행해야 한다.

이 review의 최대 claim은 frozen roadmap bytes에 대한 비권한 품질 판정이다.
다음을 생성하거나 변경하지 않는다.

```text
R007 closure = false
successor plan acceptance = false
journal/bootstrap/Stage A-G authority = absent
checkpoint/canonical/Goal/artifact/formal/device/Gate/production/release delta = 0
official project credit = 0
```
