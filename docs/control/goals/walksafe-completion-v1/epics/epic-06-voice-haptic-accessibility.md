+++
schema_version = "1.0"
goal_id = "WS-GOAL-A-EPIC-06"
goal_kind = "EPIC"
document_version = "1.0.0"
phase_id = "A"
parent_goal_id = "WS-GOAL-PHASE-A"
sequence = 106
initial_status = "PLANNED"
source_status_at_creation = "PLANNED"
target_completion_level = "IMPLEMENTATION_READY"
next_goal_id = "WS-GOAL-A-EPIC-07"
return_goal_id = "WS-GOAL-PHASE-A"
work_item_id = ""
dependencies = ["WS-GOAL-A-EPIC-02"]
child_goal_ids = []
source_policy_ids = ["FP-025", "FP-027", "FP-029", "FP-028", "FP-030", "FP-026"]
gap_ids = ["GAP-034", "GAP-035", "GAP-036", "GAP-037", "GAP-038", "GAP-039"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# EPIC-06 Goal — 음성·진동·접근성

## 목표

오프라인 한국어 음성 입력·출력과 진동·TalkBack·시각 접근성을 보행 lifecycle에 연결해, 보행약자가 화면을 보지 못하거나 음성을 듣지 못해도 상태·위험·다음 행동을 이해하고 안전하게 멈출 수 있게 한다.

## 정본 입력

- 선행: EPIC-02
- 정책: FP-025·027·029·028·030·026
- Gap: GAP-034·036·038·037·039·035
- 직접 연결 정식 시험: 24개, 전부 `NOT_RUN`

## 범위와 제외

포함:

- “길라잡이” 호출어와 활성 보행 범위
- 단말내 오프라인 한국어 STT와 듣기 시작·끝 신호
- 오프라인 TTS 사전확인과 진동·화면 대체
- 접근 가능한 통합 안전정지
- TalkBack 읽는 순서·역할·상태 알림·포커스
- 큰 글자·대비·터치영역
- 승인 명령과 비가역 명령 확인

제외:

- 서버 STT 의존
- 실제 소음환경·목표사용자·TalkBack E2E 결과
- 음성·원음 보존정책 변경

## 단계별 실행

| 순서 | 정책 / Gap | r008 | 내부 구현 결과 |
|---:|---|---|---|
| 1 | FP-025 / GAP-034 | CONFLICTING | 활성 보행 호출어·오프라인 STT·신호·보존 lifecycle |
| 2 | FP-027 / GAP-036 | CONFLICTING | 보행 전 TTS 확인과 실패 시 화면·진동 후 안전정지 |
| 3 | FP-029 / GAP-038 | MISSING | 권한·장애 통합 안전정지와 종료·설정·재검사·명시 재개 |
| 4 | FP-028 / GAP-037 | PARTIAL | 가입부터 삭제요청까지 TalkBack 읽기·포커스·상태 알림 |
| 5 | FP-030 / GAP-039 | PARTIAL | 큰 글자·대비·터치영역·읽는 순서 |
| 6 | FP-026 / GAP-035 | PARTIAL | 승인 명령 상태전이·낮은 확실성 무실행·비가역 확인 |

## 검증

- 보행 외 호출어 무실행
- 정확한 확인어 외 재개·종료 없음
- TTS/STT 실패가 조용히 계속되지 않음
- TalkBack focus와 상태 announcement
- 색상만으로 상태를 전달하지 않음
- 음성·진동·화면의 우선순위와 중복 폭주 방지
- 실제 소음·기기·사용성 시험은 `NOT_RUN`

## 완료 기준

6개 정책의 내부 접근성·음성·진동 동작과 계약검사가 준비되고, 실제 사용자·소음·TalkBack 정식 검증은 EPIC-12에 이관된다.

## 질문·중단 조건

호출어·확인어·오프라인 원칙은 확정되어 있다. 새 유료 음성 서비스나 실제 참여자 시험이 필요할 때만 외부 조건으로 요청한다.

## 완료 후 인계

완료 뒤 [`epic-07-raw-data-lifecycle.md`](epic-07-raw-data-lifecycle.md)를 자동 시작한다.
