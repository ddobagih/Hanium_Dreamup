# WalkSafe R015 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R015-INDEPENDENT-STRUCTURAL-REVIEW-R001
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R015.md
target_sha256 = f611e870e6c85b0d649aee0911fa938d5c27c37ab54420cf57af924d42e920ad
target_bytes = 21477
target_lines = 450
reviewer_axis = STRUCTURAL_AUTHORITY_ORDER_ATOMICITY_EXECUTABILITY_REVIEW_BINDING_DELEGATION_FP008_GATE
status = REVISION_REQUIRED
reviewed_at = 2026-08-02T10:04:57+09:00
findings = BLOCKING=3 MAJOR=0 MINOR=0
authority_granted = NONE
```

## 범위와 identity

R015 전체 450줄을 reviewed R002 pair/review/runbook, PASS된 successor design R001과
그 review, 현 v2.5 builder/core/wrappers/tests, v2.4 full19 계약 및 R014의 두 failed
review와 대조했다. 시작과 종료 모두 대상은 regular `0664`, uid/gid `1000/1000`,
nlink 1이며 위 SHA-256/bytes/lines와 일치했다. R015·candidate·canonical·Goal·제품
파일은 실행하거나 수정하지 않았다.

R014의 producer-less 예외, stale FP048 projection, review 결속, mode 분리, resolved
member seal, transition lock/CAS, bootstrap 재검산 및 checkpoint-authoritative flat-pair
경계는 R015에서 구조적으로 보강됐다. 아래 세 결함은 그 폐쇄와 별개다.

## Findings

### R015-STR-B01 — [BLOCKING] 최신 일반 위임이 reviewed runbook의 두 candidate-specific 승인 경계를 명시적으로 대체하지 않는다

- 근거: R015:21-48은 실제 지시 중 두 문장만 인용하고 사후
  `normalized_execution_scope`를 그 subset으로 선언한다. 그러나 reviewed R002 runbook
  54-75와 successor design R001 165-171, 231-287은 core review 뒤 exact candidate
  묶음에 대한 승인 1과 적용 뒤 FP008에 대한 별도 승인 2를 요구한다. R015의 frozen
  input 표와 authorization binding 목록(R015:81-96, 269-284)은 SHA
  `71e322de...e5b8bd7`인 runbook 자체나 그 §4/§5 supersession을 결속하지 않는다.
- 판정: 최신 사용자 지시는 저장소 문서보다 상위이고 prospective 판단을 위임할 수
  있으므로 사용자가 exact candidate hash를 직접 본 응답만 원천적으로 허용되는 것은
  아니다. 하지만 현재 R015 bytes만으로는 더 최신 전체 지시와 두 기존 확인 경계의
  명시적 대체가 증명되지 않아 v2.5/r022 적용 및 FP008 시작 권한이 성립하지 않는다.
- 최소 교정: successor가 관련 전체 실제 clause와 SHA-256/bytes를 결속하고, 그 최신
  지시가 runbook §4/§5의 추가 확인 요구를 명시적으로 supersede한다고 기록한다. 이후
  권한은 dual core review와 resolved review가 고정한 exact candidate/transaction,
  zero-credit 및 내부 실행에만 좁히고 외부 identity attestation이 아님을 유지한다.

### R015-STR-B02 — [BLOCKING] v2.5 활성 뒤 FP008 event와 full19를 검증할 실행 경로가 없다

- 근거: R015:295-315의 resolved closure와 현 core는 seq1-3/C1 exact target만 만든다.
  현 core 3523-3529, 3660-3672와 4720-4788은 active history/checkpoint를 그 exact
  projection으로 재검증하므로 R015:398-414의 `GOAL_MATERIALIZED`, `GOAL_READY`,
  `GOAL_STARTED` append 뒤에는 active check가 실패한다. 또한 R015:412가 호출하는
  current full19는 `docs/control/README.md` 14-35에서 v2.4 continuation/Goal checker와
  v2.4 test를 exact 명령으로 고정한다. R015의 source 보강·12-member closure에는
  v2.5 post-seq3 event validator나 v2.5 full19/repository-state 계약이 없다.
- 영향: `ACTIVE_VERIFIED` 뒤 FP008을 materialize하면 v2.5 active invariant를 깨고,
  그대로 두면 v2.4 전용 full19가 v2.5 checkpoint에서 PASS할 수 없다. 따라서
  `FULL19_PASS`와 `GOAL_STARTED_VALID`은 문서대로 도달 불가능하다.
- 최소 교정: FP008 전용 successor plan에 seq3 exact prefix를 보존하면서 후속 Goal
  event를 replay하는 v2.5 writer/checker/test와 checkpoint-last CAS를 먼저 고정한다.
  full19의 첫 두 checker, Goal-control tests와 19번째 repository-state 명령을 v2.5
  경로로 version/hash-bound해 dual review하고, 그 전에는 FP008 Goal/product write를 0으로 둔다.

### R015-STR-B03 — [BLOCKING] 유지할 v2.5 core가 seq2와 seq3에 같은 시각을 봉인한다

- 근거: R015:173-175는 기존 seq1→2→3 계약을 유지하지만 보강·negative 목록에는
  시간 단조성 교정이 없다. 현 core 3438-3467은 seq2와 seq3 `occurred_at`을 모두
  `quick_gate.checked_at`으로 만들고, 3681-3695는 `seq2_at <= seq3_at`으로 equality를
  허용한다. 이는 runbook 165의 "event 순서대로 엄격히 증가" 계약과 충돌한다.
- 영향: candidate/resolved/active 검사가 모두 PASS해도 transition history가 상위
  event 시간 계약을 위반한다.
- 최소 교정: predecessor `<` seq2 `<` seq3의 timezone-aware exact projection을
  정의하고 equality/reversal을 거부하는 fail-first와 active regression을 추가한다.

## 결론

finding이 하나라도 있으므로 R015는 `REVISION_REQUIRED`다. R015 기반 candidate
source/final/canonical/Goal/product write 권한은 0이며
`R015_BOUNDED_INTERNAL_EXECUTION_ONLY`를 부여하지 않는다.
