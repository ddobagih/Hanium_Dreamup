# WalkSafe R010 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R010-INDEPENDENT-STRUCTURAL-REVIEW-R001
review_type = INTERNAL_STRUCTURAL_REVIEW
reviewer_agent = /root/r010_structural_review
reviewer_session = /root/r010_structural_review@20260802-r001
independence_attestation = TRUE; 다른 R010 reviewer의 파일·결과를 읽거나 요청하거나 전달받지 않고 독립 검수했다.
target_sha256 = 731eed15ed4074134372666c0223f2aa4a1fdcdc0b3efc06e88b79c0fee541eb
target_bytes = 16233
target_lines = 291
verdict = REVISION_REQUIRED
blocking = 3
major = 2
minor = 0
```

## 범위와 방법

동결 R010, 그 normative base인 R009, 그리고 지정된 두 R009 review만 필요한 범위에서
대조했다. 두 R009 review의 identity와 결과는 각각 선언값과 일치했고, R009 skeptical review의
`3B/2M` 다섯 finding을 R010의 상태 전이, durable attempt, registry oracle, Git 역할 분리까지
역추적했다. 다른 R010 review는 열지 않았다.

후보 source를 실행·import·byte-compile·pycompile하지 않았고 network도 사용하지 않았다.
검수 시점에 R010 draft/final/evidence 세 root는 모두 absent였고, daylog preimage는 R010 §6의
type/mode/owner/nlink/SHA/bytes/lines와 일치했다. 저장소 v2.4 continuation 및 Goal graph 빠른
검사는 각각 통과했다.

## Findings

### B-01 — E1 뒤의 권한 상태가 없고 E1 authoring 실패는 R011에 도달할 수 없다

근거: R010:38-59의 유일한 상태기계는 plan-review barrier 뒤 `S1_E1_AUTHORIZED` 또는
plan finding용 `S_FAIL_R011_AUTHORIZED`까지만 정의한다. R010:61-63은 S1에서 허용되는 작업을
corrected draft/evidence authoring으로 한정한다. 따라서 E1 성공 뒤 `E2_SOURCE_REVIEW`를 여는
상태도 없다. 더 치명적으로 R010:86, 259-261, 286-288은 E1 실패 뒤에도 두 source review가
terminal이 되기 전 R011을 금지한다. E1이 seal/post/observation/marker 또는 review 가능한
source를 만들기 전에 실패하면 source-review task의 입력과 E2 write 권한이 모두 없고, plan
review는 이미 PASS라 plan-finding 전이도 사용할 수 없다. add-only/no-repair 조건 아래 영구
정지한다.

최소 교정: R011에서 `E1_TERMINAL_SUCCESS -> E2_SOURCE_REVIEW_AUTHORIZED`와
`E1_TERMINAL_FAILURE -> E_FAIL_R011_AUTHORIZED`를 별도 전이로 둔다. 실패 전이는 두 plan
review barrier, E1 호출의 terminal 결과, current root의 read-only exact snapshot, final absent,
no-repair를 결속하고 source-review 존재를 요구하지 않아야 한다. 성공 뒤에는 두 source review
terminal barrier를 거쳐 PASS면 E3, 어느 finding이면 R011로 가는 상태를 명시한다.

### B-02 — daylog block이 자기 postimage SHA를 포함해야 해 E3가 순환한다

근거: R010:239-243은 한 번의 append로 만든 `## R010 ...` block 안에 그 append가 끝난 전체
daylog의 postimage SHA/bytes/lines를 기록하라고 한다. bytes와 lines는 형식을 먼저 고정하면
계산할 수 있지만, block에 적힌 SHA는 그 SHA 문자열을 포함한 파일 전체의 SHA여야 한다.
따라서 patch 전에 값을 계산할 수 없고 patch 뒤 값을 넣으려면 금지된 두 번째 Update가
필요하다. 암호학적 자기 고정점을 찾는 것은 실행 가능한 commit protocol이 아니다.

최소 교정: terminal block에는 preimage identity, 고정된 block payload digest와 검증 결과만
기록한다. 전체 postimage SHA/bytes/lines는 append 뒤 local-memory처럼 daylog 밖의 기록에만
남긴다. 저장소 증거가 필요하면 E3 뒤 별도 literal add-only receipt 파일을 write set에 추가해
그 파일이 daylog postimage를 결속하게 한다.

### B-03 — durable claim의 최초 empty 상태와 crash-recovery absent 상태를 구별할 수 없다

근거: R010:112-118은 각 attempt root가 처음부터 exact empty라고 한 뒤 그 안에 claim과 marker를
만들도록 한다. 그러나 R010:128-130은 claim/marker absent면 child를 실행하지 않고 새 scratch를
요구한다. 최초 호출 직전의 empty root와 controller가 첫 파일을 durable하게 만들기 전에
crash한 뒤의 empty root는 byte·metadata상 같은 상태다. 재호출은 이를 최초 호출로 오인해 새
claim을 만들 수 있으므로, `publish-crash-before-first`의 child-before-output은 닫아도 claim
commit 자체의 before-first one-shot은 durable하지 않다. 이는 R009 skeptical B-02가 요구한
claim 생성 도중 crash와 새 claim 선택 방어를 완전히 닫지 못한다.

