# WalkSafe R019 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R019-INDEPENDENT-SKEPTICAL-REVIEW-R001
type = INDEPENDENT_SKEPTICAL_STATIC_REVIEW
reviewer_agent = /root/r019_skeptical_review
reviewer_axis = TIMESTAMP_CAUSALITY_NON_FUTURE_ONE_SHOT_DETERMINISM_R001_R002_PUBLICATION_PROVENANCE_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R019.md
target_sha256 = 3c21f917793b0ffbe5f6aa892e1d73d122799fcfe005c22f2f289571b45d4eb2
target_bytes = 21461
target_lines = 448
reviewed_at = 2026-08-02T11:58:39+09:00
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
blocking = 1
major = 0
minor = 0
authority_granted = NONE
```

## 범위와 물리 결속

R019 448줄 전체를 R016~R018 roadmap과 여섯 plan review, 현재 validator/builder/test
preimage, reviewed R002 pair/review, active C0와 failed physical r001에 읽기 전용으로
대조했다. source/build/test와 candidate checker는 실행하지 않았고 source, candidate,
canonical, checkpoint, Goal, 제품 파일을 수정하지 않았다.

- R019 target은 SHA-256
  `3c21f917793b0ffbe5f6aa892e1d73d122799fcfe005c22f2f289571b45d4eb2`,
  21,461 bytes, 448 lines로 시작 시 동결값과 일치했다.
- C0는 `6ec0e4f1...d698c`/1,329,415 bytes이고 v2.4 ACTIVE, sequence 39,
  Gap·Backlog r021, release `NOT_ELIGIBLE`, formal 279/279 `NOT_RUN`, open Gate 5다.
- reviewed R002 pair/review는 `7d1e5c04...5b08`/12,972 bytes와
  `f390c653...5fb7`/2,366 bytes로 일치했다.
- source preimage는 core `e8daf70a...ab5f`/180,432 bytes, builder
  `d4fa8039...e175`/51,153 bytes, test `00aa576b...9523`/69,685 bytes로
  R019 표와 일치했다.
- r001 root와 exact six의 content SHA/bytes 및 no-follow physical tuple은 R019 §2 표와
  모두 일치했다. root는 non-symlink directory `0700`, six entries는 non-symlink
  regular file `0644`, uid/gid `1000/1000`, nlink 1이고 extra/missing/hardlink는 0이다.
- r002 root와 두 r002 candidate review target은 검수 시 absent였다.

## Finding

### BLOCKING R019-SK-B01 — timestamp predecessor snapshot이 선택 뒤 고정되지 않아 causal gate에 TOCTOU가 남는다

R019 §4.3 step 1은 r001, R017, R018, R019와 plan reviews의 no-follow `lstat`에서
`PREDECESSOR_MAX_NS`를 한 번 계산하고, step 2~3에서 그 캡처값보다 큰 첫 wall-clock
microsecond를 선택한다. 그러나 선택 뒤에는 predecessor tuple을 다시 읽어 캡처 당시와
exact equality인지 확인하지 않는다. post-patch oracle도 현재 predecessor 최댓값이 아니라
이미 캡처한 `PREDECESSOR_MAX_NS < PREPARED_AT_NS`만 재검사한다.

§9 step 1의 `physical pins`, suite 뒤 `source/r001/timestamp CAS`, §10의
`provenance CAS`는 R019에서 predecessor roadmap/review에 대해 hash/bytes 이상의 exact
no-follow lstat snapshot/equality로 정의되지 않았다. §2가 full lstat equality를 명시한
r001과 달리 R017/R018/R019 roadmap/review에는 비교할 sealed tuple도 없다.

따라서 다음 실행이 문서상 허용된 gate를 통과할 수 있다.

```text
t0  predecessor lstat를 읽고 PREDECESSOR_MAX_NS를 캡처
t1  PREPARED_AT을 선택
t2  source patch 전에 roadmap/review 하나를 동일 bytes로 replace하거나 metadata touch
    -> sha256/bytes pin은 유지되지만 mtime_ns 또는 ctime_ns가 PREPARED_AT보다 커짐
