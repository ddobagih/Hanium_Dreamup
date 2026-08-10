+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-05"
goal_kind = "WORKSTREAM"
document_version = "2.1.0"
parent_goal_id = "WS-GOAL-WALKSAFE-COMPLETION-GRAPH-V2"
external_key = "EPIC-05"
workstream_type = "IMPLEMENTATION"
priority_rank = 5
initial_status = "PLANNED"
source_status_at_creation = "PLANNED"
target_completion_level = "IMPLEMENTATION_READY"
work_item_id = ""
start_requires = ["WS-GOAL-EPIC-02"]
completion_requires = ["WS-GOAL-EPIC-02"]
child_goal_ids = []
source_policy_ids = ["FP-019", "FP-020", "FP-021"]
gap_ids = ["GAP-028", "GAP-029", "GAP-030"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# EPIC-05 Goal — 객체탐지·위험안내 안전성

## 목표

승인된 모델·탐지대상·판단 기준만 사용하고, 영상 품질·거리·연결 확실성이 부족할 때 추측성 안내를 하지 않으며 반복 실패 시 보행기능을 안전정지한다.

## 정본 입력

- 선행: EPIC-02
- 정책: FP-019·020·021
- Gap: GAP-028·029·030, 모두 `CONFLICTING`
- 직접 연결 정식 시험: 23개, 전부 `NOT_RUN`

## 범위와 제외

포함:

- 승인 모델·탐지대상·승인된 탐지 기준값 목록(threshold allowlist)
- 관측번호와 탐지·거리 연결 확실성
- STOP/WARNING 문구·진동·무안내 계약
- 밝기·가림·흔들림·카메라각 품질 관문
- 탐지대상별 거리·신뢰도 관문과 반복 실패 안전정지

제외:

- 후보 모델을 정식 기능에서 사용
- 불확실 좌우 추정 안내
- 실제 기기 거리 제한모드 승인과 현장 오탐·미탐 평가

## 실행 절차

| 순서 | 정책 / Gap | 내부 구현 결과 |
|---:|---|---|
| 1 | FP-019 / GAP-028 | 승인 모델·대상·기준값만 활성화하고 관측·연결 추적과 반복 실패 안전정지 |
| 2 | FP-020 / GAP-029 | “멈추세요. 주변을 확인하세요.”, WARNING 고유 진동, 불확실 무안내, 좌우 추정 금지 |
| 3 | FP-021 / GAP-030 | 영상 품질과 대상별 거리·신뢰도 관문, 미지원 제한모드 fail-closed |

## 검증

- 미승인 모델·class·threshold 활성화 거부
- frame/detection/depth generation 불일치 거부
- STOP/WARNING/무안내 경계 계약
- 품질·거리 정보 부재 시 위험한 방향 지시 없음
- 반복 실패가 중앙 안전상태로 전달됨
- 모델·host 내부 테스트를 실기기·정식 결과로 승격하지 않음

## 완료 기준

3개 정책의 runtime 경계와 내부 검증·추적이 준비되고, 실제 기기 성능·오탐/미탐·거리 관문 증거는 EPIC-12에 이관된다.

## 질문·중단 조건

정책 문구와 안전 우선순위는 다시 묻지 않는다. 실제 임계값을 공식 지원 기기 측정 없이 정해야 한다면 보수적 비활성 기본값을 유지하고 실제 기기 측정 Goal로 넘긴다.

## 완료 후 인계

완료 뒤 새로 충족된 `PLANNED` Goal을 `GOAL_READY`로 전환하고 ready frontier를 다시 계산한다. 특정 Workstream을 자동 시작하지 않으며 선택 규칙이 다음 leaf를 결정한다.
