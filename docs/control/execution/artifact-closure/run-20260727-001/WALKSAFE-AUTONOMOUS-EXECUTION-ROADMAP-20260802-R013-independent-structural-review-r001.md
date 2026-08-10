# WalkSafe R013 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R013-INDEPENDENT-STRUCTURAL-REVIEW-R001
type = INTERNAL_STRUCTURAL_REVIEW
reviewer_agent = /root/r009_sandbox_fix_design/r013_structural_review
reviewer_session = /root/r009_sandbox_fix_design/r013_structural_review@20260802-r001
independence_attestation = TRUE; 지정된 다른 R013 reviewer의 파일·결과·메시지·존재 여부를 읽거나 검색하거나 전달받지 않았고 그 reviewer와 통신하지 않았다.
target_sha256 = 40e68ae0e808af3eb2038b672fb0545a8d35dd25a4d910f866b75dcd1c8f958a
target_bytes = 13769
target_lines = 248
verdict = REVISION_REQUIRED
blocking = 7
major = 0
minor = 0
```

## 범위와 방법

frozen R013 전부와 허용된 frozen R012 roadmap·두 review, 현재 checkpoint, r021 Gap/Backlog,
v2.4 정책·Gap Work Item template 및 현재 FP-047 leaf만 정적으로 읽었다. 시작 및 작성 직전 R013
identity는 regular 0664, uid/gid 1000/1000, nlink 1, 13,769 bytes, 248 lines와 위 SHA-256에
일치했다. candidate·제품 실행, import, bytecompile/pycompile/compile, tests/full gate/build,
network, 임시·cache 생성, predecessor·제품·canonical 수정, daylog/local-memory 기록은 모두 0이다.

## 닫힌 입력·종료 audit matrix

| 검수 축 | 정적 결과 |
|---|---|
| R012 세 frozen 입력 | 실제 SHA-256·bytes·lines가 R013:30-32와 모두 일치한다. 두 review는 서로 다른 agent/session이고 각각 `REVISION_REQUIRED 4B/4M/0m`, `REVISION_REQUIRED 6B/5M/0m`이다. |
| R012 finding union | `U01=STR-B01+SK-M01`, `U02=STR-B02+SK-M02`, `U03=STR-B03+SK-B03`, `U04=STR-B04+SK-M05`, `U05=STR-M01+SK-B01+SK-B02`, `U06=SK-B04`, `U07=SK-B05+SK-B06`, `U08=STR-M02+STR-M04`, `U09=STR-M03+SK-M03+SK-M04`로 raw 19개가 누락·중복 없이 R013:37-45의 최고 심각도 9개 군집에 결속된다. R012 `F0_PLAN_REJECTED` 결론은 맞다. |
| bootstrap roots | R008 draft/evidence는 각각 exact 9/4이고 root 0700, 모든 file regular 0600, uid/gid 1000/1000, nlink 1이다. R008 final과 R009-R012의 draft/final/evidence 12개 root는 absent다. R013:58-61의 네 anchor SHA-256도 실제 file과 일치한다. |
| 종료·공로 경계 | R013:9-15,24,55-70은 R008 보존, R009-R012 absent, bootstrap 실행·repair·resume·publish 0과 bootstrap-origin official/product/canonical/formal/device/Gate/release delta 0을 결속한다. 이 경계 자체에서는 resurrection 권한을 만들지 않는다. |
| 현재 repository·checkpoint | branch, HEAD, dirty 상태와 checkpoint SHA-256·bytes·lines·schema, `ACTIVE/ACTIVE`, seq39 tail hash, focus/ready frontier, r021 두 binding, 126/257·131, formal/device/Gate/release/project 경계가 R013:83-103과 일치한다. 과거 snapshot 재사용 금지와 dirty-tree reset/clean/delete 금지도 명시돼 있다. |
| 외부·공식 경계 | R013:185,209-211,218-228은 materialization/READY와 내부 mock·회귀를 실제 KMS/HSM, production TLS, 실기기, 독립 전문검토, formal/Gate/release 증거로 대체하지 않는다. |

위 종료 결론은 유지돼야 한다. 다만 아래 결함 때문에 종료 뒤 v2.4 쓰기 단계로 안전하게 전이할 수 없다.

## Findings

### R013-STR-B01 — [BLOCKING] R013 review availability가 success/reject 전이에 결속되지 않는다

- 정확한 근거: R013:120-124의 `각 reviewer task의 terminal 여부 ... capture`와
  R013:126-131의 상태 블록. file 검증 항목과 `PIVOT_PLAN_OK`에는 두 task가 terminal인지, child가
  0인지, availability snapshot 뒤에 판정했는지가 선행조건으로 들어가지 않는다. 선언 state 이름도
  R013:8의 `S0_PENDING_TWO_R013_REVIEWS`와 R013:127의 `S0_REVIEWING`으로 갈린다.
- 구체적 반례/영향: reviewer가 유효해 보이는 PASS file을 만든 뒤 아직 실행 중이거나 unexpected
  child를 남긴다. 현재 식은 두 file만 보고 피벗할 수 있고, 반대로 task가 끝나지 않으면 wait/deadline/
  cancel 중 어느 상태인지 결정할 수 없다. review barrier가 닫히기 전에 P1 이후 authority가 열리거나
  영구 nonterminal이 된다.
- 최소 교정: exact 두 assigned task terminal, child 0, expected-path inventory 단일 capture를 별도
  availability predicate로 두고 이를 두 판정의 필수 입력으로 넣는다. 그 전에는 S0 read-only, deadline/
  cancel/unexpected child는 write 0의 named rejection terminal로 보낸다.

### R013-STR-B02 — [BLOCKING] FP-048 next-leaf 선택이 frozen r021 안에서 유일하지 않고 drift가 재선택을 허가한다

- 정확한 근거: R013:97-100의 `current next ... FP-048 / GAP-057`, R013:152-154의
  `새 실제 상태에서 selection을 다시 계산`, R013:156-178의 FP-048 materialization. frozen r021
  Backlog는 P0 실제 순서가 `next_action_sequence`를 따른다고 하면서 FP-008/FP-046/
  NPC-SINGLE-ADMIN-RECOVERY를 order 20/21/22의 open 항목으로, FP-048을 order 23으로 둔다. 같은 file의
  explicit next pointer는 FP-048이고, Gap의 FP-048 next 설명은 다시 `advances to order 20`이라고 한다.
- 구체적 반례/영향: sequence/ordered-policy 규칙을 적용한 selector는 FP-008/GAP-017을 고르고 explicit
  pointer를 적용한 selector는 FP-048/GAP-057을 고른다. 둘 다 같은 r021 bytes를 근거로 삼을 수 있다.
  P1 drift 때는 검수되지 않은 새 leaf로 재선택할 수도 있고, P2를 문자대로 따르면 stale FP-048을 쓸
  수도 있어 canonical 단일 authority가 없다.
- 최소 교정: 이 불일치나 checkpoint/r021 drift는 P1의 write-0 terminal로 고정한다. 별도 좁은
  canonical correction과 그 독립 review가 sequence, explicit pointer, Gap 설명을 하나로 만든 뒤에만
  새 frozen identity로 exact leaf를 다시 선택한다.

### R013-STR-B03 — [BLOCKING] P0-P6가 success/error/retry를 모두 덮는 순차 상태기계가 아니다

- 정확한 근거: R013:127-130은 `PIVOT_PLAN_OK -> ... P1 only`라 하여 P0 result를 전이에 넣지 않는다.
  R013:143-185의 P1 nonzero와 P2 mismatch, R013:204-220의 post-fix regression failure·독립 구현 review
  non-PASS에는 named terminal과 이후 허용 write가 없다. R013:232-238의 Boolean은 성공조건일 뿐 전이
  precedence나 실패 여집합을 정의하지 않는다.
- 구체적 반례/영향: P1 checker가 rc1을 내지만 §2 값은 겉보기로 같거나, P5 post-fix suite가 계속
  실패하거나, P6 review가 non-PASS다. 어느 경우도 stop/retry/new-plan 중 하나로 결정되지 않아 같은
  authority로 임의 repair·resume을 하거나 부분 Goal 상태에 멈출 수 있다.
- 최소 교정: `review PASS -> P0 -> P1 -> P2 -> P3 -> P4 -> P5 -> P6`의 exact success output을 다음
  단계의 유일한 입력으로 두고, command error·drift·crash·validation/review failure의 여집합을 각각
  write set이 닫힌 absorbing terminal로 보낸다. retry/resume은 새 reviewed authority 없이는 0으로 둔다.

### R013-STR-B04 — [BLOCKING] P3 실패가 검수되지 않은 gate repair와 재시도를 연다

- 정확한 근거: R013:189-194의 exact phrase `원인을 분류하고 gate branch만 교정한다`. literal gate
  event ID, absent/O_EXCL raw-output root, receipt 경로와 실패 뒤 허용 write set도 고정하지 않는다.
- 구체적 반례/영향: 19개 중 checker/test가 실패한 뒤 해당 checker나 test를 "gate branch" 교정으로
  약화하고 같은 또는 새로 임의 선택한 event에서 재실행하면 19/19를 만들 수 있다. product file을
  쓰지 않았다는 조건만으로 start gate 우회나 dirty snapshot 계보 분기를 검출할 수 없다.
- 최소 교정: P3 attempt의 exact event/root/receipt 규칙과 add-only write set을 고정하고 어느 nonzero,
  timeout, malformed output 또는 snapshot mismatch도 보존-only terminal로 보낸다. 교정은 별도 exact
  scope·새 event·독립 review 뒤에만 가능하게 한다.

### R013-STR-B05 — [BLOCKING] GOAL_STARTED가 staged 검증보다 먼저 live canonical 상태를 바꾼다

- 정확한 근거: R013:198-202는 event로 leaf를 `READY -> IN_PROGRESS`로 바꾼 뒤 plain Quick2와 session
  checker를 검증으로 둔다. P2의 R013:182-184와 달리 P4에는 staged checkpoint 선검증과 atomic switch
  조건이 없다. frozen checkpoint의 전이 정책은 `PREPARE_VALIDATE_THEN_ATOMIC_CHECKPOINT_SWITCH`다.
- 구체적 반례/영향: live event/checkpoint를 쓴 뒤 session checker가 rc1이면 product write는 0이어도
  canonical leaf는 invalid `IN_PROGRESS`로 남는다. rollback 금지 아래 이를 되돌리거나 동일 start를
  재생할 권한도 없어 상태가 손상된다.
- 최소 교정: gate receipt와 직전 snapshot에 결속한 event·checkpoint를 별도 staged path에서 만들고 두
  Quick 검사와 session 검사를 staged 입력으로 먼저 PASS시킨 뒤 한 번만 atomic switch한다. 어느 실패도
  live write 0의 terminal로 둔다.

### R013-STR-B06 — [BLOCKING] 검수 전 FP-048 leaf bytes와 제품/test write scope가 고정되지 않았다

- 정확한 근거: R013:158의 basename-only `policy-gap-work-item.md template`, R013:161-178의 부분
  metadata, R013:182의 아직 생성되지 않은 `실제 Goal bytes`, R013:204-211의 `한 leaf 범위의 최소 구현`.
  repository에는 여러 version의 같은 template basename이 있고, 제시 block은 current v2.4 template의
  required frontmatter와 본문·materialization event/receipt/checkpoint 경로 전체를 고정하지 않는다.
  P5도 허용 product/test/config path나 각 fail-first assertion·기대 failure를 열거하지 않는다.
- 구체적 반례/영향: 두 author가 서로 다른 template/body를 채우고, 한쪽은 Android 저장소만, 다른 쪽은
  Gateway·DB·backup·감사 전역을 수정해도 둘 다 FP-048/GAP-057과 generic pre-fix FAIL/post-fix PASS를
  주장할 수 있다. 사전 review는 future bytes와 dirty-tree overwrite 범위를 판정할 수 없고 schema
  checker만으로 의미상 scope expansion을 막을 수 없다.
- 최소 교정: exact v2.4 template path/SHA, 완전한 leaf bytes 또는 모든 header/body 필드, P2의 literal
  add-only paths, P5의 좁은 file allowlist와 각 internal fail-first case·기대 failure signature·targeted
  command를 R013에 freeze한다. allowlist 밖 필요가 발견되면 write 0의 새-roadmap terminal로 보낸다.

### R013-STR-B07 — [BLOCKING] P7이 검수된 FP-048 뒤의 미지정 leaf 실행까지 포괄한다

- 정확한 근거: R013:222-225의 `다음 internal leaf를 진행한다`와 R013:240-241의 현재 단일 next action.
  P7에는 다음 Goal ID/path/policy/Gap/dependency/write set이나 새 plan-review barrier가 없다.
- 구체적 반례/영향: FP-048 완료 뒤 재계산이 FP-008, EPIC-12 또는 artifact work를 고르면 이 R013을
  근거로 materialize/start/implementation까지 계속할 수 있다. 이는 검수된 한 leaf 경계를 넘어
  canonical·제품 write authority를 무기한 확장한다.
- 최소 교정: P7은 read-only frontier 재계산과 exact next-candidate 보고까지만 허용하고 모든 다음 leaf
  write는 0으로 둔다. 선택된 다음 leaf의 identity·scope·gates를 결속한 새 독립 review barrier 뒤에만
  후속 실행을 연다.

## 결론

R012 bootstrap 계보의 rejected/not-executed 종료와 zero-credit·보존 경계는 확인됐다. 그러나 current
leaf selection 충돌, 비총체적 stage chain, gate repair, post-write start validation, unfrozen FP-048
write scope와 P7 확장 때문에 R013은 `PIVOT_PLAN_OK` 또는 P1 이후 authority를 만들 수 없다. R008
roots는 immutable, R009-R012 roots는 absent, checkpoint·Goal·제품/test write는 0으로 유지해야 한다.
