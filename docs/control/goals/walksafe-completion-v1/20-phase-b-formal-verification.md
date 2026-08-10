+++
schema_version = "1.0"
goal_id = "WS-GOAL-PHASE-B"
goal_kind = "PHASE"
document_version = "1.1.0"
phase_id = "B"
parent_goal_id = "WS-GOAL-WALKSAFE-COMPLETION-V1"
sequence = 20
initial_status = "PLANNED"
target_completion_level = "VERIFICATION_COMPLETE"
next_goal_id = "WS-GOAL-PHASE-C"
return_goal_id = ""
work_item_id = ""
dependencies = ["WS-GOAL-PHASE-A"]
child_goal_ids = ["WS-GOAL-B-EPIC-12"]
source_policy_ids = []
gap_ids = []
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# Phase B Goal — 정식 검증과 출시 적격 판단

## 목표

Phase A에서 구현 준비된 앱·서버·모델·설정·데이터 구조를 하나의 불변 출시 후보로 고정하고, EPIC-12와 연결된 정식 시험·실기기·현장·접근성·보안·개인정보 검증 및 5개 gate를 실제 증거로 완료한다.

## 정본 입력

- Phase A exit 기록과 최신 Gap·Backlog
- canonical `PLANNED_TEST_CASES` binding이 가리키는 279개 계획 TC와 각 `eligible_environment_ids`·`required_evidence_types`; 실행 전에 별도 `TEST_PLAN_APPROVAL_RECEIPT`로 정확한 snapshot을 승인·고정한다.
- SEC·AIML·WS의 계획·절차·평가 기준
- REL-01·02 출시 계획과 승인 체크리스트
- 실제 후보 APK/AAB·서버·모델·설정·DB migration·SBOM·해시

## 범위와 제외

포함:

- 불변 release candidate manifest
- 적용 가능한 279개 시험의 실제 실행과 원자료
- 실제 Android 기기·지원환경·장시간·접근성·현장 검증
- 보안 scan·계약·복구·모델 동등성 및 성능 검증
- 5개 gate의 실제 측정·독립검토·복구훈련
- 결함 수정 뒤 새 후보와 필요한 회귀
- TST-20·21·22와 REL-02 출시 적격 판단

제외:

- 내부 단위시험을 정식 시험으로 재분류
- 기기·현장·독립검토 없이 PASS 생성
- gate 면제
- 출시 적격 전 프로덕션 배포

## 단계별 실행

1. Phase A의 모든 EPIC이 `IMPLEMENTATION_READY`인지 확인한다.
2. 앱·서버·Gateway·모델·설정·DB·의존성을 하나의 후보 manifest와 해시로 고정한다.
3. 시험 환경·지원 기기·계정·데이터·안전계획을 준비한다.
4. 저장소 안에서 자동화·fixture·runner·evidence schema를 먼저 완성한다.
5. 아래 gate 선행조건을 시험 선택보다 먼저 평가한다. r008의 단순 나열 순서와 충돌하면 이 표가 우선한다.

| Gate | 실제로 닫는 조건 | 반드시 닫혀야 하는 시점 |
|---|---|---|
| `GATE-SINGLE-ADMIN-RECOVERY-DRILL` | 외부 복구수단 사용, 분실 기기 세션 폐기, 고위험 작업 동결·복구를 실제로 훈련하고 증거를 남김 | 실제 사용자시험 또는 배포 전 |
| `GATE-PHONE-QUEUE-BYTE-LIMIT` | 지원 기기별 저장공간과 대기자료 크기를 실측해 byte 상한을 확정 | 지원 기기별 실제 사용자시험 범위 확정과 TST-22 전 |
| `GATE-SERVER-CAPACITY-STATE-CONTRACT` | 서버 부하·상태지연·오프라인 시간을 측정하고 조회주기·TTL·버전·관측시각·오프라인 동작을 API와 단말 상태기계로 시험 | 관련 기능 통합시험 완료와 TST-22 전 |
| `GATE-RAW-COLLECTION-RELEASE-REVIEW` | 독립 검토와 고지·동의·권리행사 절차를 완료. 변경 요구가 나오면 변경요청·영향분석·제품책임자 재승인까지 완료 | TST-22와 실제 서비스 배포 전 |
| `GATE-CLOUD-COST-MEASUREMENT` | 실제 저장량·요청·복원 비용과 부가세를 모두 포함해 월 30,000원 이하인지 측정 | 운영비 기준 확정과 TST-22 전 |

