# Template Library — 조건부·사건 기반 Goal

이 문서는 미래 Goal 개수를 미리 고정하지 않고 실제 조건이 생길 때 정확한 Goal을 만드는 템플릿 라이브러리다. 모든 동적 Goal은 공통 front matter와 해당 유형 계약을 사용한다.

## 공통 front matter

```toml
+++
schema_version = "2.0"
goal_id = "{고유 Goal ID}"
goal_kind = "WORK_ITEM"
document_version = "2.0.0"
parent_goal_id = "{소유 Workstream 또는 Master Goal ID}"
work_item_type = "{아래 허용 유형}"
priority_rank = {정수}
initial_status = "PLANNED"
target_completion_level = "{유형별 값}"
work_item_id = "{고유 실행 ID}"
start_requires = ["{착수 hard dependency}"]
completion_requires = ["{완료 hard dependency}"]
child_goal_ids = []
source_policy_ids = []
gap_ids = []
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "{생성 근거 role}"
materialized_from_path = "{생성 근거 경로}"
materialized_from_document_id = "{근거 문서 ID}"
materialized_from_sha256 = "{근거 파일 SHA-256}"
predecessor_goal_id = "{생성 직전 focus 또는 같은 작업의 직전 revision 계보 포인터}"
predecessor_goal_content_sha256 = "{직전 Goal SHA-256}"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
+++
```

모든 문서는 `목표 / 정본 입력 / 범위와 제외 / 실행 절차 / 검증 / 완료 기준 / 질문·중단 조건 / 완료 후 인계`를 포함한다.

이 템플릿은 package가 `ACTIVE`가 된 뒤에만 새 Goal 생성에 사용한다. 유일한 사전활성 bootstrap 예외는 이미 `PACKAGE_PREPARED`에 봉인된 FP-018 r001이다. Goal 생성은 실행 시작이 아니며 별도 `GOAL_STARTED` event 전에는 작업하지 않는다.

생성 시점의 `materialized_from_*`는 그때 유효한 canonical binding snapshot과 일치해야 한다. 이후 정본 revision이 바뀌어도 과거 Goal을 고치지 않고 `CANONICAL_BINDINGS_UPDATED` event 뒤 새 Goal을 만든다. `predecessor_goal_id`는 생성 계보를 남기는 provenance 포인터이며 hard dependency가 아니다. 준비 조건은 `start_requires`와 blocker만 결정한다. `PLANNED` Goal의 선행조건이 충족되면 의존 Goal별 완료 event SHA-256을 가진 `GOAL_READY` event 하나로 해당 Goal만 `READY`로 바꾼다.

## `ARTIFACT_WORK`

생성 조건:

- Draft 산출물의 선행 입력이 준비됨
- Active 원장에 기록할 실제 사건이 생김
- Planned 증거의 실행 조건이 갖춰짐
- 조건부 산출물의 적용조건이 참이 됨

완료:

- artifact ID·revision·상태·근거·연결 요구사항·검토자를 기록
- 698개 선후관계 위반 없음
- Approved/Baselined 승격은 권한 있는 승인 receipt 없이는 금지
- 시험·배포·운영 사건이 없으면 결과 문서는 `Planned/NOT_RUN`

## `INTEGRATION_CANDIDATE`

생성 조건:

- 함께 검증할 앱·서버·Gateway·모델·설정·DB migration이 내부 준비됨

완료:

- 구성요소 버전·commit·파일 SHA-256·SBOM·provenance·설정·DB 세대를 한 manifest로 고정
- 혼합 세대와 임의 파일 교체를 거부
- 후보를 바꾸면 이전 후보와 결과를 보존하고 새 Goal을 생성

## `FORMAL_TEST_RUN`

생성 조건:

- 외부 attestation된 `TEST_PLAN_APPROVAL_RECEIPT`
- 적격 불변 후보
- 계획이 요구한 환경·기기·사람·안전조건

완료:

- 정확한 279개 TC ID와 승인 계획 snapshot
- 후보 SHA-256, 실행자·검토자·승인자, 환경·도구, 원자료 path·SHA·record count
- 적용 시험 `PASS`, 승인된 비적용 외 `FAIL/NOT_RUN=0`
- 내부 단위시험을 정식 실행으로 대체하지 않음

## `RELEASE_GATE`

각 Gate는 별도 Goal이다.

| Gate | 생성의 핵심 조건 |
|---|---|
| `GATE-SINGLE-ADMIN-RECOVERY-DRILL` | 관리자 인증·복구 구현과 실제 별도 복구수단 |
| `GATE-PHONE-QUEUE-BYTE-LIMIT` | queue 구현과 실제 지원 기기 |
| `GATE-SERVER-CAPACITY-STATE-CONTRACT` | 서버 상태·용량 계약과 측정 환경 |
| `GATE-RAW-COLLECTION-RELEASE-REVIEW` | 동의·원본수집·보존·삭제·권리행사 흐름 |
| `GATE-CLOUD-COST-MEASUREMENT` | 실제 저장·백업·복원 환경과 청구 단가 |

완료:

