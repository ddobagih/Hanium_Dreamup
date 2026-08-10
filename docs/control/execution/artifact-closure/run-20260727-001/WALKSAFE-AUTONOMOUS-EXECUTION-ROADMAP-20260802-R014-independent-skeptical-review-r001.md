# WalkSafe R014 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R014-INDEPENDENT-SKEPTICAL-REVIEW-R001
type = INDEPENDENT_SKEPTICAL_STATIC_REVIEW
reviewer_agent = /root/r014_skeptical_review
reviewer_session = /root/r014_skeptical_review@20260802-r001
independence_attestation = TRUE; 지정된 다른 R014 reviewer의 task/file/result/message/경로 존재 여부를 읽거나 검색하거나 기다리거나 전달받지 않았고 그 reviewer와 통신하지 않았다.
target_sha256 = ec2679538c34aa2001fa6a4b4210850eaf92a974c4312b5fe32ce7e27c990e0a
target_bytes = 24550
target_lines = 415
verdict = REVISION_REQUIRED
blocking = 7
major = 0
minor = 0
```

## 범위와 방법

frozen R014 415줄 전부를 읽고, 필요한 범위에서만 R013의 두 non-PASS review, 현재 checkpoint,
r021 Gap/Backlog, EPIC-03 Workstream, v2.4 static plan 및 두 현재 checker의 관련 정적 경로를 대조했다.
대상은 검토 시작 시 regular `0664`, uid/gid `1000/1000`, nlink 1, 24,550 bytes와 위 SHA-256에
일치했다. R013 roadmap 및 두 review의 SHA-256·bytes·lines와 `REVISION_REQUIRED 7B/0M/0m`,
`REVISION_REQUIRED 6B/0M/0m`도 R014 표와 일치했다.

candidate·제품 실행, import, compile/bytecompile, test/gate/build, network, 임시·cache 생성은 0이다.
predecessor/source/canonical/product/daylog/local-memory 수정도 0이며, 이 review 한 파일만 추가했다.

## 공격 판정 요약

| 공격 축 | 판정 | 근거 |
|---|---|---|
| stale/dynamic authority substitution | OPEN | checkpoint의 미교정 handoff는 B01, commit 전 권위 치환은 B05다. |
| rejected bootstrap resurrection | OPEN | P0 뒤 재검수가 없어 B06이다. |
| hardlink·dirty-tree damage | PARTIAL | known nlink3 peer를 분리하는 방향은 맞지만, 최종 경로 collision/lost update는 B05다. |
| r022 pair non-atomicity·order rewrite | OPEN | row/order 보존 요구는 명시됐지만 event scope는 B03, 물리적 half-pair는 B07이다. |
| history laundering | OPEN | seq39 trust anchor 자체는 강해졌으나 checkpoint `os.replace` 경쟁이 B05를 연다. |
| unreviewed staged bytes | OPEN | review와 설치 사이의 불변 manifest가 없어 B04다. |
| partial/crash/retry | OPEN | absorbing stop은 retry 부활을 막지만 B02의 도달 불가능 상태와 B07의 부분 게시가 남는다. |
| Gate/leaf/product/future authority expansion | OPEN | product/Goal write 0은 닫혔지만 stale handoff와 unscoped seq40이 B01/B03을 연다. |
| false official credit | CLOSED | R014:11-16,300-303,393-409는 product/Goal/formal/device/Gate/release 공로를 0으로 유지한다. |

## Findings

### R014-SK-B01 — [BLOCKING] “세 포인터뿐”이라는 전제가 live checkpoint의 추가 next 권위를 누락한다

- 정확한 근거: R014:24-35는 모순을 r021의 세 field로 한정하고, R014:300-305는 seq40의 두 canonical
  role과 working snapshot만 정의한다. 그러나 frozen checkpoint:304-337의 `current_work`는
  `work_item_id_semantics = NEXT_ACTION_POINTER_ONLY`인데도 완료된 FP047 ID를 유지하고,
  `current_focus`는 `FP048/GAP-057 준비`, `next_action`은 FP048 암호화·실제 배포 TLS·기기·독립검토를
  지시한다. checkpoint:30428-30446과 31449의 required `session_handoff`도 `FP048/GAP-057 NEXT`와 같은
  FP048 행동을 지시한다. 그 handoff의 snapshot은 603 paths인데 R014:303-305의 post-state는 609 paths다.
- 구체적 반례/영향: 구현자가 문서에 열거된 r022 세 pointer, canonical snapshot, working snapshot만
  교정한다. tail seq40/current r022/history/zero-credit 검사는 모두 통과할 수 있지만 다음 세션은 canonical
  checkpoint의 required handoff를 읽어 FP048과 실제 배포·기기·독립검토를 다음 행동으로 선택한다.
  R014_COMPLETE와 동시에 상충하는 future-leaf·외부 행동 권위가 남는 false closure다.
- 최소 교정: seq40 checkpoint postimage에 `current_work.work_item_id/current_focus/next_action`,
  `session_handoff.current_epic/next_single_action/source_commit_or_snapshot`의 exact 새 값 또는 명시적
  비권위화 값을 모두 freeze한다. 이 projection들이 r022 FP008/GAP-017과 exact 609-path identity에
  일치하지 않거나 FP048-next 문구가 남으면 거부하는 fail-first 및 final checker case를 추가한다.

### R014-SK-B02 — [BLOCKING] tool의 preimage 조건과 요구된 post-write `--check`가 동시에 참일 수 없다

- 정확한 근거: R014:277-280은 tool이 먼저 §3의 **모든** source anchor와 네 r022 output 및 event/receipt
  absence를 검증한다고 한 뒤 `--check`가 exact output bytes를 비교한다고 한다. §3 anchor에는 R014가
  바꾸는 checker/test 네 파일과 source checkpoint가 포함된다. 반면 R014:315-328은 그 네 source를
  변경하고 tool `--write`로 r022/receipt/seq40을 만든 뒤 `--check`를 요구하고, R014:377-380도 live에서
  source 설치와 seq40 switch 뒤 같은 검사를 요구한다.
- 구체적 반례/영향: 정상 구현만 해도 stage의 첫 `--write` 시 네 source hash가 §3 preimage와 다르다.
  write가 성공했다고 가정해도 이어지는 `--check`에는 r022 네 file과 receipt/event가 존재하고 checkpoint
  SHA도 바뀌어 absence/old-anchor 조건이 실패한다. 따라서 문서 그대로는 A7과 A13이 rc0가 될 수 없고,
  이를 통과시키려면 구현자가 임의로 precondition을 약화해야 한다.
- 최소 교정: mode별 predicate를 분리한다. pre-mutation 검사는 old source/checkpoint와 absence를,
  `--write`는 unchanged authority input + reviewed source postimage + old checkpoint + final-name absence를,
  `--check`는 review-bound exact postimage/presence/tail을 요구해야 한다. 각 mode의 literal input manifest와
  expected failure를 roadmap에 고정하고 old-anchor/absence 검사를 post-state에 재사용하지 않는다.

### R014-SK-B03 — [BLOCKING] seq40에 exhaustive subject diff와 impact disposition이 없어 좁은 보정이 global change가 된다

- 정확한 근거: static plan:665-673은 canonical update에 subject diff와 impact disposition을 요구하고,
  unscoped/unknown payload를 global로 취급하며 internal producer role을 allowlist한다. R014:282-302의 seq40
  계약은 event ID/type, 두 changed role, status/focus/source version, binding과 authorization만 고정한다.
  `changed_subject_ids_by_role`, `impact_closure_goal_ids`, `impact_disposition_by_goal`, correction producer
  classification의 exact 값은 없다. R014:249-263의 correction record가 event-level replay scope를 대신할
  수는 없다.
- 구체적 반례/영향: tool이 문서에 적힌 field만 넣은 seq40을 만들고 스스로 그 bytes를 `--check`한다.
  generic replay는 새 snapshot/live binding과 hash chain을 맞출 수 있지만, static policy상 이 update는
  global impact다. 반대로 구현자가 임의 subject 또는 Goal disposition을 넣어 FP048, 다른 EPIC, Gate를
  reopen/close해도 frozen roadmap에는 비교할 exact expected 값이 없다.
- 최소 교정: r021→r022 semantic diff에서 도출한 exhaustive old/new policy·Gap subject set, exact EPIC-03
  impact disposition, zero status/Goal/Gate impact, 허용된 control-correction producer/authorization
  classification을 seq40 field로 고정한다. omission, extra subject, role/order-row 변화 및 EPIC-03 밖 impact를
  각각 거부하는 checker/tool test를 추가한다.

### R014-SK-B04 — [BLOCKING] candidate reviews가 설치할 stage bytes에 불변으로 결속되지 않는다

- 정확한 근거: R014:335-344는 mutable stage root identity와 “exact 12-output tuple”을 두 task가 검수한다고
  하지만 tuple serialization/hash algorithm, add-only manifest path, per-path lstat/hash/bytes/mode 값이 없다.
  §4에서 재사용하는 12개 metadata field도 candidate tuple을 표현하는 schema를 정하지 않는다.
  R014:377-379는 A10에서 live preimage만 확인한 뒤 “reviewed stage”의 여섯 source bytes를 설치한다고 할
  뿐, review 종료 후 stage를 다시 nofollow/hash 비교하는 predicate가 없다. plan review 결과 파일 자체도
  availability capture의 file SHA/bytes를 이후 receipt/tool에 고정하라는 규칙이 없다.
- 구체적 반례/영향: 두 candidate review가 PASS한 직후 A11 전에 stage tool 또는 checker 한 파일을 바꾼다.
  허용된 여섯 path이므로 A11은 그것을 설치할 수 있고, 변조된 tool이 자체 `--check`를 PASS하게 만들 수
  있다. review가 본 tuple과 live에 설치된 tuple이 달라도 현재 상태식의 항은 모두 이름상 충족된다.
- 최소 교정: A7에서 exact 12 paths의 relative path/type/mode/uid/gid/nlink/bytes/SHA-256을 canonical JSON
  manifest로 add-only seal하고 두 review가 같은 manifest SHA를 결속하게 한다. root capture는 두 review
  file 자체의 hash/bytes도 freeze한다. A10/A11 직전과 각 install 직전에 nofollow로 manifest를 재검증하고,
  한 byte라도 다르면 live write 0 terminal로 보낸다. A12 생성 bytes도 같은 reviewed manifest와 같아야 한다.

### R014-SK-B05 — [BLOCKING] checkpoint-last는 compare-and-swap이 아니어서 concurrent successor를 덮어쓸 수 있다

- 정확한 근거: R014:277-280은 source checkpoint/absence를 tool 시작 때 먼저 검사한 후 여러 output을
  만들고 마지막에 plain `os.replace`를 사용한다. R014:377-383의 A10 preimage 확인과 A12 사이에도
  exclusive transition lock, final nofollow revalidation, destination no-replace/CAS 규칙이 없다. collision은
  R014:385-388에서 “관찰”된 경우에만 terminal이며, check와 replace 사이 경쟁은 관찰되지 않을 수 있다.
- 구체적 반례/영향: A12가 exact seq39를 읽은 직후 다른 합법적 writer가 별도 seq40 successor checkpoint를
  commit한다. R014는 이미 계산한 seq39→자기 seq40을 `os.replace`해 그 event를 지운다. 결과 history는
  여전히 seq39 anchor에서 자체적으로 유효하므로 A13 Quick2가 PASS할 수 있어 lost update/history laundering이
  성공한다. 같은 창에서 final r022 path가 생기면 temp의 O_EXCL만으로는 destination overwrite도 막지 못한다.
- 최소 교정: literal repository transition lock을 A10부터 checkpoint switch까지 소유하고, lock 획득 뒤와
  checkpoint rename 직전에 root/branch/HEAD/checkpoint dev·inode·type·mode·uid/gid/nlink/bytes/SHA 및
  reviewed inputs를 재검증한다. 새 final output은 direct O_EXCL 또는 `RENAME_NOREPLACE` 상당으로 설치하고
  collision 시 절대 덮어쓰지 않는다. checkpoint replace는 lock 아래 exact old identity가 그대로일 때만
  허용하며, 변경 시 write 0 terminal이어야 한다.

### R014-SK-B06 — [BLOCKING] rejected bootstrap 종료는 A3 한 번의 관찰일 뿐 commit/완료까지 유지되지 않는다

- 정확한 근거: R014:61-113은 complete P0 inventory를 잘 고정하고 R014:353-367은 P0 PASS를 A3에 둔다.
  그러나 P1은 R014:370-375의 repository/Quick2만, tool은 R014:277의 §3 repository anchor만 재검증한다.
  A10/A12/A13에는 §2 inventory 재검수가 없다. R014:394-403은 완료식에 bootstrap unchanged를 쓰지만
  그 값을 final 시점에 관찰하거나 event/receipt에 결속하는 명령·predicate가 없다.
- 구체적 반례/영향: A3 직후 absent여야 하는 R012 root 하나를 복원하거나 R008의 비-anchor entry를 바꾼다.
  이후 stage review, source install, seq40 switch와 모든 live checker는 그 경로를 읽지 않아 A14에 도달할
  수 있다. rejected bootstrap이 부활한 채 control correction 완료가 선언되는 false PASS다.
- 최소 교정: complete §2 manifest의 deterministic digest를 P0 result와 authorization receipt에 결속하고,
  A10 및 lock-held A12 commit 직전, A13 종료 직후에 같은 nofollow inventory를 다시 계산한다. 어느 시점의
  drift도 repository/checkpoint 후속 write 0의 named terminal로 보내고 A14는 final observation을 요구한다.

### R014-SK-B07 — [BLOCKING] 네 r022 final file은 선언된 atomic output이 아니라 crash 시 half-pair로 게시된다

- 정확한 근거: static plan:102와 686은 Gap/Backlog pair 및 policy-gap output atomicity를 요구한다.
  R014:228-237은 네 r022 output을 “한 checkpoint transaction”이라 부르지만 R014:277-280과 377-380은
  final output을 순서대로 add한 뒤 receipt와 checkpoint를 쓴다. R014:385-388은 crash/partial file을
  삭제하지 않고 보존하는 terminal로 명시하므로 물리적 all-or-none 게시가 아니다.
- 구체적 반례/영향: r022 Gap JSON만 final name으로 생성된 직후 crash한다. canonical binding은 r021이라
  다행히 checkpoint authority는 바뀌지 않지만, suffix `r022`인 독립 audit 문서가 Backlog/Markdown/commit
  marker 없이 영구 노출된다. filename/latest-revision 소비자와 후속 작업은 그것을 successor로 오인할 수
  있고, 같은 revision은 retry 금지라 pair를 완성할 수도 없다. 이는 atomic binding과 atomic output을
  혼동한 상태다.
- 최소 교정: 네 file과 pair manifest를 하나의 새 event-scoped directory에 준비해 directory 한 번의
  no-replace rename으로 게시한 뒤 checkpoint가 그 manifest를 결속하도록 path 계약을 바꾼다. 기존 flat
  path가 필수라면 uncommitted final file은 권위 0이라는 pair commit-marker 규칙을 모든 validator/consumer에
  먼저 강제하고 “물리적 atomic” 주장을 제거한다. 각 file 뒤 crash injection에서 lone r022가 선택되지
  않음을 정적으로 검수할 exact case도 고정한다.

## 확인된 폐쇄와 결론

R013 non-PASS 계보를 authority로 재사용하지 않는 결론, complete bootstrap manifest의 내용, seq39 exact
prefix anchor/uniqueness 방향, continuation checker hardlink를 atomic replace 전에 nlink1로 분리하고 두
peer를 보존하는 규칙, product/test·Goal materialization/start 0, read-only next-candidate 경계 및 모든 공식
공로 0은 유지할 가치가 있다. 특히 직접 in-place truncate를 금지한 hardlink 규칙은 현재 세 peer의
dev/inode/nlink/hash와도 일치한다.

그러나 위 7개 차단 항목 때문에 R014는 `PLAN_REVIEW_OK` 또는 control/canonical write authority를 만들 수
없다. 대상과 이 review를 frozen input으로 삼은 successor roadmap 및 두 fresh independent review 전에는
bootstrap/canonical/checker/Goal/product write가 0이어야 한다.

## 자체 검증 범위

작성 후 frozen 대상의 SHA-256·bytes·lines·regular/mode/uid/gid/nlink가 그대로인지, 이 출력의
regular `0664`, uid/gid `1000/1000`, nlink 1과 SHA-256·bytes·lines, metadata 12개 단일 출현 및
finding heading 7개를 read-only로 재검산한다.
