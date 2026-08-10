# WalkSafe 다음 단계 상세 로드맵 20260731 R001

- 문서 ID:
  `WS-WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R001`
- 작성일: `2026-07-31`
- 상태:
  `NONCANONICAL_PLAN_ONLY / NOT_EXECUTABLE / OPEN`
- 현재 권한:
  `ABSENT_DENY_ALL`
- 현재 checkpoint:
  v2.4 `ACTIVE`, sequence `39`
- 현재 canonical Gap / Backlog:
  `r021 / r021`
- 공식 성과 delta:
  `PRODUCT/CHECKPOINT/CANONICAL/GOAL/ARTIFACT/FORMAL/DEVICE_EVENT/GATE/PRODUCTION/RELEASE=0`

## 0. 이 문서가 하는 일과 하지 않는 일

이 문서는 다음 Codex가 무엇을 어떤 순서로 준비해야 하는지 설명하는
상세 작업지도다.

쉽게 말하면 현재는 “공사를 시작할 수 있는 설계”가 아니라 “설계를 다시
만들기 위한 검토 목록”까지 준비된 상태다. 이전 설계 R007에는 실행을
막는 문제 18개와 중요한 문제 4개가 남아 있다. 다음 작업자는 이 22개를
하나씩 닫는 새 설계를 만들고, 같은 설계 파일을 두 명의 독립 검토자가
각각 다시 확인해야 한다.

이 문서는 다음 행위를 허용하지 않는다.

- R007의 명령이나 Stage A~G 실행
- journal bootstrap
- candidate, resolved subject 또는 application receipt 생성
- 제품 코드, runner, 테스트, lockfile 수정
- checkpoint, canonical r021, Goal event 또는 257개 산출물 상태 변경
- formal, 실제 기기, Gate, 배포 또는 출시 성과 생성
- 과거 승인 문구·nonce·receipt 재사용

이 문서는 R007을 대체하는 실행 successor가 아니다. 이 문서에 대한
독립검수 `0/0/0`이 생겨도 R007의 `18/4/0`은 그대로 남는다.

## 1. 읽기 순서와 입력 고정

다음 작업자는 첫 쓰기 전에 아래 순서로 읽는다.

1. `WALKSAFE-PROJECT-TECHNICAL-HANDOFF-20260731-R001.md`
2. 그 adjacent independent review
3. PRE-P R007 target
4. R007 formal review
5. R007 skeptical review
6. live v2.4 checkpoint
7. 이 상세 로드맵과 그 adjacent review들
8. `daylog/2026-07-31.md`

### 1.1 보호 입력

| 입력 | SHA-256 | 처분 |
|---|---|---|
| 기술 인계서 | `f5adb13001ba61bd44998415ff4af0e267dcec4bc7515a18ff8ad0a73830b6bb` | 읽기 전용 |
| R007 target | `02766312b1bbb00eb05e2789fe4d054cbf749407e6c5bd26dce62250e6dd98ef` | rejected history, 수정 금지 |
| R007 formal review | `c2e6c226204d977db9706a58935125b472c829e5790c91ef83e3c866dde51269` | `18/4/0`, 수정 금지 |
| R007 skeptical review | `0bc4fb67ab80acaae69ae8024b7f39156bc5c98d6f6df2e9bfa508fb2c91fe44` | `18/4/0`, 수정 금지 |
| v2.4 checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | seq39, 수정 금지 |
| canonical Gap r021 | `f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a` | 수정 금지 |
| canonical Backlog r021 | `bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0` | 수정 금지 |
| DOC-01 | `a0c993257c1b80211c2e8e4db5aa464708ac4aba9d47ed9b434eda277ee6547f` | 수정 금지 |
| DOC-05 | `cea8b58fc5afa527614522481df56d69618c6fd1015c10b63d6cc8b6e0678c67` | 수정 금지 |
| runner | `4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d` | 수정 금지 |

### 1.2 첫 gate G0

아래 두 명령이 모두 PASS해야 plan-only successor 작성 준비를 계속한다.

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_4.py \
  --root . \
  --checkpoint docs/control/walksafe-project-continuation-checkpoint.json

python3 -B scripts/check_walksafe_goal_graph_v2_4.py \
  --root . \
  --checkpoint docs/control/walksafe-project-continuation-checkpoint.json
