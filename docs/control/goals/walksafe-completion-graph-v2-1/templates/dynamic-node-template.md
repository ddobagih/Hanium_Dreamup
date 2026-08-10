# Template Library — 조건부·사건 기반 Goal

이 문서는 미래 Goal 개수를 미리 고정하지 않고 실제 조건이 생길 때 정확한 Goal을 만드는 템플릿 라이브러리다. 모든 동적 Goal은 공통 front matter와 해당 유형 계약을 사용한다.

## 공통 front matter

```toml
+++
schema_version = "2.0"
goal_id = "{고유 Goal ID}"
goal_kind = "WORK_ITEM"
document_version = "2.1.0"
parent_goal_id = "{소유 Workstream 또는 Master Goal ID}"
work_item_type = "{아래 허용 유형}"
priority_rank = {정수}
initial_status = "PLANNED"
target_completion_level = "{유형별 값}"
work_item_id = "{고유 실행 ID}"
start_requires = ["{착수 hard dependency}"]
completion_requires = ["{위 start_requires 안에서 완료에도 필요한 Goal ID}"]
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
artifact_work_reason = "{DRAFT_COMPLETION|PLANNED_EVIDENCE|APPLICABILITY_DECISION|ACTIVE_EVENT_UPDATE 또는 비산출물 유형은 빈 문자열}"
artifact_trigger_evidence_refs = []
source_blocker_ids = []
# 비산출물 유형은 {}로 둔다. ARTIFACT_WORK는 아래 예시처럼 두 DLV-* 집합을
# 동일한 비어 있지 않은 값으로 바꾸고, 다른 정본 role도 생산하면 실제 행 ID를 추가한다.
output_subject_ids_by_role = {}
# ARTIFACT_WORK 예시: { ARTIFACT_REGISTER = ["DLV-DES-03"], ARTIFACT_CHANGE_LOG = ["DLV-DES-03"], MODULE_REGISTER = ["DES-03", "RQ-MODULE-001"] }
+++
```

모든 문서는 `목표 / 정본 입력 / 범위와 제외 / 실행 절차 / 검증 / 완료 기준 / 질문·중단 조건 / 완료 후 인계`를 포함한다.

이 템플릿은 package가 `ACTIVE`가 된 뒤에만 새 Goal 생성에 사용한다. 유일한 사전활성 bootstrap 예외는 이미 `PACKAGE_PREPARED`에 봉인된 FP-018 r001이다. Goal 생성은 실행 시작이 아니며 별도 `GOAL_STARTED` event 전에는 작업하지 않는다. 새 터미널에서 `IN_PROGRESS` 작업을 이어갈 때는 새 전체 gate와 `WORK_SESSION_RESUMED` event가 먼저 필요하다.

Work Item의 `completion_requires`는 `start_requires`의 부분집합이며 보통 둘을 같게 둔다. 병행 실행 뒤 완료만 막는 completion-only 의존은 Work Item이 아니라 Workstream 컨테이너에 둔다. Work Item 의존 대상은 자기 Workstream, 그 전이 upstream Workstream 또는 Master 범위 안에 있어야 하고, 활성 Goal은 `SUPERSEDED` revision을 가리키지 않는다.

생성 시점의 `materialized_from_*`는 그때 유효한 canonical binding snapshot과 일치해야 한다. 이후 정본 revision이 바뀌면 role 이름만 보지 않고 실제 변경 정책·Gap ID와 전역 규칙 변화를 계산한다. 영향받은 미완료 Work Item은 원 update SHA-256에 결속한 `GOAL_SUPERSEDED`로 교체하고, 영향받은 완료 Work Item은 독립 `UNAFFECTED` 근거나 successor가 필요하다. superseded Work Item을 선행조건으로 가진 계획 상태 Work Item도 successor로 바꿔 의존 간선을 최신 revision에 결속한다. 검사기는 부모와 `start_requires·completion_requires` 역의존 폐쇄도 계산한다. 완료 Workstream은 직접 영향 또는 child·completion 집계 갱신만 필요하면 과거 완료 event와 증거를 유지한 채 같은 canonical update에서 `REOPEN_CONTAINER`와 `READY`로, start dependency가 무효화됐으면 `REOPEN_CONTAINER`와 `PLANNED`로 처분한다. 후자는 upstream 재완료 뒤 별도 `GOAL_READY`가 필요하다. 선행조건이 깨진 준비 Goal도 `PLANNED`로 되돌린다. 재완료에는 최신 dependency·child 완료 event 뒤에 생성된 `WORKSTREAM_REVALIDATION::<goal_id>`가 필요하다. 완료 Master와 `PACKAGE_COMPLETED` 뒤에는 event를 추가하지 않는다. `predecessor_goal_id`는 생성 계보를 남기는 provenance 포인터이며 hard dependency가 아니다. 준비 조건은 `start_requires`와 blocker만 결정한다. `PLANNED` Goal의 선행조건이 충족되면 의존 Goal별 완료 event SHA-256을 가진 `GOAL_READY` event 하나로 해당 Goal만 `READY`로 바꾼다.