t3  three-source patch
    -> stale PREDECESSOR_MAX_NS, source time, three-source equality, non-future oracle PASS
t4  content provenance CAS와 pre-build 37-test gate PASS 후 r002 publication 가능
```

이 경우 physical r002는 현재 결속한 predecessor보다 앞선 준비 시각을 다시 봉인할 수
있다. R018의 fixed-time inversion과 값 선택 방식은 달라졌지만, causal fact를 P3 직전까지
fail-closed로 유지하는 계약은 닫히지 않았다. 선택 사실과 predecessor lstat snapshot을
P2 전에 add-only receipt로 남기지 않으므로, source patch 전 crash/retry가 같은 roadmap
아래 두 번째 선택을 했는지도 사후에 판별할 수 없어 one-shot 증명도 함께 약하다.

최소 교정은 다음과 같다.

1. timestamp ordering에 쓰는 exact predecessor universe와 각 path의 full no-follow
   `(dev,ino,mode,uid,gid,nlink,size,mtime_ns,ctime_ns,sha256,bytes)` snapshot을 명시한다.
2. 선택 직후, three-source patch 직후, P3 rename 직전에 그 snapshot의 exact equality를
   다시 검사하고 현재 max가 여전히 `PREPARED_AT_NS`보다 작은지 재계산한다.
3. 어느 시점이든 drift 또는 pre-patch crash/retry ambiguity가 있으면 timestamp를
   재선택하지 않고 `NEW_REVISION_REQUIRED_TIMESTAMP_CAPTURE_AUTHORITY_ZERO`로 끝낸다.
   필요하면 선택값과 predecessor snapshot을 별도 add-only capture receipt에 먼저
   봉인하고 P2/P3 allowlist와 provenance DAG에 그 receipt를 추가한다.
4. post-patch와 P3 직전 negative oracle에 same-bytes replacement, chmod/chown round-trip,
   mtime touch를 넣어 hash-only pin 우회를 검증한다.

## 나머지 공격 축

- R019의 microsecond floor는 선택값이 관측 wall clock보다 미래가 되는 반올림을 피하고,
  `PREPARED_AT <= source mtime/ctime <= post-patch observation` 방향도 맞다. 위 finding은
  선택값 계산이 아니라 predecessor snapshot의 지속성과 one-shot 증명 문제다.
- core/builder/test exact 동일 literal, `+09:00`, 6-digit fraction과 base-derived
  synthetic `+5/+10/+15/+15:30/+16/+26 minutes`, seq3 `+1us`는 독립 calendar literal
  drift를 닫는다. synthetic 미래값은 `fixture_only`이고 live authority가 아니다.
- R016 accepted → rejected R017/R018 → R019 plan reviews → source → r002 → candidate
  reviews → future core review/request/receipt의 hash 방향에는 self-hash 또는
  review-before-subject cycle이 없다.
- r001 content+lstat seal, r002 namespace/IDs/event IDs/gate-derived paths,
  exact-existing/race terminal, parent-fsync 뒤 same-revision retry 금지와 successful
  publication 뒤 read-only checks의 분리는 R017 findings를 문구상 닫는다.
- post-build r002 root/file tuple 비교, exact six set, source/r001/C0/R002/r021 재확인과
  P4 dual candidate review 경계는 명시돼 있다. 단, 위 timestamp blocker 때문에 P2/P3가
  열려서는 안 된다.
- candidate는 seq1-only non-effective/not-approved/not-applied이며 activation, canonical
  r022, checkpoint, Goal, product, formal/device/release credit과 write 권한은 모두 0이다.

## 명시적 count/status/authority 판정

```text
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
source_build_test_executions = 0
source_writes = 0
r001_writes = 0
r002_writes = 0
canonical_checkpoint_goal_product_writes = 0
formal_device_release_credit = 0
authority_granted = NONE
```

R019은 `0/0/0`이 아니므로 `PASS` 또는
`R019_R002_CANDIDATE_CORRECTION_ONLY` 권한을 부여할 수 없다. timestamp capture의
causal/one-shot TOCTOU를 닫은 새 roadmap revision과 새 독립 dual review가 필요하다.
