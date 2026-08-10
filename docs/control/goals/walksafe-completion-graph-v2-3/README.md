# WalkSafe Goal Graph v2.3

패키지 ID: `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-3`

계획 버전: `2.3.0`

상태: `PREPARED_NOT_ACTIVATED / READY_NOT_ACTIVATED`

## 목적

v2.2가 활성 상태에서 FP-005 시작 전 전체 제어 회귀의 동결 테스트 두 건이
현재 runtime 상태를 고정값으로 오판하는 결함이 확인되었다. v2.2의 20개 event,
Goal 문서, 완료·materialization provenance와 checker/test bytes는 수정하지 않는다.
v2.3은 그 활성 tail을 byte-exact archive와 projection event로 가져오고 새 checker와
명령 계약에서 runtime-derived assertions를 사용한다.

## imported Goal 소유권

17개 Goal 문서는 `walksafe-completion-graph-v2-2`의 원래 경로와 bytes를 그대로
사용한다. 복사하거나 문서 버전을 바꾸지 않는다. 현재 상태와 완료·materialization
lineage는 checkpoint의 `imported_predecessor_goal_bindings`가 결속한다.

v2.3 native 지원 파일은 README, manifest, active supersession record, v2.2 active
checkpoint archive와 template 두 개뿐이다. manifest는 자기 hash 순환을 피하려고
protected file 집합에서 제외되며 checkpoint가 manifest hash를 직접 고정한다.

## 현재 focus

- focus: `WS-GOAL-EPIC-02-FP-005-R001`
- Work Item: `EPIC-02-FP005-OFFICIAL-ENVIRONMENT-CROSSWALK`
- ready frontier: FP-005, EPIC-03, EPIC-12
- 정식 시험·실기기·5개 Gate·출시: 기존 `NOT_RUN`·미종결·`NOT_ELIGIBLE` 유지

## 활성화 경계

이 준비 package는 제품 작업 권한이 아니다. 최종 manifest SHA-256에 결속한 새
사용자 승인이 있어야 `PACKAGE_ACTIVATED`를 기록할 수 있다. 활성화 뒤에도 전체
구현 시작 gate와 별도 `GOAL_STARTED`가 성공하기 전에는 FP-005 제품 코드를
변경하지 않는다.

재개 정본은 현재 checkpoint, 이 README와 manifest, archived v2.2 checkpoint,
그리고 새 v2.3 checker다.
