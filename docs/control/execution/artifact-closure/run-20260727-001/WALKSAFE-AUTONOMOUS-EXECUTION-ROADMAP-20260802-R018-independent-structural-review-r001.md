# WalkSafe R018 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R018-INDEPENDENT-STRUCTURAL-REVIEW-R001
reviewer_agent = /root/v25_delegation_delta
reviewer_axis = STRUCTURAL_R017_CLOSURE_R001_LSTAT_R002_NAMESPACE_TIMESTAMP_DAG_EPOCH_ORACLE_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R018.md
target_sha256 = 2a1df2cd5f0fdccb3183b15607b6a017ab6b2556d661ea16e9b6caaa9d9255f7
target_bytes = 17914
target_lines = 396
reviewed_at = 2026-08-02T11:45:36+09:00
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
authority_granted = NONE
```

## 범위와 physical identity

R018 396줄 전체를 R017 roadmap과 두 `REVISION_REQUIRED` review, R016 accepted chain,
reviewed R002 pair/review, 현재 three-file source preimage와 immutable physical r001에
읽기 전용으로 대조했다. R018은 regular `0664`, uid/gid `1000/1000`, nlink 1이며 위
SHA-256/bytes/lines와 정확히 일치했다.

동결 provenance도 실제 bytes와 일치했다.

| 대상 | SHA-256 | bytes |
|---|---|---:|
| C0 checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| R002 pair manifest | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| R002 PASS review | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |
| R016 roadmap | `49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2` | 12,690 |
| R016 structural review | `eced95aa2343427b24f80f8865e2fa8e9e3f320ad48c74fab7970d646a1fac87` | 3,758 |
| R016 skeptical review | `9bae362ad4c1f0c4b2567b534197938f77d825b5d73d76dbd3a184542649bc0e` | 5,354 |
| rejected R017 | `6437009fe07692f56e9c4320ce645bc9b13f5b28d8fe6d448e26c634c6f442a5` | 13,494 |
| R017 structural review | `cbcb6a14e4af2b82ec84c300799575fd99a81b65e9e8236177ff7221be958e81` | 9,466 |
| R017 skeptical review | `d10ba282eee810c58a0d76d8658a4a21d937dcd4c76882a4936dc97538d61fe4` | 5,326 |

source preimage는 core
`e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f`
(180,432 bytes), builder
`d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175`
(51,153 bytes), test
`00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523`
(69,685 bytes)로 일치했다. r002 physical target은 absent/non-symlink였다. 지시대로
source, candidate 또는 test를 실행하지 않았다.

## R017 findings closure

R017 structural `BLOCKING=1 MAJOR=2`와 skeptical `BLOCKING=1 MAJOR=1`의 세 중복 축은
R018 문구상 모두 닫혔다.

- §1.2와 §6은 exact-existing, `RENAME_NOREPLACE` race, rename 뒤 parent-fsync 예외의
  same-revision success를 제거할 수 있도록 P2 delta를 열고, 어떤 기존 r002 target도
  `NEW_REVISION_REQUIRED_EXISTING_R002_TARGET_AUTHORITY_ZERO`로 종료한다. 성공 공개 뒤
  `builder --check`, present-state core와 두 CANDIDATE wrapper는 publication retry가 아닌
  read-only validation으로 분리돼 있다.
- §4는 BUNDLE/review/application-gate와 그 파생 plan/receipt/resolved paths, transaction
  IDs, history/checkpoint/output-manifest/active-checkpoint IDs, seq1/2/3 event IDs를 exact
  R002 namespace로 닫는다. builder resolved path는
  `validation.RESOLVED_OUTPUT_MANIFEST_REL`에서 파생하고, §9는 명시적 historical allowlist
  밖의 old r001/001 current-construction literal을 publication 전에 residual scan한다.
- §2.2와 §5.2는 r001 root 및 exact six의 content CAS와 no-follow `lstat` seal을 함께
  고정한다. 실제 root/entry의 dev, ino, mode, uid/gid, nlink, size, mtime_ns, ctime_ns는
  표와 모두 일치했고 root는 non-symlink directory, six entries는 non-symlink regular
  file, extra/missing/hardlink는 0이었다. invocation 및 epoch 전후 equality도 명시됐다.

## Finding

### BLOCKING-01 — physical r002 preparation timestamp가 자신의 authority와 실패 근거보다 앞선다

R018 §4.3은 "truthful preparation timestamps" 아래 physical r002의
`PREPARED_AT`과 seq1 `PACKAGE_PREPARED.occurred_at`을
`2026-08-02T11:00:00+09:00`으로 고정한다. 이 값은 synthetic fixture에만 머무르지
않는다. 현재 builder에서 `PREPARED_AT`은 physical transaction plan, transition history
seq1, prepared checkpoint, package manifest와 output manifest의 preparation metadata에
봉인된다.

그러나 관찰된 물리 순서는 다음과 같다.

```text
2026-08-02T11:02:02.885202981+09:00  failed r001 root mtime
2026-08-02T11:21:50.951418257+09:00  R017 skeptical review birth
2026-08-02T11:31:59.159135325+09:00  R017 structural review birth
2026-08-02T11:35:51.348663070+09:00  R018 roadmap birth
2026-08-02T11:40:37.170638620+09:00  R018 skeptical review birth
after these instants                     required P1 completion, P2 correction, P3 publication
```

§5.1, §9와 §10은 R017 rejection evidence와 R018 plan/dual reviews가 먼저 존재하고 그
physical hashes를 P2 source가 결속한 뒤 r002를 prepare/publish하도록 한다. 그런데 현재
fixed value로 만든 non-fixture seq1은 실패한 r001, 그 거부 근거, R018 실행 권위와 수정
source보다 먼저 package가 준비됐다고 주장한다. 이는 graph edge 자체의 cycle은 아니지만
physical provenance의 causal order를 역전한다. 내부 `seq1 < seq2 < seq3`, exact `+1us`,
599/600/601 및 pre/post 37-test oracle은 모두 같은 backdated 축 안의 관계만 검사하므로
이 결함을 검출하지 못한다.

§4.3의 synthetic core/request/receipt/quick `11:05`~`11:26`은 `fixture_only`이므로
그 자체가 실제 권위를 주장하는 것은 아니다. 차단점은 physical candidate에 봉인되는
non-fixture `PREPARED_AT`/seq1이다.

최소 교정은 successor roadmap dual review가 끝난 뒤 P2 preparation 시작 시각을 한 번
관찰해 source 상수로 봉인하고, pre-build gate에서 다음을 검사하는 것이다.

```text
max(r001 failure evidence time, successor roadmap/review physical times)
  < PREPARED_AT
  <= observed P2 preparation start
