# WalkSafe R012 independent skeptical review r001

```text
review_id: WS-R012-INDEPENDENT-SKEPTICAL-REVIEW-R001
type: INDEPENDENT_SKEPTICAL_STATIC_REVIEW
reviewer_agent: /root/r009_sandbox_fix_design/r012_skeptical_review
reviewer_session: /root/r009_sandbox_fix_design/r012_skeptical_review#20260802-r001
independence_attestation: true — frozen R012 한 파일만 검수했고 다른 R012 reviewer, review file, predecessor 본문 또는 결과를 읽거나 검색하거나 사용하지 않았다.
target_sha256: a7bd3d1732e4b99f6476702aceb89b7bce35603e9c8441059fee5e6a43d7e8a0
target_bytes: 24456
target_lines: 446
verdict: REVISION_REQUIRED
blocking: 6
major: 5
minor: 0
```

## 1. 범위와 방법

대상은 frozen R012 자체의 안전성·검증 가능성뿐이다. Candidate 및 candidate Python의 실행,
import, bytecompile, py_compile은 0회이고 네트워크와 predecessor 재감사는 수행하지 않았다. 시작
identity는 regular 0664, uid/gid 1000/1000, nlink 1, 24,456 bytes, 446 lines 및 위 SHA와
일치했다. 문서가 요구한 반례를 문구와 상태 전이에 대입하고, literal mutation 상수만 pipe 기반
read-only SHA/크기 계산으로 독립 확인했다.

## 2. 공격 결과 요약

| 공격군 | 판정 |
|---|---|
| alternate authority/root/path, stale/replay/cross-case | 한 entry 내부 tuple 결속과 committed attempt 재사용 거부는 존재한다. 그러나 root-row schema, pathname/FD 결속, 전역 cross-case disjointness가 각각 B03, B04, M02로 열려 있다. |
| reservation 및 crash windows | `mkdirat` 전 무-delta 재시도와 directory 관찰 후 no-replay는 닫혔다. selector/oracle/authority partial과 spawn 이후 receipt commit은 B06으로 열려 있다. |
| future/self cycle, mutual authority, output/self oracle | `authority_id`가 oracle SHA를 포함하지 않고 authority가 뒤에서 oracle SHA를 결속하므로 직접 hash cycle은 닫혔다. 기대 payload derivation과 case-spec authority는 B01, B02로 열려 있다. |
| inode/type/link/mount/ancestor 및 pathname/FD split | leaf row와 nofollow walk만으로 untracked ancestor와 mount identity를 결속하지 못하므로 B03, B04다. |
| daylog epoch 및 source/official/product/canonical delta | acceptance 비권한화 문구는 있다. delta의 관측 의미와 local-memory 추가 write는 M03, M04다. |
| stdout/stderr, timeout/signal/exec/truncation | 완전한 raw capture를 가정하면 extra byte는 mismatch다. 그 가정과 signal provenance 자체가 동결되지 않아 B05다. |
| mutation pre/post/delta/sentinel/attempt binding | 표의 네 pre/post SHA 및 두 size는 재계산과 일치했다. receipt/attempt/sequence 결속은 M05다. |
| exact 30 completeness 및 독점 authority | 30개 ID의 산술적 unique/disjoint 목록은 닫혔다. fixture recipe와 canonical case spec이 없어 B01, B02다. |
| consumed/absent/incomplete/conflict, liveness, no repair | attempt pair의 큰 분기는 있다. authority/result 상태와 task-child 비정상 상태가 B06, M01로 비총체적이다. |

## 3. Findings

### R012-SK-B01 — BLOCKING — source-independent output oracle가 draft payload에서 유도된다

- 대상: §6 첫 문단, lines 297-300의 exact 문구 `future manifest generator는 이 절만 입력으로
  쓰고 candidate source/AST/output/stream을 읽지 않는다`와 §6.2 lines 354-355의
  `reviewed R012 draft payload 7 ... 독립 재생성한 final manifest/seal`.
- 실행 가능한 반례/영향: 동일 frozen R012에서 서로 다른 draft payload `P`와 `Q`를 만든다. 각
  generator가 자기 reviewed payload를 읽어 output SHA를 만들고 publisher가 같은 bytes를 복사하면
  두 상이한 기대값이 모두 문구상 생성 가능하다. 악성 또는 잘못된 `P`도 자기 자신으로 만든
  oracle와 일치하므로 output 검사에 독립 기준이 없고 false PASS가 가능하다.
