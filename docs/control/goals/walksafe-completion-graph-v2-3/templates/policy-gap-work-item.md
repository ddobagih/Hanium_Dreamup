# Template — 정책·Gap Work Item Goal

이 파일은 Goal 문서가 아니라 `POLICY_GAP_WORK`를 만들 때 사용하는 프롬프트 템플릿이다. 중괄호 값을 실제 정본에서 채운 새 Markdown 파일을 만든다.

```toml
+++
schema_version = "2.0"
goal_id = "{WS-GOAL-...-R001}"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "{소유 Workstream Goal ID}"
work_item_type = "POLICY_GAP_WORK"
priority_rank = {canonical next_action_sequence order}
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "{EPIC-NN-...}"
start_requires = ["{실제 hard dependency Goal ID}"]
completion_requires = ["{위 start_requires 안에서 완료에도 필요한 Goal ID}"]
child_goal_ids = []
source_policy_ids = ["{정확히 한 정책 ID}"]
gap_ids = ["{canonical IMPLEMENTATION_GAP에서 매핑한 정확히 한 Gap ID}"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "{최신 Backlog 경로}"
materialized_from_document_id = "{최신 Backlog ID}"
materialized_from_sha256 = "{최신 Backlog SHA-256}"
predecessor_goal_id = "{생성 직전 focus 또는 같은 작업의 직전 revision 계보 포인터}"
predecessor_goal_content_sha256 = "{직전 Goal SHA-256}"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++
```

이 템플릿은 package가 `ACTIVE`가 된 뒤에만 새 Goal 생성에 사용한다. 유일한 사전활성 bootstrap 예외는 이미 봉인된 FP-018 r001이다. `predecessor_goal_id`는 provenance 계보 포인터이며 hard dependency가 아니고, 준비 조건은 `start_requires`와 blocker만 결정한다. Goal 생성 뒤 별도 `GOAL_STARTED` event 전에는 실행하지 않는다. 새 터미널에서 `IN_PROGRESS` 작업을 이어갈 때는 새 전체 gate와 `WORK_SESSION_RESUMED` event를 먼저 남긴다.

Work Item의 `completion_requires`는 `start_requires`의 부분집합이며 보통 둘을 같게 둔다. 병행 실행 뒤 완료만 막는 completion-only 의존은 Workstream 컨테이너에 둔다. 의존 대상은 자기 Workstream, 전이 upstream Workstream 또는 Master 범위 안에 있어야 하며 활성 Goal은 `SUPERSEDED` revision을 가리키지 않는다.

## 목표

- 정책 문장을 비전공자도 이해할 수 있는 실제 동작으로 다시 적는다.
- 최신 Gap의 반대 동작·누락·부분 구현을 구체적으로 적는다.
- 이 작업에서 달성할 내부 완료수준과 남길 정식·외부 증거를 분리한다.

## 정본 입력

- 정책 ID와 정확한 원문 위치
- 요구사항·인수조건·설계·API·데이터·화면 연결
- canonical `IMPLEMENTATION_GAP.assessments`의 유일한 정책→Gap 연결
- 최신 Backlog의 작업 ID·행동·우선순위
- 예정 시험 ID와 현재 `NOT_RUN/PARTIAL` 상태
- 관련 코드·기존 내부 검증·과거 successor

정책과 Gap은 한 Work Item에 각각 정확히 하나만 둔다. 여러 정책을 묶어 한 번에 완료하지 않는다.

## 범위와 제외

- 포함할 코드·설정·문서·내부검증을 적는다.
- 실제 기기·사람·독립검토·프로덕션·승인처럼 이 Work Item이 대체할 수 없는 것을 적는다.
- 조건부 산출물이 활성화되는지 적는다.

## 실행 절차

1. 현재 bytes에서 정책과 반대되는 동작을 재현하거나 이미 맞는지를 증명한다.
2. 정책→요구→설계→코드→시험 차이를 표로 만든다.
3. fail-first 테스트나 정적 검사를 추가한다.
4. 승인 정책을 만족하는 최소 변경을 구현하고 실제 변경 hash를 가진 구현기록을 확정한다.
5. targeted·구성요소·회귀 검증을 실행하고 명령·exit code·원출력 hash를 가진 검증결과를 확정한다.
6. Draft·Active 산출물과 Gap·Backlog successor bytes를 실제 사실에 맞게 확정한다. 새 Gap evidence catalog에는 구현기록·검증결과 두 결과만 exact hash로 넣는다.
7. 새 Gap·Backlog hash가 정해진 뒤 resulting binding을 가진 post-update successor trace를 별도 JSON으로 만든다.
8. 세 결과를 대상으로 독립 검토를 수행하고 완료 receipt에 Goal·가장 최근 실행 세션 event(`GOAL_STARTED` 또는 `WORK_SESSION_RESUMED`)·시간·review를 결속한다.
9. Gap·Backlog canonical binding이 바뀌면 `CANONICAL_BINDINGS_UPDATED`에 새 snapshot과 완료 receipt를 먼저 봉인한다.
10. 바로 다음 `GOAL_COMPLETED`가 같은 증거·receipt binding을 가리키는지 staged checkpoint에서 사전검사한다.
11. 한 번의 논리적 commit 후 다음 Work Item이 필요하면 `GOAL_MATERIALIZED → GOAL_READY` 순서로 추가하고 ready frontier를 다시 계산한다.

