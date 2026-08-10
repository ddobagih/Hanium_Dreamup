+++
schema_version = "1.0"
goal_id = "WS-GOAL-PHASE-D"
goal_kind = "PHASE"
document_version = "1.1.0"
phase_id = "D"
parent_goal_id = "WS-GOAL-WALKSAFE-COMPLETION-V1"
sequence = 40
initial_status = "PLANNED"
target_completion_level = "OPERATIONS_HANDOVER_AND_PROJECT_CLOSURE"
next_goal_id = ""
return_goal_id = ""
work_item_id = ""
dependencies = ["WS-GOAL-PHASE-C"]
child_goal_ids = []
source_policy_ids = []
gap_ids = []
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# Phase D Goal — 운영 안정화·이관·프로젝트 종료

## 목표

배포된 WalkSafe를 관찰 가능한 상태로 운영하고 장애·복구·비용·권한·데이터 생명주기를 실제 책임자에게 이관한 뒤, 남은 위험과 후속 작업을 숨기지 않은 채 한이음 드림업 프로젝트를 공식 종료하거나 지속운영 체계로 넘긴다.

## 정본 입력

- Phase C 실제 배포·인도 기록
- OPS-01~24의 소유자·SLO·관측·알림·runbook·백업·복구·비용·보안 절차
- CLS-01~16의 완료·인수·회고·아카이브·이관·폐기 조건
- 최신 결함·RAID·기술부채·잔여위험·데이터 보존 상태

## 범위와 제외

포함:

- 운영 소유자·지원·에스컬레이션 확정
- 로그·메트릭·트레이스·대시보드·알림 실제 작동 확인
- 백업·복원·재해복구·권한·키 회전·취약점 대응
- 운영 사건·postmortem·변경·비용·기술부채 관리
- 최종 산출물·소스·릴리스 아카이브
- 목표·KPI·미해결 결함·잔여위험·회고·후속계획
- 운영 권한·자료·책임의 실제 이관과 프로젝트 종료 승인

제외:

- 서비스가 계속 운영된다는 이유만으로 프로젝트 종료를 무기한 미룸
- 운영하지 않은 기간·훈련·사건을 결과로 작성
- 잔여위험과 기술부채를 삭제해 완료 수치를 맞춤
- CLS-16 계획을 곧바로 서비스 폐기로 실행

## 단계별 실행

Phase D의 모든 Work Item은 `target_completion_level=HANDOVER_CLOSURE_ACTION_COMPLETE`만 사용한다. 특정 정책/Gap을 직접 해결하면 canonical `IMPLEMENTATION_GAP.assessments`의 `source_policy_id → gap_id` mapping이 지정한 pair를 사용하고, 그렇지 않은 phase-direct 행동은 두 배열을 모두 비운다. source Phase plan은 계보 입력일 뿐 완료 증거가 아니다.