- 최소 교정: R013에 payload 7의 literal digest/size와 final manifest/seal을 만드는 완전한 canonical
  알고리즘 및 비-candidate 입력을 동결한다. Oracle generator가 draft/candidate bytes를 읽는 경로는
  금지하고, frozen digest 불일치는 generation 이전 terminal conflict로 보낸다.

### R012-SK-B02 — BLOCKING — exact-30 ID/결과표가 fixture recipe와 case-spec bytes를 독점하지 못한다

- 대상: §4.1 line 202의 `normative_case_spec_sha256`, §6 lines 299-300, producer 표 lines
  321-334, crash 표 lines 341-352, recovery/mutation 표 lines 362-389.
- 실행 가능한 반례/영향: generator A는 `tar-dotdot`을 `../x` entry로, generator B는
  `a/../../x` entry로 만들 수 있다. `json-duplicate-key`의 exact bytes, symlink target/ancestor
  tree, mount recipe, drift pre/post bytes, crash injection handshake 및 recovery marker preimage도
  고정되어 있지 않다. 양쪽은 같은 ID/rc/class 표를 만족하면서 서로 다른
  `normative_case_spec_sha256`를 스스로 선택할 수 있고, candidate가 한 fixture만 인식해도 그와
  함께 만든 registry/oracle가 승인할 수 있다.
- 최소 교정: 30개 각각에 대해 canonical spec serialization, exact fixture tree/bytes/physical
  preconditions, mutation 또는 crash trigger, first/second delta와 expected result를 하나의
  frozen byte authority로 열거하고 그 digest를 R013에 직접 고정한다. Generator와 verifier가
  임의 fixture를 공유해 답을 만드는 경로를 제거한다.

### R012-SK-B03 — BLOCKING — authority root row의 exact schema와 byte-equality가 서로 양립하지 않는다

- 대상: §4.1 lines 195-197의 root exact object
  `{literal_path,dev,ino,type,mode,uid,gid,nlink}`, line 212의 `known full physical rows`,
  §4.2 lines 251-253의 `byte-equal`, §5 lines 274-276의 full row
  `...,size,mtime_ns,ctime_ns,sha256`.
- 실행 가능한 반례/영향: directory root를 처음의 8-key object로 canonicalize해
  `authority_id`를 만든다. Selector는 directory에 유효한 size/mtime/ctime을 포함한 full row를
  가진다. Strict byte comparison은 항상 `AUTHORITY_CONFLICT`이고, 구현이 겹치는 8개 field만
  비교하면 문서에 없는 subset rule을 발명한다. 즉 valid chain이 없거나 구현마다 authority가
  달라져 검증 가능하지 않다.
- 최소 교정: 각 role/type의 단 하나의 exact row schema와 null/omission rule을 정의하고,
  preimage·selector·oracle·runtime 비교가 동일 canonical bytes를 사용하도록 한다. 비교가 whole-row인지
  명시하고 예외적 subset 비교를 없앤다.

### R012-SK-B04 — BLOCKING — pinned FD가 현재 literal pathname·ancestor·mount identity를 증명하지 않는다

- 대상: §4.2 lines 251-254의 pinned FD 검증, §4.3 lines 258-260의 pinned parent `mkdirat`,
  §5 lines 274-292의 leaf physical rows와 `pinned output root 아래 nofollow fd walk`.
- 실행 가능한 반례/영향: output leaf FD `A`를 pin한 뒤 추적되지 않는 상위 ancestor를 rename하고
  원 literal path에 별도 tree `B`를 둔다. `A`와 그 하위 파일의 FD row는 그대로이고 모든 write와
  observation은 `A` 아래에서 통과하지만 현재 literal path는 `B`를 가리킨다. 같은-device bind
  mount는 dev/ino row만으로 mount crossing을 구별할 수 없다. 또한 `mkdirat` 뒤 attempt directory
  FD를 pin한다는 요구가 없어 pathname swap 시 claim/marker가 예약한 inode와 갈라질 수 있다.
- 최소 교정: 신뢰 anchor부터 모든 ancestor/role을 nofollow로 pin하고 mount ID를 포함한다. 각
  security boundary 전후 fresh literal-path resolution과 pinned `fstat`의 dev/ino/type/mount ID를
  일치시키며, attempt 생성 직후 directory FD를 pin해 이후 모든 access를 그 FD-relative로 수행한다.
  어느 ancestor/path/FD drift도 child 0의 단일 terminal conflict로 보낸다.

