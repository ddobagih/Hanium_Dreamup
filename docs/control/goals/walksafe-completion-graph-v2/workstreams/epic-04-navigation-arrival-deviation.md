+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-04"
goal_kind = "WORKSTREAM"
document_version = "2.0.0"
parent_goal_id = "WS-GOAL-WALKSAFE-COMPLETION-GRAPH-V2"
external_key = "EPIC-04"
workstream_type = "IMPLEMENTATION"
priority_rank = 4
initial_status = "PLANNED"
source_status_at_creation = "PLANNED"
target_completion_level = "IMPLEMENTATION_READY"
work_item_id = ""
start_requires = ["WS-GOAL-EPIC-02"]
completion_requires = ["WS-GOAL-EPIC-02"]
child_goal_ids = []
source_policy_ids = ["FP-022", "FP-023", "NPC-NAVIGATION-ROUTE-DIRECTION", "FP-024"]
gap_ids = ["GAP-007", "GAP-031", "GAP-032", "GAP-033"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# EPIC-04 Goal — 경로·도착·이탈 사용자 결정 흐름

## 목표

TMAP 경로·GPS·진행방향·보폭의 책임을 분리하고, 도착과 경로 이탈 뒤 행동을 시스템이 멋대로 확정하지 않고 사용자 확인·선택으로 완결한다.

## 정본 입력

- 선행: EPIC-02
- 정책: FP-022·023, NPC-NAVIGATION-ROUTE-DIRECTION, FP-024
- Gap: GAP-031·032·007·033
- 직접 연결 정식 시험: 12개, 전부 `NOT_RUN`

## 범위와 제외

포함:

- 남은 거리·도착·이탈 계산 입력 책임
- GPS 불신 시 방향안내 중지
- 도착 사용자 확인
- 이탈 시 승인된 10단계 선택 흐름
- 점자블록 방향 보조와 손상 신고 분리

제외:

- 보폭만으로 좌표·진행방향·이탈 판정
- 자동 새 경로 요청
- 실제 현장 GPS·경로·점자블록 시험

## 실행 절차

| 순서 | 정책 / Gap | r008 | 내부 구현 결과 |
|---:|---|---|---|
| 1 | FP-022 / GAP-031 | CONFLICTING | GPS+저장 TMAP 기준, 보폭 보조, 도착 사용자 확인 |
| 2 | FP-023 / GAP-032 | CONFLICTING | 경로 암호화 보존과 이탈 10단계 선택 흐름 |
| 3 | NPC-NAVIGATION-ROUTE-DIRECTION / GAP-007 | CONFLICTING | 경로·위치·진행방향·보폭 책임과 fail-closed 분리 |
| 4 | FP-024 / GAP-033 | EVIDENCE_MISSING | 점자블록은 확실할 때만 방향 보조, 손상은 신고 후보로 분리 |

## 검증

- GPS 불신 시 보폭 대체판단·방향안내 없음
- 저장 경로와 GPS 거리로 이탈 판정
- 이탈 뒤 회전안내 중지와 사용자 선택 전 자동 경로요청 없음
- 도착은 경로 끝·GPS·진행량 확인 뒤 사용자 확인
- 불확실 점자블록 무안내

## 완료 기준

4개 정책의 내부 상태·계산·사용자 선택 흐름과 계약검사가 준비되고, 실기기·현장 정식 검증을 EPIC-12에 이관한다.

## 질문·중단 조건

FP-022 보폭 보조 역할과 FP-023 10단계는 확정되어 있으므로 다시 묻지 않는다. TMAP 계약·실제 키·실측 임계값이 불가피할 때만 외부 조건으로 처리한다.

## 완료 후 인계

완료 뒤 [`epic-05-object-detection-safety.md`](epic-05-object-detection-safety.md)를 자동 시작한다.