1. Phase C `technical_delivery.accepted_at`과 그 receipt SHA-256을 먼저 검증한다. 이 수락 전에는 handover 실행 구간을 시작하지 않는다.
2. Phase C 수락 뒤 handover `execution_window.started_at`을 열고 운영 책임자·지원시간·연락·에스컬레이션을 실제 인수자와 확인한다.
3. handover 구간 안에서 SLI/SLO·대시보드·알림·runbook 연결, 백업·복원·재해복구·비밀정보 회전·접근권한, 계정·지원·비용 책임 등 모든 handover required check를 실제로 수행한다.
4. required check 뒤 transferor와 transferee가 각각 결정을 기록한다. 모든 check `executed_at`과 두 `decided_at`은 handover 시작 이상이고 실제 `accepted_at` 이하여야 한다.
5. 두 결정과 check가 끝난 뒤 실제 운영 이관을 수락하고 `PHASE_D_OPERATION_HANDOVER_RECEIPT.accepted_at`을 기록한다. `operational_responsibility.effective_at`은 이 수락보다 이르지 않고 같은 handover 실행 구간 안이어야 한다.
6. handover 수락 뒤에만 closure `execution_window.started_at`을 열고 안정화 결과, 미해결 결함·잔여위험·기술부채·유지보수 Backlog, 최종 산출물·아카이브·KPI·회고, 계정·키·인프라·데이터 처분을 정리한다.
7. closure 구간 안에서 모든 closure required check를 실제로 수행한 뒤 project owner와 accepting owner가 각각 결정을 기록한다. 모든 check `executed_at`과 두 `decided_at`은 closure 시작 이상이고 `closed_at` 이하여야 한다.
8. 지속운영이면 운영 소유권을 유지한 채 프로젝트만 종료한다. 서비스 종료가 별도로 승인된 경우에만 CLS-16에 따라 폐기한다.
9. closure check와 결정이 모두 끝난 뒤 `PHASE_D_PROJECT_CLOSURE_RECEIPT.closed_at`을 기록한다. closure receipt 생성은 handover receipt 생성보다 이르지 않아야 한다.
10. 각 완료 Work Item에 `WORK_ITEM_COMPLETION::<goal_id>` role의 `WORK_ITEM_EXECUTION_RECEIPT`를 정확히 하나 등록한다. Goal SHA-256·Work Item ID·Phase D target enum·정확한 정책/Gap 배열·최신 시작 event·실행 시간·`IMPLEMENTATION_RECORD`·`VERIFICATION_RESULT`·`SUCCESSOR_TRACE`를 결속한다. 실행 구간은 시작 event `occurred_at`보다 이르지 않고 receipt 완료·생성 시각은 `GOAL_COMPLETED.occurred_at`보다 늦지 않아야 하며, `GOAL_COMPLETED` binding snapshot과 증거 집합을 canonical binding에 일치시킨다.
11. 전체 사건 순서 `Phase C acceptance → handover start → handover checks/decisions → handover acceptance → closure start → closure checks/decisions → closed`를 두 Phase D canonical receipt와 원자료로 재현한다. Phase receipt는 개별 Work Item completion receipt를 대신하지 않는다.

모든 transition event는 같은 날짜의 `occurred_on`과 timezone 포함 `occurred_at`을 가지며 `occurred_at`은 직전 event보다 엄격히 증가하고 checker·체크포인트에 함께 고정된 `validation_cutoff_at` 이하여야 한다.

### Phase D 정본 receipt 계약

두 Phase D receipt는 공통으로 candidate manifest 경로/SHA-256과 전체 구성요소 hash, 중복 없는 환경 ID, 도구 버전, 결함·잔여위험 참조, 실행 구간을 가진다. executor·reviewer·approver는 서로 다른 ID·role·authority를 가지며 검토·승인 결정과 시간 순서는 실제 실행 뒤여야 한다. 각 raw evidence는 path·SHA-256·record count·media type·수집 시각·collector 권한을 가지며 `collected_at`은 해당 receipt `execution_window` 안이어야 한다.

`PHASE_D_OPERATION_HANDOVER_RECEIPT`는 기술자료를 받았다는 사실이 아니라 운영 책임이 실제로 이전됐음을 증명한다. 최소한 다음을 포함한다.

- `schema_version=1.0`, `status=HANDED_OVER`, Phase D `source_goal_id`와 해당 Goal SHA-256
- `phase_c_technical_delivery_receipt_sha256`으로 Phase C 정본에 결속하고 `deployed_candidate_sha256 = candidate_sha256 = Phase C deployed candidate`
- 서로 다른 receipt executor·reviewer·approver의 ID·role·authority와 시간 순서
- 이전자와 인수자의 ID·role·authority, `APPROVED` 결정·시각, 실제 수락 시각. 수락은 handover `execution_window` 안이고 Phase C 기술자료 수령 뒤여야 함
- `operational_responsibility`의 service owner는 transferee이고 support contact·cost owner를 가지며, 책임 `effective_at`은 handover `execution_window` 안이고 실제 수락보다 이르지 않아야 함
- 계정·역할·권한·지원 연락·에스컬레이션·on-call·비용 책임의 이전 check 목록
- 대시보드·알림·runbook·백업복원·재해복구·키 회전·권한 검토·데이터 보존삭제의 실제 수행 상태
- 각 원자료의 path·SHA-256·record count·media type·수집 시각·collector 권한
- 미달 SLO/RTO/RPO, 잔여위험·결함·기술부채·후속 Backlog의 인수자와 기한

