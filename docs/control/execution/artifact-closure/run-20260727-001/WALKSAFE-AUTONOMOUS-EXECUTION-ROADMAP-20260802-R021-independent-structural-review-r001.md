# WalkSafe R021 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R021-INDEPENDENT-STRUCTURAL-REVIEW-R001
reviewer_agent = /root/r021_structural_review
reviewer_axis = R020_B01_B02_I_S0_S1_T0_P2C_DAG_TERMINAL_NAMESPACE_R001_R002_TEST_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R021.md
target_sha256 = b65d0242656b5898392fcab2b168832a6ce19577fa5e469cbbce2cfda777b919
target_bytes = 28757
target_lines = 601
reviewed_at = 2026-08-02T12:27:48+09:00
status = REVISION_REQUIRED
findings = BLOCKING=2 MAJOR=0 MINOR=0
authority_granted = NONE
```

## 범위와 관측 상태

R021 601줄 전체를 accepted R016, rejected R017/R018/R019/R020과 각 독립 review,
현재 three-source preimage, immutable failed r001, reviewed R002 pair 및 C0에 읽기 전용으로
대조했다. 시작과 review 추가 직전 target은 SHA-256
`b65d0242656b5898392fcab2b168832a6ce19577fa5e469cbbce2cfda777b919`, 28,757 bytes,
601 lines인 regular file이고 mode `0664`, uid/gid `1000/1000`, nlink 1이었다.
source/build/test/checker는 실행하지 않았고 이 review 파일만 add-only로 추가했다.

§1.1의 C0, reviewed R002 pair/review, R016~R020 roadmap/review SHA-256과 bytes는 모두
현재 파일과 일치했다. 현재 S0도 §1.2와 일치하고 test method universe는 정적 선언 기준
정확히 37개다.

| source | SHA-256 | bytes |
|---|---|---:|
| validation core | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| builder | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| test | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |

failed r001은 exact six만 가진 non-symlink directory이고 root+six content 및 full
`lstat`이 §2와 일치했다. UTF-8 bytewise basename sort와 각 terminal NUL로 계산한 현재
entry-name digest는
`f4c9e884242b5acf190f0cb95d1567845173d8ae8e3a20919d81519f88b5d86c`다.
r002 root와 candidate review pair, P2A capture, P2C postimage 및 두 R021 review target은
검수 시작 시 absent였다.

## R020 blocking 교정 판정

- R021 §4~§7은 28-row immutable registry `I`, P2B 직전까지만 live comparator인
  three-row `S0`, P2B 뒤의 `S1`을 분리한다. 정상 S0→S1 correction을 drift로 오판한
  R020 B01은 이 phase cutoff 자체로 닫힌다.
- `I`는 ordinal/row kind/role/path가 28개 모두 열거되고 FILE/DIRECTORY/lstat exact keys,
  root exact-six NUL digest, semantic review null/parse 규칙을 구분한다. max component도
  `mtime_ns|ctime_ns|semantic_reviewed_at_ns`, null 제외, tuple
  `(integer_ns,role,component)` Python lexical max로 고정된다. R020 structural B02의
  registry/tie-break 모호성은 닫힌다.
- P2A 뒤 동일 supervisor가 capture SHA/bytes/full `lstat` T0를 보유하고 세 S1 source에
  독립 상수로 봉인하며 새 process validator가 source-frozen T0를 expected tuple로 쓰도록
  했다. P2B 상수 봉인 전 crash/drift는 새 revision terminal이므로 R020 skeptical B02의
  receipt same-byte replacement 창은 닫힌다.

그러나 새 P2C 노드가 아래 두 독립 blocking 때문에 authority-bearing persistent postimage
baseline으로 유일하게 정의되지 않는다.

## Findings

### BLOCKING-01 — P2C의 exact bytes와 downstream binding schema가 유일하지 않다

§6.2는 **P2A capture receipt에만** UTF-8, sorted keys, compact separators, duplicate-key
금지, terminal LF인 canonical JSON을 명시한다. §8은 별도 schema version의 P2C를
`exact P2C JSON`이라고 부르지만 그 serialization 규칙을 재기술하거나 §6.2 규칙을
명시적으로 상속하지 않는다. 같은 JSON value를 whitespace, key order, terminal LF
유무가 다른 bytes로 쓸 수 있으므로 P2C content SHA/bytes는 하나로 정해지지 않는다.

또한 `source_postimage = 3 FILE rows, roles suffix _s1, canonical order core,builder,test`는
ordinal의 exact 값과 §4.2 FILE exact-key schema를 명시적으로 참조하지 않는다.
`capture_receipt_binding`과 `source_preimage_binding`의 path value도 §3 exact path에
대한 equality로 고정한다는 문구가 없다. 마지막으로 P2C SHA/bytes를 r002
package/output manifest에 동적으로 bind한다고만 하며 어느 두 artifact의 어떤 exact JSON
pointer, object keys, path/size/hash value를 생성·검사하는지 열거하지 않는다.

따라서 서로 다른 P2C bytes와 서로 다른 manifest representation이 모두 문구상 계약을
만족할 수 있고, core/builder/tests와 independent verifier가 고정할 유일한 oracle이 없다.
최소 교정은 successor에서 P2C canonical serialization을 완전하게 명시하고, 세 row의
exact ordinal/role/path/key schema 및 nested binding의 exact key/value를 열거하며, package와
output manifest의 exact JSON pointer와 binding schema를 고정하는 것이다.

`BLOCKING=1`.

### BLOCKING-02 — P2C 최초 content baseline과 same-revision terminal이 지속되지 않는다

§8은 P2C를 add-only receipt라고 하지만 공개 직후 그 receipt 자체의 SHA/bytes를 관측해
P3 전 expected baseline으로 보유·전달하는 절차가 없다. P2C physical tuple을 authority
input에서 제외하는 것은 가능하지만 content identity도 source constant, immutable seal,
validator argument 또는 deterministic reconstruction rule 어디에도 고정되지 않는다.
`validate_source_transition_evidence(root)`는 현재 P2C를 읽어 schema, capture/S0 binding과
live S1 equality를 검사할 뿐 최초 공개 bytes와 비교할 expected value가 없다.

예를 들어 최초 P2C를 같은 S1 rows와 capture binding을 유지하되 더 늦은 유효
`captured_ns`로 다시 쓴 P2C는 현재 schema와 `captured_ns >= max(S1 times)`를 만족한다.
P3 builder가 그 시점의 SHA/bytes를 동적으로 package/output manifest에 넣으면 이후 검사도
그 drifted value를 새 baseline으로 받아들인다. 그러므로 §8의 “P2C content drift는
hash/schema/S1 검사로 실패” 주장은 현재 계약에서 성립하지 않는다.

P2A와 달리 P2C Add File 도중/직후 crash, existing target, partial publication 또는 content
drift에 대해 same R021 reuse/repair/continuation을 금지하는 exact terminal 상태도 없다.
target absent 재확인과 “한 번 공개”만으로는 이미 생긴 artifact를 새 process가 authority
있는 성공으로 받아도 되는지 결정되지 않는다.

최소 교정은 successor에서 P2C canonical bytes를 이미 frozen inputs로부터 유일하게
재계산할 수 있게 하거나, 공개 직후 exact SHA/bytes와 full no-follow `lstat` post-add
baseline `T1`을 관측해야 한다. 후자를 택하면 P2C→P3는 T1을 메모리에 보유한 단일
uninterrupted supervisor만 수행하고, r002 package/output manifest의 exact JSON pointer에
P2C path/SHA/bytes/T1 중 authority로 삼는 exact fields를 봉인해야 한다. 후속 validator는
그 manifest-frozen binding을 expected value로 사용해 live P2C content를 모든 경계에서
비교해야 한다. 그와 함께 P2C crash/existing/partial/drift는 repair·replace·reuse 없이
`NEW_REVISION_REQUIRED_*_AUTHORITY_ZERO`로 끝나야 한다.

`BLOCKING=1`.

## 나머지 구조 판정

| 축 | 판정 | 근거 |
|---|---|---|
| I 28 / max | CLOSED | exact 28-row order, FILE/DIRECTORY schema, full lstat, root digest, 3-component max enum과 null/tie-break가 고정된다. |
| S0→S1 cutoff | CLOSED | S0 equality는 P2B mutation 직전 종료되고 뒤에는 historical evidence이며 S1 full tuple이 comparator가 된다. |
| P2A/T0 handoff | CLOSED | one committed microsecond-floor capture, nonfuture bounds, I/S0 reread, T0 source constants와 same-supervisor no-resume가 연결된다. |
| source/capture DAG | CLOSED_EXCEPT_P2C_IDENTITY | I/S0→P2A→S1→P2C→r002→reviews 방향이며 P2A/P2C는 future candidate hash를 담지 않아 hash cycle은 없다. 다만 두 findings 때문에 P2C node identity가 유일하지 않다. |
| r001 / R002 namespace | CLOSED | r001 exact-six full physical seal, R002 bundle/review/gate paths, IDs/events와 historical allowlist가 유지된다. |
| P3 retry/read-only | CLOSED | any-existing/file/symlink/race/fsync ambiguity는 new-revision terminal이고 `RECOVERED_EXACT_EXISTING` 제거, 성공 뒤 `--check`/checker read-only snapshot 계약이 분리된다. |
| tests | BLOCKED_BY_FINDINGS | 정적 method 수는 37이고 pre/post 37, skip 0, exit 0, OK를 요구하지만 P2C unique bytes/persistence oracle이 없어 선언된 subcase가 두 findings를 고정할 수 없다. |
| authority | CLOSED_ZERO | activation/canonical/checkpoint/Goal/product/formal/device/release write·credit은 0이고 finding이면 P2A 이후 write가 열리지 않는다. |

## 명시적 count/status/authority

```text
status = REVISION_REQUIRED
findings = BLOCKING=2 MAJOR=0 MINOR=0
source_build_test_executions = 0
source_writes = 0
r001_writes = 0
r002_writes = 0
capture_writes = 0
postimage_writes = 0
canonical_checkpoint_goal_product_writes = 0
formal_device_release_credit = 0
authority_granted = NONE
```

R021은 `0/0/0`이 아니므로 `PASS` 또는
`R021_R002_CAPTURE_SOURCE_AND_CANDIDATE_CORRECTION_ONLY` 권한을 부여할 수 없다. P2C
exact serialization/binding schema와 persistent content/terminal handoff를 닫은 새 roadmap
revision 및 새 dual review가 필요하다.