최소 교정: attempt root는 최초 호출 전에 `absent`여야 하고 controller가 pinned parent에서
literal `mkdirat` one-shot 생성과 parent fsync를 먼저 수행하게 한다. 이후 기존 attempt root는
empty여도 재사용하지 않고 `ATTEMPT_INCOMPLETE`로 끝낸다. 또는 별도 상위 reservation marker를
먼저 durable commit해 virgin/recovery를 구별한다. claim JSON의 literal schema 값과 모든
absent/partial/conflict 경로의 exact rc/stdout/stderr도 함께 고정한다.

### M-01 — E1의 exact 9/4 basename이 R010의 write boundary에 직접 고정되지 않았다

근거: R010:83과 253-254는 `draft exact 9`, `evidence exact 4`를 말하지만 basename을 직접
열거하지 않고 R009 §2/§5를 상속한다. R009 §2도 draft 이름을 열거하지 않고 다시 “R008과
같은 exact 9”라고 한다. 결국 신규 R010 authoring allowlist를 rejected predecessor 여러 세대의
간접 참조로 복원해야 하며, R010만으로는 Add File 대상과 crash-prefix 순서를 판정할 수 없다.

최소 교정: R011 본문에 draft의 `README.md`, `publish-source.py`, `build-projection.py`,
`run-readonly-sandbox.py`, `verify-bootstrap.py`, `runtime-closure.json`,
`source-input-manifest.json`, `draft-content-manifest.json`, `draft-content-manifest.sha256`와
evidence의 `authorization-gate.json`, `draft-post.json`, `e1-observation.json`, `E1.COMPLETE`를
literal 순서로 열거하고 E1 행이 그 목록만 참조하게 한다.

### M-02 — 30개 ID의 산술은 맞지만 expected outcome/snapshot oracle은 아직 exact하지 않다

근거: producer 22 + E1 recovery 4 + oracle mutation 4는 30개 unique ID로 정확하다. 그러나
R010:125-127의 `attempt.claim`은 schema field의 literal 값이 없고, R010:136-140의 first
observation은 canonical JSON을 어느 file/stream에 내는지 고정하지 않는다. R010:153-155의
`permitted projection prefix only`와 `preexisting exact tree only`, R010:173-175의 first-N
prefix도 literal path/bytes/metadata 또는 독립 digest가 없다. root/file snapshot key는
R010:210-215에 정의됐지만 이 세 허용 delta의 expected rows가 없으므로 producer와 verifier가
같은 잘못된 prefix를 공유해도 plan oracle과 대조할 수 없다. R009 skeptical M-01의
tautological-oracle 문제가 일부 남는다.

최소 교정: claim schema literal과 모든 case의 controller rc/stdout/stderr/observation destination을
직접 고정한다. status-drift, preexisting fixtures, 10개 crash prefix는 각 단계의 literal path,
type/mode/uid/gid/nlink/bytes/SHA 행 또는 R011 본문이 고정한 독립 oracle-manifest digest에
결속하고 before/first/second expected snapshot digest를 표에 넣는다.

## 확인된 구조

- 두 R009 review의 다섯 finding은 R010 §3 ledger에 정확히 한 번씩 등장하고 두 review가 모두
  terminal이 된 뒤 단일 root author가 successor를 쓰는 barrier는 review가 실제 생성된 경우의
  late-finding race를 닫는다.
- C13은 공통 Git confinement, producer의 선언된 create-only projection delta, verifier의
  read-only full equality를 역할별로 분리해 R009 M-02를 닫는다.
- source input은 이미 동결된 R010과 두 plan review만 current input으로 쓰고 미래 seal/evidence/
  source review/publication/projection을 제외하므로 새 evidence 자기순환은 찾지 못했다.
- `dynamic validation NOT_RUN`, candidate unexecuted, publication/projection 별도 plan-review gate는
  static source review가 실행 권한이나 공식 진척으로 승격되는 것을 막는다.

## 결론

R010은 R009의 late-review race, C13 역할 혼합, registry ID 누락을 유의미하게 교정했다. 그러나
E1 success/failure 이후의 권한 전이가 없고 failure successor가 source review 존재를 선행조건으로
삼아 결정적 liveness가 끊긴다. daylog self-hash commit은 실행 불가능하며 durable claim과 exact
oracle/write boundary도 아직 독립적으로 재구성되지 않는다. 따라서 R010 세 root는 absent로
유지하고 두 R010 plan review가 모두 terminal이 된 뒤 complete finding union을 결속한 R011만
작성해야 한다.
