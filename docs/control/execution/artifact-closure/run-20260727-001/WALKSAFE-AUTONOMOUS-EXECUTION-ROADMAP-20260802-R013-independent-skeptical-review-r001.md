# WalkSafe R013 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R013-INDEPENDENT-SKEPTICAL-REVIEW-R001
type = INDEPENDENT_SKEPTICAL_STATIC_REVIEW
reviewer_agent = /root/r009_sandbox_fix_design/r013_skeptical_review
reviewer_session = /root/r009_sandbox_fix_design/r013_skeptical_review@20260802-r001
independence_attestation = TRUE; 다른 R013 reviewer의 파일·결과·메시지·존재 여부를 읽거나 검색하거나 전달받아 사용하지 않았고 그 reviewer와 통신하지 않았다.
target_sha256 = 40e68ae0e808af3eb2038b672fb0545a8d35dd25a4d910f866b75dcd1c8f958a
target_bytes = 13769
target_lines = 248
verdict = REVISION_REQUIRED
blocking = 6
major = 0
minor = 0
```

## 범위와 방법

지정된 frozen R013 전체를 줄 단위로 정적 검수했다. 필요한 범위에서만 frozen R012와 그 두
non-PASS review의 정체성·terminal 결론을 대조했고, 현재 checkpoint·r021 binding·branch·HEAD를
read-only로 확인했다. R012 세 입력은 R013:30-32의 SHA-256·bytes·lines와 일치했고, 현재 checkpoint와
r021 두 JSON 및 branch·HEAD도 R013:83-103의 선언과 일치했다.

candidate 또는 제품 실행·import·compile, test/full gate/build, network, temp/cache, daylog/local-memory,
target·predecessor·제품·canonical 수정은 모두 0이다. 시작 및 작성 직전 대상은 regular 0664,
uid/gid 1000/1000, nlink 1, 13,769 bytes, 248 lines와 위 SHA-256에 일치했고 지정 출력은 absent였다.

## 공격 판정 요약

| 공격 | 판정 | 근거 |
|---|---|---|
| rejected R012/bootstrap root 부활·repair·stale review 재사용 | OPEN | 영구 금지 문구와 R013 review barrier는 유효하지만, 실행 가능한 종료 inventory와 P0 선행 전이가 없어 R013-SK-B01이 남는다. |
| dirty tree clean/reset/delete/overwrite 또는 FP-048 흡수 | OPEN | 파괴 명령은 금지됐지만 기존 변경의 소유권·baseline delta가 닫히지 않아 R013-SK-B04다. |
| P0-P7 건너뛰기와 선행 product/test/canonical/Goal 전이 | OPEN | 선언 순서와 시작 식은 있으나 live commit을 요구하는 총체적 전이가 없어 R013-SK-B05다. |
| stale seq39/checkpoint/r021/snapshot 또는 alternate root/branch/leaf | OPEN | 과거 snapshot 재사용 금지는 유효하지만 drift 재선택이 R013-SK-B02를 연다. |
| leaf plan/review 전 구현 또는 시작 event 없는 code/test 변경 | OPEN | 시작 event 경계는 적혀 있으나 future leaf의 complete plan/review barrier가 없어 R013-SK-B03이고, gate 교정 경로는 R013-SK-B05와 겹친다. |
| 외부·사용자·maintainer·device·formal 권한 확대와 future evidence 선사용 | CLOSED | R013:24,133,209-211,215-220,226-228이 내부 권한과 외부 근거를 분리하고 요청 packet을 증거로 쓰지 않는다. |
| 내부 검사 결과를 formal/device/5 Gate/release 공로로 과장 | CLOSED | R013:11-15,102-103,218-220,233-238이 모든 공식 delta와 release 적격성을 0/NOT_RUN/NOT_ELIGIBLE로 유지한다. |
| failure·severity-only·retry·crash 뒤 권한 부활 | OPEN | R013 review의 nonzero finding은 terminal이지만 P2-P6 runtime partial/crash는 R013-SK-B06으로 남는다. |

## Findings

### R013-SK-B01 — [BLOCKING] 부트스트랩 종료 predicate가 재구성 불가능하고 P0도 전이에서 우회된다

- 정확한 근거: R013:49-70은 R008 root를 literal path와 complete entry identity 없이 count/mode 및 네
  anchor로만 기술하고 R009-R012 root도 literal path 없이 absent라 한다. 허용된 frozen R012도 R012
  세 root path만 주며 R008 및 R009-R011 exact root inventory는 주지 않는다. 동시에 R013:126-131의
  exact 전이는 `PIVOT_PLAN_OK -> canonical v2.4 stage P1 only`이고 R013:137-141의 P0를 선행 상태로
  요구하지 않는다.
- 구체적 반례/영향: 두 review 뒤 P1 직전에 anchor가 아닌 R008 entry를 바꾸되 count/mode와 네 anchor를
  유지하거나, 문서에 path가 없는 rejected root 하나를 원래 위치에 다시 만든다. 검사자는 허용된 입력만으로
  어느 literal tree의 어떤 전체 bytes가 `unchanged/absent`인지 판정할 수 없고, exact 전이대로 P0 없이
  P1에 진입할 수도 있다. rejected bootstrap이 살아 있는 상태에서 pivot authority가 열리는 false PASS다.
- 최소 교정: R008의 literal 세 root와 complete nofollow tree manifest, R009-R012의 literal absent root
  목록을 한 frozen 표로 결속한다. 전이 한 줄을 `PIVOT_PLAN_OK -> P0 only`, `P0 PASS -> P1 only`,
  `P0 failure -> immutable incident STOP`으로 바꾸고 failure 때 repair/resume/write 0을 유지한다.

### R013-SK-B02 — [BLOCKING] P1 drift가 review되지 않은 root·branch·state·leaf 재선택 권한을 만든다

- 정확한 근거: R013:83-103은 현재 branch, HEAD, checkpoint SHA, seq39, r021과 FP-048을 선언하지만
  R013:152-154의 P1 합격 목록은 branch·HEAD·literal repository root·checkpoint full SHA를 요구하지
  않는다. 특히 exact 문구 `새 실제 상태에서 selection을 다시 계산한다`는 frozen R013을 끝내지 않고
  drift한 authority에서 계속하게 한다.
- 구체적 반례/영향: 같은 checkpoint/r021을 복사한 alternate checkout에서 Quick2를 통과시키거나,
  review 뒤 합법적인 seq40/r022가 생긴 상태에서 P1을 실행한다. 전자는 다른 branch/root에 canonical
  write를 만들 수 있고, 후자는 R013이 review하지 않은 next leaf를 현장에서 골라 P2 이하를 재해석할 수
  있다. stale 값 덮어쓰기는 피했지만 authority substitution은 막지 못한다.
- 최소 교정: P1 입력에 literal repository root의 nofollow identity, branch, HEAD, checkpoint path/full
  SHA, r021 두 full SHA와 직전 actual snapshot을 직접 포함한다. 하나라도 달라지면 selection 재계산 없이
  이 revision을 terminal로 끝내고, 새 실제 상태와 leaf를 고정한 successor 한 파일과 그 reviews로
  이동한다.

### R013-SK-B03 — [BLOCKING] complete FP-048 leaf plan은 두 R013 review 뒤에 처음 생겨 검수 barrier가 없다

- 정확한 근거: R013:110-124의 두 review는 frozen R013만 대상으로 한다. R013:158-178은 future leaf의
  front matter 일부만 고정하고 목표·정책 기준·포함 path·제외 범위·TC-FP-048-01~07별 acceptance와
  stop 조건을 exact bytes로 정하지 않는다. 그 future file은 R013:180-185에서 materialize된 뒤 별도
  plan review 없이 R013:187-202의 generic full gate와 시작 event로 간다.
- 구체적 반례/영향: 열거된 ID/hash/front matter는 그대로 두되 leaf 본문에 unrelated 정책·사용자 변경
  path를 포함하거나 TC 하나와 실패 경계를 누락한다. 구조 checker와 기존 19 gate는 통과할 수 있고,
  P4는 그 unreviewed scope를 `IN_PROGRESS`로 만들어 FP-048 code/test mutation 권한으로 사용한다. 뒤의
  implementation review는 이미 발생한 write의 선행 plan review를 대신하지 못한다.
- 최소 교정: complete leaf bytes 또는 그 유일한 canonical payload SHA를 successor roadmap에 먼저
  고정하고, 해당 exact leaf plan을 대상으로 한 두 fresh independent review가 모두 valid PASS 0/0/0인
  것을 P2 live materialization과 P3의 선행조건으로 둔다. 어느 nonzero finding도 새 successor 전까지
  leaf/product/test write 0으로 끝낸다.

### R013-SK-B04 — [BLOCKING] dirty worktree 보존은 있지만 FP-048가 소유할 delta 경계가 없다

- 정확한 근거: R013:74-85는 backup identity와 reset/clean/delete/광범위 overwrite 금지만 둔다.
  R013:191-193은 gate 종료 snapshot을 결속하고 R013:206-218은 한 leaf 범위와 같은 실행 구간을 말하지만,
  pre-existing dirty path/content와 P4 이후 FP-048가 만든 exact create/update delta를 구분하거나 unrelated
  path를 evidence·successor에서 제외하는 predicate는 없다.
- 구체적 반례/영향: GOAL_STARTED 전에 이미 수정된 backend 보안 file이나 test를 FP-048 구현기록의
  content set에 넣거나, 시작 뒤 unrelated 사용자 file을 한 번 더 수정해 같은 session delta로 만든다.
  reset/clean은 하지 않았고 final hashes와 회귀 PASS도 만들 수 있으므로 현재 문구만으로는 사용자 변경이
  FP-048 공로와 canonical successor에 흡수되는 것을 거부할 수 없다.
- 최소 교정: 기존 P3 post-gate snapshot을 immutable pre-start baseline으로 명명하고 complete leaf가
  고정한 exact path allowlist에 대해 P4 이후 create/update delta만 구현 evidence로 허용한다. baseline에
  이미 있던 user delta와 allowlist 밖 delta는 그대로 보존하되 evidence/canonical update에서 제외하고,
  충돌 시 clean/reset 없이 terminal successor로 보낸다.

### R013-SK-B05 — [BLOCKING] P1-P4 순서식이 staged PASS를 live canonical commit으로 오인한다

- 정확한 근거: R013:126-131은 review 뒤 P1까지만 전이시키고 P1→P2→P3→P4의 상태 전이는 없다.
  R013:182-184는 P2의 staged checkpoint 검증만 명시하며, R013:232-235의 `LEAF_START_READY`도
  `P1/P2 staged validation PASS`만 요구해 seq40 materialization과 seq41 READY의 live commit 및
  post-commit 검증을 요구하지 않는다. R013:194의 `gate branch만 교정`도 P4 이전 허용 write set과
  fresh 19-command 재시작 경계를 정하지 않는다.
- 구체적 반례/영향: P2 candidate를 별도 경로에서 검증한 뒤 live seq39는 그대로 두고 P3 19개를
  통과시킨다. 그 후 seq40/41/GOAL_STARTED를 한 checkpoint에 합치거나 P3 실패를 test/control source
  변경으로 교정해도 readiness 식의 항은 참으로 읽힌다. 각 최종 file이 유효해도 gate가 READY leaf보다
  먼저 실행됐고 code/test write가 GOAL_STARTED보다 앞서 발생한 순서 우회다.
- 최소 교정: 기존 단계 이름만 사용해 `P1_PASS -> P2_STAGED -> P2_LIVE_SEQ40 -> P2_LIVE_SEQ41 ->
  P3_FRESH_19 -> P4_STAGED -> P4_LIVE_VERIFIED`를 단조 전이로 적고 각 live tail/hash를 다음 단계
  predicate에 넣는다. P3 failure에서는 source/test/canonical 교정을 허가하지 말고 새 event의 full 19를
  처음부터 수행할 successor 판단으로 끝낸다.

### R013-SK-B06 — [BLOCKING] P2·P4·P6 partial/crash/retry 상태가 terminal이 아니다

- 정확한 근거: R013:180-202는 두 materialization event와 시작 event를 만들지만 create/commit 사이
  crash, partial file, post-write checker failure의 branch가 없다. R013:213-220은 결과 세트부터 두
  canonical event까지 긴 순서만 적고 각 중간 durable 상태의 실패 처리를 정의하지 않는다. 반면 새
  successor를 명시한 terminal은 R013:129의 plan rejection뿐이다.
- 구체적 반례/영향: seq40 file/checkpoint를 쓴 뒤 seq41 전에 죽거나, GOAL_STARTED를 live switch한 뒤
  session checker가 실패하거나, completion receipt 뒤 canonical update 전에 죽는다. 기존 bytes를
  보존하면 다음 행동이 정의되지 않고, 같은 revision으로 repair/replay/retry하면 previously failed 또는
  partial authority가 다시 살아난다. 어느 선택도 fail-closed terminal이 아니다.
- 최소 교정: 각 P2/P4/P6 live 전환에 기존 v2.4 `PREPARE_VALIDATE_THEN_ATOMIC_CHECKPOINT_SWITCH`를
  필수로 직접 결속하고 exact post-state 확인 전 다음 단계 권한을 0으로 둔다. partial·collision·checker
  failure·crash 관찰은 bytes 보존, repair/replay 0의 absorbing incident로 보내며, 재개가 필요하면 실제
  observed state를 고정한 새 successor와 review를 요구한다.

## 확인된 폐쇄와 결론

R012 두 review가 모두 terminal non-PASS이고 R012 root가 absent여야 한다는 방향, R013 review의
missing/malformed/duplicate/non-independent/nonzero finding 전부를 rejection으로 보내는 규칙,
bootstrap root 영구 repair 금지, 외부·정식·실기기·5 Gate·release 비승격은 명시적으로 닫혀 있다.

그러나 위 여섯 항목은 review된 bytes와 실제 write authority 사이의 필수 선행조건을 빠뜨린다. 특히
부트스트랩 종료를 검증하기 전에 P1로 갈 수 있고, drift와 future leaf를 현장에서 재해석할 수 있으며,
dirty delta·live commit·crash 상태가 terminal로 수렴하지 않는다. 따라서 현 revision은 product/test,
Goal 시작 또는 canonical transition으로 진행할 수 없고, 대상과 이 review를 불변 입력으로 삼은 다음
successor 한 파일만 허용해야 한다.

## 자체 검증 범위

이 review는 지정 경로의 단일 Add File로만 작성했다. 작성 후 대상 정체성, 출력의 regular/mode/uid/gid/
nlink/SHA-256/bytes/lines, 12개 metadata field의 단일 출현, 판정 수와 finding heading 수를 read-only로
재검산한다. 이 reviewer가 요청한 그 밖의 filesystem write는 0이다.
