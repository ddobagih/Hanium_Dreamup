# WalkSafe R019 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R019-INDEPENDENT-STRUCTURAL-REVIEW-R001
reviewer_agent = /root/r019_structural_review
reviewer_axis = STRUCTURAL_R017_R018_CLOSURE_OBSERVED_TIMESTAMP_R001_LSTAT_R002_NAMESPACE_DAG_PUBLICATION_EPOCH_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R019.md
target_sha256 = 3c21f917793b0ffbe5f6aa892e1d73d122799fcfe005c22f2f289571b45d4eb2
target_bytes = 21461
target_lines = 448
reviewed_at = 2026-08-02T12:03:26+09:00
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
authority_granted = NONE
```

## 범위와 physical identity

R019 448줄 전체를 R017/R018 roadmap과 각 독립 structural/skeptical review, accepted
R016 roadmap과 두 PASS review, 현재 core/builder/test preimage, C0와 reviewed R002 pair,
immutable failed r001에 읽기 전용으로 대조했다. 대상은 regular file, mode `0664`,
uid/gid `1000/1000`, nlink 1이며 위 SHA-256/bytes/lines와 일치했다. source, builder,
candidate, wrapper 또는 test는 실행하지 않았고 이 review 파일만 add-only로 추가했다.

동결 physical pins는 모두 실제 bytes와 일치했다.

| 대상 | SHA-256 | bytes |
|---|---|---:|
| C0 checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| R002 pair manifest | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| R002 PASS review | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |
| R016 roadmap | `49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2` | 12,690 |
| R016 structural PASS | `eced95aa2343427b24f80f8865e2fa8e9e3f320ad48c74fab7970d646a1fac87` | 3,758 |
| R016 skeptical PASS | `9bae362ad4c1f0c4b2567b534197938f77d825b5d73d76dbd3a184542649bc0e` | 5,354 |
| rejected R017 | `6437009fe07692f56e9c4320ce645bc9b13f5b28d8fe6d448e26c634c6f442a5` | 13,494 |
| R017 structural review | `cbcb6a14e4af2b82ec84c300799575fd99a81b65e9e8236177ff7221be958e81` | 9,466 |
| R017 skeptical review | `d10ba282eee810c58a0d76d8658a4a21d937dcd4c76882a4936dc97538d61fe4` | 5,326 |
| rejected R018 | `2a1df2cd5f0fdccb3183b15607b6a017ab6b2556d661ea16e9b6caaa9d9255f7` | 17,914 |
| R018 structural review | `a87d59d2a3a5bbf601b4c3d9c3fdf8adaa1511c99e1c538a03db7be474a3b584` | 8,180 |
| R018 skeptical review | `8fa2bfe82ac5b0b4a7a9d3708f9cf3407e995c8c82bd74d79ce2e8a4161879a4` | 4,551 |

현재 source preimage도 core
`e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f`
(180,432 bytes), builder
`d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175`
(51,153 bytes), test
`00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523`
(69,685 bytes)로 일치했다. 실제 test method universe는 37개다. r002 target과 두
candidate review는 absent/non-symlink 상태였다.

## R017/R018 findings closure

R019는 timestamp만 바꾸고 R018의 나머지 교정을 잃지 않았다.

- R017의 exact-existing 모순은 §1.2, §6, §9에서 recovery success path와 두 기존 retry
  success oracle의 수정을 명시적으로 허용하고, existing/race/parent-fsync 뒤 동일
  revision 재시도를 `NEW_REVISION_REQUIRED_EXISTING_R002_TARGET_AUTHORITY_ZERO`로
  닫는다. 공개 뒤 `--check`, present-state core와 두 CANDIDATE wrapper는 read-only
  검증으로 별도 분리된다.
- R017 namespace 결함은 exact r002 bundle/review/application-gate와 gate-derived
  plan/receipt/resolved paths, transaction/document/checkpoint IDs 및 seq1/2/3 event ID를
  모두 열거해 닫는다. builder resolved path는
  `validation.RESOLVED_OUTPUT_MANIFEST_REL`에서 파생하고, historical allowlist 밖 old
  r001/001 current-construction literal은 P3 전 residual scan 0을 요구한다.
- R017의 r001 physical-preservation 결함은 content CAS와 no-follow root+six `lstat`
  seal, exact entry/type/mode/uid/gid/nlink, invocation snapshot equality 및 epoch 사이
  equality를 함께 요구해 닫는다.
- R018의 fixed-time provenance inversion은 P1 dual PASS와 모든 P2 precondition 뒤에만
  선택 가능한 observed timestamp로 대체됐다. R017/R018의 나머지 closure는 source delta,
  test negative oracle, pre/post publication gate와 candidate review 범위에 그대로 남는다.

## observed timestamp causal oracle

선택 알고리즘은 immutable r001 root+six, rejected R017/R018와 각 reviews, current R019와
두 PASS review의 `max(mtime_ns, ctime_ns)` 최댓값을 strict lower bound로 사용한다.
`time.time_ns()` 관측값을 microsecond 경계로 내림하므로 선택값은 관측 시점보다 미래가
아니며, `SELECTED_NS > PREDECESSOR_MAX_NS`인 첫 값만 허용한다. source patch 뒤에는
선택값이 세 changed source 각각의 mtime/ctime과 immediate post-patch wall time 이하인지
검사한다. 동일 predecessor snapshot이 유지된다는 조건에서는 다음 인과 순서를 검사할 수
있다.

```text
r001/R017/R018/R019 dual-review evidence
  < one-shot P2_PREPARATION_STARTED_AT == physical PREPARED_AT
  <= each changed-source mtime/ctime
  <= immediate post-patch observation
