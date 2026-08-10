# WalkSafe Goal Graph v2.4

패키지 ID: `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4`

계획 버전: `2.4.0`

상태: `PREPARED_NOT_ACTIVATED / READY_NOT_ACTIVATED`

## 목적

활성 v2.3은 seq17에서 FP-011을 READY로 만들었다. 그러나 FP-011 시작 전 제어
회귀를 현재 상태에 맞게 고치면 이미 완료된 FP-005·FP-006·FP-010의 변경 산출물
provenance가 깨진다. v2.3 checker/test와 17-event checkpoint를 수정하지 않고,
그 활성 tail을 byte-exact archive와 projection event로 가져오는 정식 successor가
필요하다.

## imported Goal 소유권

20개 Goal 문서는 v2.2/v2.3의 원래 경로와 bytes를 그대로 사용한다. 복사하거나
완료 문서를 다시 쓰지 않는다. 현재 상태와 완료·materialization lineage는
checkpoint의 `imported_predecessor_goal_bindings`가 결속한다.

v2.4 native 지원 파일은 README, manifest, active supersession record, v2.3 active
checkpoint archive와 template 두 개뿐이다. manifest는 자기 hash 순환을 피하려고
protected file 집합에서 제외되며 checkpoint가 manifest hash를 직접 고정한다.

## 현재 focus

- focus: `WS-GOAL-EPIC-02-FP-011-R001`
- Work Item: `EPIC-02-FP011-LONG-LIVED-LOGIN`
- ready frontier: FP-011, EPIC-03, EPIC-12
- 정식 시험·실기기·5개 Gate·출시: 기존 미종결 상태 유지

## 활성화 경계

이 builder는 `PACKAGE_PREPARED` seq1만 만든다. 최종 manifest SHA-256과 initial
event SHA-256에 결속한 새 사용자 승인이 있어야 quick gate 뒤 seq2
`PACKAGE_ACTIVATED`를 기록할 수 있다. 그 뒤에도 새 19-check full gate와 별도
seq3 `GOAL_STARTED`가 성공하기 전에는 FP-011 제품 코드를 변경하지 않는다.
