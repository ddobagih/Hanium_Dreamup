# WalkSafe R005 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R005-INDEPENDENT-SKEPTICAL-REVIEW-R001
review_type = INDEPENDENT_SKEPTICAL
reviewer_agent = /root/r005_skeptical
reviewer_session = /root/r005_skeptical@20260802-r001
independence_attestation = TRUE
target_sha256 = 75b7b2742a898ddae05f44ee2fb6378ce5c00f8982c7c4a9e74b0e7809d692c0
target_bytes = 21066
target_lines = 362
verdict = REVISION_REQUIRED
blocking = 1
major = 0
minor = 0
```

## 범위와 판정

다른 R005 review를 읽거나 기다리지 않고 frozen target을 독립 검수했다. 실수 path,
preexisting object, 선의의 drift, 부분 실패, crash와 잘못된 resume만 공격 근거로
사용했다. 같은 UID의 악의적 동시 프로세스, kernel, bwrap, frozen executable 또는
trusted-tool compromise는 finding 근거에서 제외했다.

R004의 writer confinement, fd-bound mount identity, source-use-post CAS, tar 중간
component traversal, attached branch/raw status, negative postcondition, epoch별 allowlist
교정은 문서 수준에서 닫혔다. 그러나 아래 terminal receipt의 crash 경계가 닫히지 않아
E1 draft authoring을 시작할 수 없다. 미래 publication/projection 실행은 이 review의
범위가 아니며 별도 successor plan과 독립 review 전에는 계속 금지된다.

## Finding

### B-01 — terminal `e1-result.json`이 자기 invocation의 사후 사실을 선행 주장한다

- 근거: §2는 `e1-result.json`을 마지막 terminal marker로 정의한다. §3은 evidence 세
  파일을 각각 별도 confined `apply_patch` invocation으로 만들고 매 invocation 뒤 tool
  identity를 검사한다. §7은 terminal 파일에 apply-patch tool pre/post identity를 넣고,
  E1 PASS에서 **모든** invocation의 `rc0`과 empty stderr를 요구한다.
- 재현: controller가 terminal 파일용 patch를 계산해 마지막 child에 전달한다. child가
  완전한 파일을 생성한 직후, parent가 child 종료 상태·stderr와 post-use tool identity를
  관찰하기 전에 controller crash를 주입한다. 그러면 exact 3-file set과
  `DRAFT_SOURCE_PREPARED_NOT_EXECUTED` marker는 남지만 그 파일이 주장하는 마지막
  invocation의 rc/stderr/post 관찰은 실행되지 않았다. 재시작 후에는 파일 hash와
  metadata만으로 정상 완료 실행과 이 crash 실행을 구별할 수 없다. 악의적 경쟁이나
  tool compromise가 필요 없는 명시적 in-scope crash다.
- 영향: terminal marker 및 성공 receipt가 완료의 crash-consistent 증거가 아니다.
  특히 잘못된 resume가 exact set과 marker만 보고 E1 완료로 승격할 수 있고, 반대로
  §7의 비영속 사후 조건을 엄격히 적용하면 정상 결과도 crash 후 재검증할 수 없다.
- 최소 교정: 마지막 apply-patch 호출이 관찰하기 전인 사실만 기록하게 하고, 그 호출의
  성공 및 post-use identity를 관찰한 뒤 별도의 create-only finalize 단계/marker로
  commit한다. finalize marker는 자기 생성 호출의 미관찰 rc·stderr를 내용으로
  주장하지 않아야 하며, crash-before/after-finalize fixture가 각각 terminal 부재/존재와
  독립 재계산 가능한 acceptance를 증명해야 한다. 변경된 evidence exact set과 epoch
  allowlist도 successor에서 함께 고정해야 한다.

## 결론

R005는 add-only successor에서 위 crash/receipt 순환을 제거하고 다시 두 독립 review를
받아야 한다. 그 전에는 draft/evidence root write를 0으로 유지한다.
