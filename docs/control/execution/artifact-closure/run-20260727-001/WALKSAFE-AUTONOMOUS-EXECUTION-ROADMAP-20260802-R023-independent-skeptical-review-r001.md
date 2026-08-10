# WalkSafe R023 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R023-INDEPENDENT-SKEPTICAL-REVIEW-R001
type = INDEPENDENT_SKEPTICAL_STATIC_REVIEW
reviewer_agent = /root/r022_skeptical_review
reviewer_axis = FIRST_FSTAT_TOUCH_RACE_CREATED_FD_PATH_PARENT_FSYNC_SUPERVISOR_DESCRIPTOR_LOSS_S1_MEMFD_POST_P3_RESEAL_I34_R001_R002_TEST_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R023.md
target_sha256 = e0d2f11064b3a38745555332aca16bcc6b876094c33d89d26db0aa5da289272d
target_bytes = 25895
target_lines = 555
reviewed_at = 2026-08-02T13:09:16+09:00
status = REVISION_REQUIRED
findings = BLOCKING=2 MAJOR=0 MINOR=0
blocking = 2
major = 0
minor = 0
authority_granted = NONE
```

## 범위와 target identity

R023 555줄 전체를 first-observation race, same-byte inode 교체와 touch/unlink/link,
created FD 대 path CAS, parent fsync, supervisor/descriptor loss, S1 precommit과 held FD,
sealed-memfd child, coupled rewrite/current fallback, package/output persistence와 cycle,
exact-existing/fsync ambiguity, I34, failed-r001, R002 namespace, 37-test oracle와
zero-authority 축에서 정적 red-team 검토했다. 검토 시작과 판정 직전 target은 모두
SHA-256 `e0d2f11064b3a38745555332aca16bcc6b876094c33d89d26db0aa5da289272d`,
25,895 bytes, 555 lines였다.

source/build/test/candidate checker는 실행하지 않았다. source, P2A, P2C, r001, r002,
canonical, checkpoint, Goal, 제품은 수정하지 않았다.

## Findings

### BLOCKING R023-SK-B01 — `fsync(fd)` 뒤 최초 `fstat(fd)` 전 same-inode metadata drift를 T0/T1로 채택한다

§5.2는 created FD로 R022의 inode-replacement race를 크게 줄였지만 순서가
`write-all -> fsync(fd) -> T_created=fstat(fd)`다. 즉 publication path가 이미 같은 uid의
다른 process에 보이는 상태에서, authorized write가 끝난 tuple과 최초 expected tuple 사이에
여전히 syscall 하나의 창이 있다.

다음 interleaving은 모든 명시 검사를 통과한다.

```text
supervisor: fsync(created_fd) returns
other process: touch(path) on that same inode
supervisor: T_created = fstat(created_fd)       # moved mtime/ctime adopted
supervisor: pread(created_fd) == intended raw
supervisor: path lstat == T_created
supervisor: second read fd dev/ino/content exact
supervisor: parent snapshot and all repeated checks exact
```

touch는 raw/hash/length, dev/ino, mode, uid/gid, nlink와 parent directory tuple을 바꾸지
않는다. 더 늦어진 mtime/ctime은 P2A의 `selected_ns <= min(...)` 및 P2C의
`captured_ns <= min(...)`도 만족한다. hardlink를 추가했다가 제거하거나 mode를 바꿨다가
0600으로 돌리는 pair도 첫 fstat 전에 끝나면 최종 nlink/mode는 정상이고 이동한 ctime만
새 baseline으로 채택된다. same-byte write도 같은 유형이다. 반면 다른 inode replacement나
unlink는 held created FD와 path dev/ino/nlink 비교로 제대로 실패한다.

따라서 §5.2의 touch/link terminal과 §11의 “file fsync 뒤 first path comparison 전”
negative oracle은 그 구간 중 **최초 fstat 이전**에는 구현할 expected tuple이 없다.
최소 교정은 final write 직후 fsync 전에 exact post-write tuple `T_written`을 먼저 고정하고,
fsync 뒤 첫 fstat부터 이를 expected로 비교하는 것이다. create부터 tuple commitment까지의
모든 same-owner mutation까지 배제한다고 주장하려면 path가 노출되는 동안의 동시 metadata
operation을 배제하거나 검출하는 더 강한 isolation primitive도 명시해야 한다. 어느
경우든 drift를 current tuple로 채택하지 말고 P2A/P2C new-revision authority-zero terminal로
끝내야 한다.

### BLOCKING R023-SK-B02 — supervisor 종료 뒤 candidate 내부에서 expected anchor를 재파생해 coordinated reseal이 새 baseline이 된다

§4/§9는 original in-memory commitment와 T0/S1/T1 held FD를 P3 publication 및 published
anchor validation까지 유지한다. 그 구간의 coupled rewrite와 child fallback은 닫힌다.
그러나 P3 뒤 validator는 expected state를 오직 “sealed candidate”의 현재
`/source_transition_evidence`에서 파생하고, P4는 supervisor 종료 뒤 독립적으로 수행된다.
로드맵에는 P3 당시 package/output exact bytes/hash/full tuple 또는 original anchor를
candidate 밖의 immutable predecessor가 보유하거나 P4 reviewer에게 전달하는 계약이 없다.

두 JSON pointer의 deep equality와 logical seal은 내부 무결성 검사일 뿐 외부에 고정된
expected digest가 없으면 재계산할 수 있다. 따라서 supervisor와 held FD가 사라진 뒤 P4
전에 다음 coordinated rewrite를 수행할 수 있다.

```text
1. P2A를 새 tuple로 교체하고 세 source의 T0 constants와 내용을 함께 다시 작성
2. P2C를 새 S1 rows/T0/T1에 맞춰 다시 작성
3. package와 output의 /source_transition_evidence를 새 current state로 함께 변경
4. 두 logical seal과 나머지 r002 outputs를 다시 계산
5. post-P3 validator는 candidate의 새 pointer를 expected로 파생
6. live P2A/P2C/S1과 새 candidate가 일치하므로 current coordinated state가 PASS
```

I34는 roadmap/reviews/failed-r001을 고정하지만 P2A/P2C/S1/r002의 original P3 postimage는
포함하지 않는다. P2B intended-byte commitment와 retained descriptors도 memory-only라
supervisor 종료 후에는 비교 기준이 아니다. “r002 exact-six가 read-only checks 전후
unchanged”라는 intra-check snapshot은 rewrite가 다음 독립 check 전에 끝나면 P3 당시
identity와 비교할 수 없다. P4 review가 새 상태를 처음 관측해 pin하면 공격 상태가 오히려
후속 authority의 predecessor가 된다. package가 output을 back-reference하지 않아 hash
self-cycle은 없지만, 바로 그 단방향 reseal 가능성 때문에 candidate 자체만으로 원래 P3
publication을 증명할 수 없다.

최소 교정은 original anchor와 r002 root/exact-six identities를 P3 시점의 supervisor가
candidate 밖 add-only predecessor에 결박하고, P4가 그 외부 expected 값을 의무 입력으로
받게 하는 것이다. 또는 supervisor/held FDs와 precommitted S1 bytes를 P4 dual review가
exact candidate identities를 기록할 때까지 유지하고 sealed transport로 두 reviewer에게
주입해야 한다. P4 전 current candidate에서 expected 값을 새로 구성하는 fallback과
coordinated reseal은 terminal이어야 한다.

## 나머지 공격 축

| 축 | 판정 | 근거 |
|---|---|---|
| I34 / S0 / failed-r001 | CLOSED | ordinal 1~34, exact FILE/DIRECTORY/lstat/type/semantic rules, S0 cutoff와 failed-r001 root+six/NUL-name digest가 유일하다. |
| created FD 대 path / inode replacement | CLOSED_EXCEPT_B01 | O_EXCL created FD의 dev/ino와 path/second-read FD를 비교하고 fd를 보유하므로 replacement·unlink는 채택되지 않는다. 첫 fstat 전 same-inode metadata drift만 열린다. |
| parent fsync / descriptor loss | CLOSED_TO_P3 | parent dirfd snapshot, file/parent fsync, 반복 CAS, unexpected close/loss/ambiguity terminal과 no-resume가 명시돼 있다. |
| S1 precommit / held FD | CLOSED_TO_P3 | exact final path/hash/bytes가 mutation 전에 commit되고 첫 stable S1 snapshot 및 세 read-only FD가 different-byte와 snapshot 후 replacement를 막는다. |
| sealed memfd child / mandatory API | CLOSED_TO_P3 | write/grow/shrink/seal 봉인, canonical one-LF bytes, pass_fds, no-default APIs와 child 전후 original checks가 current-live child adoption을 막는다. |
| P2C canonical schema | CLOSED | exact v3 keys/types/rows/digest와 canonical UTF-8 serialization이 하나의 raw encoding을 정한다. |
| coupled rewrite/current fallback | CLOSED_TO_P3_OPEN_AFTER_SUPERVISOR | original commitment와 held FDs가 살아 있을 때는 닫히지만, 이후 candidate-current-only derivation은 B02 공격을 허용한다. |
| package/output mismatch/cycle | CLOSED_FOR_STATIC_MISMATCH | exact top-level pointers, deep equality와 logical seals가 단일 mismatch를 잡고 DAG는 비순환이다. 외부 pin 없는 coordinated reseal persistence는 B02로 열려 있다. |
| exact-existing / retry / fsync ambiguity | CLOSED | P2A/P2C O_EXCL 이후 실패와 P3 existing/EEXIST/parent-fsync ambiguity는 repair/recovery 없이 target untouched 및 new-revision terminal이다. |
| R002 namespace / r001 allowlist | CLOSED | exact R002 paths·IDs와 의도된 R022 lineage token, 제한된 historical r001/R001 및 immutable failed-r001 계약이 명시돼 있다. |
| 37 tests / read-only | BLOCKED_BY_B01_B02 | exact 37, skip0/exit0/OK 및 listed negatives는 유지되지만 pre-fstat touch와 post-supervisor full coordinated reseal의 trusted expected oracle이 없다. |
| authority/credit zero | CLOSED | R023은 non-effective이고 canonical/checkpoint/Goal/product write 권한은 0이며 finding이면 P2 onward authority가 없다. |

## 명시적 판정

```text
status = REVISION_REQUIRED
findings = BLOCKING=2 MAJOR=0 MINOR=0
source_build_test_executions = 0
source_writes = 0
capture_writes = 0
postimage_writes = 0
r001_writes = 0
r002_writes = 0
canonical_checkpoint_goal_product_writes = 0
formal_device_release_credit = 0
authority_granted = NONE
```

R023은 `0/0/0`이 아니므로 `PASS` 또는
`R023_R002_ATOMIC_CAPTURE_SOURCE_CANDIDATE_CORRECTION_ONLY` 권한을 부여할 수 없다.
최초 post-write tuple과 `fsync` 후 tuple의 non-adoption 비교, 그리고 P3 original anchor를
P4까지 candidate 외부에서 승계하는 새 roadmap revision과 새 dual review가 필요하다.