```

G0 종료조건:

- 보호 입력의 경로·SHA-256이 모두 일치
- v2.4 Quick2 `PASS/PASS`
- sequence `39`, canonical `r021/r021`
- r022, active v2.5, P/M physical candidate 없음
- current authority `ABSENT_DENY_ALL`
- 기존 target mutation `0`

하나라도 다르면 새 설계를 쓰지 않고 drift 사실만 보고한다.

## 2. 전체 작업 구조

다음 작업은 다섯 horizon으로 나눈다.

| Horizon | 목적 | 현재 실행 가능 여부 |
|---|---|---|
| H1 | R007의 22개 finding을 닫는 새 설계와 검증계약 작성 | plan-only 범위만 가능 |
| H2 | accepted 설계에 따른 journal·PRE-P staged transaction | 별도 승인이 생길 때까지 금지 |
| H3 | 새 control 전환 뒤 실제 frontier·제품·257개 산출물 진행 | H2 완료 전 금지 |
| H4 | 하나의 고정 후보로 formal·실기기·현장·접근성 검증 | 후보·권한·환경 준비 전 금지 |
| H5 | 5개 Gate, 배포, 운영, 이관과 종료 | H4 완료와 별도 승인 전 금지 |

현재 실제 다음 행동은 H1뿐이다.

## 3. H1 — R007 closure successor 설계

### 3.1 revision 원칙

- R007은 수정하지 않는다.
- 새 문서는 add-only successor다.
- R007 본문의 post-seq40 future label과 혼동되는 bare `R008` 이름을
  쓰지 않는다.
- 문서 ID와 경로에는 `PRE_P_CLOSURE_SUCCESSOR`처럼 이 lineage의 목적을
  명시한다.
- 한 revision을 byte freeze한 뒤에는 그 파일을 고치지 않는다.
- finding이 생기면 다음 revision을 새 경로로 추가한다.
- formal·skeptical review는 정확히 같은 frozen target SHA를 검토한다.
- 두 review가 각각 `0/0/0`이어야 “설계 수용 후보”가 된다.
- 설계 수용은 journal이나 Stage A 실행 권한이 아니다.

### 3.2 phase와 병렬 wave

| Phase | 담당 finding | Gate |
|---|---|---|
| P0 기준선 freeze | 없음 | G0 입력·상태·권한 고정 |
| P1 물리 타입·결정성 | B11, B12, B17, M02 | G1 tree/projection 전체성 |
| P2 권한·lifecycle | B01, B02, B03, B05, M03 | G2 authority FSM 폐쇄 |
| P3 producer·hash topology | B06, B16, M04 | G3 single producer·acyclic graph |
| P4 Stage-B·runtime·full19 | B07, B08, B13, B14, B15, B18, M01 | G4 raw evidence 재계산 가능 |
| P5 Stage-C·exact6·receipt | B04, B09, B10 | G5 post-check byte closure |
| P6 통합 수용계약 | 전체 22건 | G6 누락·중복·cycle 0 |
| P7 freeze·이중검수 | 없음 | G7 동일 SHA formal/skeptical `0/0/0` |

산술:

```text
P1 = 3 BLOCKING + 1 MAJOR
P2 = 4 BLOCKING + 1 MAJOR
P3 = 2 BLOCKING + 1 MAJOR
P4 = 6 BLOCKING + 1 MAJOR
P5 = 3 BLOCKING
TOTAL = 18 BLOCKING + 4 MAJOR
```

권장 병렬 순서:

```text
Wave 0: P0
Wave 1: P1 || P2
Wave 2: P3 || P4
Wave 3: P5
Wave 4: P6
Wave 5: P7 formal || P7 skeptical
```

P3는 P1·P2의 공통 type/authority freeze 뒤 시작한다. P4는 P1의
projection type freeze 뒤 시작한다. P5는 P2 capability, P3 topology와
P4 RuntimeActual 계약을 입력으로 한다.

## 4. 18 BLOCKING closure 상세

각 finding은 아래 한 절에만 귀속한다. 다른 절에서 같은 결함을 새 번호로
중복 계산하지 않는다.

### B01 — grant bytes와 lifecycle

- 입력:
  R007 preissuance/review grant 정의
- 새 설계 산출물:
  strict `CanonicalRootSpec`, payload 밖 signature wrapper,
  issue/consume/close/failure artifact, per-wave immutable subject prefix
- positive fixture:
  한 번 발행된 exact grant가 같은 head·subject·deadline에서 한 번만 소비됨
- negative fixture:
  replay, fork, prefix mutation, 자기참조 signature, undefined root 거부
- 완료 판정:
  JCS bytes와 signature domain을 독립 재계산하고 success/failure
  cardinality가 유일함
- 선행조건:
  P1 physical tagged union
- 권한 경계:
  schema와 fixture specification만 작성하며 실제 grant를 발행하지 않음

### B02 — revocation과 공통 guard FSM

- 산출물:
  issue/consume/lease/renew/revoke에 공통인 strict transition table
- 필수 field:
  current head, event time, original hard deadline, renewal ordinal,
  one-use, unrevoked, consumed state, failure predecessor
- positive fixture:
  legal edge 전수
- negative fixture:
  deadline reset, expired consume, revoked consume, duplicate consume,
  wrong head, renewal ordinal skip
- 완료 판정:
  A/B/C/recovery의 legal·illegal edge가 모두 열거되고 stage별 의미 차이 0
- 권한 경계:
  실제 lease·renew·revoke event 생성 금지

### B03 — literal capability pair

- 산출물:
  stage별 signed `(operation, CanonicalRootSpec)` 배열
- 금지:
  `its root`, `source allowlist` 같은 자연어 alias, glob, 자동
  operation×root cross-product
- positive fixture:
  exact anchored root와 exact operation만 허용
- negative fixture:
  부모·형제 root, alias expansion, undeclared operation 거부
- 완료 판정:
  서명된 bytes에서 모든 허용 pair를 독립 재열거할 수 있음

### B04 — C/recovery receipt tagged extension

- 산출물:
  stage-discriminated receipt union
- C/recovery 필수 binding:
  `N26`, `T1`, `X1`, `V1`, capability pairs,
  byte-equal `ApplicationReceiptTargetSpec`, predecessor/executor phase
- positive fixture:
  exact post-check target과 receipt가 같은 bytes를 결속
- negative fixture:
  공통 field만 있는 receipt, future/self reference, wrong phase 거부
- 완료 판정:
  strict schema/JCS와 SCC 검사가 self/future cycle 0을 확인
- 선행조건:
  B06, B09, B10

### B05 — D~G future approval boundary

- 산출물:
  D~G를 `NON_OPERATIVE_FUTURE_LABEL_ONLY`로 유지하는 strict boundary
- future activation 필수조건:
  별도 successor plan, 같은 target에 대한 두 `0/0/0` review,
  별도 exact user approval
- negative fixture:
  현재 token·grant·review만으로 D~G issuer 구성 시도 거부
- 완료 판정:
  H1/H2 receipt가 D~G 권한으로 변환될 경로가 없음

### B06 — T1/X1/StageCReviewBinding/V1 구성

- 산출물:
  네 객체의 strict payload, canonical path, publisher, domain-separated
  hash/signature 공식
- positive fixture:
  독립 구현 두 개가 동일 digest를 계산
- negative fixture:
  label-only value, future reference, self reference, publisher mismatch 거부
- 완료 판정:
  inward-only acyclic DAG에서 실제 bytes를 순서대로 구성 가능

### B07 — Stage-B exact10 결과와 repeat oracle

- 범위:
  두 환경 × BEFORE/AFTER/regression-A/regression-B/AFTER-repeat
- 산출물:
  strict `StageBValidationResult`, environment aggregate,
  AFTER-repeat byte-equality receipt
- 필수 상태:
  `PASS/FAIL/NOT_RUN`
- positive fixture:
  assertion별 raw source에서 exact10 결과와 aggregate 재계산
- negative fixture:
  opaque evidence array, repeat 누락, partial result, wrong environment 거부
- 완료 판정:
  두 환경의 다섯 invocation이 각각 유일한 result와 evidence를 가짐

### B08 — Stage-B input manifests와 SandboxIntent

- 산출물:
  세 signed `StageBSourceInputManifest`, complete `SandboxIntent`
- 필수 내용:
  ordered member, physical tagged type, sandbox alias, set digest,
  publisher, predecessor, literal argv/bind/environment/input/output/tracer
- publication:
  all references actual → input manifest → command manifest
- negative fixture:
  암묵 alias, broad mount, undeclared env, manifest 전 command publish 거부
- 완료 판정:
  actual sandbox argv와 signed intent가 byte-equal

### B09 — Stage-C live root 봉인

- 산출물:
  checkpoint durability 뒤의 strict `StageCLiveRootManifest`와 physical receipt
- 필수 내용:
  627 member, exact26 physical identities, seq40 tail, exclusions
- positive fixture:
  모든 exact6 intent/input/result가 동일 live-root manifest를 결속
- negative fixture:
  generic `DirPhysical`, invocation별 다른 live root, pre-checkpoint root 거부
- 완료 판정:
  live root를 독립 재열거한 결과와 manifest가 exact 일치

### B10 — exact6 input·command·assertion oracle

- 산출물:
  six typed input manifests/bundles, command framing,
  assertion extractor/result/verifier
- expected command count:
  `1/1/1/1/1/2`
- 필수 field:
  raw source, parser, expected, actual, PASS/FAIL, producer, canonical path
- positive fixture:
  immutable raw evidence에서 six 결과 독립 재계산
- negative fixture:
  string-only role, untyped assertion, row006 두 command 혼합 거부
- 완료 판정:
  모든 assertion이 rc0 주장 없이 재계산됨

### B11 — generated scratch 결정성

- 대상:
  `.next`, `dist`, coverage, Gradle/build, tsbuildinfo 등
- 산출물:
  literal `GeneratedScratchPolicy`, exclusion/collision order,
  empty 또는 seed placeholder 생성 규칙, pre/post digest
- positive fixture:
  현재 존재하는 일곱 generated target을 포함해 두 번 materialize한
  digest가 일치
- negative fixture:
  glob 제외, source member와 placeholder 충돌, 기존 scratch 잔존 거부
- 완료 판정:
  입력 tree 상태와 관계없이 같은 reviewed input은 같은 projection 생성

### B12 — BEFORE/AFTER projection schema와 단일 DAG

- 산출물:
  type-correct `BeforeProjectionManifest`,
  `AfterProjectionManifest`, 각각의 receipt, normative publication DAG
- positive fixture:
  유일한 topological order로 두 projection 구성
- negative fixture:
  상충하는 publication order, missing/extra field, 비정규 cast 거부
- 완료 판정:
  normative DAG가 정확히 하나이고 cycle 0

### B13 — RuntimeActual producer/consumer/use chain

- 산출물:
  exact resolved-subject payload/hash, consumer map, use receipt,
  normalized-output producer/path/schema
- 소비자:
  local, hosted, Stage-C exact6, recovery
- positive fixture:
  실제 trace consumer와 선언 map exact equality
- negative fixture:
  row006/recovery 누락, owner 없는 normalized output, hash domain 누락 거부
- 완료 판정:
  모든 consumer가 같은 actual scalar와 receipt를 결속

### B14 — full19 raw assertion extraction

- 산출물:
  19개 row별 raw evidence, extractor executable Physical,
  parser/JSON pointer, normalization, expected/actual/result
- 포함:
  structured pytest summary, nodeid, child/task/count,
  multi-command stream의 ordinal·offset framing
- positive fixture:
  immutable raw bytes만으로 19개 의미 assertion 재계산
- negative fixture:
  `REVIEWED_EXIT_CONTRACT`, rc0-only, unframed multi-command 거부
- 완료 판정:
  reviewer 설명 없이 verifier가 같은 결과를 계산

### B15 — Git·row18 complete closure

- 산출물:
  complete source/test/control/.git member arrays,
  gitfile→external gitdir anchored mapping,
  `GIT_ENVIRONMENT`, GIT executable
- positive fixture:
  actual trace와 literal closure equality, undeclared read 0
- negative fixture:
  broad prefix, 외부 gitdir 누락, Git executable 누락 거부
- 완료 판정:
  row18과 모든 Git consumer를 sandbox에서 동일하게 재현 가능

### B16 — FutureSealed single producer

- 산출물:
  role별 exactly-one producer, input manifest, canonical output path,
  resolution barrier
- positive fixture:
  producer 1, resolved role 1
- negative fixture:
  lane-d-final/after-control 이중 소유, producer 0, premature consume 거부
- 완료 판정:
  duplicate producer 0, unresolved role 0, DAG cycle 0

### B17 — mixed tree physical tagged union

- 산출물:
  regular/directory/symlink/direct-origin을 구분하는
  `TreeMemberPhysical`
- 필수 내용:
  relative link target, lstat identity, anchored copy,
  no-follow/no-escape semantics, tree digest
- positive fixture:
  현재 symlink 29개와 directory node를 보존해 반복 projection
- negative fixture:
  absolute link, root escape, target type mismatch, FilePhysical-only 축소 거부
- 완료 판정:
  source/projection/role binding이 모두 같은 tagged union을 사용

### B18 — source-only BEFORE 실행

- 산출물:
  BEFORE는 current positional CLI/input,
  AFTER는 successor CLI/routing을 쓰는 별도 literal spawn contract
- positive fixture:
  current runner bytes가 실제 지원하는 BEFORE command 성공
- negative fixture:
  BEFORE가 absent successor flag나 exact26 row16 routing을 요구하면 거부
- 완료 판정:
  BEFORE 의미를 AFTER overlay 없이 실제 spawn으로 검증

## 5. 4 MAJOR closure 상세

### M01 — row15 executable closure

- 최소 조사대상:
  `dirname`, `mktemp`, `chmod`, `rm`, `find`, `sort`와 final runner의
  모든 subprocess
- 산출물:
  final sealed runner의 executable role 집합
- 검증:
  subprocess trace와 declared closure exact equality
- 중단:
  FutureSealed runner가 subprocess를 제거했다고 근거 없이 가정

### M02 — typed late-bound invocation data

- 산출물:
  role별 strict `LATE_BOUND_ROLE`, allowed phase, constructor
- Stage A actual 허용:
  executable, environment, package closure
- Stage A actual 금지:
  future live root, transaction, exact26, runtime scalar
- 검증:
  phase/type 정적 checker와 wrong-phase negative fixture

### M03 — recovery scope 분리

- branch 1:
  `EXACT6_SUFFIX` — durable prefix와 never-dispatched suffix 필수
- branch 2:
  `APPLICATION_FINALIZATION` — actual `POSTCHECK_PASSED` 필수
- 검증:
  각 branch의 predecessor와 권한을 cross-use할 수 없음
- 중단:
  한 capability가 두 recovery 작업을 함께 허용

### M04 — review-subject graph 구성

- 산출물:
  Candidate/Resolved/per-wave manifest별 node/edge 추출 algorithm,
  deterministic node ID/order, producer/checker Physical, expected cardinality
- positive fixture:
  모든 nested Physical reference가 정확히 한 번 포함
- negative fixture:
  missing/duplicate node, edge 누락, 구현 정의 ordering 거부
- 완료 판정:
  두 독립 구현의 graph digest 일치

## 6. G6 통합 수용 matrix

P6 merge 담당자 한 명이 다음 표를 채운다.

| 필드 | 모든 22행의 필수값 |
|---|---|
| finding ID | B01~B18 또는 M01~M04, 중복 없음 |
| owner phase | P1~P5 중 정확히 하나 |
| predecessor | 실제 선행 finding·schema·physical object |
| strict output | schema/path/producer/hash/signature/DAG |
| positive fixture | 구성 가능한 정상 사례 |
| negative fixture | fail-closed 사례 |
| independent verifier | 입력·출력·판정 방식 |
| completion evidence | raw output 또는 deterministic receipt의 미래 role |
| authority ceiling | plan/review와 실행 권한 분리 |
| disposition | `OPEN` 또는 실제 검증 뒤 `CLOSED_CANDIDATE` |

G6 산술 검증:

```text
row count = 22
unique finding ID = 22
BLOCKING = 18
MAJOR = 4
unowned = 0
multi-owned = 0
implicit alias/glob = 0
duplicate producer = 0
graph cycle = 0
future/self reference = 0
```

이번 로드맵 작성 시점에는 모든 R007 finding disposition이 `OPEN`이다.

## 7. constructive review verification

R007 review는 글만 더 자세히 쓰는 것으로 수용되지 않는다. 새 successor가
실제 수용 후보가 되려면 frozen target과 별도 add-only fixture/verifier
범위에서 다음을 보여야 한다.

1. mixed tree와 generated scratch를 포함한 source snapshot materialization
2. BEFORE/AFTER projection의 반복 digest equality
3. current CLI BEFORE와 successor CLI AFTER의 별도 spawn
4. 두 환경 exact10의 structured result와 repeat equality
5. full19 raw evidence 독립 재계산
6. checkpoint 이후 live-root를 사용하는 exact6 독립 재계산
7. authority transition의 negative·replay·revocation·crash matrix

현재 권한으로 위 fixture를 실행했다고 주장하지 않는다. 다음 successor는
먼저 정확한 fixture 입력·출력·격리 경로·부작용 0 계약을 작성한다. 실제
verification 실행이 별도 허용 범위를 필요로 하면 그 범위만 사용자에게
질문한다. journal, candidate 또는 live source apply와 섞지 않는다.

## 8. P7 freeze와 독립검수

검수 순서:

```text
successor 작성
→ target bytes freeze
→ SHA-256/bytes/lines/mode/LF/NUL 기록
→ formal review
→ skeptical review
→ 두 review가 같은 target SHA를 봤는지 확인
```

수용조건:

- formal `BLOCKING/MAJOR/MINOR=0/0/0`
- skeptical `BLOCKING/MAJOR/MINOR=0/0/0`
- target SHA 동일
- 22행 closure matrix exact
- required constructive verification evidence 결속
- official delta 0

finding이 하나라도 있으면:

1. target과 두 review를 history로 보존한다.
2. 기존 파일을 고치지 않는다.
3. add-only next revision을 만든다.
4. 두 review를 처음부터 다시 수행한다.

## 9. H2 — accepted successor 뒤의 staged transaction

H2는 H1 수용 뒤에도 자동 시작하지 않는다. 아래 승인은 모두 서로 다른
subject·nonce·scope·receipt를 가져야 한다.

```text
accepted successor frozen bytes
→ journal-bootstrap 전용 사용자 승인
→ bootstrap-only receipt 검증
→ Stage A 전용 사용자 승인
→ immutable candidate/environment/pack build
→ candidate-bound independent review
→ Stage B 전용 사용자 승인
→ frozen subject validation
→ Stage C 전용 사용자 승인
→ fenced apply, post-check, application receipt
```

각 단계 공통 중단조건:

- input/CAS mismatch
- authority expired/revoked/consumed
- target review finding 존재
- wrong predecessor
- undeclared write/read/exec
- raw evidence 또는 receipt 누락
- crash state가 truth table에 없음

Stage C가 끝나도 formal·실기기·Gate·release 성과는 0으로 유지한다.

## 10. 네 regression debt lane

bounded regression은 `450 passed / 7 failed`에서 중단됐다. 두 번째
241개 suite는 `NOT_RUN`이다. 아래 네 debt는 successor 안에서 역사와
현재 상태를 분리한다.

### Lane R-A — Python lock epoch

- 현재:
  backend/hosted CPU Pillow `12.3.0`, local test lock `12.2.0`
- 원인:
  W5 local test projection 재생성 누락
- 다음 설계:
  canonical source input, exact pip-tools/runtime, add-only receipt,
  반복 lock generation equality
- 금지:
  기존 W5 receipt 소급수정, 즉석 lockfile write

### Lane R-B — runner epoch

- 현재:
  pytest 호출 live `4`, stale preflight 기대 `3`;
  discovered/assigned/unassigned `132/127/5`
- 다음 설계:
  current-control, historical debt, non-running staged candidate를
  구분하는 add-only successor runner/test contract
- 금지:
  seq39 runner나 checkpoint를 즉석 수정

### Lane R-C — Gateway epoch

- 현재:
  historical public exact4, live source exact5
- 다음 설계:
  historical replay와 current `/api/field-walk` 포함 validation 분리
- 금지:
  과거 exact4 receipt를 current exact5 증거로 재해석

### Lane R-D — artifact baseline epoch

- 현재:
  20260722 event-time README bytes와 current README bytes가 다름
- 다음 설계:
  historical replay와 current validation을 모두 통과하는 dual control
- 금지:
  과거 receipt hash 덮어쓰기

네 lane은 조사·fixture 준비를 병렬화할 수 있지만 common schema, hash
domain과 publication DAG는 P6 merge 담당자 한 명만 결속한다.

## 11. H3 — control 전환 뒤 제품·산출물 진행

이 절은 장기 순서다. Master 인계서의 S1 exact seq39→40/P17 경로는
history-only다. accepted replacement 없이 실행하지 않는다.

### H3-1 selector-preflight transaction

진입:

- H1 successor accepted
- journal/Stage A/B/C 각 승인과 receipt 유효

종료:

- reviewed successor가 정의한 exact post-check PASS
- application receipt durable
- 조기 main transition·제품·artifact credit 0

### H3-2 main control transition

기존 M15를 실행하지 않는다. H3-1의 실제 receipt를 입력으로 별도
successor 설계·검수·승인을 거친다.

목표:

- v2.5와 r022 후보의 실제 bytes·DAG·recovery를 다시 검증
- checkpoint-last atomic apply
- stale pointer 제거
- artifact/formal/device/Gate/release 조기 credit 0

### H3-3 frontier 재계산

전환 뒤에만 live dependency를 다시 계산한다.

- 현재 focus:
  `WS-GOAL-EPIC-03`
- 현재 ready frontier:
  EPIC-03, EPIC-12
- 현재 materialized leaf:
  없음

FP-008을 과거 계획대로 강행하지 않는다. 재계산 결과가 FP-008이면
별도 design/review/authorization 후 정확히 한 leaf만 materialize/start한다.

### H3-4 단일 Work Item loop

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

## 12. H3 제품 dependency roadmap

### EPIC-02·03

- EPIC-02:
  보행 시작 조건과 실제 lifecycle 연계 잔여
- EPIC-03:
  관리자 업무, privacy deletion 전 저장소 연계·부분실패 복구,
  암호화·키 분리·회전·감사, 외부 복구수단 provisioning

내부 구현이 끝나도 실제 관리자·실기기·기관 receipt·formal 결과가 없으면
관련 Gap 상한은 `PARTIAL`이다.

### EPIC-04·05·06

- EPIC-04:
  도착 확인, 이탈 중지·설명·선택·재탐색
- EPIC-05:
  승인 모델 fence, 거리·품질 gate, 위험 메시지·진동
- EPIC-06:
  wake word·청취 cue·오프라인 음성, 접근 가능한 안전정지

### EPIC-07~11

```text
EPIC-01 → EPIC-02, EPIC-03
EPIC-02 → EPIC-04, EPIC-05, EPIC-06
EPIC-02 + EPIC-03 → EPIC-07
EPIC-02 + EPIC-03 + EPIC-07 → EPIC-08
EPIC-05 + EPIC-07 → EPIC-10
EPIC-03 + EPIC-07 + EPIC-08 → EPIC-09
EPIC-09 + EPIC-10 → EPIC-11
```

- EPIC-07:
  승인 원본 schema·권리·보존·삭제 lifecycle
- EPIC-08:
  암호화 영속 queue·고정 ID·receipt·재부팅 복구
- EPIC-10:
  승인 dataset·평가·TFLite 동등성·model bundle
- EPIC-09:
  용량·비용·배터리·발열·안전정지
- EPIC-11:
  통합 후보·배포 전 종합 closure

후보 모델의 외부 신고 사용 차단은 P0 안전 우선순위다.

## 13. 257개 산출물 병렬 계획

현재 open 131의 lane은 다음과 같다.

| Lane | 수 | 가능한 준비 | 실제 종료조건 |
|---|---:|---|---|
| A `INTERNAL_READY` | 62 | 내용·trace·내부 review | required content·적격 승인 |
| B fact/owner/attest | 24 | 요청 packet·owner map | 실제 귀속 가능한 사실·결정·독립 확인 |
| C `INTERNAL_RUN_REQUIRED` | 24 | 환경·명령·receipt schema | 실제 raw output·receipt·review |
| D `REAL_EVENT_PENDING` | 21 | 대상·권한·일정 준비 | 정당하게 발생한 actual-event receipt |

검산:

```text
62 + 24 + 24 + 21 = 131
duplicate = 0
omitted = 0
```

운영 원칙:

- Lane A와 독립적인 B 준비는 병렬 가능
- C는 관련 code/build/data/candidate·권한·환경 준비 뒤 실행
- D는 증거를 만들기 위해 실제 사건을 조작하지 않음
- root/merge 한 명만 canonical 후보를 작성
- artifact 상태 상승 전 exact257 replay와 독립검수 필수
- `126/257`은 전체 프로젝트 완료율로 표현하지 않음

## 14. H4 — 정식 검증

EPIC-12의 `READY`는 준비 branch만 열렸다는 뜻이다.

진입조건:

- EPIC-04, 05, 06, 08, 09, 10, 11 완료
- 하나의 immutable candidate
- 승인된 formal plan, 환경, 기기, 참여자, 권한

동일 candidate로 수행할 항목:

- formal 279
- 실제 기기와 현장 보행
- TalkBack·접근성
- 장시간 사용, 배터리, 발열, 용량
- 장애·재부팅·복구
- 보안·개인정보
- AI 모델·dataset·동등성

현재 값은 계속 다음과 같다.

```text
formal PASS = 0/279
actual device/event = 0/0
```

내부 테스트나 계획 문서는 이를 대신하지 않는다.

## 15. H5 — Gate, release, deployment, operation

정식 검증 뒤에도 다음을 각각 실제 증거로 마쳐야 한다.

1. release Gate 5개, 모두 `waived=false`
2. 권한 있는 release decision
3. signed immutable candidate와 production configuration
4. deploy·canary·smoke
5. rollback·backup/restore·recovery
6. 운영 안정화·비용·권한·데이터 처리
7. 운영 이관 또는 승인된 종료

현재 값:

```text
Gate PASS = 0/5
production deployment = 0
release = NOT_ELIGIBLE
project = NOT_COMPLETE
```

절차서 작성, mock 실행 또는 내부 검토만으로 이 값을 올리지 않는다.

## 16. 사용자·외부 승인 시점

현재 사용자에게 요청할 실행 승인은 없다.

향후 질문은 내부 ready work가 소진되고 exact subject가 준비됐을 때만 한다.

| 시점 | 사용자에게 제시할 것 | 승인으로 허용되는 범위 |
|---|---|---|
| successor 수용 뒤 | frozen SHA, 두 `0/0/0`, bootstrap exact scope | journal bootstrap만 |
| bootstrap receipt 뒤 | exact Stage A attempt와 write set | candidate/env/pack build만 |
| candidate review 뒤 | exact Stage B subject와 commands | validation만 |
| Stage-B PASS 뒤 | exact Stage C transaction·recovery | fenced apply/post-check만 |
| 각 D~G 준비 뒤 | 서로 다른 subject·nonce·receipt | 해당 한 stage만 |
| formal 준비 뒤 | candidate·plan·device·participant·authority | 승인된 시험 범위만 |
| release 준비 뒤 | 모든 Gate와 운영 증거 | 명시된 release/deploy action만 |

과거 승인, “계속 진행해” 같은 일반 지시, 계획 review 또는 다른 stage
receipt는 위 승인을 대체하지 않는다.

## 17. 전역 중단조건

다음 중 하나가 생기면 즉시 write를 멈추고 현재 사실만 보고한다.

- 보호 입력의 bytes 또는 Quick2가 달라짐
- R007·review·checkpoint·r021·제품 파일 수정 발생
- 권한 없는 journal/candidate/apply 시도
- 22 finding의 owner 누락·중복
- strict schema에 alias, glob, unknown field, 구현 정의 ordering이 남음
- producer 중복, graph cycle, future/self reference
- raw evidence에서 assertion을 재계산할 수 없음
- 한 independent review라도 `0/0/0`이 아님
- formal·기기·Gate·release의 조기 credit
- 실제 사람·기기·외부 서비스·비밀·유료 자원이 필요한데 권한이 없음

진행되지 않는 lane은 blocker와 필요한 입력을 기록해 미루고, 독립적인
ready lane만 계속한다.

## 18. 팀 구성과 merge 규칙

권장 역할:

| 역할 | 책임 |
|---|---|
| root/merge | 보호 manifest, 공통 type/domain/DAG, 최종 revision freeze |
| lane P1 | physical tree·scratch·projection |
| lane P2 | authority·lifecycle·recovery |
| lane P3 | producer·hash·graph |
| lane P4 | Stage-B·runtime·full19·Git/runner |
| lane P5 | Stage-C·live root·exact6·receipt |
| formal reviewer | 구성 가능성·schema·DAG·수용조건 |
| skeptical reviewer | 우회경로·권한확대·crash·oracle 반례 |

파일 충돌 방지:

- lane은 자기 finding 절과 closure row 초안만 작성
- 공통 schema 이름·domain separator·publication DAG는 merge 담당만 결속
- reviewer는 target을 수정하지 않음
- daylog는 root/merge가 한 번만 통합

## 19. 예상 시간 범위

아래는 약속이 아니라 계획용 범위다.

| 작업 | 대략적 범위 | 변동 요인 |
|---|---:|---|
| G0 재검산·22행 원장 | 1~2시간 | drift 여부 |
| P1·P2 계약 초안 | 1~3 작업일 | type/authority 반례 |
| P3·P4 계약 초안 | 2~4 작업일 | trace·oracle·Git closure |
| P5와 P6 통합 | 1~3 작업일 | live-root·exact6·DAG |
| isolated constructive verification | 1~3 작업일 이상 | 환경·fixture 허용 범위 |
| formal·skeptical review 한 회 | 각 반나절~1일 이상 | finding 수 |
| H2 staged transaction | accepted 설계 뒤 별도 산정 | 승인·환경·recovery |
| H3 내부 제품·artifact | 여러 작업일~수주 | 실제 frontier·dependency |
| H4~H5 | 장비·사람·외부 권한 일정에 종속 | formal·현장·Gate·배포 |

일정 단축을 위해 finding을 생략하거나 검수 대상을 바꾸지 않는다.

## 20. 다음 Codex용 즉시 실행 문장

```text
먼저 WALKSAFE-PROJECT-TECHNICAL-HANDOFF-20260731-R001.md와
WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R001.md 및 각 adjacent
review를 읽어라. live checkpoint·r021·R007 3종·runner의 고정 SHA를
read-only 재검산하고 v2.4 Quick2를 실행하라. 값이 같으면 R007을
수정하거나 실행하지 말고, 18 BLOCKING+4 MAJOR closure matrix를 정확히
한 번씩 다루는 add-only PRE_P_CLOSURE_SUCCESSOR 계획의 다음 revision을
작성하라. target bytes를 freeze한 뒤 같은 SHA를 formal·skeptical 두
검수자에게 맡겨라. 어느 review라도 0/0/0이 아니면 기존본을 보존하고 새
revision으로 반복하라. 둘 다 0/0/0이고 constructive verification까지
결속되기 전에는 bootstrap 또는 Stage A 승인을 질문하지 마라.
```

## 21. 이 로드맵의 완료조건

이 로드맵 자체는 다음 조건을 만족하면 인계용으로 완료다.

- R007 최종 `18 BLOCKING / 4 MAJOR`를 누락·중복 없이 각각 한 번 매핑
- immediate H1과 long-term H2~H5의 진입·종료·승인 경계 분리
- old Master S1 exact sequence와 R007 실행 금지 명시
- current seq39/r021/r021와 공식 zero-delta 명시
- 사용자 승인 시점과 전역 중단조건 명시
- target physical identity 동결
- 같은 target을 본 formal·skeptical roadmap review 각각 `0/0/0`

이 완료는 R007 finding closure, successor 수용, 제품 진척 또는 실행
권한을 뜻하지 않는다.