유형별 부모·착수 계약은 정적 manifest가 정한다. 정책/Gap은 구현 Workstream, 정식 시험·Gate·출시 결정은 EPIC-12, 통합 후보는 EPIC-11 또는 Master, 배포·운영·이관은 Master 아래에 둔다. 정식·외부 유형의 `start_requires`에는 manifest가 요구한 선행 Work Item 유형과 수를 모두 넣는다. 출시 결정은 정확한 5개 Gate ID를 각각 가진 `RELEASE_GATE` Goal을 모두 요구한다.

`FORMAL_TEST_RUN`, `RELEASE_GATE`, `RELEASE_DECISION`, `DEPLOYMENT_DELIVERY_EVENT`, `OPERATION_EVENT`, `HANDOVER_CLOSURE_EVENT`는 다음 두 전환마다 manifest의 `required_start_evidence_roles`를 확인한다.

1. `GOAL_READY`: 그 시점 canonical binding의 path·document ID·파일 SHA-256·상태와 같은 후보 결속을 `start_evidence_bindings`에 봉인
2. `GOAL_STARTED`: 실행 직전 같은 검사를 다시 하고, 외부 receipt는 checker의 attestation trust anchor가 없으면 시작을 거부

모든 제품 구현 Work Item은 위 유형별 증거와 별도로 manifest가 고정한 전체 구현 시작 gate를 통과한다. 최초 시작은 `GOAL_STARTED`, 새 작업 세션 재개는 상태를 바꾸지 않는 `WORK_SESSION_RESUMED`에 새 receipt·실행 전 저장소 snapshot·event 전용 원출력을 결속한다.

Gate 원출력은 저장소 밖 임시 디렉터리에서 모두 성공한 뒤 event 전용 경로로 add-only 반영한다. checkpoint와 그 event의 gate 파일은 실행 전 working snapshot 내용 hash에서 제외하고 receipt와 event가 각 파일 hash를 직접 결속한다.

후속 `CANONICAL_BINDINGS_UPDATED`가 같은 role을 새 revision으로 바꿔도 과거 READY/STARTED 사건은 당시 snapshot으로 재검증한다. 내부 생산자는 Work Item 유형별 allowlist를 따르고 완료 receipt와 update 직후 완료 event를 한 후보에 넣는다. typed 완료에 필요한 증거 role은 정확한 canonical role 이름을 각각 한 번 참조해야 하며 접두어·접미어가 비슷한 별칭은 인정하지 않는다. 승인 정책 안의 내부 기술·관리 문서 변경은 실제 전후 hash·검증 원출력·서로 다른 실행자와 검토자·`DELEGATED_INTERNAL_DOCUMENT_APPROVAL` event 선언이 모두 있을 때만 위임 승인한다. 정책 기준선, 규범·안전·개인정보, `NOT_RUN/PASS`, blocker·면제·적용성, Gate·출시·인수·이관·종료와 외부서명 주장은 외부 authorization 없이는 적용하지 않는다. 외부 결정용 authority roster 회전은 predecessor·sequence·effective time을 단조롭게 잇는다. 필요한 receipt가 없거나 권한·후보가 맞지 않으면 Goal을 시작하지 않고 blocker로 기록한다.

