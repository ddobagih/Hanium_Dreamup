+++
schema_version = "1.0"
goal_id = "WS-GOAL-PHASE-C"
goal_kind = "PHASE"
document_version = "1.1.0"
phase_id = "C"
parent_goal_id = "WS-GOAL-WALKSAFE-COMPLETION-V1"
sequence = 30
initial_status = "PLANNED"
target_completion_level = "RELEASE_DELIVERED"
next_goal_id = "WS-GOAL-PHASE-D"
return_goal_id = ""
work_item_id = ""
dependencies = ["WS-GOAL-PHASE-B"]
child_goal_ids = []
source_policy_ids = []
gap_ids = []
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# Phase C Goal — 통제된 릴리스·배포·인도

## 목표

Phase B에서 승인된 정확한 release candidate를 변경 없이 서명·릴리스하고, 승인된 환경의 일부 대상부터 배포하는 단계 확인(canary), 배포 직후 핵심 기능 확인(smoke), 이전 정상 버전으로 되돌리기(rollback)를 거친 뒤 사용자·관리자·운영자에게 기술자료를 인도한다. 이 단계의 인도는 bytes와 설명자료의 기술적 전달이며 운영 계정·권한·지원 의무와 의사결정 책임의 이전은 Phase D에서만 완료한다.

## 정본 입력

- TST-22와 REL-02의 출시 적격 승인
- 불변 release candidate manifest와 앱·서버·모델·설정·DB·SBOM·해시
- REL-01~22의 계획·절차·설명서·승인조건
- OPS의 배포 전 소유자·알림·runbook·백업·복구 준비상태

## 범위와 제외

포함:

- REL-03~10 버전·manifest·태그·산출물·서명·SBOM·릴리스노트
- REL-11~15 배포·migration·smoke·canary·rollback
- REL-16~22 설치·사용자·관리자·교육·인수인계·라이선스 자료
- 배포 전 백업과 되돌리기 가능성 확인
- 승인 환경에서 실제 배포와 인도 증거

제외:

- Phase B 미완료 후보 배포
- 다른 bytes를 같은 승인 후보로 취급
- 사용자 권한 없이 프로덕션이나 외부 계정을 변경
- 실패한 smoke/canary를 무시하고 전면 배포

## 단계별 실행

Phase C의 모든 Work Item은 `target_completion_level=RELEASE_DELIVERY_ACTION_COMPLETE`만 사용한다. 특정 정책/Gap을 직접 해결하는 Work Item이면 canonical `IMPLEMENTATION_GAP.assessments`의 `source_policy_id → gap_id` mapping이 지정한 pair를 사용하고, 그렇지 않은 phase-direct 행동은 두 배열을 모두 비운다. source Phase plan은 계보 입력이지 완료 증거가 아니다.

1. 배포할 bytes와 Phase B 승인 후보가 정확히 같은지 확인한다.
2. 버전·태그·manifest·해시·서명·SBOM·릴리스노트를 고정한다.
3. DB·데이터 migration, 백업, 복원, rollback 명령을 dry-run 또는 승인된 staging에서 검증한다.
4. 사용자·관리자·운영 설명서와 알려진 문제·우회법을 후보에 맞춘다.
5. 실제 프로덕션 변경 직전에 범위·계정·비용·영향·rollback을 묶어 외부 실행 승인을 받는다.
6. 실제 배포 `started_at`을 기록한 뒤 canary를 실행하고, canary가 통과한 뒤 smoke를 실행한다. 시간 순서는 `deployment.started_at ≤ canary.executed_at < smoke.executed_at`이어야 한다.
7. smoke 통과 뒤 승인 범위만 단계 확대하고 실제 배포 `ended_at`을 기록한다. 실패 기준에 닿으면 확대를 중단하고 승인된 이전 정상 조합으로 되돌리며 기술자료 수령으로 진행하지 않는다.
8. 배포 종료 뒤에만 인도 대상에게 설치·운영·지원·복구 자료를 전달하고 `technical_delivery.accepted_at`을 기록한다. 전체 순서는 `deployment start → canary → smoke → deployment end → delivery acceptance`이며 `smoke.executed_at ≤ deployment.ended_at ≤ technical_delivery.accepted_at`이어야 한다. 자료 수령을 운영 책임 인수로 해석하지 않는다.
9. 실제 결과와 해시·시각·실행자·환경을 REL·OPS 기록에 남긴다.
10. 각 완료 Work Item에 `WORK_ITEM_COMPLETION::<goal_id>` role의 `WORK_ITEM_EXECUTION_RECEIPT`를 정확히 하나 등록한다. Goal SHA-256·Work Item ID·Phase C target enum·정확한 정책/Gap 배열·최신 시작 event·실행 시간·`IMPLEMENTATION_RECORD`·`VERIFICATION_RESULT`·`SUCCESSOR_TRACE`를 결속한다. 실행 구간은 시작 event `occurred_at`보다 이르지 않고 receipt 완료·생성 시각은 `GOAL_COMPLETED.occurred_at`보다 늦지 않아야 하며, `GOAL_COMPLETED` binding snapshot과 증거 집합을 canonical binding에 일치시킨다.
11. 아래 계약을 만족하는 `PHASE_C_TECHNICAL_DELIVERY_RECEIPT`를 canonical binding에 등록한다. 이 Phase receipt는 개별 Work Item completion receipt를 대신하지 않는다.

