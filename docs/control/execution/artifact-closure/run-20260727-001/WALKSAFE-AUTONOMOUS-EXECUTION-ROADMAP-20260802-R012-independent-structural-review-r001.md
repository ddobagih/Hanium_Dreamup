# WalkSafe R012 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R012-INDEPENDENT-STRUCTURAL-REVIEW-R001
type = INTERNAL_STRUCTURAL_REVIEW
reviewer_agent = /root/r009_sandbox_fix_design/r012_structural_review
reviewer_session = /root/r009_sandbox_fix_design/r012_structural_review@20260802-r001
independence_attestation = TRUE; 지정된 다른 R012 reviewer의 파일·결과·메시지를 읽거나 검색하거나 전달받지 않았고 그 reviewer와 통신하지 않았다.
target_sha256 = a7bd3d1732e4b99f6476702aceb89b7bce35603e9c8441059fee5e6a43d7e8a0
target_bytes = 24456
target_lines = 446
verdict = REVISION_REQUIRED
blocking = 4
major = 4
minor = 0
```

## 범위와 방법

지정된 frozen R012 전부와, 추적에 허용된 frozen R011 roadmap 및 그 두 predecessor review만
읽었다. 네 문서의 선언 identity는 실제 SHA-256·bytes·lines와 일치했다. 다른 R012 review의
존재 여부도 조사하지 않았다. candidate 및 candidate Python의 실행·import·byte-compilation·
pycompile, network, daylog/local-memory, 임시 파일·캐시, 검수 대상·predecessor 수정은 모두 0이다.

R011 두 review의 의미상 중복을 합쳐 8개 union finding으로 다시 구성하고, 각 항목을 R012의
실제 전이·byte 계약·write authority까지 추적했다. 표에 적힌 closure 선언만으로 닫힘을
인정하지 않았다.

## R011 union closure matrix

| 정규화된 union finding | 판정 | R012 근거 또는 남은 결함 |
|---|---|---|
| invalid/missing review의 barrier failure unreachable | PARTIAL / OPEN | R012:37-49,80-82는 file validation 여집합을 F0/F2로 보내지만, valid PASS 뒤 user authority 철회는 어느 전이에도 들지 않는다(B-01). |
| authority/oracle mutual full-file SHA cycle | CLOSED | R012:174-180,219-249가 registry → selector → oracle → authority → claim의 단방향 순서를 만들고 oracle에서 authority file SHA를 제거했다. |
| future output inode/time을 pre-freeze해야 함 | PARTIAL / OPEN | R012:274-295가 logical row와 runtime physical observation을 분리한 방향은 맞지만, required attempt/output mutation과 root equality가 양립하지 않고 두 root 표현의 equality도 정의되지 않는다(B-03). |
| alternate authority/root를 판정할 global anchor 부재 | OPEN | run selector가 생겼지만 selector 자체를 이전 authority가 선택하지 않아 coherent alternate selector chain이 여전히 가능하다(B-02). |
| raw stream oracle이 source-dependent | CLOSED | R012:302-373이 CJSON, first/second/verifier의 RC와 exact stream bytes를 candidate보다 먼저 규범화했다. 다만 그 case-spec digest preimage는 별도 결함(M-01)이다. |
| byte mutation preimage/carrier 불명확 | CLOSED | R012:375-389가 네 source/carrier, role, pre/post bytes·size·SHA와 expected rejection을 고정한다. 네 제시 SHA와 size도 독립 재계산과 일치했다. |
| daylog append와 exhaustive write set 충돌 | PARTIAL / OPEN | exact daylog epoch/path/preimage는 생겼지만 같은 automation이 요구하는 memory DB backup/write는 열거되지 않았다(M-03). |
| source delta 의미가 draft write와 충돌 | CLOSED | R012:136-150이 command/execution counts, protected unlisted delta와 authorized draft/review 예외를 분리했다. |

따라서 union 8개 중 4개는 닫혔고, 3개는 부분 교정 뒤에도 open이며 1개는 open이다.

## Findings

### R012-STR-B01 — [BLOCKING] valid zero-finding review 뒤 authority 철회가 nonterminal dead end다

- 정확한 근거: R012:51-54의 S1은 두 review가 VALID/PASS인 것 외에 현재 user 지시가
  취소·대체되지 않았음을 요구한다. R012:56-58의 F0는 `not(S1의 review-valid-and-zero 조건)`만
  받으므로 review가 valid 0/0/0이면 false다. 같은 조건은 R012:429-430의 PLAN_OK에도 다시
  들어간다.
- 구체적 반례/영향: A0와 V0가 모두 성공하고 두 review가 PASS 0/0/0인 직후, 첫 E1 write 전에
  user가 지시를 취소하거나 대체한다. S1은 false이고 F0도 false이며 F1/F2의 선행 상태에도
  도달하지 않는다. R012 root absent를 유지하는 terminal STOP도 R013 authority도 정의되지 않아
  상태기계가 total하지 않다.
- 최소 교정: review-valid-zero와 current authority를 별도 Boolean으로 두고, 전자가 true지만
  후자가 false인 경우 모든 R012/R013 write를 0으로 유지하는 absorbing
  `AUTHORITY_WITHDRAWN_OR_REPLACED` terminal을 추가한다. A0 뒤의 나머지 모든 조합이 정확히 한
  success/reject/stop branch로 가는 truth table을 함께 고정한다.

### R012-STR-B02 — [BLOCKING] selector 자체를 선택하는 선행 anchor가 없어 alternate chain을 검출할 수 없다

- 정확한 근거: R012:170-180은 selector를 DAG의 첫 runtime file로 두고, R012:208-215는 그
  content에 downstream authority path/root를 넣는다. 그러나 selector 자신의 literal path,
  preexisting parent identity, O_EXCL basename 또는 expected selector SHA를 selector보다 먼저
  frozen된 input이 선택하는 계약은 없다. R012:251-254의 controller 검사는 제공된 selector/oracle/
  authority 세 FD 사이의 내부 일치만 검사한다.
- 구체적 반례/영향: 동일한 `dynamic_plan_sha256`, run, source seal과 tuple T에 대해 S1은 root A와
  authority A를, S2는 root B와 authority B를 선택하게 한다. 각 selector에서 T는 한 번뿐이고 각자
  coherent oracle/authority를 만들 수 있다. controller에 S2 세트만 제공하면 S1이나 T→S1의 외부
  mapping을 관찰할 입력이 없어 모든 byte-equality를 통과하고 root B에서 새 attempt를 commit한다.
  R011의 alternate-root finding이 selector 한 단계 위로 이동했을 뿐 닫히지 않는다.
- 최소 교정: future dynamic plan이 selector의 exact parent full identity, literal basename/path,
  run identity와 최초 absent/O_EXCL 규칙을 미리 고정하게 한다. controller에는 pinned plan/selector
  parent/selector FD를 함께 주고 그 선행 mapping을 검증하게 하되, plan에 future selector full-file
  SHA를 넣어 새 cycle을 만들지는 않는다.

### R012-STR-B03 — [BLOCKING] physical-row equality가 required durable writes와 구조적으로 양립하지 않는다

- 정확한 근거: R012:195-197의 authority preimage root는 8개 identity 필드만 갖지만,
  R012:211-215의 selector root는 known full physical row다. 그런데 R012:251-253은 두 표현을
  byte-equal하게 검증하라고 하며 projection 규칙이 없다. 더 직접적으로 R012:258-267은 모든
  producer 전에 attempt parent 아래 directory와 두 file을 durable create하고, R012:274-292는
  preexisting root의 before→first→second full physical equality를 §6.4의 단일 mutation 외에는
  요구한다. R012:356은 attempt pair를 output delta에서도 제외한다.
- 구체적 반례/영향: `mkdirat("attempt")`만으로도 attempt parent directory의 nlink·size·mtime·
  ctime 중 일부가 바뀐다. publication prefix file 생성은 output root의 size·mtime·ctime을 바꾼다.
  따라서 올바른 first run조차 root equality를 실패한다. 반대로 이 변화를 무시하면 oracle에 없는
  외부 delta가 허용된다. 또한 8-field object와 extra physical fields가 있는 object는 그 자체로
  byte-equal할 수 없다.
- 최소 교정: immutable `root_identity` projection과 runtime `physical_observation` schema를
  명시적으로 분리하고 equality는 전자의 공통 exact fields에만 적용한다. attempt control delta는
  output delta와 별도 열거하며, attempt parent와 각 publication output root에 대해 허용되는
  entry-set/nlink·size·time 관계 변화를 case별로 고정한다. future time exact 값을 예측하지 않는
  현재 원칙은 유지한다.

### R012-STR-B04 — [BLOCKING] exact30 집합이 selector entry 및 attempt 생명주기에 일대일로 결속되지 않는다

- 정확한 근거: R012:208-214는 `(global_attempt_id,case_id)` tuple이 selector 안 한 번만 나온다고
  할 뿐 entries cardinality, case-id set equality 또는 case-id 단독 uniqueness를 요구하지 않는다.
  R012:388-389는 12+10+4+4의 30 unique/disjoint ID를 선언하지만 selector와의 등가식을 두지 않는다.
  R012:356-373은 second rc81/attempt consumption을 producer 22에만 정의하고, recovery 4와 mutation
  4에는 first/second attempt state와 allowed second delta를 완결하지 않는다.
- 구체적 반례/영향: selector가 한 case를 누락한 29 entries여도, 또는 같은 case-id를 서로 다른
  global attempt 두 개로 넣은 31 entries여도 각 tuple은 한 번뿐이라 현재 schema를 만족한다.
  후자는 동일 case를 두 root에서 실행해 one-shot을 우회한다. 또한 recovery case를 반복 가능한
  read-only verifier로 보는 구현과 committed attempt를 소비하는 구현 모두 표의 단일 RC/stream
  행을 만족할 수 있어 oracle/attempt acceptance가 결정적이지 않다.
- 최소 교정: selector에 entries length 30, case-id set이 registry exact set과 같음, 각 case-id
  정확히 한 번, category cardinality 22/4/4를 강제한다. 각 category마다 controller/child/verifier
  role, first/second RC·stdout·stderr, attempt before/committed/consumed state와 first/second delta를
  직접 매핑한다. recovery/mutation에 durable attempt가 적용되지 않는다면 그 제외와 반복 정책을
  명시한다.

### R012-STR-M01 — [MAJOR] `normative_case_spec_sha256`의 canonical preimage가 정의되지 않았다

- 정확한 근거: 이 digest는 R012:184-203의 authority preimage, R012:208-212의 selector와
  R012:219-236의 oracle을 연결하는 필수 값이다. 그러나 R012:297-302는 Markdown 절을 normative
  registry라 부르고 result용 CJSON만 정의할 뿐, per-case spec object의 exact keys·serialization·
  extraction 또는 literal digest table을 정의하지 않는다.
- 구체적 반례/영향: author A는 case table 한 행의 Markdown bytes를, author B는 case-id와
  expected-result JSON을 CJSON으로 hash할 수 있다. 각자가 같은 digest를 selector/oracle/authority에
  반복하면 cross-file 검사는 모두 통과하지만 서로 다른 normative behavior에 결속된다. future
  manifest generator와 static reviewer가 유일한 expected digest를 재구성할 수 없다.
- 최소 교정: 30 case 각각의 exact normative-spec JSON schema와 CJSON bytes를 정의하고 그 digest를
  literal table로 고정하거나, 동등한 결정적 generator와 입력 field order/set을 고정한다. 이
  preimage에는 selector/oracle/authority의 future hash를 넣지 않는다.

### R012-STR-M02 — [MAJOR] inherited source-input manifest가 R012 authority로 명시적으로 rebase되지 않았다

- 정확한 근거: R012:28-29는 replacement 외 R011을 normative base로 유지하고 R012:94-105는
  `source-input-manifest.json` basename만 다시 열거한다. R012:165-166도 inherited contracts를
  유지하지만 그 manifest의 current-input 내용을 교체하지 않는다. 허용된 frozen R011:316-319의
  literal 계약은 current input을 R011과 두 R011 plan review로 두고 future R011 artifacts를
  제외한다. R012:409-425에는 새 review path가 있지만 source-input current set의 replacement 문장은
  없다.
- 구체적 반례/영향: literal inheritance를 따르면 R012 E1 payload가 rejected predecessor R011과
  그 non-PASS reviews를 current authority로 기록한다. implicit rebasing을 택하면 R012와 두 R012
  reviews를 기록한다. 두 byte-distinct manifests가 모두 문서 해석상 가능해 independent
  reconstruction과 E1_PHYSICAL_OK가 결정적이지 않다.
- 최소 교정: R011의 해당 paragraph를 명시적으로 교체해 current input을 frozen R012와 exact 두
  valid plan review identities로 정하고, R011 및 그 review union은 correction-history로만 둔다.
  future seal/evidence/source-review/dynamic artifacts의 제외도 R012 이름으로 다시 적는다.

### R012-STR-M03 — [MAJOR] daylog epoch 밖의 memory DB backup/write가 exact external delta로 열거되지 않았다

- 정확한 근거: R012:121-134의 유일한 automation epoch는 terminal 뒤 daylog append 예외 하나이고,
  R012:148-150도 authorized daylog write만 protected-unlisted delta에서 제외한다. 그런데
  R012:399-404는 실제 postimage identity를 local-memory log-work에 기록하고 그 DB write 전에
  backup과 writer 상태 확인을 요구한다. DB와 backup의 literal path, write count/method, pre/post
  predicate 또는 별도 epoch가 없다.
- 구체적 반례/영향: §7을 수행하면 daylog Update 외에 최소 DB write와 보통 backup create라는
  외부 delta가 생기지만 exhaustive 표로 분류할 수 없다. 이를 생략하면 §7의 기록 명령을 어긴다.
  bootstrap acceptance에서 분리됐다는 사실은 권한 없는 external write를 결정적으로 만들지 않는다.
- 최소 교정: memory automation을 계속 요구한다면 bootstrap과 비권한 관계는 유지하면서 별도
  terminal epoch에 exact DB/backup 대상, snapshot/lock 조건, add/update count, 실패 시 no-retry
  경계를 열거한다. 이 실행에서 범위를 고정할 수 없다면 memory write/backup을 명시적으로 0으로
  두고 daylog append 결과를 session 외부에서만 보고한다.

### R012-STR-M04 — [MAJOR] V2의 singular source-review target identity가 결정되지 않았다

- 정확한 근거: R012:42-49의 V0는 실제 R012 SHA/bytes/lines와의 일치를 구체적으로 요구한다.
  R012:71-72의 V2는 V0와 같은 validation이라고 한 뒤 plan/seal/post/observation/marker 결속만
  추가한다. R012:421-425는 source review에 공통 target identity triad와 다섯 artifact digest를
  요구하지만, 그 triad가 R012 plan, draft manifest, seal file 또는 다른 단일 file 중 무엇인지
  정의하지 않는다.
- 구체적 반례/영향: 한 reviewer가 triad를 R012에, 다른 reviewer가 draft manifest에 결속해도 다섯
  추가 digest는 같을 수 있다. V2를 V0의 literal 복사로 읽으면 전자는 valid이고, analogous source
  validation으로 읽으면 후자가 valid일 수 있어 F2/S3 분기가 validator 구현에 따라 달라진다.
- 최소 교정: 두 source review의 singular target을 exact path와 SHA/bytes/lines 의미로 지정하고 V2의
  target check를 그 대상으로 직접 작성한다. plan identity와 다섯 artifact identity는 별도 required
  fields로 유지하며 V0의 plan-specific 문구를 그대로 재사용하지 않는다.

## 확인된 구조와 byte 검산

- mutual full-file hash는 oracle에 final authority SHA를 넣지 않는 순서로 제거되어 직접 cycle이 없다.
- future file의 inode와 timestamps를 expected literal로 복사하지 않는 logical/physical 분리 원칙은
  확인했다. B-03은 그 원칙이 아니라 row equality와 allowed delta의 내부 모순이다.
- base 12, crash 10, recovery 4, mutation 4의 표상 ID는 실제로 30개이며 서로 중복되지 않는다.
  producer family 산술도 22이고 나머지는 4/4다. B-04는 이 집합과 runtime entries의 결속 결함이다.
- pass-FD 14 bytes, sentinel 15 bytes, failure receipt 144→162 bytes와 네 pre/post SHA-256은 표의
  값과 모두 일치했다.
- static source review와 dynamic NOT_RUN의 경계, candidate execution/import/compile 0 및 review가
  live authority를 만들지 않는 원칙은 유지된다. 위 findings 때문에 그 static acceptance predicate가
  아직 유일하고 실행 가능한 판정으로 수렴하지 않는다.

## 결론

R012는 R011 union 중 mutual hash, literal streams, exact mutation과 source-delta 구분을 실질적으로
교정했다. 그러나 상태기계 terminal totality, selector의 외부 uniqueness, required writes와 physical
oracle의 양립성, exact30-to-attempt binding에 차단 결함이 남는다. case-spec digest, current source
authority, memory external delta와 source-review target도 한 가지 방식으로 실행할 수 없다. 따라서
R012 candidate/evidence roots는 absent로 유지하고, 두 plan-review task가 terminal인 뒤 이 finding
union을 결속한 R013 한 file만 허용해야 한다.
