+++
schema_version = "1.0"
goal_id = "WS-GOAL-A-EPIC-10"
goal_kind = "EPIC"
document_version = "1.0.0"
phase_id = "A"
parent_goal_id = "WS-GOAL-PHASE-A"
sequence = 109
initial_status = "PLANNED"
source_status_at_creation = "PLANNED"
target_completion_level = "IMPLEMENTATION_READY"
next_goal_id = "WS-GOAL-A-EPIC-09"
return_goal_id = "WS-GOAL-PHASE-A"
work_item_id = ""
dependencies = ["WS-GOAL-A-EPIC-05", "WS-GOAL-A-EPIC-07"]
child_goal_ids = []
source_policy_ids = ["FP-037", "FP-039", "FP-038"]
gap_ids = ["GAP-046", "GAP-047", "GAP-048"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# EPIC-10 Goal — AI 모델 수명주기

## 목표

후보 모델과 정식 승인 모델을 분리하고, 데이터 출처·동의·분할·평가부터 앱·모델·탐지대상·threshold·설정의 서명된 배포 조합과 문제 발생 시 이전 정상 조합으로 되돌리기(rollback)까지 하나의 추적 가능한 수명주기로 만든다.

## 정본 입력

- 선행: EPIC-05·07
- 정책: FP-037·039·038
- Gap: GAP-046 `CONFLICTING`, GAP-048 `CONFLICTING`, GAP-047 `PARTIAL`
- 직접 연결 정식 시험: 17개, 전부 `NOT_RUN`

## 범위와 제외

포함:

- 후보/승인 모델 registry와 사용 경계
- 모델·class·threshold·pre/postprocess·앱 설정 manifest
- 이전 정상 조합으로 원자 rollback
- 데이터 출처·동의·hash·분할·독립시험셋·철회 제거
- 학습/평가/변환/배포 모델 동등성 추적

제외:

- 후보 모델을 정식 위험안내·자동신고·기관반출에 사용
- host smoke를 모바일 동등성·성능으로 승격
- 실제 재학습·독립평가·실폰 성능 완료 주장

## 단계별 실행

| 순서 | 정책 / Gap | 내부 구현 결과 |
|---:|---|---|
| 1 | FP-037 / GAP-046 | 후보는 통제 시연만, 승인 모델·앱·설정 묶음만 정식 기능 |
| 2 | FP-039 / GAP-048 | 서명 구성 manifest와 데이터 손상 없는 이전 정상 조합 rollback |
| 3 | FP-038 / GAP-047 | 출처·동의·hash·촬영순서 비누수 분할·잠긴 시험셋·철회 제거 |

## 검증

- 미승인 모델·class·threshold의 정식 runtime 차단
- manifest 불일치·부분 rollout·중간상태 거부
- rollback 조합의 schema·DB·앱 호환성
- 데이터 split 누수와 source hash 검증
- PT↔TFLite 출력 동등성 계획 및 host 내부 검사
- 실기기 FPS·강건성·독립평가는 `NOT_RUN`

## 완료 기준

세 정책의 모델·데이터·manifest·rollback 내부 구현과 추적자료가 준비되고, 실제 평가·동등성·기기성능·독립검토는 EPIC-12에 이관된다.

## 질문·중단 조건

후보와 승인 모델의 경계는 다시 묻지 않는다. 실제 GPU 비용·외부 데이터 권한·독립 평가자·배포 승인 모델 선택이 필요할 때만 외부 조건으로 요청한다.

## 완료 후 인계

다음 Goal은 [`epic-09-server-capacity-resilience.md`](epic-09-server-capacity-resilience.md)다.
