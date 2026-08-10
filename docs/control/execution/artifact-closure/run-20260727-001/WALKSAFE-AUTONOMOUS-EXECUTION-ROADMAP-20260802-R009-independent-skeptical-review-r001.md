# WalkSafe R009 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R009-INDEPENDENT-SKEPTICAL-REVIEW-R001
review_type = INDEPENDENT_SKEPTICAL
reviewer_agent = /root/r009_skeptical_review
reviewer_session = /root/r009_skeptical_review@20260802-r001
independence_attestation = TRUE; 다른 R009 reviewer의 산출물을 열거나 요청하거나 기다리거나 전달받지 않았고 그 reviewer와 통신하지 않았다.
target_sha256 = bce7b9dbac301e09f1083b4bc92d892d282b421bfaad0b75ec8d8cd0a6c9ce13
target_bytes = 12404
target_lines = 209
verdict = REVISION_REQUIRED
blocking = 3
major = 2
minor = 0
```

## 범위와 방법

동결 R009, R008 roadmap과 두 plan PASS review, 그리고 서로 독립적으로 작성된 R008
boundary/recovery source review를 읽었다. 필요한 R005~R008 상속 계약을 역추적하고 선언된
identity를 read-only로 재계산했다. R009의 세 root는 모두 absent였고, R008 draft/evidence는
각각 exact 9/4 이름을 유지하며 final은 absent였다.

rejected R008 candidate는 finding과 FD/negative-oracle 문맥 확인에 필요한 부분만 일반 텍스트로
읽었다. candidate를 실행, import, byte-compile 또는 pycompile하지 않았고 candidate/evidence를
수정하지 않았다. network도 사용하지 않았다.

## Findings

### B-01 — frozen executable scope와 E1 권한 전이가 서로 모순된다

R009:9는 유일한 현재 실행 범위를 `R009_PLAN_REVIEW_ONLY`로 고정한다. 그러나 R009:39-42는
live goal과 두 plan review의 무결점 판정 뒤 source 작성 권한이 생긴다고 하고, R009:63과
R009:173-190은 곧바로 `E1_CORRECTED_DRAFT_AUTHOR`를 acceptance 경로에 둔다. 동결 문서를
바꾸지 않은 채 plan review가 끝나도 line 9의 범위는 그대로다. 이를 상태 snapshot으로 보면
범위 전이 규칙이 없고, 불변 allowlist로 보면 E1은 금지된다. 두 static review가 스스로 새
권한을 만든다고 해석해야만 E1에 진입할 수 있어 inherited live-authority 분리와 충돌한다.

Successor closure: frozen metadata에 `PLAN_OK` 뒤 허용되는 exact authoring epoch를 조건부로
명시하고, 두 review는 필요조건일 뿐 E1 직전의 contemporaneous live authority가 별도로
참이어야 한다고 전이식과 scope 양쪽에 같은 말로 고정한다. source 실행은 계속 금지한다.

### B-02 — C10은 원래 지적된 `before-first` one-shot 공격을 닫지 않고 명시적으로 버린다

R008 recovery B-06은 `before-first` crash에서 같은 fault의 재발만으로 no-resume를 거짓
통과시키는 경로를 특정했다. R009:139는 바로 그 case를 registry에서 제외한다. 반면
R009:140-141은 child spawn 전에 output 밖 create-only attempt claim을 소비하므로, 이
claim이 정상적으로 동결됐다면 output이 전혀 없는 `before-first`도 구별할 수 있어야 한다.

더구나 C10 registry 필드에는 attempt-claim path/root, case 및 producer identity binding,
exact bytes/schema/mode/owner/nlink, `O_EXCL`, file/parent fsync, preexisting/partial claim 판정,
두 호출 사이의 read-only identity가 없다. claim 생성 도중 crash나 caller가 새 claim을
선택하는 경우를 `ONE_SHOT_CONSUMED`와 구별할 규칙도 없다. 따라서 recovery B-06은 mapping
표에 이름만 들어갔을 뿐 successor-only closure가 아니다.

Successor closure: `publish-crash-before-first`를 exact registry에 넣고, claim을 case와
reviewed producer/seal에 결속된 별도 create-only durable artifact로 완전히 고정한다. 첫
호출은 claim commit 뒤에만 child에 진입하고, fault를 제거한 둘째 호출은 동일 claim의
read-only 검증 뒤 producer 진입 전에 exact one-shot failure를 내야 한다.

### B-03 — 첫 finding이 R010을 선점하면 늦게 끝난 독립 review를 수용할 add-only 경로가 없다

R009:65와 R009:192-193은 어느 finding이든 생기면 exact `R010.md` 하나를 허용하지만, 두
plan review 또는 두 source review가 모두 terminal identity에 도달한 뒤 successor를
작성해야 한다는 barrier가 없다. 첫 reviewer의 finding으로 R010을 freeze하면 독립적으로
늦게 끝난 둘째 reviewer의 새 finding을 R010에 추가할 수 없다. R009/R010 repair는 금지되고
이 문서에는 그 상태에서 R011로 진행할 권한도 없다. 두 writer가 같은 R010을 선점하는
경우도 결정되지 않는다. 이는 finding 누락과 add-only liveness dead end를 동시에 만든다.

Successor closure: 해당 단계의 두 독립 review가 모두 terminal/frozen임을 root가 확인하고,
모든 nonzero finding ID를 중복 없이 결속한 complete ledger를 만든 뒤 단일 designated
successor author만 R010을 작성하도록 한다. 이 barrier 전에는 R010 write를 0으로 둔다.

### M-01 — C10의 “exact 25”는 정답 oracle과 M-04 mutation 검증을 동결하지 않는다

C10은 first/second result와 output delta를 registry에 넣으라고만 하고 각 case의 exact
failure class, receipt count/status, 허용 delta와 root snapshot key를 plan 또는 독립 anchor로
열거하지 않는다. `arbitrary expected`를 금지한다는 문구는 무엇이 올바른 expected인지를
선택하지 못하므로 producer와 verifier가 같은 잘못된 값을 공유해도 형식상 1:1 결속이다.

또한 R008 boundary M-04가 요구한 pass-FD mutation, second-run sentinel mutation, output-root
chmod, malformed failure receipt rejection fixture는 R009:109-137의 폐쇄된 case 목록에 없다.
일반 실행에서 불변을 재확인하는 것만으로는 그 검사기가 실제 mutation을 거부하는지 동적으로
증명하지 못한다.

Successor closure: per-case frozen table에 exact first/second JSON/RC, zero-success-receipt 및
`INCOMPLETE_UNTRUSTED`, exact allowed delta와 full root/sentinel/pass-FD/preexisting snapshot을
직접 적고, 위 네 mutation fixture를 registry에 추가하거나 각 기존 case에 적용되는 별도
의무 matrix로 동결한다.

### M-02 — C13의 producer/verifier 범위는 한 해석에서 불가능하고 다른 해석에서는 불완전하다

C13은 `producer/verifier` 공통 env를 말한 뒤 subject를 바꾸지 않고 read-only sandbox와
`.git` 포함 repository pre/post identity equality를 함께 요구한다. 이를 둘 모두에 적용하면
projection producer가 clone, patch, tar restore와 upstream 제거로 absent/empty output을
완성해야 하는 R005:254-270 계약과 양립할 수 없다. verifier에만 적용하려는 뜻이라면 그
scope가 predicate에 없으므로 source author와 두 static reviewer가 서로 다른 계약을 구현할
수 있다.

Successor closure: 공통 Git env/argv confinement과 역할별 조건을 분리한다. producer에는
허용된 create-only projection delta와 final oracle을, verifier에는 reviewed read-only
sandbox 및 `.git`을 포함한 exact pre/post byte/metadata equality를 명시한다.

## 확인된 방어와 결론

- 두 R008 source review의 19개 원 ID는 C01~C13 표에 이름 기준으로 각각 한 번씩 등장하고,
  중복 병합 severity 산술도 맞는다. 위 finding은 그중 C10/C13의 predicate가 실제 의미를
  보존하지 못하는 문제다.
- C01의 FD 방향과 payload 진입 FD set, C02의 `libm` `0644`, C03의 non-ASCII canonical JSON,
  C04~C09 및 C11~C12의 정적 교정 방향에서는 별도 재현 가능한 모순을 찾지 못했다.
- R009:145-149의 current/history/future identity 분리와 R009:188-195의 `NOT_RUN` 및 별도
  publication/projection plan gate는 static source review를 동적 실행 권한으로 직접
  오인하는 경로를 막는다. 이 방어는 B-01의 E1 authoring scope 충돌을 해소하지 않는다.
- R008 roots의 no-repair/no-rerun과 R009 root의 initial-absence 조건은 현재 관찰과
  일치한다. 다만 B-03을 고치기 전에는 안전한 R010 correction completeness가 보장되지 않는다.

따라서 frozen R009는 source authoring gate로 사용할 수 없다. R009 root는 absent로 유지하고,
두 plan review가 모두 끝난 뒤 전체 finding을 결속한 add-only successor에서 위 항목을 먼저
닫아야 한다.