- 지정 측정·독립검토·복구훈련의 실제 원자료
- 실행자·검토자·승인자·권한 anchor
- 최종 후보 SHA-256 결속
- `status=CLOSED`, `waived=false`

## `RELEASE_DECISION`

생성 조건:

- 같은 후보의 정식 시험, 실제 기기 보고서, 5개 Gate가 모두 완료

완료:

- `TEST_PLAN_APPROVAL_RECEIPT`
- `FORMAL_TEST_REPORT`
- `ACTUAL_DEVICE_TEST_REPORT`
- `RELEASE_GATE_CLOSURE_RECEIPT`
- `RELEASE_ELIGIBILITY_APPROVAL`
- TST-22·REL-02 권한 있는 승인

다섯 receipt는 같은 후보와 승인된 시험계획에 결속하고 정확한 외부 attestation hash를 checker가 고정한다.

## `DEPLOYMENT_DELIVERY_EVENT`

생성 조건:

- `RELEASE_DECISION`이 `ELIGIBLE`
- 프로덕션 계정·비밀·서명·배포 권한을 사용자가 제공

완료 순서:

`deployment started → canary → smoke → deployment ended → technical delivery accepted`

`PHASE_C_TECHNICAL_DELIVERY_RECEIPT`라는 기존 canonical role 이름은 호환성을 위해 유지할 수 있지만 source Goal은 `DEPLOYMENT_DELIVERY_EVENT`여야 한다. 같은 승인 후보의 배포 bytes, rollback, 설명서·운영자료 수령을 실제 원자료로 결속한다.

## `OPERATION_EVENT`

생성 조건:

- 실제 운영·장애·복원·비용·권한검토·비밀회전·데이터 삭제 사건 발생

완료:

- 사건 시간, 담당자, 영향, 조치, 결과, 원자료
- 관련 Active 원장과 OPS/SEC 산출물 successor
- 일정·담당자만 정한 상태를 실행 완료로 처리하지 않음

운영 사건 수는 미리 정하지 않는다.

## `HANDOVER_CLOSURE_EVENT`

생성 조건:

- 기술 인도가 수락되고 운영책임 이관 또는 프로젝트 종료 조건이 실제로 생김

완료 순서:

`technical delivery accepted → handover checks/decisions → handover accepted → closure checks/decisions → closed`

`PHASE_D_OPERATION_HANDOVER_RECEIPT`와 `PHASE_D_PROJECT_CLOSURE_RECEIPT`라는 기존 canonical role 이름은 호환성을 위해 유지할 수 있다. 그러나 실제 서비스 소유자·접근권한·지원·복구·잔여위험·데이터 처리·계정 정리·최종 인수 증거가 없으면 완료하지 않는다.

## `BLOCKER_OR_EXTERNAL_RECEIPT`

생성 조건:

- 정책 결정, 실제 기기·참여자, 독립검토, 유료자원, 비밀정보, 외부 승인·서명이 필요함

처리:

- blocker ID·대상 Goal·condition code·owner·질문·`return_status`·snapshot hash 기록
- 원 상태가 `PLANNED`이면 `return_status=PLANNED`, `READY/IN_PROGRESS`면 안전한 재시작을 위해 `return_status=READY`
- 다른 ready branch가 있으면 계속 진행
- 해제는 원 blocker event·snapshot hash·owner에 맞는 typed receipt와 authority anchor로만 수행
- 해제 event는 봉인된 `return_status`로만 돌아가며 `PLANNED → READY`를 대신하지 않음
- 일정 또는 구두 합의만으로 해제하지 않음

## `SUCCESSOR_OR_REOPEN`

생성 조건:

- 정책 변경 승인
- 회귀 결함
- 증거 반려
- canonical input revision
- Goal 지시 교정

처리:

- 과거 완료 Goal과 증거를 보존
- `GOAL_SUPERSEDED` event 하나로 과거 Work Item을 `SUPERSEDED`, 같은 `work_item_id`의 연속 `r002+` successor를 `PLANNED`로 원자적으로 전환
- Master·Workstream은 대체하지 않고 Work Item revision만 대체
- 같은 `work_item_id`에서 대체되지 않은 활성 revision은 최대 하나
- trigger receipt가 무효화할 completion event SHA-256과 새 Goal을 결속
- 기존 DAG를 순환시키지 않음

## 공통 시간·증거 규칙

- 모든 event는 timezone 포함 시각과 hash-linked predecessor를 가진다.
- 실행은 Goal이 `IN_PROGRESS`가 된 event보다 먼저 시작할 수 없다.
- raw evidence 수집은 receipt 실행 구간 안이어야 한다.
- 완료·검토·승인·receipt 생성은 실제 시간 순서를 지킨다.
- `validation_cutoff_at` 뒤 사건을 같은 checkpoint revision에 미리 기록하지 않는다.
- 외부 권한 receipt는 `AUTHORITY_ROSTER`만 맞는 것으로 부족하며, 서명 또는 외부 anchor가 끝난 정확한 파일 SHA-256을 checker trust anchor에 고정한다.
