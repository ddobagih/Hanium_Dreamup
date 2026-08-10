# WalkSafe R028 독립 structural review r001

```text
review_id: WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R028-INDEPENDENT-STRUCTURAL-REVIEW-R001
reviewer_agent: /root/r028_plan_structural_review
reviewer_axis: TRANSCRIPT_INVERSE_DETERMINISM_DIRTY_EXCLUSION_OPERATION_KIND_TEST_PROVENANCE_CHRONOLOGY_AUTHORITY
target_path: docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R028.md
target_sha256: 0a83406951f4963405035649a52675e781f706cbd11bac6d0060540a8567f6db
target_bytes: 20142
target_lines: 300
reviewed_at: 2026-08-02T15:07:45.766913+09:00
status: NEW_REVISION_REQUIRED
findings: BLOCKING=3 MAJOR=0 MINOR=0
authority_granted: NONE_FOR_ACTIVATION_GOAL_PRODUCT
```

## 범위와 독립 재계산

R028 300줄 전체와 physical path를 정적으로 읽었다. 검수 시작 target은 SHA-256
`0a83406951f4963405035649a52675e781f706cbd11bac6d0060540a8567f6db`, 20,142 bytes,
300 lines인 regular file, mode `0664`, uid/gid `1000/1000`, nlink 1이었다.

R027 roadmap/P1 trio와 rejected source-review 둘, live S1 세 파일, wrapper 둘, C0 및 daylog
preimage는 R028 §1의 SHA/bytes와 모두 일치했다. R017~R027 physical roadmap trio 33건과
failed-r001 exact-six/name digest를 재해시했으며 R027 source structural B-02의 actual 표와
일치했다. `/usr/bin/python3.14`은 7,481,192 bytes 및 문서의 SHA와 일치한다. S1 test를 import하지
않고 AST로 읽은 결과 test method는 unique 37개이고 §5 allowlist 여덟 이름은 모두 존재하며 이전
parent-fsync 이름은 absent였다.

Transcript는 문서의 SHA/bytes와 일치하고 call/output record가 각각 정확히 하나였다. Terminal-LF
포함 raw line은 각각 `2b558124...9df / 21,438`, `3a5f1391...a40 / 423`; decoded patch는
`d9c90719...fe1 / 18,972 bytes / 395 splitlines / 394 LF`, exact three Update File sections와
48 hunks였다. 이 patch를 source/test/builder 실행·import·compile 없이 line stream으로만 분석했다.
Source/test/builder runtime, build, unittest와 wrapper 실행은 0이다.

## BLOCKING findings

### B-01 — 명시된 ordered inverse가 test section의 locally ambiguous hunk에서 terminal이다

§4 lines 126~130은 각 section/hunk를 순서대로 처리하고, 현재 monotone cursor 뒤 postimage의
`유일 위치`만 허용하며 ambiguous match는 terminal이라고 명시한다. 이를 그대로 적용하면 test
section의 1-based hunk 2에서 cursor 417 뒤 postimage
`        for binding_name in (`가 두 위치 1195와 1217에 존재한다. 앞 위치를 임의로 선택해
계속해도 1-based hunk 7에서 cursor 1346 뒤 3-line postimage가 위치 1411과 1471에 두 번
존재한다. 따라서 문서 자신의 규칙으로는 S0 pin 비교나 recovery root 생성 전에 반드시 중단된다.

첫 match를 임의 선택하면 우연히 세 S0 SHA/bytes가 나오지만, 이는 `ambiguous ... terminal`을
위반하므로 승인 가능한 실행 해석이 아니다. Apply-patch 내부의 skip/context 선택을 추측해서
대체할 수도 없다.

최소 교정: 새 revision에서 transcript patch parser의 exact chunk/skip semantics를 정의하고,
각 ambiguous anchor의 선택 규칙을 명시적으로 결속하거나 전체 ordered placement path가 하나뿐임을
검증하도록 계약을 바꾼다. 그 결과가 세 S0 raw bytes와 exact 일치해야 하며 ambiguous-local
terminal 문구와 모순되지 않아야 한다.

### B-02 — S0→S1 forward round-trip의 placement 규칙도 결정되지 않았다

§4는 inverse 뒤 `같은 hunk를 정방향으로 메모리 적용`한다고만 쓰고 forward cursor, recorded
span 변환, skip/context 또는 ambiguity 처리 규칙을 정의하지 않는다. Recovered S0에서 앞선
core hunks를 순서대로 적용한 상태의 1-based core hunk 9는 cursor 269 뒤 one-line preimage `}`가
위치 339와 372에 존재한다. 단순 unique-match는 실패하고 first-match는 original S1 SHA로
round-trip하지 않는다. Inverse 시 기록한 raw position도 서로 다른 중간-state의 line shift 때문에
그대로 재사용할 수 없다.

최소 교정: 새 revision은 forward application의 exact state machine과 span adjustment/anchor
selection을 규정하고, 모든 48 hunk placement가 결정적임을 먼저 증명한 뒤 raw S1 equality를
검사해야 한다. 또는 inverse 결과를 독립 raw S0 pin으로 봉인하는 별도 결정적 reconstruction
procedure를 정의하고 불명확한 forward claim을 제거한다.

### B-03 — dirty baseline exclusion이 staging을 동시에 제외하고 포함한다

§4 line 99는 source와 `P4/P5/P6 targets`를 제외한다고 한다. §2 write allowlist에서 P5 target은
transient `.v2-5-control-candidate-r002.staging-<pid>-<16-lowerhex>/`와 final r002 root 두 건이다.
따라서 문언대로면 staging은 baseline에서 제외된다. 그러나 lines 102~103은 staging을 제외하지
않아 잔존 staging이 equality를 실패시킨다고 정반대로 규정한다. 종료 equality의 canonical exclude
set이 하나로 정해지지 않아 unrelated-dirty 성공 조건이 재현 불가능하다.

최소 교정: 새 revision에서 exact repository-relative exclusion path/prefix를 열거한다. P5에서는
final r002 root prefix만 제외하고 staging glob/prefix는 명시적으로 포함하거나, staging을 별도
absence gate로만 검사한다. 어느 선택이든 baseline과 종료에 동일한 단일 알고리즘을 사용한다.

## 나머지 구조 축

위 findings를 상쇄하거나 다음 epoch 권한을 주지는 않지만, write allowlist는 plan/P1, external
exact-ten backup, one S1→S2 three-file patch, P4/P6 add-only reviews, owned staging→final 및 exact-prefix
daylog append로 구분되어 있다. P4 dual static review가 runtime보다 앞서고, prebuild→single
publication→ordered post gates→dual candidate review→dirty/daylog/memory handoff 순서와 no-retry,
zero-authority 경계도 명시되어 있다. §5의 provenance/failed-r001/call-boundary/namespace/negative
matrix 요구와 §6 review oracle은 R027 findings의 필요한 검수 축을 열거한다.

그러나 B-01 하나만으로도 R028 §3에 따라 P2 이후 write와 source/build/test 실행은 0이어야 한다.
R028은 이 상태로 PASS할 수 없고 새 roadmap revision이 필요하다. 이 review는 publication,
activation, canonical/checkpoint, Goal, product 또는 배포 권한을 부여하지 않는다.

## 종료 identity

검수 종료 직전 target은 시작과 동일한 SHA-256
`0a83406951f4963405035649a52675e781f706cbd11bac6d0060540a8567f6db`, 20,142 bytes,
300 lines였다. 이 review 파일 외 source, test, builder, wrapper, candidate, backup 및 daylog write는
0이다.
