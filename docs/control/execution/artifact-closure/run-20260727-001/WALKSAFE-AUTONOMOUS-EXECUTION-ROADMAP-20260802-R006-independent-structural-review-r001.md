# WalkSafe R006 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R006-INDEPENDENT-STRUCTURAL-REVIEW-R001
review_type = INTERNAL_STRUCTURAL_REVIEW
reviewer_agent = /root/r006_structural
reviewer_session = /root/r006_structural@20260802-r001
independence_attestation = TRUE
target_sha256 = bc505ad98dc6e6cf62b55b7e0ef8d9d976a0875c09287613e3a1b25f75de2e06
target_bytes = 10274
target_lines = 204
verdict = REVISION_REQUIRED
blocking = 1
major = 0
minor = 0
```

## 범위와 독립성

동결된 R006과 §0이 고정한 R005 본문 및 두 R005 review만 읽었다. 다른 R006 review는
읽거나 기다리지 않았다. 선언된 위협 모델과 현재 WP001 bootstrap draft authoring
범위에서 상속·대체의 결정성, R005 skeptical B-01의 crash-cycle 종결, exact path와
evidence/source-manifest/review 전이를 검수했다. 대상과 세 normative input의
SHA-256·byte·line identity는 모두 선언값과 일치했고, R006의 세 one-shot root는 검수
시점에 모두 absent였다.

## 확인된 종결

- R006 우선 규칙과 각 replacement는 live authority를 R006 두 review의 zero finding과
  분리하고, E1의 root suffix, evidence 네 파일, plan/source review 이름을 R006으로
  전환한다.
- `e1-observation.json`은 앞선 호출만 기록한다. 그 생성 호출의 사후 관찰 뒤에 별도
  create-only `E1.COMPLETE`를 쓰며 marker는 자기 호출 결과나 자기 hash를 주장하지
  않는다. exact marker 뒤 parent-observation 전 crash도 seal, draft-post, observation과
  marker bytes를 read-only로 재계산하고 추가 write 없이 판정하므로 R005 skeptical
  B-01의 자기주장 순환은 닫혔다.
- source input은 R006과 두 R006 review를 추가하면서 R005와 두 R005 review를 history로
  유지하고 미래 output은 배제한다. plan review nonzero 시 E1/E2를 금지하고 R007 하나만
  허용하는 전이도 명시돼 있다.

## Finding

### B-01 — E2 exact write-set의 source-review 절 참조가 존재하지 않는다

- 근거: R006 §3의 전면 교체 epoch 표는 `E2_SOURCE_REVIEW_LOG`의 허용 파일을 “§8 source
  review 두 파일”이라고 고정한다(65행). 그러나 §8은 finding closure와 next action이며
  source-review 파일을 열거하지 않는다(193~204행). 실제 두 exact filename은 §6에만
  있다(171~174행). 이 표를 effective R005 §1.2에 삽입해 읽어도 R005 §8은 plan-review
  gate이므로 source-review 두 파일을 식별하지 못한다.
- 재현: E2 writer가 §3의 exact write-set만 따라 §8을 해석하면 허용 source-review
  basename을 얻을 수 없다. 반대로 §6의 두 파일을 쓰려면 §3의 명시 참조를 임의로
  `§6`으로 교정해야 하므로, “작성자가 새 값을 선택할 수 없다”는 §0 규칙과 충돌한다.
- 영향: source static-review/log epoch의 exact path allowlist가 단일하게 해석되지 않는다.
  §7은 어느 severity든 nonzero이면 E1을 0으로 두도록 하므로 현재 draft authoring gate도
  열 수 없다.
- 최소 교정: add-only R007에서 §3의 E2 참조를 source-review exact filenames가 있는
  `§6`으로 고정하고, 다른 write-set은 변경하지 않은 채 다시 독립 검수한다.

## 결론

R005 skeptical B-01은 문서 수준에서 닫혔지만, 위 exact-path 상호참조 결함 때문에
R006의 E1을 시작할 수 없다. R006 draft/evidence path는 absent로 유지하고 R007
successor만 작성해야 한다.
