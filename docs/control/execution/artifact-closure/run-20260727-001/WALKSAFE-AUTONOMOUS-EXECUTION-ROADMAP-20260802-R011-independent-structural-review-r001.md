# WalkSafe R011 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R011-INDEPENDENT-STRUCTURAL-REVIEW-R001
review_type = INTERNAL_STRUCTURAL_REVIEW
reviewer_agent = /root/r009_sandbox_fix_design/r011_structural_review
reviewer_session = /root/r009_sandbox_fix_design/r011_structural_review@20260802-r001
independence_attestation = TRUE; 다른 R011 reviewer의 파일·결과를 읽거나 요청하거나 전달받지 않았고 그 reviewer와 통신하지 않았다.
target_sha256 = 5ad6a09870a30bc91c1c77ff29a731c526809ee86dd23f64d8b41b522b42d6f6
target_bytes = 17611
target_lines = 353
verdict = REVISION_REQUIRED
blocking = 3
major = 1
minor = 0
```

## 범위와 identity 확인

동결 R011과 그 normative base인 R010, 그리고 지정된 두 R010 review를 대조했다. exact
file/evidence와 mode·marker 계약은 R011이 명시적으로 유지한 R009 및 R005/R006의 해당 절까지만
역추적했다. 대상과 R010 세 frozen input의 SHA-256·bytes·lines는 R011:17-21의 선언과 모두
일치했다. 두 R010 review는 서로 다른 agent/session이 같은 R010 identity를 독립 검수한 terminal
file이며, 양쪽의 의미상 같은 다섯 finding은 E1/source liveness, daylog self-hash, virgin/alternate
attempt root, independent exact oracle/delta, literal draft9/evidence4의 정확한 합집합이다.

후보 source를 실행·import·byte-compile·pycompile하지 않았고 candidate root를 수정하지 않았다.
network도 사용하지 않았다.

## Findings

### B-01 — invalid review identity 또는 누락 file의 failure 전이가 자기 barrier 때문에 도달 불가능하다

정확한 근거: R011:38-42의 `B0_PLAN_REVIEW_BARRIER`는 exact 두 file이 같은 실제 R011
SHA/bytes/lines에 결속되고 distinct agent/session이며 complete union까지 재구성돼야 true다.
그런데 R011:44-46의 `F0_PLAN_REJECTED`는 다시 `B0`와 identity/independence failure를 동시에
요구한다. target identity가 틀리거나 expected file이 없으면 `B0`가 false이므로 그 failure
branch에 들어갈 수 없다. R011:67-75도 source barrier가 same physical identity와 distinct
reviewer를 먼저 요구한 뒤 identity/independence failure를 `F2` 조건으로 삼아 같은 모순을
반복한다. 이는 R011:82-84의 terminal/liveness 주장과도 맞지 않는다.

영향: reviewer task가 terminal이어도 file 누락·malformed metadata·target mismatch가 있으면 E1도
R012도 허용되지 않는 add-only dead end가 된다. late finding을 기다리는 안전성은 얻지만 실패
상태의 결정적 liveness를 잃는다.

최소 successor 교정: plan/source 각각에 “두 task terminal + expected 두 path의 read-only
inventory 동결”만 요구하는 completion barrier를 먼저 둔다. 그 뒤 exact file 존재, metadata,
target identity, independence, union reconstruction이 모두 valid이고 0/0/0인 branch만 success로,
누락·malformed·mismatch·nonzero 중 하나라도 있는 branch는 failure successor로 보낸다. failure
branch는 읽을 수 있는 late finding 전부와 누락/invalid 사실을 기록하되 valid barrier를
선행조건으로 요구하지 않아야 한다.

### B-02 — case authority와 fixture oracle이 서로의 SHA를 포함해 add-only hash cycle을 만든다

정확한 근거: R011:152-177은 execution 전에 immutable `case-authority.json`을 freeze하고
R011:167에서 그 content에 `fixture_oracle_manifest_sha256`을 넣는다. R011:252-275는 역시 execution
전에 immutable `negative-fixture-oracle-manifest.json`을 freeze하면서 R011:261에서 그 content에
`authority_sha256`을 넣는다. 어느 쪽에도 back-reference 제외 digest나 placeholder 규칙이 없다.

영향: 첫 file의 최종 SHA를 계산하려면 두 번째 file의 최종 SHA가 필요하고 그 반대도 같으므로,
일반적인 SHA-256 생성 절차로 두 canonical add-only file을 구성할 수 없다. 따라서 external case
authority와 independent oracle이 모두 존재해야 하는 future dynamic input은 생성 불가능하고,
R010의 exact-oracle finding은 닫히지 않는다.

최소 successor 교정: oracle에서 authority SHA back-reference를 제거하고 대신
`run_id/global_attempt_id/case_id/source_seal_sha256` 같은 비순환 key를 결속한다. oracle을 먼저
freeze한 뒤 authority가 그 oracle SHA를 단방향으로 결속하고, claim이 authority SHA를 결속하는
literal 생성 순서를 고정한다. 또는 동등하게 명시적인 excluded-field digest 규칙을 정의하되
두 full-file SHA가 서로를 참조하게 두지 않는다.

### B-03 — pre-execution oracle이 아직 생성되지 않은 output의 inode·시간 metadata까지 exact 값으로 요구한다

정확한 근거: R011:246-247은 publication crash의 future present file에서 독립 재구성 가능한
bytes/SHA/mode/uid/gid/nlink만 열거한다. 그러나 R011:252-270은 execution 전에 freeze되는
oracle에 complete `expected_first`/`expected_second` snapshots를 넣고, R011:272는 모든 nested
root/file snapshot이 R010 §4.4의 exact keys를 쓰게 한다. frozen base R010:211-215의 file keys에는
`dev,ino,mtime_ns,ctime_ns`까지 포함된다. initially empty output에 candidate가 뒤에 만드는 file의
inode와 timestamps는 oracle freeze 시점에 알 수 없다.

영향: wildcard/count-only를 R011:274-275에서 금지했으므로 manifest author는 알 수 없는 값을
날조하거나, 실행 뒤 actual 값을 expected로 복사하거나, 항상 mismatch를 내야 한다. 첫 방식과
마지막 방식은 실행 불가능하고 두 번째는 독립 oracle을 다시 자기충족적으로 만든다.

최소 successor 교정: pre-frozen oracle에는 path/type/mode/uid/gid/nlink/size/SHA와 exact absent→present
전이처럼 실행 전에 결정 가능한 logical rows만 둔다. runtime observation은 full physical
dev/ino/time keys를 별도로 기록하고, 새 regular inode·prebound root 아래 위치·첫 관찰 뒤 second
관찰 equality 같은 관계 predicate로 검증한다. preexisting fixture/root의 이미 알려진 physical
identity만 pre-frozen exact 값으로 유지한다.

### M-01 — 분리했다고 한 daylog automation이 exhaustive write set과 충돌한다

정확한 근거: R011:122-130의 epoch 표에는 daylog epoch가 없고, 열거되지 않은 repository
Update/write를 모두 0으로 만든다. R011:304는 R010의 daylog 절을 폐기하지만 R011:308-311은
terminal 뒤 repository global instruction에 따른 daylog block 기록을 별도 수행한다고 한다.
그 별도 epoch의 exact path/method는 없고 R011:130의 금지에 대한 예외도 없다.

영향: session-end automation을 수행하면 exact write boundary를 위반하고, 수행하지 않으면 §6의
명시적 후속 동작을 이행할 수 없다. daylog가 bootstrap authority/acceptance에서 제외됐다는 좋은
분리는 유지되지만 filesystem 권한 계약은 결정적이지 않다.

최소 successor 교정: bootstrap terminal 이후에만 열리는 non-authorizing automation epoch를
표에 추가하고 exact daylog path, 단일 append 방법, preimage 보존 조건과 R011:130의 좁은 예외를
직접 열거한다. 이 write가 acceptance·successor·execution authority의 입력이 아님을 유지한다.

## 확인된 closure와 경계

- R011:135-143은 두 R010 review의 의미상 다섯 finding을 누락 없이 한 번씩 결속한다.
- R011:94-129은 draft 9와 evidence 4의 literal basename, 7 payload → manifest/seal → post →
  prior-only observation → non-self-claiming marker 순서, epoch별 Add File 범위를 직접 고정한다.
  R005/R006에서 상속된 confined writer·`umask 077`·four-line marker 규칙과도 모순이 없다.
- R011:174-217의 externally bound roots, initially-absent `attempt`, parent-fsynced `mkdirat`,
  committed pair 전 child 0, existing empty/partial/conflict의 fail-closed 처리는 R010의 기본
  virgin/recovery 구분을 교정한다. 위 B-02가 그 authority/oracle을 실제 freeze하지 못하게 하는
  별도 차단 결함이다.
- R011:77-84, 292-300, 342-350은 static source acceptance와 dynamic `NOT_RUN`, candidate
  unexecuted, future publication/projection plan-review gate를 분리한다. static PASS 자체를 source
  execution 권한으로 승격하는 전이는 찾지 못했다.

## 결론

R011은 full five-finding ledger, literal 9/4 write set, durable attempt의 기본 상태, daylog
self-hash 제거와 static/dynamic 경계를 유의미하게 교정했다. 그러나 invalid-review failure
branch가 unreachable이고, authority/oracle mutual hash 및 미래 physical metadata 요구 때문에
independent oracle을 add-only로 생성할 수 없다. 따라서 R011 candidate/evidence roots는 absent로
유지하고 두 R011 review가 terminal인 뒤 이 finding union을 결속한 R012만 작성해야 한다.
