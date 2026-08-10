# WalkSafe R020 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R020-INDEPENDENT-STRUCTURAL-REVIEW-R001
reviewer_agent = /root/r020_structural_review
reviewer_axis = R019_B01_CAUSAL_UNIVERSE_CANONICAL_CAPTURE_TRANSITION_DAG_R001_R002_RETRY_TEST_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R020.md
target_sha256 = 276f499ce235289cbe7b6f15abc835ce87ed0ce8ccd76d03df904bc5f5cd44f1
target_bytes = 25239
target_lines = 575
reviewed_at = 2026-08-02T12:15:51+09:00
status = REVISION_REQUIRED
findings = BLOCKING=2 MAJOR=0 MINOR=0
authority_granted = NONE
```

## 범위와 관측 상태

R020 575줄 전체를 accepted R016, rejected R017/R018/R019와 각 structural/skeptical
review, 현재 three-source preimage, immutable failed r001 및 absent r002/capture 상태에
읽기 전용으로 대조했다. target은 regular file, mode `0664`, uid/gid `1000/1000`,
nlink 1이며 위 SHA-256/bytes/lines와 일치했다. source/build/test/checker는 실행하지
않았고 이 review 파일만 add-only로 추가했다.

동결 chain의 R016~R019 roadmap/review, C0와 reviewed R002 pair/review SHA/bytes는 R020
§1.1과 일치했다. 현재 source preimage도 다음과 같이 §1.2와 일치하며 test method
universe는 정적 선언 기준 37개다.

| source | SHA-256 | bytes |
|---|---|---:|
| core | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| builder | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| test | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |

failed r001은 exact six만 가진 non-symlink directory이고 root+six의 content SHA/bytes와
dev/ino/mode/uid/gid/nlink/size/mtime_ns/ctime_ns가 R020 §2 표와 모두 일치했다.
r002 target, R020 capture target과 두 R020 review target은 검수 시작 시 absent였다.

## R019 B01 교정의 유효 부분

R020은 R019의 stale timestamp lower-bound 결함에 대해 persistent add-only receipt,
microsecond floor, raw `observed_ns`/`postcheck_ns`, semantic review time의 physical
non-future 조건, 선택 직후 snapshot 재독해, same-revision no-resume terminal을 도입했다.
`CAUSAL_MAX_NS < selected_ns <= observed_ns <= postcheck_ns` 방향은 미래 반올림을
허용하지 않고, receipt가 자기 hash나 future source/candidate hash를 주장하지 않는
방향도 맞다. 그러나 아래 두 finding 때문에 그 receipt와 boundary validator를 유일하고
실행 가능한 계약으로 만들지 못했다.

## Findings

### BLOCKING-01 — source preimage를 불변 causal predecessor로 취급해 허용된 P2B mutation과 모순된다

R020 §4.1의 exact causal-input universe는 §1.2 three-source preimage를 receipt row로
포함한다. §4.4는 P2A 직후뿐 아니라 **P2B 직후**, pre-build suite 뒤, P3 rename 직전과
P3 직후에도 `모든 causal-input row`의 live full tuple/hash/bytes/semantic time이 receipt와
exact equal이고 current max도 receipt의 `causal_max`와 같아야 한다고 요구한다. §6.1의
`validate_preparation_capture(root)`와 builder/wrapper boundary 호출도 같은 §4.4 equality를
그대로 요구한다.

하지만 P2B의 유일한 목적은 그 세 source를 한 번 수정하는 것이다. 정상 correction이면
적어도 source bytes/hash/size 또는 mtime_ns/ctime_ns가 preimage row에서 달라진다. 따라서
다음 두 조건은 동시에 성립할 수 없다.

```text
P2B_THREE_SOURCE_FULL_CORRECTION_ONLY
AND all(captured source-preimage row == post-P2B live source full tuple/hash/bytes)
```

즉, P2B가 성공하면 mandatory post-P2B gate가 반드시 실패하고, source를 바꾸지 않으면
P2B success 자체가 성립하지 않는다. 이 때문에 pre-build 37 suite와 P3 publication에는
도달할 수 없다. R019 B01을 닫으려던 persistent snapshot이 mutable transition의 before와
immutable predecessor를 구분하지 않아 새 fail-closed contradiction이 됐다.

최소 교정은 successor roadmap에서 다음을 exact하게 분리하는 것이다.

1. `IMMUTABLE_CAUSAL_PREDECESSORS`: C0/R002/R016~successor reviews와 immutable r001
   root+six. 이 집합만 P2A 이후 모든 boundary에서 captured full identity와 exact equal이다.
2. `MUTABLE_SOURCE_PREIMAGE`: 현재 three-source `S0`. P2B 직전까지 receipt의 S0
   hash/bytes/full tuple과 CAS하고, mutation이 시작된 뒤에는 S0 live equality를 요구하지
   않는다.
3. `P2B_SOURCE_TRANSITION`: exact `S0 -> S1` one-shot transition. partial/drift/crash이면
   repair/resume 없이 새 roadmap/receipt로 종료한다.
4. P2B 직후부터는 immutable set의 equality와 three-source `S1` postimage
   hash/bytes/full tuple의 시작/종료 equality를 검사한다. receipt의 S0 rows는 historical
   before-evidence로 보존하되 live comparator로 재사용하지 않는다.
5. DAG를 versioned node로 `S0 -> capture receipt -> S1 -> r002 -> candidate reviews`로
   선언한다. S1은 capture SHA/bytes를 pin할 수 있지만 capture는 미래 S1 hash를 담지 않아
   cycle이 없고, immutable predecessors는 capture로만 향한다.

`BLOCKING=1`.

### BLOCKING-02 — causal row registry와 tie-break schema가 canonical JSON을 유일하게 정하지 못한다

§4.1은 causal input을 “exact role/path”로 열거한다고 하지만 실제로는 범주만 적고,
예상 28개 row 각각의 exact `role`, repository-relative `path`, row kind를 열거하지 않는다.
§6.3의 exact authorization roles도 C0/R002/r001/source capture rows를 포함하지 않으므로
이를 보완하지 못한다.

또한 max 동률은 `(ns, role, component)`로 결정한다고 했지만 row schema에는
`component`가 없고 `mtime_ns`, `ctime_ns`, `semantic_reviewed_at_ns` 중 어느 exact string을
쓰는지 정의하지 않는다. r001 root row는 file SHA 대신 NUL-name digest를 둔다고만 하여
file-row의 exact `sha256`/`bytes` keys를 유지하는지, 별도 directory-row keys를 쓰는지와
`bytes` 의미도 정해지지 않았다. 결과적으로 동일 physical state에서도 서로 다른 role,
path spelling, root representation과 max provenance를 가진 여러 canonical bytes가 §4.3을
만족할 수 있다. source에 봉인할 receipt SHA/bytes와 독립 verifier의 expected bytes가
유일하지 않으므로 P2A authority gate로 사용할 수 없다.

최소 교정은 successor에서 exact 28-row registry를 표나 machine-readable literal로
봉인하는 것이다. 각 row는 `row_kind`, exact role, normalized repository-relative path와
file/directory별 exact keys를 갖고, directory root에는 예를 들어
`entry_name_digest_sha256`처럼 file SHA와 구별되는 key 및 size 처리 규칙을 둔다.
max 후보 component는 `mtime_ns|ctime_ns|semantic_reviewed_at_ns` exact enum으로 row에서
파생하고, final sort tuple과 null 제외 규칙, row count/order를 명시해야 한다. receipt
자체의 post-add physical baseline을 누가 보유하고 어느 same-process boundary에서
비교하는지도 one-shot/no-resume 계약에 연결해야 한다.

`BLOCKING=1`.

## 나머지 구조 판정

- R020 review의 semantic `reviewed_at`이 review physical mtime/ctime보다 미래가 아니어야
  한다는 조건과 PASS `0/0/0`/exact authority gate는 문구상 올바르다. 이 review는 finding이
  있으므로 그 PASS 전제와 P2A authority를 성립시키지 않는다.
- R018에서 닫힌 exact R002 bundle/review/application-gate namespace와 R002 IDs/events,
  immutable r001 root+six seal, existing/file/symlink/race/parent-fsync same-revision terminal,
  successful publication 뒤 read-only checks의 분리는 R020에도 유지됐다.
- pre/post exact 37 tests, skip 0, exit 0, `OK`, builder `--check`와 두 candidate checker,
  root+six physical snapshots 계약은 남아 있다. 본 review에서는 지시대로 실행하지 않았다.
- 의도한 receipt/source graph는 future/self hash를 capture에 넣지 않아 cycle을 피하려 하지만,
  BLOCKING-01의 preimage/postimage node 혼합을 교정하기 전에는 exact acyclic execution DAG가
  아니다.
- 성공 상태도 non-effective/not-approved/not-applied candidate에 한정되며 canonical r022,
  active checkpoint, Goal, full19, product, formal/device/release write 또는 credit은 0이다.

## 명시적 count/status/authority

```text
status = REVISION_REQUIRED
findings = BLOCKING=2 MAJOR=0 MINOR=0
source_build_test_executions = 0
source_writes = 0
r001_writes = 0
r002_writes = 0
capture_writes = 0
canonical_checkpoint_goal_product_writes = 0
formal_device_release_credit = 0
authority_granted = NONE
```

R020은 `0/0/0`이 아니므로 `PASS` 또는
`R020_R002_CAPTURE_AND_CANDIDATE_CORRECTION_ONLY` 권한을 부여할 수 없다. source preimage
transition과 immutable causal equality를 분리하고 canonical row registry를 완전하게 만든
새 roadmap revision 및 새 dual review가 필요하다.