모든 transition event는 같은 날짜의 `occurred_on`과 timezone 포함 `occurred_at`을 가지며 `occurred_at`은 직전 event보다 엄격히 증가하고 checker·체크포인트에 함께 고정된 `validation_cutoff_at` 이하여야 한다.

### Phase C 정본 receipt 계약

`PHASE_C_TECHNICAL_DELIVERY_RECEIPT`는 다음 실제 사건을 하나의 배포된 후보에 결속한다.

- `schema_version=1.0`, `status=DELIVERED`, Phase C `source_goal_id`와 해당 Goal SHA-256
- `release_eligibility_receipt_sha256`으로 Phase B 승인 정본에 결속하고 `approved_candidate_sha256 = candidate_sha256 = deployed_candidate_sha256`
- 실제 배포 bytes·서명·SBOM·provenance·DB migration·설정의 candidate manifest 경로/SHA-256과 구성요소 전체 hash
- 서로 다른 executor·reviewer·approver의 ID·role·authority, 승인 결정·시각과 실행→검토→승인→receipt 생성 시간 순서
- 배포 환경·채널·범위, 시작/종료 시각과 receipt executor ID. 시작·종료는 receipt `execution_window` 안이고 배포 시작은 Phase B 출시 적격 승인 뒤여야 함
- 기술자료 목록의 실제 경로·SHA-256, recipient의 ID·role·authority와 수령 시각. 수령은 receipt `execution_window` 안이고 배포 종료 뒤여야 함
- 각 원자료의 경로·SHA-256·record count·media type·수집 시각·collector 권한. `collected_at`은 receipt `execution_window` 안이어야 함
- 알려진 문제·잔여위험·실패 또는 rollback 사건의 참조

`required_checks`에는 `candidate_identity`, `artifact_signature`, `sbom_provenance_license`, `backup_migration_rollback`, `canary`, `smoke`, `delivery_documents`, `acceptance_record`를 정확히 한 번씩 둔다. 각 값은 `{status: "PASS", executed_at, executor_id, reviewer_id, approver_id, evidence_refs: [...]}` 구조다. `executed_at`은 receipt `execution_window` 안이고 actor ID는 receipt actor와 같으며 refs는 receipt의 실제 raw evidence 경로만 가리킨다. `required_check_summary`는 정확히 `{total: 8, passed: 8, failed: 0, not_run: 0}`이어야 한다. 하나라도 이 조건을 어기거나 배포 bytes가 Phase B 후보와 다르면 receipt는 완료 효력을 갖지 않는다.

시간 증거는 하나의 순서를 재현해야 한다. `PHASE_C_TECHNICAL_DELIVERY_RECEIPT.execution_window.started_at`은 Phase C를 가장 최근 `IN_PROGRESS`로 만든 event의 `occurred_at` 이상이어야 한다. 그 뒤 배포를 시작하고 `deployment.started_at ≤ canary.executed_at < smoke.executed_at ≤ deployment.ended_at ≤ technical_delivery.accepted_at`이며 모든 시각은 receipt `execution_window` 안이어야 한다. Phase B 적격 승인 뒤라도 Phase C 시작 전에 실행한 receipt는 완료 증거가 아니다.