`PHASE_D_PROJECT_CLOSURE_RECEIPT`는 다음을 결속한다.

- `schema_version=1.0`, `status=CLOSED`, Phase D `source_goal_id`와 해당 Goal SHA-256
- `operation_handover_receipt_sha256`으로 운영 이관 정본에 결속하고 같은 deployed candidate SHA-256을 유지
- 최종 산출물·소스·릴리스 아카이브 manifest와 SHA-256
- KPI 결과, 미해결 결함·잔여위험·기술부채·후속계획
- 데이터·계정·키·인프라의 유지·이관·삭제 실제 상태
- CLS-01·02·03의 필수 check와 project owner·accepting owner의 ID·role·authority·`APPROVED` 결정/시각
- `CONTINUED_OPERATION` 또는 `SERVICE_TERMINATION` 종료 형태와 실제 종료 시각. 종료는 closure `execution_window` 안이고 운영 이관 수락 뒤여야 함

운영 이관 `required_checks`에는 `service_owner`, `access_transfer`, `runbook`, `monitoring`, `backup_restore`, `support_escalation`을 정확히 한 번씩 둔다. handover `required_check_summary`는 정확히 `{total: 6, passed: 6, failed: 0, not_run: 0}`이어야 한다. 프로젝트 종료 `required_checks`에는 `final_acceptance`, `remaining_defects`, `remaining_risks`, `technical_debt`, `data_disposition`, `account_key_cleanup`, `lessons_learned`를 정확히 한 번씩 두고 closure `required_check_summary`는 정확히 `{total: 7, passed: 7, failed: 0, not_run: 0}`이어야 한다. 각 check는 `{status: "PASS", executed_at, executor_id, reviewer_id, approver_id, evidence_refs: [...]}`이고, `executed_at`은 해당 receipt `execution_window` 안이며 actor ID는 해당 receipt actor와 같고 refs는 해당 receipt의 실제 raw evidence 경로만 가리킨다.

운영 이관의 최종 수락시각은 `handover.accepted_at`, 프로젝트 종료 확정시각은 `project_closure.closed_at`에 기록한다. 두 값과 각 receipt의 `generated_at`은 해당 Phase D·Master `GOAL_COMPLETED.occurred_at`보다 늦을 수 없다.

handover와 closure의 `execution_window.started_at`은 모두 Phase D를 가장 최근 `IN_PROGRESS`로 만든 event의 `occurred_at` 이상이어야 한다. handover 시작은 Phase C 기술자료 수락보다도 이르지 않아야 하고, 모든 handover check와 transferor/transferee 결정은 handover 시작부터 `accepted_at` 사이에 있어야 한다. closure `execution_window.started_at`은 handover 수락보다 이르지 않아야 하며 closure receipt의 생성 시각도 handover receipt 생성 시각보다 이르지 않아야 한다. 모든 closure check와 project owner/accepting owner 결정은 closure 시작부터 `closed_at` 사이에 있어야 한다. Phase C 수락 뒤라도 Phase D 시작 전에 실행한 receipt는 완료 증거가 아니다.

일정, 담당자 또는 미래 훈련일만 정한 check는 `PASS`가 아니며 실제 수행 전까지 `NOT_RUN/PARTIAL`이다. 내부 에이전트가 만든 JSON은 원자료를 결속하는 구조 검증 봉투일 뿐 계정 이전, 훈련, 운영자의 수락이나 종료 서명을 대신하지 못한다. transferor·transferee·project owner·accepting owner와 reviewer/approver 권한이 서명되었거나 외부 시스템에 anchor되지 않으면 `PARTIAL/AWAITING_EXTERNAL`이며 Phase D와 Master를 완료하지 않는다.