## 검증

- 변경 전 재현 또는 기존 합격 근거
- 변경 후 targeted·관련 회귀
- 정책·REQ·DES·TC·산출물 추적
- 정식 시험·Gate·출시 상태 비승격
- 사용자 변경과 안전한 병합
- 새 Gap·Backlog successor와 다음 초점
- 직접 소유한 Gap row가 실제로 바뀌고 내부 근거와 row 지문을 가진 `PARTIAL`, `EVIDENCE_MISSING`, `IMPLEMENTED` 중 하나인지 확인
- 모든 Gap 근거 ID가 유일한 evidence catalog 행과 실제 파일 지문에 연결되고, 최소 한 행은 이번 Goal·완료 receipt·구현기록 또는 검증 원출력에 직접 연결되는지 확인
- Backlog의 부모 EPIC 상태와 다음 행동이 완료된 정책/Gap Work Item 집합과 dependency 순서에서 다시 계산되는지 확인

## 완료 기준

`WORK_ITEM_COMPLETION::<goal_id>` binding에 정확히 하나의 `WORK_ITEM_EXECUTION_RECEIPT`가 있어야 한다. receipt는 다음을 결속한다.

- Goal ID·Goal 파일 SHA-256·Work Item ID
- 정책 ID·Gap ID·목표 완료수준
- 가장 최근 실행 세션 event SHA-256
- 실행·완료·검토·생성 시간
- 서로 다른 경로의 `IMPLEMENTATION_RECORD`, `VERIFICATION_RESULT`, `SUCCESSOR_TRACE`
- 새 Gap 내부 `evidence_catalog`에는 `IMPLEMENTATION_RECORD`와 `VERIFICATION_RESULT`만 exact hash로 기록하고, 새 Gap·Backlog hash가 필요한 post-update `SUCCESSOR_TRACE`는 canonical update와 completion receipt에서 별도로 결속해 hash 자기순환을 만들지 않는다.

`GOAL_COMPLETED` event는 위 receipt의 canonical binding snapshot과 같은 완료 증거 목록을 가리켜야 한다. 세 결과는 같은 Goal ID·실행 구간과 `status=PASS`를 가져야 한다. source plan이나 문서 작성만으로 완료하지 않는다.

증거와 전환 순서는 `IMPLEMENTATION_RECORD → VERIFICATION_RESULT → 새 Gap·Backlog bytes 확정 → post-update SUCCESSOR_TRACE → 독립 review → WORK_ITEM_EXECUTION_RECEIPT → CANONICAL_BINDINGS_UPDATED → GOAL_COMPLETED`로 고정한다. 다음 정책 Work Item이 필요하면 그 뒤에만 `GOAL_MATERIALIZED → GOAL_READY`를 추가한다. 새 Gap·Backlog hash를 모르는 시점에 successor trace를 미리 만들거나, `GOAL_COMPLETED` 뒤 canonical binding을 바꾸거나, 준비되지 않은 다음 Goal을 바로 시작하지 않는다.

이 내부 완료수준은 정식 시험 완료와 다르다. 구현 후에도 정식 증거가 없으면 Gap은 `PARTIAL` 또는 `EVIDENCE_MISSING`일 수 있지만, 핵심 구현이 여전히 없거나 승인 정책과 충돌하거나 외부 Gate가 막힌 `MISSING`, `CONFLICTING`, `BLOCKED` 상태로는 이 Work Item을 완료하지 않는다. Backlog의 다른 EPIC 진행상태를 함께 바꿀 수 없고, 현재 부모 EPIC은 모든 소유 정책/Gap Work Item이 완료된 경우에만 `IMPLEMENTATION_READY`, 그 전에는 `IN_PROGRESS`로 기록한다.

## 질문·중단 조건

정책을 다시 묻지 않는다. 정책 충돌이나 새 사용자 결정이 필요하면 사용자 질문 queue로, 외부 권한·기기·사람·비용·비밀값·receipt가 필요하면 외부 action/evidence queue로 분리한다. 새 요청은 사건 직전 결정적 초점만 blocker로 바꾸며 canonical logical request key를 사용한다. 다른 저장소 내부 ready branch가 있으면 활성 요청이 그 branch를 선점하지 않도록 계속 진행하고 USER 질문 전달은 내부 frontier 소진까지 유예한다. 내부 구현이 끝나고 외부 근거만 남으면 정책/Gap leaf를 과장해 완료하지 말고 typed 외부 branch와 `COMPLETE_AWAITING_EXTERNAL` 경계로 이관한다.

## 완료 후 인계

- 과거 파일을 덮어쓰지 않고 successor를 만든다.
- `focus_goal_id`, `ready_frontier_goal_ids`, `blocked_goal_ids`, 선택 근거를 갱신한다.
- 독립 branch 이동은 정상 ready 선택이며 고정 순서 우회로 기록하지 않는다.
- 완료 event·파일 hash·daylog·local-memory를 갱신한 뒤 다음 ready leaf를 진행한다.
