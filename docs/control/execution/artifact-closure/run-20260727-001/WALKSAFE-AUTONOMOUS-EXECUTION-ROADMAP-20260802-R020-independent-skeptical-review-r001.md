# WalkSafe R020 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R020-INDEPENDENT-SKEPTICAL-REVIEW-R001
type = INDEPENDENT_SKEPTICAL_STATIC_REVIEW
reviewer_agent = /root/r020_skeptical_review
reviewer_axis = P2A_RECEIPT_CAUSAL_SNAPSHOT_TOCTOU_SOURCE_TRANSITION_R001_R002_PUBLICATION_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R020.md
target_sha256 = 276f499ce235289cbe7b6f15abc835ce87ed0ce8ccd76d03df904bc5f5cd44f1
target_bytes = 25239
target_lines = 575
reviewed_at = 2026-08-02T12:15:54+09:00
status = REVISION_REQUIRED
findings = BLOCKING=2 MAJOR=0 MINOR=0
blocking = 2
major = 0
minor = 0
authority_granted = NONE
```

## 범위와 동결 identity

R020 575줄 전체를 accepted R016과 두 PASS review, rejected R017/R018/R019와 각
structural/skeptical review, 현재 validator/builder/test preimage, reviewed R002 pair,
active C0와 immutable failed r001 exact six에 읽기 전용으로 대조했다. target은 시작 시
SHA-256 `276f499ce235289cbe7b6f15abc835ce87ed0ce8ccd76d03df904bc5f5cd44f1`,
25,239 bytes, 575 lines로 일치했다.

동결 chain과 current source도 R020 표와 일치했다.

| 대상 | SHA-256 | bytes |
|---|---|---:|
| C0 checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| R002 pair manifest | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| R002 PASS review | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |
| R016 roadmap | `49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2` | 12,690 |
| rejected R017 | `6437009fe07692f56e9c4320ce645bc9b13f5b28d8fe6d448e26c634c6f442a5` | 13,494 |
| rejected R018 | `2a1df2cd5f0fdccb3183b15607b6a017ab6b2556d661ea16e9b6caaa9d9255f7` | 17,914 |
| rejected R019 | `3c21f917793b0ffbe5f6aa892e1d73d122799fcfe005c22f2f289571b45d4eb2` | 21,461 |
| validation core preimage | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| builder preimage | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| test preimage | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |

r001 root는 R020 §2의 dev/inode/mode/uid/gid/nlink/size/time과 일치하고 정확히 여섯
non-symlink regular file만 가진 non-symlink directory다. 여섯 content SHA/bytes도 §2와
일치했다. source/build/test 또는 candidate checker는 실행하지 않았고, source, r001,
r002, canonical, checkpoint, Goal, 제품은 수정하지 않았다.

## Findings

### BLOCKING R020-SK-B01 — preimage full equality가 의도한 P2B correction을 drift로 판정한다

R020 §4.1 item 6은 수정 전 three-source preimage 세 파일을 `causal_inputs`에 넣고 각
row의 hash/bytes와 full `lstat`을 receipt에 봉인한다. 그런데 §4.4는 P2B 직후를 포함한
모든 경계에서 **모든** causal row의 live full tuple/hash/bytes exact equality와 현재
max가 receipt `causal_max`와 같은지를 요구한다. §6.1의
`validate_preparation_capture(root)`도 바로 그 §4.4 live equality를 수행하며 builder와
wrapper의 모든 중요 경계에서 호출된다.

P2B의 유일한 목적은 이 세 파일을 한 번 수정하는 것이다. 정상 correction은 적어도
content hash/bytes 또는 inode/time을 preimage row와 다르게 만들고, changed-source
mtime/ctime은 보통 receipt의 `causal_max`보다 커진다. 따라서 허용된 P2B 자체가 다음
고정 경로로 실패한다.

```text
P2A: source preimage A를 receipt에 봉인
P2B: allowlisted correction으로 source B != A 생성
P2B 직후 §4.4: live source B == receipt preimage A 요구 -> false
recomputed current max == captured causal_max 요구 -> false
NEW_REVISION_REQUIRED_TIMESTAMP_CAPTURE_AUTHORITY_ZERO
```

이는 공격자가 필요한 TOCTOU가 아니라 honest path도 통과하지 못하는 실행 불가능
계약이다. 반대로 구현자가 P3에 도달하려고 source row를 equality 대상에서 조용히 빼면
§4.4, §6.1, §10, §11, §14의 all-boundary/full-snapshot 주장을 위반하고 source mutation
TOCTOU가 다시 열린다. 현재 37-test 요구의 `current tuple equality`도 어느 phase의
expected state를 뜻하는지 분리하지 않아 이 모순을 올바른 oracle로 고정할 수 없다.

최소 교정은 successor roadmap에서 causal row를 명시적으로 두 종류로 나누는 것이다.

- immutable predecessor/r001 rows는 P2A부터 P4까지 receipt snapshot과 계속 exact
  equality여야 한다.
- mutable three-source rows는 P2B 직전까지만 receipt의 preimage와 exact equality여야
  한다. P2B 뒤 preimage mismatch는 의도된 전이이며 success 조건이다.
- P2B 직후의 exact postimage SHA/bytes/full tuple을 별도 phase baseline으로 캡처하고,
  builder 시작/종료, P3 rename 직전/직후와 P4에서 그 postimage baseline과 exact
  equality를 요구한다. crash/retry 시 baseline이 모호하면 새 revision terminal이다.
- test는 pre-P2B preimage equality, post-P2B expected transition, 그 뒤 postimage drift를
  서로 다른 phase oracle로 검증해야 한다.

### BLOCKING R020-SK-B02 — receipt 최초 physical tuple의 지속 위치가 없어 same-byte replacement를 닫지 못한다

§4.3은 receipt가 자기 physical time을 주장하지 않으며 P2A 공개 뒤 "부모"가 receipt
SHA/bytes와 `lstat`을 별도로 캡처한다고 한다. 그러나 그 최초 full tuple은 receipt
schema 어디에도 없고, §6.1의 source-frozen 상수에도 `PREPARATION_CAPTURE_SHA256`과
`PREPARATION_CAPTURE_BYTES`만 있을 뿐 expected dev/ino/mode/uid/gid/nlink/size/
mtime_ns/ctime_ns가 없다. 별도 영속 artifact, validator argument 또는 exact source
constant도 지정되지 않았다.

그럼에도 §6.1은 인자가 `root` 하나뿐인 `validate_preparation_capture(root)`가 §4.4의
receipt physical tuple equality를 새 builder/checker process에서도 검증한다고 요구한다.
비교할 최초 expected tuple을 복원할 수 없으므로 구현은 현재 tuple을 새 baseline으로
삼거나 content SHA/bytes만 검사할 수밖에 없다. 다음 same-byte replacement가 문서의
content/schema/inequality 검사를 통과할 수 있다.

```text
P2A receipt publish -> parent가 tuple T0를 메모리에서 관측
P2B 전/후 receipt를 동일 bytes로 replace -> tuple T1 != T0, SHA/bytes 동일
새 builder/checker process가 receipt를 열어 T1을 시작 snapshot으로 채택
invocation 내 T1 equality와 receipt content pin 모두 PASS
```

부모가 계속 살아 있어 나중에 T0/T1 차이를 찾더라도 builder의 staging rename 직전
TOCTOU gate가 T0를 알지 못하면 bad P3 publication을 사전에 막는 계약이 아니다. P2A 뒤
crash는 resume 금지라는 규칙도 정상 non-crash subprocess 사이에 전달되지 않은 baseline을
복원하지 못하는 문제를 해결하지 않는다.

최소 교정은 P2A 직후 관측한 receipt exact SHA/bytes와 full no-follow tuple T0를 P2B의
세 source에 명시적 독립 상수로 함께 봉인하고, source patch 직후 그 세 값이 exact 같은지
검사하는 것이다. 이후 `validate_preparation_capture`는 그 source-frozen T0를 expected
값으로 사용해야 한다. P2A 직후부터 P2B 상수 봉인 완료까지는 동일 supervisor가 T0를
보유하고 매 경계에서 비교하며, crash/target-existing/partial은 새 revision terminal로
끝나야 한다. tests에는 P2B 전후 및 새 process 재진입에서 receipt same-byte inode
replacement/touch/chmod/link drift가 T0 대비 실패하는 oracle이 필요하다.

## 나머지 공격 축

| 축 | 판정 | 근거 |
|---|---|---|
| timestamp 의미 | CLOSED_EXCEPT_FINDINGS | microsecond floor, strict lower bound, raw observed/postcheck, canonical Asia/Seoul serialization과 review semantic time의 physical non-future 검사는 R019의 future-rounding 및 stale predecessor 문제를 문구상 닫는다. B01의 phase 혼동 때문에 post-P2B current-max equality만 성립할 수 없다. |
| P2A target-existing·crash/retry | CLOSED | capture target을 두 번 absent 확인하고 partial/existing/drift면 같은 receipt repair/replace/reuse 없이 새 roadmap terminal로 끝낸다. |
| self/future hash cycle·ordering | CLOSED | R020 reviews → capture receipt → corrected source → r002 → candidate reviews 방향이고 receipt는 자기 hash나 미래 source/candidate/review hash를 주장하지 않는다. B02는 graph cycle이 아니라 receipt physical baseline 전달 누락이다. |
| R002 namespace | CLOSED | exact r002 bundle/review/gate paths, R002 transaction/document/event IDs, gate-derived final/receipt/resolved paths와 intentional historical r001 allowlist가 R018 closure를 유지한다. |
| failed r001 | CLOSED | exact-six content와 root/file full lstat seal, no-follow type/set 검증, 모든 epoch equality 및 current-candidate 오인 금지가 유지된다. 실제 physical r001도 seal과 일치했다. |
| publisher terminal | CLOSED | any-existing/file/symlink/exact-existing, rename race와 rename 후 parent-fsync ambiguity는 same-revision success가 아니라 authority-zero terminal이다. 성공 뒤 `--check`와 core/wrappers는 publication retry와 분리된 read-only path다. |
| candidate read-only root/files | CLOSED | P3 뒤 root exact-six name digest와 root/file full tuple/hash snapshot을 read-only checks 전후 exact 비교하고 P4에 argv/exit/output digest와 snapshot을 기록한다. |
| test oracle | BLOCKED_BY_B01_B02 | 선언된 37-method subcase 범위는 namespace, r001, publisher, chronology, zero-credit를 포괄하지만 preimage/postimage phase와 receipt T0 expected source가 없어 현재 형태로는 두 blocking을 검증할 수 없다. |
| authority/credit zero | CLOSED | candidate는 seq1-only, non-effective/not-approved/not-applied이고 canonical/checkpoint/Goal/product/formal/device/release write와 credit은 0이다. finding이 있으면 P2A 이후 write 0이다. |

## 명시적 판정

```text
status = REVISION_REQUIRED
findings = BLOCKING=2 MAJOR=0 MINOR=0
source_build_test_executions = 0
source_writes = 0
r001_writes = 0
r002_writes = 0
canonical_checkpoint_goal_product_writes = 0
formal_device_release_credit = 0
authority_granted = NONE
```

R020은 `0/0/0`이 아니므로 `PASS` 또는
`R020_R002_CAPTURE_AND_CANDIDATE_CORRECTION_ONLY` 권한을 부여할 수 없다. B01의
phase-aware preimage→postimage transition과 B02의 persistent receipt physical baseline을
명시한 새 roadmap revision 및 새 dual review가 필요하다.
