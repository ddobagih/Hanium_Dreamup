# WalkSafe R024 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R024-INDEPENDENT-STRUCTURAL-REVIEW-R001
reviewer_agent = /root/r022_structural_review
reviewer_axis = CONTENT_CAS_THREAT_MODEL_SAFE_READER_APPLY_PATCH_I37_P2A_V5_P2C_V4_IPC_ANCHOR_P3_P4_P5_CHALLENGE_CLOSURE_R002_R001_TEST_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R024.md
target_sha256 = 356fb4308542b129e0d2ef5957328cfd0fdebc57bd8dbf297b0856675eb21a92
target_bytes = 26522
target_lines = 571
reviewed_at = 2026-08-02T13:22:42+09:00
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
authority_granted = NONE
```

## 범위와 관측 상태

R024 571줄 전체를 rejected R023과 두 독립 review, R016~R022 chain/reviews, 현재
three-source S0, immutable failed r001 content, reviewed R002 pair/review 및 예정 target
상태에 읽기 전용으로 대조했다. 검토 시작과 review 추가 직전 target은 SHA-256
`356fb4308542b129e0d2ef5957328cfd0fdebc57bd8dbf297b0856675eb21a92`, 26,522 bytes,
571 lines인 regular file이고 mode `0664`, uid/gid `1000/1000`, nlink 1이었다.
source/build/test/checker는 실행하지 않았고 source, r001, r002, canonical, checkpoint,
Goal, 제품을 수정하지 않았다. 이 review 파일만 add-only로 추가했다.

§1.1의 C0, reviewed R002 pair/review, R016~R023 roadmap/review SHA-256과 bytes는 현재
파일과 모두 일치했다. 현재 S0도 §1.2와 일치하고 test method universe는 정적 선언 기준
정확히 37개다.

| source | SHA-256 | bytes |
|---|---|---:|
| validation core | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| builder | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| test | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |

failed r001 exact-six SHA/bytes, semantic zero-authority와 NUL-name digest
`f4c9e884242b5acf190f0cb95d1567845173d8ae8e3a20919d81519f88b5d86c`가
§1.3과 일치했다. 검토 시작 시 R024 structural/skeptical review, P2A, P2C, P5 closure,
r002 root 및 r002 candidate review pair target은 모두 absent였다.

## R023 blocking 교정 판정

- R024는 evidence authority를 exact canonical content `{path,sha256,bytes}`로 한정하고
  dev/ino/mtime/ctime 비교와 continuous physical-history 주장을 성공 조건에서 제거한다.
  safe reader는 no-follow regular envelope와 read 중 stable FD를 검사하되 same-byte
  replacement/touch를 의미동등으로 명시 수용한다. 따라서 R023 first-fstat race는 더 이상
  권위 drift가 아니며 content-CAS threat model 안에서 닫힌다.
- original T0/T1/S1과 anchor를 보유한 동일 supervisor가 P3에서 끝나지 않고 P4 dual
  review, P5 closure add/검증까지 유지된다. P4는 candidate-current가 아니라 supervisor가
  미리 구성한 challenge를 task input으로 받고, review files와 P5 closure가 original
  challenge/candidate/anchor content를 결속한다. 마지막 P5 path/SHA/bytes를 candidate 외부
  handoff에 남기므로 R023 post-supervisor coordinated reseal 재채택도 닫힌다.
- apply_patch Add File과 long-lived pipe supervisor의 commit/nonce/ACK 상태기는 generated
  P2A/P2C/P5 및 independent P4 review를 content 기준으로 검증하며, process/nonce/content
  loss는 same-revision resume 없이 authority-zero terminal이다.

그러나 R024 자체가 권위로 삼는 P2A/P2C canonical JSON value schema 두 곳이 유일하지
않다. content hash를 authority로 단순화했으므로 이 ambiguity는 producer와 supervisor가
비교할 하나의 expected raw를 만들 수 없게 하는 직접적인 blocking이다.

## Findings

### BLOCKING-01 — P2A causal_max와 P2C authority_boundary의 exact JSON value가 정의되지 않는다

§5는 `causal_max`를 review rows에서 만든 Python tuple
`(semantic_reviewed_at_ns, role)`의 lexical max라고 정의하지만, P2A document 안의 exact
JSON representation을 정의하지 않는다. 아래 표현들은 같은 선택 알고리즘과 scalar를
보존하면서 모두 문구상 가능하다.

```json
{"causal_max":{"ns":123,"role":"r024_structural_review"}}
{"causal_max":{"semantic_reviewed_at_ns":123,"role":"r024_structural_review"}}
{"causal_max":[123,"r024_structural_review"]}
```

R021은 `{ns,role,component}`를 명시했지만 R024는 physical components를 제거하고 새 v5
schema를 정의했으며 R021 representation을 import한다는 조항이 없다. 따라서 과거 schema를
추정해 자동 승계할 수도 없다.

또한 §7의 P2C top-level exact keys에는 `authority_boundary`가 있으나 그 value를 §5의 exact
zero-authority object와 같다고 지정하지 않는다. §7의 “raw canonical rules equal P2A”는
UTF-8/sort/separators/LF/parser serialization 규칙만 같게 하며 nested value equality를
만들지 않는다. P2C에 P2A와 같은 object, 축약 zero-authority object 또는 다른
zero-authority extra field를 넣은 서로 다른 canonical bytes가 모두 나머지 §7 조건을
만족할 수 있다.

이 두 누락 때문에 parent가 commit한 P2A/P2C raw, supervisor가 재구성·검증할 raw,
source constants, 37-test fixture와 future closure verifier가 하나의 SHA/bytes에 합의할 exact
oracle이 없다. 최소 교정은 P2A `causal_max`의 exact key set/type/value mapping을 명시하고,
P2C `authority_boundary`를 §5 exact object와 deep-equal로 고정하는 것이다. 이 교정은
content-CAS DAG에 새 hash cycle을 만들지 않는다.

`BLOCKING=1`.

## 나머지 구조 판정

| 축 | 판정 | 근거 |
|---|---|---|
| content-CAS threat model | CLOSED | canonical SHA/bytes만 authority이고 physical identity/time/history는 비권위이며 same-byte equivalence와 미지원 hostile transient history가 명시돼 있다. |
| safe reader / failed r001 | CLOSED | normalized allowlist, no-follow ancestors/final, stable safe envelope, two content reads, exact directory names/member contents와 unsafe type/link rejection이 content model에 맞는다. |
| apply_patch / IPC continuity | CLOSED | intended content/binding commit, random one-use nonce, canonical ACK state와 long-lived supervisor memory가 external apply_patch operations를 검증하고 loss는 no-resume terminal이다. |
| I37 / S0 | CLOSED | R021 1~21, R022 22~24, R023 25~27, R024 28~30, failed-r001 31~37의 exact content-only schemas/order와 S0 cutoff가 유일하다. |
| P2A v5 | BLOCKED_BY_B01 | selection/observation, exact keys, authority/capture/pre-state와 canonical raw는 닫혔지만 `causal_max` nested representation이 유일하지 않다. |
| P2B / R002 source | CLOSED | exact intended three content rows를 mutation 전에 commit하고 different bytes/unsafe envelope를 거부하며 same-byte identity drift만 의도적으로 수용한다. |
| P2C v4 / mandatory anchor | BLOCKED_BY_B01 | T0/T1/S1 content DAG, digest, mandatory expected APIs와 sealed memfd는 닫혔지만 P2C authority object value가 exact하지 않다. |
| P3 candidate binding | CLOSED_AFTER_EXACT_P2C | package/output exact pointer와 logical seals, original expected comparison, exact-six content binding 및 PUBLISHED_NEW-only terminal이 유지된다. |
| P4 challenge | CLOSED_AFTER_EXACT_P2C | supervisor-original challenge가 candidate 외부 expected input이고 before/between/after recheck 및 recorded challenge/candidate bindings가 current-derived fallback을 금지한다. |
| P5 closure / external handoff | CLOSED_AFTER_EXACT_P2C | original challenge/anchor/candidate/reviews를 canonical closure에 결속하고 exact closure binding 없이는 future expected derivation을 금지한다. |
| cycle / retry | CLOSED | `S0 -> P2A -> S1 -> P2C -> package -> output -> reviews -> closure`는 one-way이고 existing/partial/different/fsync/supervisor ambiguity에는 recovery가 없다. |
| R002 namespace / r001 | CLOSED | exact R002 paths/IDs와 intentional R022 design-lineage literals, restricted historical r001/R001 및 content-only failed-r001 seal이 유지된다. |
| 37 tests | BLOCKED_BY_B01 | exact 37, skip0/exit0/OK와 declared negative axes는 충분하지만 P2A/P2C raw expected value가 하나로 정해지지 않는다. |
| zero authority | CLOSED | activation/canonical/checkpoint/Goal/product/formal/device/release write·credit은 0이고 finding이면 P2 이후 write가 열리지 않는다. |

## 명시적 실행·write count

| item | count |
|---|---:|
| source/build/test executions | 0 |
| source writes | 0 |
| r001 writes | 0 |
| r002 writes | 0 |
| capture writes | 0 |
| postimage writes | 0 |
| closure writes | 0 |
| canonical/checkpoint/Goal/product writes | 0 |
| formal/device/release credit | 0 |

R024는 `0/0/0`이 아니므로 `PASS` 또는
`R024_R002_CONTENT_CAS_SOURCE_CANDIDATE_CLOSURE_ONLY` 권한을 부여할 수 없다. P2A/P2C
exact value schema를 닫은 새 roadmap revision과 새 dual review가 필요하다.