6. 각 시험의 선행 gate가 닫힌 뒤 외부 자원이 필요한 시험을 동일 후보에 대해 실제로 실행한다. gate가 막지 않는 자동화·fixture·통제된 준비는 계속할 수 있다.
7. 결함은 TST-18과 Gap successor에 연결하고 후보가 바뀌면 영향 회귀를 다시 수행한다.
8. P0/P1 미해결 결함 0과 잔여위험을 확인한 뒤에만 TST-22와 REL-02를 판정한다.

Phase B의 모든 Work Item은 `target_completion_level=FORMAL_VERIFICATION_ACTION_COMPLETE`만 사용한다. 정책별 Work Item이면 canonical `IMPLEMENTATION_GAP.assessments`의 `source_policy_id → gap_id` mapping이 지정한 pair를 정확히 하나 사용하고, 두 배열의 위치를 zip해 pair를 만들지 않는다. 검증 실행계획에서 만든 source plan은 계보 입력일 뿐 완료 증거가 아니다.

각 Work Item을 완료할 때는 canonical role `WORK_ITEM_COMPLETION::<goal_id>`의 `WORK_ITEM_EXECUTION_RECEIPT`가 정확히 하나 있어야 한다. 이 receipt는 Goal 파일 SHA-256, Work Item ID, Phase B target enum, 정확한 정책/Gap 배열, 최신 실행 시작 event SHA-256, 실행 구간·완료시각과 서로 다른 경로의 `IMPLEMENTATION_RECORD`·`VERIFICATION_RESULT`·`SUCCESSOR_TRACE` typed JSON을 결속한다. 실행 구간은 시작 event `occurred_at`보다 이르지 않고 receipt 완료·생성 시각은 `GOAL_COMPLETED.occurred_at`보다 늦지 않아야 한다. `GOAL_COMPLETED.completion_receipt_binding`과 event 증거 집합까지 canonical binding과 일치해야 하며, 아래 다섯 Phase B 정본 receipt는 이 Work Item 완료 receipt를 대신하지 않고 Phase 전체의 상위 완료 경계를 추가로 증명한다. 모든 transition event는 같은 날짜의 `occurred_on`과 timezone 포함 `occurred_at`을 가지며 `occurred_at`은 직전 event보다 엄격히 증가하고 checker·체크포인트에 함께 고정된 `validation_cutoff_at` 이하여야 한다.

### 정본 증거 계약

Phase B 완료에는 아래 다섯 canonical role이 모두 필요하다.

| canonical role | 필수 상태 | 증명해야 하는 실제 사건 |
|---|---|---|
| `TEST_PLAN_APPROVAL_RECEIPT` | `APPROVED` | 시험 시작 전에 정확한 279개 계획 snapshot을 권한 있는 승인자가 승인하고 외부에 고정한 사건 |
| `FORMAL_TEST_REPORT` | `PASS` | 279개 전체의 적용성·PASS·FAIL·NOT_RUN 집계와 각 TC의 실제 실행 원자료 |
| `ACTUAL_DEVICE_TEST_REPORT` | `PASS` | 승인 생산 기기 프로필에서 수행한 실기기·접근성·현장·장시간 결과 |
| `RELEASE_GATE_CLOSURE_RECEIPT` | `CLOSED` | 5개 gate 각각의 실제 측정·훈련·독립검토와 `waived=false` |
| `RELEASE_ELIGIBILITY_APPROVAL` | `ELIGIBLE` | 권한 있는 승인자가 TST-22·REL-02에 따라 정확한 후보의 출시 적격을 승인한 사건 |

각 정본 증거는 최소한 다음을 포함하고 서로 같은 release candidate SHA-256을 가리켜야 한다.

- canonical binding과 일치하는 문서 ID, `schema_version=1.0`, 증거 종류·필수 상태와 생성 시각
- 후보 manifest의 저장소 경로·SHA-256. manifest 파일 SHA-256이 candidate SHA-256이고 내부 component 이름·경로·SHA-256 집합이 receipt의 `component_hashes`와 정확히 일치
- `execution_window` 시작/종료, 서로 다른 실제 executor·reviewer·approver의 ID·role·authority, reviewer/approver의 `APPROVED` 결정·시각, 실행→검토→승인→생성 시간 순서
- 중복 없는 환경 ID, 실행 도구 이름·버전, 결함·잔여위험 참조
- 변조되지 않은 각 원자료의 저장소 경로·SHA-256·`record_count`·media type·수집 시각과 collector의 ID·role·authority. `collected_at`은 해당 receipt의 `execution_window` 안이어야 함
- 결함·재시험·적용성 판정·잔여위험의 역추적

