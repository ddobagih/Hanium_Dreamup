# WalkSafe R024 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R024-INDEPENDENT-SKEPTICAL-REVIEW-R001
type = INDEPENDENT_SKEPTICAL_STATIC_REVIEW
reviewer_agent = /root/r022_skeptical_review
reviewer_axis = CONTENT_EQUIVALENCE_DIFFERENT_BYTE_EXEC_RACE_PATH_ENVELOPE_IPC_CHALLENGE_P5_CLOSURE_I37_R001_R002_TEST_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R024.md
target_sha256 = 356fb4308542b129e0d2ef5957328cfd0fdebc57bd8dbf297b0856675eb21a92
target_bytes = 26522
target_lines = 571
reviewed_at = 2026-08-02T13:25:46+09:00
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=0 MINOR=0
blocking = 1
major = 0
minor = 0
authority_granted = NONE
```

## 범위와 target identity

R024 571줄 전체를 content-equivalence scope, different-byte/symlink/hardlink race, IPC
nonce·EOF, current fallback, P3→P4 coordinated reseal, reviewer challenge 전달, P5
self-reference·closure rewrite·external handoff, R002/r001/retry, 37-test oracle와
zero-authority 축에서 정적 red-team 검토했다. 검토 시작과 판정 직전 target은 모두
SHA-256 `356fb4308542b129e0d2ef5957328cfd0fdebc57bd8dbf297b0856675eb21a92`,
26,522 bytes, 571 lines였다.

source/build/test/candidate checker는 실행하지 않았다. source, P2A, P2C, r001, r002,
P4/P5, canonical, checkpoint, Goal, 제품은 수정하지 않았다.

## scope 판정

inode·mtime·ctime을 authority에서 제거하고 same-byte replacement/touch를 의미동등으로
취급하는 단순화 자체는 일관된다. authoritative bindings는 path/SHA-256/bytes뿐이고,
causal time도 review semantic time에서만 나오며, success predicate가 뒤에서 물리 tuple을
다시 authority로 끌어들이지 않는다. 같은 바이트의 물리 이력을 증명하지 않는다는
명시적 한계도 이 모델과 맞는다.

그러나 이 단순화 뒤에도 **different bytes는 authority 위반**이다. 아래 finding은 transient
metadata history가 아니라 실제 builder/test process가 다른 source bytes를 실행할 수 있는
content-consumption race다.

## Finding

### BLOCKING R024-SK-B01 — supervisor가 검증한 bytes와 child가 import/실행한 bytes가 같은 FD 또는 sealed payload로 결박되지 않는다

§2.2 safe reader는 그 reader가 연 FD에서 exact hash/bytes를 읽게 한다. 하지만 §7/§12가
child에 봉인해서 전달하는 것은 `SOURCE_TRANSITION_ANCHOR`뿐이고, 세 S1 source bytes나
그 read FD는 아니다. 명시된 child 경계는 receipt/source path를 **child 전후에**
content-CAS로 다시 읽는 bracket이다. 표준 `unittest` child와 builder/checker import가
supervisor가 검증한 동일 FD 또는 sealed source payload에서 코드를 로드해야 한다는
계약은 없다.

따라서 다음 interleaving이 문서상 pre/post equality를 통과할 수 있다.

```text
supervisor: S1 core/builder/test paths == original expected content
supervisor: child pre-check PASS, sealed anchor memfd 전달
attacker: child import 직전에 한 S1 path를 different-byte regular file로 교체
child: 교체된 validator/builder/test code를 import·실행하고 OK 또는 outputs 생성
attacker: child 종료 전후 원래 expected bytes를 path에 복원
supervisor: child post-check와 original anchor comparison PASS
```

교체 code는 sealed anchor 값을 읽어 예상 결과를 출력하거나, anchor pointer만 올바른
임의 candidate를 만들 수 있다. 이후 supervisor는 원래 S1 content와 candidate 안의
anchor equality는 확인하지만 **그 결과를 만든 executable bytes**가 원래 S1이었다는
증거는 갖지 않는다. 이는 R024가 포기한 same-byte/time history가 아니라 다른 바이트의
실행이다. §12의 different-byte rejection과 pre/post 37 결과가 바로 이 창에서는 oracle이
되지 않는다.

같은 결함은 path envelope에도 나타난다. §2.2는 ancestor를 no-follow로 먼저 walk한 뒤
final path를 `O_NOFOLLOW`로 연다고만 하며, anchored repository dirfd에서 각 component를
retained `openat(O_DIRECTORY|O_NOFOLLOW)`로 연결하거나 `openat2(RESOLVE_BENEATH|
RESOLVE_NO_SYMLINKS)`를 사용한다고 하지 않는다. final `O_NOFOLLOW`는 중간 ancestor가
check와 open 사이 symlink로 바뀌는 것을 막지 않는다. 표준 child import에는 safe-reader
envelope조차 강제되지 않으므로 transient symlink/hardlink/different-byte path를 실행한 뒤
원복할 수 있다.

최소 교정은 다음 둘을 함께 요구하는 것이다.

- 모든 source/receipt read를 anchored dirfd component walk 또는 동등한 beneath/no-symlink
  primitive로 수행해 실제 opened object가 허용 repository path 아래임을 보장한다.
- supervisor가 exact S1 executable bytes를 no-follow FD에서 읽어 sealed memfd/immutable
  payload로 만들고, unittest·builder·checker child가 바로 그 payload에서 import/execute
  하게 한다. 단순 path pre/post bracket은 실행 identity 근거가 될 수 없다.
- 기존 37-method universe 안에 pre-check 뒤 child import 전 different-byte regular,
  ancestor-symlink 및 hardlink substitution을 주입하고, 교체 code가 실행되지 않으며 P3
  rename/P4/P5가 발생하지 않음을 고정한다.

또는 hostile concurrent different-byte execution까지 명시적으로 threat scope 밖으로
제외해야 한다. 현재 문구는 hostile same-UID의 transient **metadata history**만 제외하면서
different-byte rejection과 content integrity는 계속 success 근거로 주장하므로 그 해석은
현 revision에 없다.

## 나머지 공격 축

| 축 | 판정 | 근거 |
|---|---|---|
| same-byte/time 의미동등 | CLOSED | content-only binding, semantic causal time와 명시적 unsupported physical-history 범위가 전 epoch에서 일관된다. |
| safe-reader observed state | CLOSED_FOR_SINGLE_READ | final FD의 before/after envelope와 두 번의 exact SHA/bytes read는 관측된 persistent different-byte/symlink/hardlink/unsafe state를 거부한다. 소비 child와의 TOCTOU는 B01이다. |
| IPC nonce / EOF / resume | CLOSED | canonical one-line frames, decoded hash/bytes, one-use nonce, strict state order와 EOF/broken-pipe/process loss terminal이 current reconstruction을 금지한다. |
| P2A/P2C canonical content | CLOSED | exact v5/v4 schema, canonical raw, committed original T0/T1과 mandatory no-default anchor가 유일하다. |
| P3→P4 coordinated reseal | CLOSED | supervisor가 P5까지 original object를 보유하고 candidate 밖 challenge를 reviewer task input에 먼저 전달하며 각 review 전·사이·후를 original로 재검사한다. |
| reviewer challenge handoff | CLOSED | challenge는 candidate에서 파생되지 않고 두 review가 challenge/candidate/anchor hashes와 시작·종료 equality를 기록하며 supervisor가 결과를 원본에 대조한다. |
| P5 self-reference / closure | CLOSED | closure는 앞선 challenge·anchor·candidate·review bindings만 담고 자기 hash는 담지 않는다. original raw validation 뒤 parent가 P5 binding을 외부 세 채널에 기록하므로 DAG가 순방향이다. |
| closure rewrite / future handoff | CLOSED | different-byte closure는 committed P5 binding과 달라지고 future R025는 live closure가 아닌 handoff pin을 요구한다. same-byte replacement는 선언된 의미동등이다. |
| exact-existing / retry / fsync ambiguity | CLOSED | P2A/P2C/P5 add-only와 r002 rename-noreplace에서 existing·partial·EEXIST·fsync ambiguity는 recovery 없이 terminal이다. |
| I37 / failed-r001 / R002 namespace | CLOSED | exact 37-row content registry, failed-r001 exact-six content/semantic seal, R002 paths·IDs와 제한된 historical r001/R001가 명시돼 있다. |
| 37 tests | BLOCKED_BY_B01 | method universe와 negative axes는 유지되지만 validated path bytes와 실제 child-import bytes를 결박하는 oracle이 없다. |
| zero authority | CLOSED | 결과는 non-effective이고 canonical/checkpoint/Goal/product write 권한은 0이며 finding이면 P2 onward write가 열리지 않는다. |

## 명시적 판정

Finding 한 건으로 revision이 필요하며 source/build/test 실행과 product-side credit은 모두
0이다. 요청된 content-CAS correction authority는 부여하지 않는다. 검증된 S1 bytes와
실제 child executable bytes를 동일 immutable input으로 결박하는 새 roadmap과 새 dual
review가 필요하다.