### R012-SK-B05 — BLOCKING — crash oracle가 injected signal, timeout 및 완전한 stream capture를 구분하지 않는다

- 대상: §4.1 line 201의 `timeout_seconds`, §6.2 lines 338-339의 `SIGKILL returncode -9`와
  EMPTY streams, §6.3 lines 369-373의 `raw result` 비교.
- 실행 가능한 반례/영향: 잘못된 producer가 기대 prefix 파일을 만든 뒤 hang한다. Controller timeout이
  SIGKILL을 보내면 결과는 기대한 `-9`, stdout EMPTY, stderr EMPTY와 같아 planned crash injection으로
  오인된다. 별도로 child가 기대 stderr 뒤 extra bytes를 내보내도 bounded capture가 기대 길이에서
  truncate하면 exact compare가 통과한다. Exec failure, timeout kill, harness injection, child self-signal,
  capture overflow/EOF의 판별 field나 terminal precedence가 없다.
- 최소 교정: exec-success handshake, raw wait status, signal sender/reason, deadline-expired flag를
  동결한다. stdout/stderr는 분리된 lossless byte streams로 EOF까지 capture하고 exact length/hash 및
  `truncated=false`를 검증하며 overflow/exec/timeout을 서로 다른 fail-closed 결과로 정한다. Crash
  case는 동결된 harness injection event와 결속한다.

### R012-SK-B06 — BLOCKING — authority 생성과 post-spawn receipt의 crash 상태가 one-shot 상태기계에 없다

- 대상: §4 lines 219-270의 selector/oracle `freeze`, authority `O_EXCL create`, attempt pair commit,
  §6.3 lines 369-373의 first/second result, §6.4 line 384의
  `captured-first-stderr.bin`.
- 실행 가능한 반례/영향: selector/oracle/authority create 뒤 write-all 또는 parent fsync 전에 crash하면
  partial existing artifact를 retry, conflict, incomplete 중 어디로 보낼지 정의가 없다. 더 직접적으로
  child spawn 및 output/error 발생 뒤 `captured-first-stderr.bin`/observation의 durable commit 전에
  controller가 죽으면 attempt pair는 exact라 재호출은 rc81 consumed지만 검증할 first receipt는
  absent/partial이다. late fabrication은 silent repair이고, 금지하면 영구 비terminal이다. Existing
  attempt basename이 symlink/special인 경우와 `wrong` 대 `value conflict`의 겹치는 분류도 precedence가
  없다.
- 최소 교정: selector, oracle, authority, attempt, child-result receipt 각각에 O_EXCL
  write-all/fsync/close/parent-fsync와 ordered state table을 둔다. Receipt payload와 final commit marker를
  authority/claim에 결속하고, 모든 absent/partial/exact/conflict/type/link 및 각 crash point를 상호배타적
  terminal code로 보내며 repair/reconstruction은 0으로 고정한다.

### R012-SK-M01 — MAJOR — review/E1 availability guard 실패에는 terminal branch가 없다

- 대상: §1 lines 37-40의 `task ... terminal and ... child가 0`, lines 56-58의 F0가 A0를
  전제로 하는 구조, lines 60-69의 E1/source-review 동일 구조.
- 실행 가능한 반례/영향: 지정 reviewer가 child 하나를 만든 뒤 parent와 child 모두 terminal이 된다.
  `child가 0`이 아니므로 A0는 영원히 false이고 F0도 A0를 요구하므로 PASS도 REJECT도 아니다. E1
  parent가 child를 남긴 경우 A1도 같은 dead state다. Task 자체가 terminal이 되지 않는 경우의
  deadline/cancel transition도 없다. `총체적 상태기계`와 autonomous liveness가 성립하지 않는다.
- 최소 교정: parent terminal/inventory availability는 무조건 성립시키고 child-count, unexpected child,
  deadline/cancel은 validation failure로 분리해 exact rejection state로 보낸다.

### R012-SK-M02 — MAJOR — selector root/path uniqueness가 entry 사이에는 적용되지 않는다

- 대상: §4.1 lines 210-215, 특히 `tuple은 selector 안 정확히 한 번`과 `세 root inode는 서로
  다르다`.
- 실행 가능한 반례/영향: 두 distinct tuple의 entries가 같은 `attempt_parent`, `output_root` 또는
  `authority_literal_path`를 공유하게 한다. 각 entry 내부의 세 inode는 서로 다르고 tuple도 unique라
  문구를 만족한다. 첫 case의 `attempt`가 둘째를 consumed/conflict로 만들거나 mutation/output이 다른
  case oracle를 오염해 exact-30 완주가 순서 의존적이 된다.
