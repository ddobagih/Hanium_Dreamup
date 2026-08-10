# WalkSafe R007 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R007-INDEPENDENT-STRUCTURAL-REVIEW-R001
review_type = INTERNAL_STRUCTURAL_REVIEW
reviewer_agent = /root/r007_structural
reviewer_session = /root/r007_structural@20260802-r001
independence_attestation = TRUE
target_sha256 = c7a00043d3dc450fc9b1c3e46ca32ce57183d51ebd434bbe6cf7d92fa1a21950
target_bytes = 6419
target_lines = 129
verdict = REVISION_REQUIRED
blocking = 0
major = 0
minor = 1
```

## 범위와 독립성

동결된 R007, 그 frozen base인 R006 roadmap과 두 R006 review, 그리고 R006이 상속한
R005 roadmap과 두 R005 review만 읽었다. 다른 R007 review는 읽거나 기다리거나
요청하지 않았다. effective body의 상속·교체 우선순위, epoch별 path, evidence와 source
input, plan/source review의 identity 전이, crash recovery와 no-resume를 독립 검수했다.
대상과 frozen chain의 선언된 파일 identity는 직접 재계산한 값과 일치했고, R007의 세
one-shot root는 모두 absent였다.

## 확인된 구조

- R007 우선 규칙과 R006/R005 상속 범위가 명시되어 있고, authority gate와 plan review
  gate는 R007 identity로 전환된다. 이전 roadmap과 review는 history로 남을 뿐 실행
  권한을 만들지 않는다.
- E0 plan author/review, E1 draft/evidence, E2 source review/log, failure successor는
  서로 다른 exact write set이다. E1은 R007 suffix의 draft 9개와 evidence 4개만 허용하고
  final candidate는 absent로 유지한다.
- E2 행은 R006의 잘못된 절 참조를 제거하고 두 R007 source-review basename을 직접
  열거한다. §5의 동일한 두 basename과 exact equal이므로 실제 write allowlist는 한 가지다.
- source input은 R007과 두 현재 plan review를 current input으로, R006/R005 chain을
  rejected/history input으로 전이하며 미래 evidence, source review와 output을 배제한다.
- prior-only observation과 별도 create-only marker, marker dependency의 read-only 재계산,
  absent/partial/wrong marker의 incomplete 판정, 모든 실패 상태의 no-repair/no-resume가
  상속된다. exact marker 직후 crash도 추가 write 없이 결정적으로 판정된다.

## Finding

### m-01 — defect-closure 서술이 실제 E2 교체 방식과 일치하지 않는다

- 재현: §4의 closure 행은 E2 exact write set이 source-review filename을 열거한 이 문서
  §5를 “참조한다”고 말한다. 그러나 §2의 실제 E2 행에는 두 basename이 직접 쓰여 있고
  `§5` 참조는 없다. 행을 문자 그대로 비교하면 closure가 주장한 교정 방식과 적용된
  교정 방식이 다르다.
- 영향: §2와 §5의 basename은 서로 같아 실행 allowlist, source-review identity 또는
  crash 판정은 모호해지지 않는다. 다만 R006 공통 finding의 closure record가 실제 delta로
  재현되지 않아 후속 감사가 “직접 열거”와 “절 참조” 중 어떤 구조를 의도한 것인지 다시
  추론해야 한다.
- 최소 교정: add-only successor의 closure 행을 “E2 exact write set에 두 source-review
  exact filename을 직접 열거하고 §5와 exact equal로 고정했다”로 바꾸거나, 실제 E2 행을
  §5 참조 방식으로 일치시킨다. 전자가 현재 결정적 allowlist를 그대로 보존하는 최소
  문구 교정이다.

## 결론

R006의 잘못된 §8 참조는 실행 규칙에서 실질적으로 닫혔고 path/evidence/source/review
전이와 crash/no-resume에도 새 구조 결함은 발견되지 않았다. 그러나 closure 표의 재현
불일치가 남아 있으므로 R007 E1/E2를 시작하지 않고 add-only successor에서 위 한 문구를
교정한 뒤 다시 독립 검수해야 한다.
