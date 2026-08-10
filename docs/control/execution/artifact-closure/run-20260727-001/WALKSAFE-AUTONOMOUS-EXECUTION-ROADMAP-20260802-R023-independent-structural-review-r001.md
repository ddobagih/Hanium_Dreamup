# WalkSafe R023 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R023-INDEPENDENT-STRUCTURAL-REVIEW-R001
reviewer_agent = /root/r022_structural_review
reviewer_axis = R022_FIRST_OBSERVATION_CREATED_FD_FSTAT_PATH_CAS_PARENT_FSYNC_SUPERVISOR_S1_MEMFD_I34_P2A_P2C_R002_TEST_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R023.md
target_sha256 = e0d2f11064b3a38745555332aca16bcc6b876094c33d89d26db0aa5da289272d
target_bytes = 25895
target_lines = 555
reviewed_at = 2026-08-02T13:06:43+09:00
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
authority_granted = NONE
```

## 범위와 관측 상태

R023 555줄 전체를 rejected R022와 두 독립 review, accepted R016, rejected R017~R021
chain/reviews, current three-source S0, immutable failed r001, reviewed R002 pair/review 및
예정 target 상태에 읽기 전용으로 대조했다. 검토 시작과 review 추가 직전 target은 SHA-256
`e0d2f11064b3a38745555332aca16bcc6b876094c33d89d26db0aa5da289272d`, 25,895 bytes,
555 lines인 regular file이고 mode `0664`, uid/gid `1000/1000`, nlink 1이었다.
source/build/test/checker는 실행하지 않았고 source, r001, r002, canonical, checkpoint,
Goal, 제품을 수정하지 않았다. 이 review 파일만 add-only로 추가했다.

§1.1의 C0, reviewed R002 pair/review, R016~R022 roadmap/review SHA-256과 bytes는 현재
파일과 일치했다. 현재 S0도 §1.2와 일치하고 test method universe는 정적 선언 기준 정확히
37개다.

| source | SHA-256 | bytes |
|---|---|---:|
| validation core | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| builder | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| test | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |

failed r001은 exact-six만 가진 non-symlink directory이고 root+six content/physical seal과
NUL-name digest
`f4c9e884242b5acf190f0cb95d1567845173d8ae8e3a20919d81519f88b5d86c`가
§1.3과 일치했다. 검토 시작 시 R023 structural/skeptical review, P2A, P2C, r002 root 및
r002 candidate review pair target은 모두 absent였다.

## R022 공통 blocker 교정 판정

- `openat(O_EXCL|O_NOFOLLOW)`로 생성한 FD에서 exact raw를 write/fsync하고 그 FD의
  dev/ino를 계속 보유하므로, path가 같은 바이트의 다른 inode로 바뀌어도 created FD와
  path/second-read FD CAS가 불일치한다. R022의 **inode replacement 재채택**은 닫힌다.
- T0/P2A FD, 세 S1 read-only FD와 T1/P2C FD를 P3 parent fsync와 published anchor 검증까지
  보유하고 모든 child 경계를 original in-memory anchor와 bracket한다. child에는 sealed
  canonical memfd만 `pass_fds`로 전달하고 receipt/source FD는 `CLOEXEC`으로 남기므로
  current-live baseline fallback과 child-side expected 재구성도 닫힌다.
- P2B는 mutation 전에 exact intended source bytes를 commit하고 첫 stable S1 snapshot의
  content를 그 commitment에 비교한다. apply_patch가 만든 inode를 주장하지 않으면서
  different-byte S1 adoption을 금지하고, snapshot 뒤에는 held FD/full row로 same-byte
  replacement를 검출한다.

그러나 §5.2가 T0/T1로 채택하는 `T_created`는 create 시점 또는 write 완료 시점에 이미
봉인된 tuple이 아니라 **`fsync(fd)` 반환 뒤 처음 실행하는 `fstat(fd)`의 현재값**이다.
따라서 created inode 자체에 대한 touch가 그 사이 일어나면 R022에서 지적된 최초 tuple
재채택 창이 남는다.

## Findings

### BLOCKING-01 — fsync 반환과 T_created fstat 사이의 metadata 변경을 최초 T0/T1로 재채택한다

§5.2의 순서는 exact write-all → `fsync(fd)` → `T_created=fstat(fd)` → pread/path CAS다.
created FD는 dev/ino replacement를 막지만, 같은 inode의 mtime/ctime을 변경하는 별도
process의 `touch`는 FD가 열려 있어도 가능하다. 다음 interleaving에서 모든 명시 검사가
통과한다.

```text
supervisor: fsync(created_fd) returns
other process: touch literal path on the same created inode
supervisor: T_created = fstat(created_fd)  # touched mtime/ctime adopted
supervisor: pread exact raw
supervisor: path lstat == T_created
supervisor: second read fd dev/ino/content exact
supervisor: parent fstat unchanged
supervisor: repeat checks all exact
```

`touch`는 receipt raw/hash/length, dev/ino, mode, uid/gid, nlink 및 parent directory tuple을
바꾸지 않는다. `selected_ns <= T0 times`와 `captured_ns <= T1 times`도 더 늦어진 timestamp
때문에 통과한다. 즉 supervisor는 publisher가 완료한 tuple과 concurrent touch 뒤 tuple을
구분할 precommitted expected metadata가 없고, touched tuple을 T0/T1과 source/candidate
anchor에 영속 봉인한다.

이는 §5.2의 “touch는 new-revision terminal”과 §11의 “file fsync 뒤 first path comparison
전 touch를 주입하면 P3 rename absent” negative oracle에 직접 모순된다. 해당 구간은
`T_created` capture 이전을 포함하지만 그 경우 expected tuple이 아직 없으므로 선언한
oracle을 구현할 수 없다. 같은 이유로 mode를 원상복구하는 chmod pair처럼 최종 scalar
값은 복구되지만 ctime만 이동한 변경도 첫 `fstat` 전에 일어나면 새 baseline으로 채택된다.

최소 교정은 final write 직후 fsync 전에 exact post-write FD tuple을 별도 expected
`T_written`으로 포착하고, fsync 뒤 첫 `fstat`부터 `T_written`과 exact equality를 요구해
최소한 문서가 선언한 post-fsync injection interval을 닫는 것이다. 더 강한
“publication process 외 metadata mutation 전부 금지”를 주장하려면 create부터 final tuple
봉인까지 concurrent same-owner metadata operation을 배제하거나 검출하는 명시적 isolation
primitive가 필요하다. 어느 방식을 택하든 expected tuple이 생기기 전 변경을 현재값으로
재채택해서는 안 되고 ambiguity는 P2A/P2C의 new-revision authority-zero terminal이어야
한다.

`BLOCKING=1`.

## 나머지 구조 판정

| 축 | 판정 | 근거 |
|---|---|---|
| I34 / causal max | CLOSED | R021 rows 1~21, R022 trio 22~24, R023 trio 25~27, failed-r001 28~34의 exact order/schema/type와 review semantic time, S0 3 rows 및 lexical max가 유일하다. |
| P2A v4 / T0 | BLOCKED_BY_B01 | canonical raw, one committed selection, exact v4 schema, O_EXCL created inode/path CAS/parent fsync/retained FD는 닫히지만 post-fsync 최초 metadata baseline이 사전결속되지 않는다. |
| precommitted S1 / held FDs | CLOSED | exact intended path/SHA/bytes가 mutation 전 결속되고 stable post-patch rows와 held read-only FDs가 P3까지 content/full tuple drift를 막는다. |
| P2C v3 / T1 | BLOCKED_BY_B01 | exact v3 schema, row order/types, T0/S1 binding과 created-inode FD는 닫히지만 T1 최초 metadata capture에 같은 post-fsync 창이 있다. |
| supervisor continuity | CLOSED_AFTER_VALID_T0_T1 | P2 전부터 P3 parent fsync 및 exact candidate anchor 검증까지 original commitments와 five retained FDs를 요구하고 loss/ambiguity는 no-resume terminal이다. |
| sealed memfd / mandatory API | CLOSED | four write/grow/shrink/seal seals, canonical one-LF bytes, exact env FD, mandatory keyword-only expected argument와 None/current-live 거부가 child baseline substitution을 닫는다. |
| package/output pointers | CLOSED_AFTER_VALID_T1 | both exact top-level `/source_transition_evidence`, deep equality, logical seals 및 pre-P3 original-anchor comparison이 유지된다. B01 때문에 입력 tuple authority만 성립하지 않는다. |
| post-P3 validator / cycle | CLOSED_AFTER_VALID_T1 | sealed candidate에서만 expected state를 파생해 live P2A/T0, P2C/T1, S1 full rows를 검사하고 one-way DAG에는 self/future cycle이 없다. |
| R002 namespace / r001 | CLOSED | exact bundle/gate/review paths와 IDs, intentional R022 design-lineage literals, historical r001 allowlist, immutable failed-r001 seal이 유지된다. |
| publication terminal | CLOSED_EXCEPT_B01 | existing/race/partial/fsync/descriptor/supervisor ambiguity는 same-revision recovery 없이 authority-zero terminal이고 only success는 `PUBLISHED_NEW`다. B01 touch만 최초 baseline으로 오인된다. |
| 37 tests | BLOCKED_BY_B01 | exact method count, pre/post 37, skip0/exit0/OK와 negative axes는 유지되지만 post-fsync/pre-fstat touch oracle에는 사전 expected tuple이 없다. |
| authority | CLOSED_ZERO | activation/canonical/checkpoint/Goal/product/formal/device/release write·credit은 0이고 finding이면 P2 이후 write가 열리지 않는다. |

## 명시적 count/status/authority

```text
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
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

R023은 `0/0/0`이 아니므로 `PASS` 또는
`R023_R002_ATOMIC_CAPTURE_SOURCE_CANDIDATE_CORRECTION_ONLY` 권한을 부여할 수 없다.
post-fsync 최초 `fstat` 전에 발생한 same-inode metadata 변경을 current baseline으로
재채택하지 않는 새 roadmap revision과 새 dual review가 필요하다.