```

`PREPARED_ON`, synthetic cascade와 invalid-time fixtures는 그 봉인값에서 명시적 offset으로
파생하면 이후 deterministic rebuild를 유지할 수 있다. 고정 미래값을 택한다면 P2가 그
instant 이후에만 시작되고 놓친 경우 새 revision으로 종료하는 gate가 필요하다. 이미 지난
`11:00`은 재사용할 수 없다.

## 나머지 구조 판정

- R018 plan reviews → P2 source pins → r002 outputs → P4 candidate reviews → future
  request/receipt의 hash dependency는 단방향이고 review-before-subject 또는 self-hash
  cycle은 없다. 위 finding은 DAG cycle이 아니라 timestamp causal-order 위반이다.
- P0-P4 allowlist는 plan/reviews, three source, exact r002 root, candidate reviews로 닫혀
  있다. P2 전 r002 absence와 source preimage CAS, 수정 후 source hash capture, AST/key/
  namespace/legacy scan, 37-test gate 및 epoch 사이 r001 seal 순서는 실행 가능하다.
- P3는 `PUBLISHED_NEW`만 허용하고, post-build 37 suite → read-only builder check → 두
  CANDIDATE checker → final CAS 순서와 present physical identity oracle을 고정한다.
- canonical r022, active checkpoint, Goal, full19, 제품, dynamic receipt/resolved output,
  formal/device/release write와 credit은 모두 0이다. 성공 상태도 non-effective,
  not-authorized, not-applied candidate로 제한된다.
- 이미 생성된 R018 skeptical review
  `8fa2bfe82ac5b0b4a7a9d3708f9cf3407e995c8c82bd74d79ce2e8a4161879a4`
  (4,551 bytes, 79 lines)도 동일한 timestamp finding으로 `REVISION_REQUIRED`이다. 따라서
  현재 P1 dual PASS는 성립하지 않고 P2 이후 write는 열리지 않는다.

## 결론

R018은 R017의 세 defect axis를 닫았지만 physical preparation provenance를 역전시키는
BLOCKING 1건 때문에 그대로 실행할 수 없다. R018 아래 source/candidate write 권한은
부여하지 않으며 `authority_granted=NONE`이다. timestamp를 교정한 새 roadmap revision과
새 독립 dual review가 필요하다.