두 Phase D receipt는 actor의 `AUTHORITY_ROSTER` 일치만으로 완료 효력이 없다. 독립 검토와 서명 또는 외부 시스템 anchor를 마친 각 정확한 receipt 파일 SHA-256이 버전 관리되는 checker의 `EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE`에 `PHASE_D_OPERATION_HANDOVER_RECEIPT`, `PHASE_D_PROJECT_CLOSURE_RECEIPT` role별로 고정돼야 한다. 실제 receipt가 없어 map이 비어 있는 동안은 정상적인 fail-closed 상태이며, 실제 승인 receipt가 생겼을 때만 검토된 checker 변경으로 hash를 추가한다.

Phase D와 Master를 닫기 전에 운영 이관과 프로젝트 종료 receipt가 모두 최종화돼야 한다. 두 receipt 각각의 `generated_at`, handover `accepted_at`, project closure `closed_at`은 그 receipt를 completion evidence로 사용하는 Phase D 또는 Master `GOAL_COMPLETED.occurred_at` 이하여야 한다.

## 검증

- 실제 운영 소유자와 연락·권한이 문서와 일치
- 대시보드·알림·runbook이 배포 환경에서 동작
- 백업 복원 결과와 RTO/RPO 또는 미달 위험 기록
- 권한·키·데이터 보존·삭제 실행 기록 존재
- 열린 결함·위험·기술부채가 책임자·기한과 연결
- 최종 아카이브의 파일 목록·버전·SHA-256 검증
- 종료 서명자가 실제 권한 있는 사람
- canonical `PHASE_D_OPERATION_HANDOVER_RECEIPT`와 `PHASE_D_PROJECT_CLOSURE_RECEIPT`의 deployed hash, 실행·인수·승인자, 원자료 path+SHA-256, 필수 check 집계 검증

## 완료 기준

- OPS의 필수 운영 통제가 실제 환경에 연결됨
- 운영 소유권·계정·권한·지원 책임이 인수자에게 이전됨
- 최종 산출물과 소스·릴리스가 검증 가능한 형태로 보존됨
- KPI 결과와 미해결 결함·잔여위험·기술부채·후속계획이 승인됨
- 데이터·계정·키·인프라의 유지·이관·삭제 상태가 명확함
- CLS-01~03과 필요한 최종 인수·승인 기록이 실제로 완료됨
- 두 Phase D canonical receipt의 모든 필수 check가 실제 수행되어 PASS이고 서로 같은 배포 버전과 이관 사건을 가리킴
- 완료된 모든 Phase D Work Item의 `WORK_ITEM_COMPLETION::<goal_id>` receipt와 `GOAL_COMPLETED` binding snapshot이 정확함

## 질문·중단 조건

운영 문서와 인계 패키지 준비는 자율 수행한다. 실제 권한 이전, 계정 폐기, 키 회전, 데이터 삭제, 비용 계약 종료, 서비스 폐기, 인수·종료 서명과 signed/externally anchored authority evidence는 정확한 대상과 복구 가능성을 제시하고 승인받는다. 외부 수행 일정과 담당자만 확정되면 해당 Work Item은 `PARTIAL/AWAITING_USER` 또는 `PARTIAL/AWAITING_EXTERNAL`로 유지한다. 실제 수행 원자료와 권한 있는 수락이 생긴 뒤에만 완료한다.

## 완료 후 인계

두 Phase D canonical receipt의 경로·SHA-256을 체크포인트에 결속한 뒤에만 Phase D와 Master Goal을 `COMPLETE_AT_TARGET`으로 전환하고 최종 토큰 사용량·변경 파일·검증·실제 완료 범위·지속운영 책임자를 보고한다. 서비스가 계속되는 경우 Goal 완료는 프로젝트 이관 완료를 뜻하며 서비스 폐기를 뜻하지 않는다.