저장소에서 생성한 receipt JSON은 원자료 색인·구조 검증 봉투이며 배포 플랫폼·서명 서비스와 사람의 실제 기록을 대신하지 않는다. 프로덕션 실행자·검토자·승인자·수령자의 권한이 서명되었거나 외부 시스템에 anchor되지 않으면 `PARTIAL/AWAITING_EXTERNAL`이고 Phase C를 완료하지 않는다.

`PHASE_C_TECHNICAL_DELIVERY_RECEIPT`는 actor의 `AUTHORITY_ROSTER` 일치만으로 완료 효력이 없다. 독립 검토와 서명 또는 외부 시스템 anchor를 마친 정확한 receipt 파일 SHA-256이 버전 관리되는 checker의 `EXPECTED_EXTERNALLY_ATTESTED_RECEIPT_SHA256_BY_ROLE["PHASE_C_TECHNICAL_DELIVERY_RECEIPT"]`에 고정돼야 한다. 실제 receipt가 없어 map이 비어 있는 동안은 정상적인 fail-closed 상태이며, 실제 승인 receipt가 생겼을 때만 검토된 checker 변경으로 hash를 추가한다.

Phase C를 닫기 전에 receipt 자체와 기술자료 수락이 모두 확정돼야 한다. `PHASE_C_TECHNICAL_DELIVERY_RECEIPT.generated_at`과 `technical_delivery.accepted_at`은 이 receipt를 completion evidence로 사용하는 Phase C `GOAL_COMPLETED.occurred_at` 이하여야 한다.

## 검증

- 배포 산출물과 승인 후보 해시 동일
- 서명·SBOM·provenance·라이선스 고지 존재
- backup·migration·rollback 사전검증 통과
- canary와 smoke의 실제 원자료 존재
- 실패 시 확대 중단·rollback 작동
- 문서에 과거 PWA 주제품 설명이나 미승인 환경이 없음
- REL-20·21 중 기술자료 전달·배포 검수 범위의 실제 수령·검수 주체 확인
- canonical `PHASE_C_TECHNICAL_DELIVERY_RECEIPT`의 candidate/deployed hash, 실행·승인자, 원자료 path+SHA-256, 필수 check 집계 검증

## 완료 기준

- 승인 후보가 승인 범위에 실제 배포됨
- 배포 후 smoke와 필수 canary 기준 통과
- rollback 가능성을 실제 절차로 확인
- 사용자·관리자·운영 자료와 라이선스 고지 인도
- 배포 기록·릴리스노트·알려진 문제·잔여위험 최신화
- REL-20·21 중 기술적 인도·검수 기록 존재
- `PHASE_C_TECHNICAL_DELIVERY_RECEIPT`가 같은 후보의 실제 배포와 기술자료 수령을 증명하고 모든 필수 check가 PASS
- 완료된 모든 Phase C Work Item의 `WORK_ITEM_COMPLETION::<goal_id>` receipt와 `GOAL_COMPLETED` binding snapshot이 정확함
- 운영 계정·권한·지원·복구 책임의 이전은 아직 Phase D 책임이며 Phase C 완료로 주장하지 않음

## 질문·중단 조건

릴리스 패키지 작성과 staging 검증은 자율 수행한다. 실제 프로덕션 계정·서명키·앱 배포 채널·DNS·클라우드·외부 통지 또는 signed/externally anchored authority evidence가 필요한 순간에는 정확한 대상과 변경을 제시하고 사용자 권한을 요청한다. 승인되지 않은 대상에 배포하거나 비용을 지출하지 않는다. 일정·실행자·배포 창만 승인됐거나 staging만 검증된 상태는 `PARTIAL/AWAITING_*`이며 실제 배포 receipt 없이 완료하지 않는다.

## 완료 후 인계

배포된 정확한 버전, 기술자료 수령 시점과 `PHASE_C_TECHNICAL_DELIVERY_RECEIPT`의 경로·SHA-256을 체크포인트에 기록하고 Phase C를 `COMPLETE_AT_TARGET`, Phase D를 `READY`로 전환한다. 이어서 운영 안정화와 공식 책임 이관 조건을 평가한다. Phase C receipt를 Phase D 운영책임 이관 증거로 재사용하지 않는다.