`TEST_PLAN_APPROVAL_RECEIPT`와 `FORMAL_TEST_REPORT`에는 같은 `planned_test_cases_binding`이 있어야 한다. 이 snapshot의 `role=PLANNED_TEST_CASES`, `document_id`, `path`, `file_sha256`, `version`, 정렬된 279개 ID의 `test_case_id_set_sha256`은 실제 canonical binding과 정확히 같다. 계획 receipt의 `plan_approved_at`은 receipt approver 결정 및 계획 metadata의 `approved_at`과 같고 계획 receipt `generated_at`과 함께 `FORMAL_TEST_REPORT`, `ACTUAL_DEVICE_TEST_REPORT`, `RELEASE_GATE_CLOSURE_RECEIPT` 각 `execution_window.started_at` 이하여야 한다. 파일 내부 상태 문자열만 바꿔 소급 승인하지 않는다. 보고서에는 이 계획과 ID 집합이 정확히 같은 279개 authoritative TC 행이 있어야 한다. 각 행은 candidate SHA-256, 보고서 executor ID, 승인 계획에 허용된 environment ID, 계획의 `required_evidence_types`와 정확히 같은 증거유형 집합, 실제 raw evidence 참조를 가진다. `APPLICABLE`은 반드시 `PASS`여야 한다. `NOT_APPLICABLE`은 이유와 보고서 approver가 남긴 `APPROVED_NOT_APPLICABLE` 결정·시각이 있을 때만 허용하며, 그 `decided_at`은 보고서 실행 시작 이후이고 보고서 approver 결정과 `generated_at`보다 늦지 않아야 한다. 요약은 `PASS + NOT_APPLICABLE = 279`, `FAIL=0`, `NOT_RUN=0`, P0/P1 열린 결함 0이어야 하며 raw `record_count` 합계도 279 이상이어야 한다.

`ACTUAL_DEVICE_TEST_REPORT`는 `tested_candidate_sha256`이 후보와 같고 승인 생산 기기 프로필이 최소 1개 있어야 한다. 각 프로필은 고유 ID, 기기 모델, OS 버전, `PASS`, 해당 원자료 참조를 가지며 raw `record_count` 합계가 프로필 수 이상이어야 한다.

`RELEASE_GATE_CLOSURE_RECEIPT`에는 정확한 gate 5개의 개별 행, `CLOSED`, `waived=false`, 완료 시각, receipt와 같은 executor·reviewer·approver ID, 각 gate 원자료 참조가 있어야 하며 raw `record_count` 합계는 5 이상이어야 한다. 각 gate `completed_at`은 gate receipt의 `execution_window` 안이어야 한다. 비용 gate는 `storage_krw + request_krw + restore_krw + vat_krw = monthly_total_krw`, `vat_included=true`, `currency=KRW`, 월 합계 30,000원 이하를 만족한다.

`RELEASE_ELIGIBILITY_APPROVAL`은 `release_candidate_sha256`이 후보와 같아야 한다. `eligibility_approved_at`은 approver의 `decided_at`과 같고 네 prerequisite receipt가 모두 생성된 뒤여야 한다. `TEST_PLAN_APPROVAL_RECEIPT`, `FORMAL_TEST_REPORT`, `ACTUAL_DEVICE_TEST_REPORT`, `RELEASE_GATE_CLOSURE_RECEIPT` 네 canonical 파일 SHA-256의 정확한 prerequisite map을 가져야 한다.

저장소 에이전트가 만든 JSON은 정본 증거를 찾아 구조적으로 검증하는 봉투일 뿐 실제 기기 실행, 사람의 독립검토, 복구훈련, 클라우드 청구자료나 권한 있는 승인을 대신하지 못한다. 서명되었거나 외부 시스템에 anchor된 행위자 권한 증거와 외부 실제성 원자료가 없으면 올바른 필드가 있어도 `NOT_RUN/PARTIAL/AWAITING_EXTERNAL/NOT_ELIGIBLE`이다.

위 다섯 Phase B 완료 receipt는 actor가 `AUTHORITY_ROSTER`에 있다는 사실만으로 승인되지 않는다. 각 receipt는 `authority_roster_ref=AUTHORITY_ROSTER`, `authority_roster_document_id`, `authority_roster_sha256`으로 사용 당시 immutable roster를 결속하고 roster `approved_at`은 receipt 실행 시작보다 늦지 않아야 한다. v1.1 roster는 제자리 갱신하지 않는다. 독립 검토와 서명 또는 외부 시스템 anchor를 마친 정확한 receipt 파일 SHA-256이 버전 관리되는 checker의 `EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE`에 해당 role의 trust anchor로 고정돼야 한다. 실제 receipt가 아직 없어서 map이 비어 있는 것은 정상이며 Phase B는 fail-closed한다. 실제 승인된 receipt가 생겼을 때만 별도 검토한 checker 변경으로 hash를 추가하고 임시·예상 hash는 넣지 않는다.

