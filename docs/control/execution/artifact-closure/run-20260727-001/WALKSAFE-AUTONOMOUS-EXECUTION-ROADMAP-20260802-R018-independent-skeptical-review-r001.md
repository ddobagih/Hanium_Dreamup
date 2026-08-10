# WalkSafe R018 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R018-INDEPENDENT-SKEPTICAL-REVIEW-R001
reviewer_agent = /root/fp048_transition_mechanics
reviewer_axis = SKEPTICAL_TIMESTAMP_PROVENANCE_NAMESPACE_PUBLICATION_LSTAT_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R018.md
target_sha256 = 2a1df2cd5f0fdccb3183b15607b6a017ab6b2556d661ea16e9b6caaa9d9255f7
target_bytes = 17914
target_lines = 396
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
authority_granted = NONE
```

## 범위와 physical identity

R018 396줄 전체를 현재 core/builder/test와 immutable r001, R017 roadmap 및 두
`REVISION_REQUIRED` review에 대조했다. source/candidate는 실행하거나 수정하지 않았다.

- C0: `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c`,
  1,329,415 bytes
- reviewed R002 pair/review: `7d1e5c04...5b08`/12,972 bytes,
  `f390c653...5fb7`/2,366 bytes
- source preimage: core `e8daf70a...ab5f`/180,432 bytes, builder
  `d4fa8039...e175`/51,153 bytes, test `00aa576b...9523`/69,685 bytes
- r001 exact six의 SHA/bytes와 root/entry `lstat` 값은 R018 §2 표와 모두 일치했다.
  root는 non-symlink directory `0700`, six entries는 regular `0644`, uid/gid
  `1000/1000`, nlink 1, extra/missing/hardlink 0이었다.

## R017 findings closure

R017 structural `1B/2M`과 skeptical `1B/1M`의 세 중복 축은 다음처럼 닫혔다.

- exact-existing/race/parent-fsync 뒤 same-revision success를 제거하고 write retry와
  present read-only validation을 분리했다.
- seq1/seq2/seq3 event ID, transaction gate, plan/resolved paths와 namespace residual
  scan을 exact하게 추가했다.
- failed r001의 content와 no-follow physical identity를 epoch 및 invocation 경계에서
  함께 봉인했다.

현재 source의 숨은 candidate-specific `R001/20260730-001` literal도 R018 §4 mapping과
§9 historical allowlist로 모두 분류 가능했다. R018 plan reviews는 source보다 먼저
고정되고 candidate reviews/receipt hash는 미래에 한 방향으로 읽으므로
review-before-subject/self-hash cycle은 없다. P0-P4 allowlist도 canonical, checkpoint,
Goal, product 또는 formal/release authority로 확대되지 않는다.

## Finding

### BLOCKING R018-SK-B01 — fixed preparation cascade가 실제 provenance보다 이르다

R018 §4.3은 r002 physical revision의 `PREPARED_AT`과 seq1 `occurred_at`을
`2026-08-02T11:00:00+09:00`으로 고정하고 이를 truthful preparation timestamp라고
규정한다. 그러나 immutable r001 root는 `11:02:02`에 공개됐고, r001 실패를 확정한
R017 skeptical/structural reviews는 각각 `11:21:50`, `11:31:59`, 이를 대체하는 R018
자체도 `11:35:51`에 물리적으로 생성됐다. 필수 R018 plan reviews와 P2 source correction은
그보다 더 뒤에만 존재할 수 있다.

따라서 현재 cascade로 만든 r002는 실패한 r001과 rejection 근거, R018 실행 권위 및
source correction보다 먼저 `PACKAGE_PREPARED`됐다고 주장한다. 이는 §5.1의 provenance
순서와 모순되며 `PREPARED_AT`, static/package metadata, seq1 event, checkpoint cutoff에
동시에 봉인된다. deterministic build와 37 tests가 통과해도 이 역사 역전은 검출되지
않는다. synthetic core/request/receipt/quick fixture의 `11:05`~`11:26` cascade도 R018
문서보다 앞서지만 `fixture_only`이므로 실제 권위는 아니며, 문제의 핵심은 physical
candidate seq1의 비-fixture 사실 주장이다.

최소 교정은 새 roadmap dual review 뒤 P2 preparation 시작 시각을 한 번 관찰해 source
상수로 봉인하고, 그 값이 R018 successor plan/reviews와 r001 failure evidence보다 늦으며
P2 시작보다 미래가 아님을 pre-build gate에서 검사하는 것이다. 나머지 synthetic
timestamps와 invalid-time fixtures는 그 봉인값에서 명시적 offset으로 파생한다. 최종
three-source hash가 이 값까지 포함하므로 이후 rebuild 결정성은 유지된다. 고정값을 계속
사용하려면 실제 P2가 그 instant에 시작되도록 하는 실행 gate와 만료/재계획 조건이
필요하지만, 이미 지난 `11:00`은 사용할 수 없다.

## 판정

R018은 위 BLOCKING 1건 때문에 현재 형태로 P2 이후를 실행할 수 없다.
`authority_granted=NONE`이며 timestamp/provenance를 교정한 새 roadmap revision과 새 독립
dual review가 필요하다.