`BLOCKER_OR_EXTERNAL_RECEIPT`만 `source_blocker_ids`를 비어 있지 않게 사용한다. 생성 event는 각 ID의 당시 활성 blocker 전체 record와 snapshot hash를 `source_blocker_bindings`에 봉인하며, READY/STARTED 때도 같은 blocker의 append-only 생명주기가 존재해야 한다. 다른 유형은 이 필드를 빈 배열로 둔다.

`ARTIFACT_WORK`는 `ARTIFACT_REGISTER` SHA에 결속된 `artifact_work_queue`에서 materialize하고, `output_subject_ids_by_role`의 `ARTIFACT_REGISTER`·`ARTIFACT_CHANGE_LOG`에 같은 단일 `DLV-*` 대상을 고정한다. 같은 대상의 live Goal은 하나뿐이다. `artifact_work_reason`은 네 허용값 중 하나이며 사건 기반 갱신은 실제 `artifact_trigger_evidence_refs`가 필요하다. 일반 작업은 `ACTIVE`, `APPLICABILITY_DECISION`만 `PENDING_EVALUATION` 대상을 허용한다. 함께 생산하는 요구·설계·모듈·시험 role에는 실제 행 ID 범위를 추가한다. `CANONICAL_BINDINGS_UPDATED`의 생산자 출력 범위, 실제 register delta, append된 change-log affected 범위가 생성 시 선언과 정확히 같아야 하므로 실행 중 범위를 조용히 넓힐 수 없다. successor revision도 reason·trigger·`source_blocker_ids`·`output_subject_ids_by_role`을 포함한 의미 범위를 바꿀 수 없으며, 대상이나 출력 범위가 달라지면 새 Work Item으로 만든다.

## `ARTIFACT_WORK`

생성 조건:

- Draft 산출물의 선행 입력이 준비됨
- Active 원장에 기록할 실제 사건이 생김
- Planned 증거의 실행 조건이 갖춰짐
- 조건부 산출물의 적용성을 결정할 근거가 생김
- queue의 같은 대상에 live Goal이 없고 필요한 upstream이 종결됨

완료:

- artifact ID·revision·상태·근거·연결 요구사항·검토자를 register와 append-only change log에 같은 `DLV-*` 집합으로 기록
- change log 새 행에 고유 ID·날짜·사유·전후 요약·실제 대상 경로·생산 Goal 지문·같은 revision의 register binding을 기록
- 두 원장의 `content_sha256` self-seal을 갱신하고 검증
- 구현기록에는 실제 변경 경로·전후 hash, 검증결과에는 명령·exit code 0·원출력 hash, successor trace에는 resulting canonical binding을 기록
- 실행자와 다른 검토자의 `INDEPENDENT_INTERNAL_REVIEW` record로 세 결과 hash를 결속
- 698개 선후관계 위반 없음
- 승인 정책 안의 내부 기술·관리 문서 승격은 위임된 내부 승인과 독립 review record로 처리하고, 규범·안전·개인정보·Gate·출시·인수·이관·종료 승격은 권한 있는 외부 receipt 없이는 금지
- 시험·배포·운영 사건이 없으면 결과 문서는 `Planned/NOT_RUN`

## `INTEGRATION_CANDIDATE`

생성 조건:

- EPIC-11 아래에서는 EPIC-02~10 완료, EPIC-11의 모든 정책/Gap Work Item 완료, 후보 작성용 `ARTIFACT_WORK` 완료
- Master 아래에서는 EPIC-02~11 완료와 후보 작성용 `ARTIFACT_WORK` 완료
- 함께 검증할 앱·서버·Gateway·모델·설정·DB migration이 내부 준비됨

완료:

- `ANDROID_APP`, `BACKEND`, `ANDROID_GATEWAY`, `ON_DEVICE_MODEL`, `CONFIGURATION`, `DATABASE_MIGRATION` 여섯 kind를 각각 정확히 한 구성요소의 이름·버전·경로·SHA-256으로 고정
- source commit, 구성요소 집합 hash, 설정 세대, DB 세대, 실제 패키지 목록이 있는 SBOM, 같은 commit·구성요소 hash를 가진 build provenance를 한 manifest에 결속
- 혼합 세대와 임의 파일 교체를 거부
- 후보를 바꾸면 이전 후보와 결과를 보존하고 새 Goal을 생성