- 최소 교정: 모든 entries에 걸친 authority path/basename uniqueness, role inode all-pairs
  disjointness, ancestor/descendant 비중첩, fixture·sentinel·output·attempt alias 0을 selector VALID
  조건으로 추가한다.

### R012-SK-M03 — MAJOR — zero delta가 event, final diff, cardinality 중 무엇인지 정의되지 않는다

- 대상: §2 lines 136-150의 `live_protected_root_unlisted_delta = 0`과
  `official_product_canonical_formal_device_gate_release_delta = 0`, §8 lines 432-436의
  E1_PHYSICAL_OK 입력.
- 실행 가능한 반례/영향: official file을 다른 bytes로 바꿨다가 snapshot 전에 원복하거나, 한 file을
  삭제하고 다른 file을 추가해 cardinality/net delta를 0으로 만든다. Compound 이름을 합산값으로
  구현하면 product `+1`과 canonical `-1`도 0이다. Post-state diff checker는 zero를 보고
  E1_PHYSICAL_OK를 승인하지만 mutation event 0 주장은 거짓이다.
- 최소 교정: source/official/product/canonical/formal/device/gate/release 각 domain을 별도 predicate로
  정의한다. Event count와 before/after full identity inventory를 구분하고, 어느 domain의 create/update/
  delete/rename/link/chmod/chown도 개별 0이어야 하며 상쇄와 mutate-restore가 불가하도록 관측 근거를
  명시한다.

### R012-SK-M04 — MAJOR — one-write daylog epoch가 추가 local-memory backup/DB writes와 충돌한다

- 대상: §2 lines 121-134의 `E_AUTOMATION_DAYLOG` 단일 append 예외와 `유일한 좁은 예외`, §7
  lines 399-404의 local-memory log-work 및 `memory DB write 전 backup`.
- 실행 가능한 반례/영향: exact daylog append 뒤 지시대로 DB backup과 log-work를 수행한다. 이는
  table에 path, preimage, count, method가 없는 추가 writes다. Checker가 이를 epoch 밖이라 무시하면
  `exact write set`이 거짓이고, 포함하면 단일 예외를 위반한다. 실패 시 backup/DB partial 상태의
  terminal 처리도 없다.
- 최소 교정: local-memory를 하지 않거나, acceptance가 끝난 별도 non-authorizing epoch로 분리해 exact
  paths/write set/preconditions/crash states를 동결한다. 그 성공/실패와 bytes가 PLAN/E1/SOURCE의
  입력이 아님을 기계적 dependency 목록으로 유지한다.

### R012-SK-M05 — MAJOR — mutation observation/receipt가 고유 authority와 attempt에 byte-bound되지 않는다

- 대상: §6.2 lines 356-358의 모든 producer second-call rc81, §6.4 lines 375-389의 source/carrier
  표, 특히 generic `captured-first-stderr.bin` bytes.
- 실행 가능한 반례/영향: 다른 `tar-dotdot` attempt에서 얻은 동일 144-byte stderr를 현재 case의
  carrier에 복사한 뒤 명시된 162-byte postimage로 바꾼다. 표에는 carrier content 안의
  authority ID, claim digest, spawn/capture sequence 또는 EOF proof가 없어 current first observation의
  receipt인지 구별할 수 없다. 또한 mutation verifier rc83과 base source의 second-call rc81 중 누가
  어느 순서로 판정하는지 동결되지 않아 구현별 결과가 갈린다.
- 최소 교정: immutable observation manifest에 case ID, authority ID, selector SHA, claim SHA,
  attempt inode, spawn sequence, raw stream length/hash/EOF를 넣고 commit SHA를 mutation verifier 입력에
  결속한다. 각 mutation의 before→first→mutation→verify/second exact delta와 rc81/rc83 precedence를
  명시한다.

## 4. 결론

직접 cycle 제거, future inode/time의 logical 분리, literal mutation hashes, committed attempt의
second-call 소비 규칙은 유효한 부분 방어다. 그러나 위 6 blocking finding 때문에 independent
expected value와 authority/result identity가 유일하게 재구성되지 않거나 unsafe execution이 같은
관측값으로 승인될 수 있다. 5 major finding도 total terminal/liveness와 zero-write 증명을 막는다.
따라서 현 R012는 E1 authoring으로 진행할 PASS가 아니며 fail-closed R013 corrigendum 경로로 가야 한다.
