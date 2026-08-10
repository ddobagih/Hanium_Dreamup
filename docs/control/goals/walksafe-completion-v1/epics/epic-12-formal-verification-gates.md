+++
schema_version = "1.0"
goal_id = "WS-GOAL-B-EPIC-12"
goal_kind = "EPIC"
document_version = "1.1.0"
phase_id = "B"
parent_goal_id = "WS-GOAL-PHASE-B"
sequence = 201
initial_status = "PLANNED"
source_status_at_creation = "PLANNED"
target_completion_level = "VERIFICATION_COMPLETE"
next_goal_id = ""
return_goal_id = "WS-GOAL-PHASE-B"
work_item_id = ""
dependencies = ["WS-GOAL-A-EPIC-04", "WS-GOAL-A-EPIC-05", "WS-GOAL-A-EPIC-06", "WS-GOAL-A-EPIC-08", "WS-GOAL-A-EPIC-09", "WS-GOAL-A-EPIC-10", "WS-GOAL-A-EPIC-11"]
child_goal_ids = []
source_policy_ids = ["FP-049", "FP-050", "GATE-CLOUD-COST-MEASUREMENT", "GATE-PHONE-QUEUE-BYTE-LIMIT", "GATE-RAW-COLLECTION-RELEASE-REVIEW", "GATE-SERVER-CAPACITY-STATE-CONTRACT", "GATE-SINGLE-ADMIN-RECOVERY-DRILL"]
gap_ids = ["GAP-058", "GAP-059", "GAP-064", "GAP-065", "GAP-066", "GAP-067", "GAP-068"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# EPIC-12 Goal — 정식 시험·현장 검증·5개 gate

## 목표

EPIC-01~11에서 구현 준비된 전체 기능을 하나의 불변 release candidate에서 검증하고, 279개 정식 시험과 5개 미면제 gate를 실제 증거로 종료한다.

## 정본 입력

- 선행: EPIC-04·05·06·08·09·10·11
- 정책: FP-049·050와 5개 gate
- Gap: GAP-058·059·064~068
- canonical `PLANNED_TEST_CASES`의 승인/Baselined authoritative 279개 TC ID, 환경·필수 증거유형
- 현재 기준: 279 `NOT_RUN`, 실제 기기 `NOT_RUN`, 승인 생산 프로필 0, release `NOT_ELIGIBLE`

## 범위와 제외

포함:

- release candidate 고정
- 자동·통합·API·E2E·회귀·성능·호환성·접근성·사용성·복구 시험
- 실제 지원폰·현장·목표사용자·안전요원
- 보안·개인정보·AI 평가와 독립검토
- 5개 gate 실제 종료
- TST-20·21·22와 REL-02 판정

제외:

- 문서 작성이나 내부 단위시험으로 gate 대체
- 서로 다른 후보 결과 혼합
- 미실행 시험 숨김
- P0/P1 결함이 남은 출시 승인

## 단계별 실행

다음 표는 r008의 소유 항목 목록이지 실제 실행 순서가 아니다. 실제 시험보다 먼저 닫혀야 하는 gate의 `must_close_before`가 우선한다.

| 우선 경계 | 정책·gate / Gap | 현재 | 실제 완료 조건 |
|---:|---|---|---|
| 1 | GATE-SINGLE-ADMIN-RECOVERY-DRILL / GAP-068 | BLOCKED | 실제 사용자시험 전에 외부 복구수단·기기 세션 폐기·고위험 작업 동결과 복구훈련 |
| 2 | GATE-PHONE-QUEUE-BYTE-LIMIT / GAP-064 | BLOCKED | 지원 기기별 사용자시험 범위를 확정하기 전에 실제 queue byte 한도 확정 |
| 3 | GATE-SERVER-CAPACITY-STATE-CONTRACT / GAP-065 | BLOCKED | 관련 통합시험 완료 전에 조회주기·TTL·버전·관측시각·오프라인 동작 계약 검증 |
| 4 | GATE-RAW-COLLECTION-RELEASE-REVIEW / GAP-066 | BLOCKED | 출시 전 독립 검토와 고지·동의·권리행사 완료. 변경 요구 시 CR·영향분석·제품책임자 재승인 |
| 5 | GATE-CLOUD-COST-MEASUREMENT / GAP-067 | BLOCKED | 실제 저장량·요청·복원 비용과 부가세를 포함한 월 30,000원 이하 측정 |
| 6 | FP-049 / GAP-058 | EVIDENCE_MISSING | 선행 gate를 지킨 불변 후보의 전체 자동·실폰·접근성·현장·보안·개인정보 시험 |
| 7 | FP-050 / GAP-059 | EVIDENCE_MISSING | 선행 gate를 지킨 지원폰 장시간 성능·열·배터리·queue와 목표사용자 현장시험 |

시험 준비·runner·fixture·evidence schema는 자율 완성한다. 실제 외부 실행은 한 번의 통합 패키지로 필요한 자원과 일정을 요청한다.

완료 증거는 `FORMAL_TEST_REPORT`, `ACTUAL_DEVICE_TEST_REPORT`, `RELEASE_GATE_CLOSURE_RECEIPT`, `RELEASE_ELIGIBILITY_APPROVAL` 네 canonical role로 고정한다. 모두 같은 candidate manifest SHA-256에 결속하고 서로 다른 실제 실행자·검토자·승인자의 ID·role·authority, 실행·검토·승인 시각, 환경·도구 버전, 원자료 경로·SHA-256·record count·collector를 포함해야 한다.

- 정식 시험보고서는 승인 원장과 ID 집합이 같은 279개 TC 행을 가진다. 각 환경은 계획의 허용 환경이어야 하고 증거유형은 계획과 정확히 같아야 한다. 적용 시험은 `PASS`, 비적용은 보고서 approver의 `APPROVED_NOT_APPLICABLE` 결정·시각이 있어야 하며 `FAIL/NOT_RUN=0`, P0/P1=0, raw count≥279다.
- 실제 기기 보고서는 승인 생산 기기 프로필과 기기·OS·환경·현장·접근성·장시간 원자료를 보존한다.
- gate receipt는 정확한 5개 개별 상태와 `waived=false`, actor ID, receipt `execution_window` 안의 완료시각, 측정·훈련·독립검토 raw ref를 보존한다. 비용 gate는 KRW 저장·요청·복원·부가세 합계, `vat_included=true`, 월 30,000원 이하를 검증한다.
- 출시 적격 승인서는 앞 세 receipt의 canonical 파일 SHA-256 map과 TST-22·REL-02 실제 승인자의 권한·서명을 보존한다. 승인 시각은 approver 결정 시각과 같고 세 prerequisite receipt 생성 뒤여야 한다.

에이전트가 내부에서 생성한 JSON은 위 자료의 색인·구조 검증 봉투일 뿐 실제 기기 실행, 독립검토, 복구훈련, 청구자료, 승인 서명을 대신하지 않는다. 서명되었거나 외부 시스템에 anchor된 행위자 권한 증거와 실제 원자료가 없으면 해당 항목은 `NOT_RUN/PARTIAL/AWAITING_EXTERNAL`, 출시는 `NOT_ELIGIBLE`이다.

## 검증

- 각 TC의 `eligible_environment_ids`와 `required_evidence_types` 준수
- 동일 candidate·모델·설정·DB generation
- 실제 기기·환경·실행자·검토자·승인자·원자료·결함·SHA-256 결속
- P0/P1 결함 0
- gate 5개 `PASS/CLOSED`, `waived=false`
- 정확히 279개 TC 행과 적용성·PASS·FAIL·NOT_RUN·NOT_APPLICABLE 집계 일치
- TST-22와 REL-02 실제 승인
- 네 canonical 증거가 같은 후보를 가리키고 모든 원자료 SHA-256 검증에 성공

## 완료 기준

Phase B Goal의 완료 기준과 동일하다. 직접 16개만 실행하고 끝내지 않으며 전체 279개 책임을 진다.

## 질문·중단 조건

실제 기기·참여자·안전요원·독립검토자·클라우드 계정·비용이 필요해지면 준비된 통합 실행 패키지로 요청한다. 이 조건을 내부 에이전트나 모의 결과로 우회하지 않는다.

## 완료 후 인계

TST-22·REL-02가 출시 적격을 승인한 경우에만 Phase B를 완료하고 [`../30-phase-c-release-delivery.md`](../30-phase-c-release-delivery.md)로 전환한다.