다섯 required receipt는 Phase B를 가장 최근 `IN_PROGRESS`로 만든 event 뒤에만 실행하고 Phase B `GOAL_COMPLETED` 전에 모두 최종화해야 한다. 각 `execution_window.started_at`은 Phase B 시작 event `occurred_at` 이상이고, `TEST_PLAN_APPROVAL_RECEIPT`, `FORMAL_TEST_REPORT`, `ACTUAL_DEVICE_TEST_REPORT`, `RELEASE_GATE_CLOSURE_RECEIPT`, `RELEASE_ELIGIBILITY_APPROVAL` 각각의 `generated_at`은 이 receipt를 completion evidence로 사용하는 Phase B `GOAL_COMPLETED.occurred_at` 이하여야 한다.

Phase B와 EPIC-12의 completion evidence 및 `verification_evidence_refs`는 각각 위 다섯 role의 정확한 집합이어야 한다. 마지막 Work Item을 함께 닫는 `GOAL_COMPLETED.evidence_refs`는 이 집합과 그 Work Item의 `WORK_ITEM_COMPLETION::<goal_id>`를 합친, 같은 event에서 완료되는 Goal들의 정확한 증거 합집합이어야 한다.

## 검증

- 모든 결과는 동일 release candidate 해시와 환경·시각·실행자·검토자·승인자·원자료에 결속
- test-cases 원장의 적용 환경과 필수 증거 유형 준수
- 279개 TC 행과 PASS/FAIL/NOT_RUN/NOT_APPLICABLE 집계·근거 일치
- 실제 기기·현장시험의 참여자 동의·안전 중단계획 존재
- 독립검토는 내부 서브에이전트가 아닌 지정 외부 검토자 기록
- 결함 수정 후 영향받는 시험 회귀
- gate 5개 모두 `PASS/CLOSED`, `waived=false`
- 같은 후보 SHA-256에 결속된 위 다섯 canonical role과 각 역할의 원자료·실행자·검토자·승인자 기록 존재

## 완료 기준

- 279개 전체 행이 누락 없이 집계되고 적용되는 정식 시험이 모두 실제 실행되어 요구된 합격 기준을 충족
- 미실행 시험은 승인된 적용성 판정 없이 숨기지 않음
- 실제 기기·접근성·현장·보안·모델 검증 완료
- 5개 gate가 실제 증거로 종료
- P0/P1 열린 결함 0
- TST-20 보고서와 TST-22 출시 준비도 판단 완료
- REL-02의 실제 승인자가 출시 적격을 승인
- 위 다섯 증거가 서로 다른 release candidate를 가리키지 않고 각 receipt의 원자료 SHA-256 검증이 성공
- 완료된 모든 Phase B Work Item의 typed completion receipt와 `GOAL_COMPLETED` binding snapshot이 정확함

## 질문·중단 조건

시험 자동화와 준비는 질문 없이 계속한다. 실제 기기, 목표 사용자, 안전요원, 독립검토자, 운영계정, 유료 클라우드 또는 서명·외부 anchor된 권한 증거가 임계경로에서 필요해지면 준비된 시험 목록·필요 자원·비용·예상 시간·대체 불가 이유를 하나의 외부 실행 패키지로 묶어 요청한다.

Phase B가 완료되지 않으면 Phase C를 시작하지 않는다. 다섯 exact anchored receipt가 모두 검증되기 전에는 `formal_test_not_run_count=279`, release gate 5개와 aggregate `NOT_RUN`, `actual_device_test_status=NOT_RUN`, `formal_test_pass_claimed=false`, `approved_production_profile_count=0`을 하나의 경계로 유지한다. 일부 시험만 통과한 상태에서 이 값 중 일부만 올리거나 `VERIFICATION_COMPLETE`로 전환하지 않는다.

## 완료 후 인계

출시 적격 승인과 후보 manifest를 체크포인트에 결속하고 Phase B를 `COMPLETE_AT_TARGET`, Phase C를 `READY`로 전환한다. 실제 배포 권한이 없으면 Phase C를 `AWAITING_USER`로 두되 배포 전 준비자료는 계속 완성한다.