```

core/builder/test의 base literal exact equality, `+09:00`, six fractional digits와
post-patch 재선택 금지가 source 간 backdating 또는 서로 다른 timestamp를 차단한다.
`PREPARED_ON` 및 synthetic core/request/receipt/quick/seq3 시간은 한 timezone-aware base
helper의 exact offset으로 파생되고 independent calendar literal scan 0이 요구된다.
strict chronology, exact `+1us`, expiry와 599/600/601 monotonic oracle도 유지된다. 이
oracle 하나라도 실패하면 P3 write 0과 새 revision이 명시돼 있다. 그러나 아래 finding처럼
그 lower-bound snapshot 자체의 지속성과 TOCTOU equality는 닫히지 않았다.

## provenance DAG와 immutable r001

provenance 역할은 R016 accepted predecessor, R017/R018 rejected history, R019 current
execution authority로 구분된다. R019와 두 plan review가 먼저 physical pin되고 그 hashes가
P2 source에 들어간 뒤 r002 outputs가 생성되며, candidate dual reviews와 future dynamic
receipt는 이후 physical bytes를 결속한다. 미래 output/review/receipt hash를 과거 source
상수로 추측하지 않으므로 review-before-subject, self-hash 및 dependency cycle은 0이다.

실제 r001은 exact six entries만 가진 non-symlink directory `0700`이고 각 entry는
non-symlink regular `0644`, uid/gid `1000/1000`, nlink 1이다. dev/ino/size/mtime_ns/
ctime_ns는 R019 §2.2 표와 모두 일치했고 six content SHA/bytes도 §2.1과 일치했다. history는
seq1-only이며 package/output manifest는 `effective=false`, `approved=false`,
`applied=false`다. 봉인된 old test SHA
`5f1e80f3a22018191a753957bcefbcf620960017f47ccab55a00f031d8d46459`
(68,995 bytes)는 현재 test preimage와 다르고 r001 candidate reviews도 absent였다. 따라서
r001을 current r002 또는 성공 권위로 오인하지 않으며 content와 physical identity를
수정·복구하지 않는 계약이 실제 상태와 일치한다.

## epochs, publication, tests와 authority

P0-P4는 R019 plan, 두 plan reviews, observed timestamp와 three-source correction, exact
r002 six-output publication, 두 candidate reviews의 순서를 선언한다. P2 allowlist는
현재 세 source뿐이고 P3/P4 target은 exact add-only 경로다. r002가 어떤 entry 형태로든
존재하거나 rename race가 나면 같은 terminal이며, parent fsync가 rename 뒤 실패해도
target을 삭제·수정하지 않고 동일 revision retry를 거부한다. 반면 성공 공개 뒤 두 번의
read-only check 전후에는 r002 root+six full physical tuple과 content가 불변이어야 한다.

pre/post targeted universe는 각각 정확히 37 tests, skip 0, exit 0, `OK`를 요구한다.
namespace/provenance mutation, r001 content/metadata, timestamp/offset, publication terminal,
absent/present dual-state, strict schema/chronology/zero-credit가 같은 universe에 고정되고,
builder `--check`와 두 CANDIDATE checker가 별도로 요구된다. R019 아래 real receipt,
resolved/final/canonical/checkpoint/Goal/full19/product write와 activation/formal/device/
release credit은 모두 0이다.

## Findings

### BLOCKING-01 — predecessor timestamp snapshot이 지속되지 않아 P3 전 causal TOCTOU를 닫지 못한다

R019 §4.3은 predecessor root/files의 no-follow `lstat`에서
`PREDECESSOR_MAX_NS`를 계산한 뒤 wall clock을 관측한다. 그러나 선택에 사용한 exact
predecessor entry set과 각 `(dev,ino,mode,uid,gid,nlink,size,mtime_ns,ctime_ns)`, 계산된
`PREDECESSOR_MAX_NS`, raw `observed_ns` 또는 그 snapshot digest를 three-source나 별도
add-only capture에 봉인하지 않는다. source에 남는 값은 선택된 `PREPARED_AT`뿐이다.

더 직접적으로, §4.3 post-patch oracle과 §9/§10 P3 직전 gate는 선택 때의 predecessor
snapshot을 다시 no-follow `lstat`해 full equality를 요구하지 않는다. R017/R018/R019
roadmap/review의 frozen pins는 SHA/bytes뿐이므로 선택 직후 같은 bytes를 유지한 touch,
chmod/chown, inode replacement 같은 metadata drift를 검출하지 못한다. 그러면 다음 순서가
허용된다.

```text
old predecessor lstat snapshot -> PREDECESSOR_MAX_NS 계산 -> timestamp 선택
-> predecessor same-bytes metadata drift -> source patch/post oracle hash PASS
-> P3 r002 immutable publication -> P4에서 current lstat로 뒤늦게 causal failure 발견
```

r001은 exact lstat table과 epoch equality가 있어 이 공격을 닫지만 roadmap/review
predecessors에는 같은 계약이 없다. §11의 P4 재검증은 publication 뒤이므로 bad r002를
막는 pre-publication gate가 아니며, 과거 snapshot이 지속되지 않아 selection 당시의
`first observed`/non-future 주장을 독립 재구성할 수도 없다. 이는 R018의 유일한 blocking
원인이었던 physical causal provenance correction 자체를 fail-closed로 만들지 못한다.

최소 교정은 새 roadmap에서 선택에 사용한 exact predecessor lstat snapshot,
`PREDECESSOR_MAX_NS`, raw `observed_ns`, selected value와 snapshot digest를 immutable
source constants 또는 별도 add-only capture로 지속하고, source patch 직후 및 P3 직전에
모든 predecessor의 no-follow full-lstat equality와 lower/upper bound를 재검증하는 것이다.
그 exact evidence와 post-patch source physical timestamps도 P4 reviews에 기록해야 한다.

`BLOCKING=1 MAJOR=0 MINOR=0`.

## 결론

R019는 R017의 full correction을 유지하고 observed timestamp의 lower/upper bound를
설계했지만, 그 predecessor snapshot persistence와 P3 전 TOCTOU equality가 없어 R018의
causal-provenance blocker를 완전히 닫지 못한다. 따라서 `REVISION_REQUIRED`이며
`authority_granted=NONE`이다. R019 아래 source, r002 publication, activation,
canonical/checkpoint, Goal, 제품 또는 release write 권한은 부여하지 않는다.