## `FORMAL_TEST_RUN`

생성 조건:

- 외부 attestation된 `TEST_PLAN_APPROVAL_RECEIPT`
- 완료된 `INTEGRATION_CANDIDATE`와 그 불변 manifest
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
- `start_requires`가 `INTEGRATION_CANDIDATE` 1개 이상, `FORMAL_TEST_RUN` 1개 이상, 서로 다른 5개 Gate ID의 `RELEASE_GATE`를 포함

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
- 완료된 `RELEASE_DECISION`을 `start_requires`에 포함하고 같은 `INTEGRATION_CANDIDATE_MANIFEST`를 사용
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

- 기술 인도가 수락되고, 그 배포를 선행으로 한 실제 `OPERATION_EVENT`가 적어도 하나 완료된 뒤 운영책임 이관 또는 프로젝트 종료 조건이 실제로 생김

완료 순서:

`technical delivery accepted → operation event recorded → handover checks/decisions → handover accepted → closure checks/decisions → closed`

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

## Successor·reopen 전환 패턴

이 절은 새 Work Item 유형이 아니다. 아래 조건이 생기면 기존 Work Item과 같은 `work_item_type`을 유지한 연속 revision을 만든다.

적용 조건:

- 정책 변경 승인
- 회귀 결함
- 증거 반려
- canonical input revision
- Goal 지시 교정

회귀 결함·증거 반려·지시 교정은 먼저 Gap·Backlog successor에 기록해 canonical impact transaction으로 만든다. standalone receipt만으로 `GOAL_SUPERSEDED`를 만들 수 없다. 정적 구조가 바뀌거나 현재 패키지가 종결된 경우에는 versioned successor package를 사용한다.

처리:

- 과거 Work Item과 이미 생긴 증거를 보존
- `GOAL_SUPERSEDED` event 하나로 완료 또는 실행 중인 Work Item을 `SUPERSEDED`, 같은 `work_item_id`의 연속 `r002+` successor를 `PLANNED`로 원자적으로 전환
- 완료 Workstream 영향은 역의존 폐쇄와 함께 같은 canonical update에서 원자 적용한다. 직접 영향 또는 child·completion 집계 갱신만 필요하면 `READY`, start dependency가 무효화됐으면 `PLANNED`로 바꾸고 후자는 upstream 재완료 뒤 `GOAL_READY`를 요구하며, 재완료는 최신 dependency·child 완료 뒤의 새 집계 검증에 결속하고 완료 Master 뒤에는 새 event를 금지
- 정책/Gap 소유·Workstream·정적 dependency·우선순위·목표수준 변경은 같은 패키지 reopen이 아니라 versioned successor manifest 사용
- 같은 `work_item_id`에서 대체되지 않은 활성 revision은 최대 하나
- 원인이 된 canonical update가 무효화할 completion event SHA-256, 역의존 successor와 새 Goal을 함께 결속
- 기존 DAG를 순환시키지 않음

## 공통 시간·증거 규칙

- 모든 event는 timezone 포함 시각과 hash-linked predecessor를 가진다.
- 실행은 Goal이 `IN_PROGRESS`가 된 event보다 먼저 시작할 수 없다.
- 정식·외부 실행 receipt의 실행 시작은 해당 `GOAL_STARTED` 이후이고 생성·승인은 `GOAL_COMPLETED` 이전이어야 하며, 시작 때 고정한 candidate와 완료 receipt candidate가 같아야 한다.
- raw evidence 수집은 receipt 실행 구간 안이어야 한다.
- 완료·검토·승인·receipt 생성은 실제 시간 순서를 지킨다.
- `validation_cutoff_at` 뒤 사건을 같은 checkpoint revision에 미리 기록하지 않는다.
- 외부 권한 receipt는 `AUTHORITY_ROSTER`만 맞는 것으로 부족하며, 서명 또는 외부 anchor가 끝난 정확한 파일 SHA-256을 checker의 role별 append-only trust-anchor history에 추가한다. 새 revision을 추가해도 과거 hash를 제거하지 않는다.
