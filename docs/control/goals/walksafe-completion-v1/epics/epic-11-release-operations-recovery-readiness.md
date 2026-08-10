+++
schema_version = "1.0"
goal_id = "WS-GOAL-A-EPIC-11"
goal_kind = "EPIC"
document_version = "1.0.0"
phase_id = "A"
parent_goal_id = "WS-GOAL-PHASE-A"
sequence = 111
initial_status = "PLANNED"
source_status_at_creation = "PLANNED"
target_completion_level = "IMPLEMENTATION_READY"
next_goal_id = ""
return_goal_id = "WS-GOAL-PHASE-A"
work_item_id = ""
dependencies = ["WS-GOAL-A-EPIC-09", "WS-GOAL-A-EPIC-10"]
child_goal_ids = []
source_policy_ids = ["FP-051", "FP-053", "FP-052", "FP-054"]
gap_ids = ["GAP-060", "GAP-061", "GAP-062", "GAP-063"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# EPIC-11 Goal — Android 릴리스·운영·복구 구현 준비

## 목표

서명된 불변 릴리스 조합, 단계 배포·긴급 차단·rollback, 백업·비용·대시보드·알림·유지보수·서비스 종료 절차를 실제 실행 가능한 코드와 runbook으로 준비한다.

## 정본 입력

- 선행: EPIC-09·10
- 정책: FP-051·053·052·054
- Gap: GAP-060·062·061·063, 모두 `PARTIAL`
- 직접 연결 정식 시험: 28개, 전부 `NOT_RUN`

## 범위와 제외

포함:

- 앱·서버·모델·설정·DB 세대 release manifest와 서명
- 단계 배포·활성 보행 업데이트 연기·신규 보행 긴급 차단
- 호환 가능한 이전 정상 조합 rollback
- 주/백업 저장소 사용량·비용·35일 백업 순환
- 안전상태·전송·용량·외부서비스·버전 대시보드와 알림
- 신규 보행 차단·자료 migration·서비스 종료 절차

제외:

- 실제 production 배포·rollback·장애훈련
- 실제 월 비용·복원시험 결과
- 서비스 종료 실행과 인수 완료

## 단계별 실행

| 순서 | 정책 / Gap | 내부 구현 결과 |
|---:|---|---|
| 1 | FP-051 / GAP-060 | 불변 release manifest·단계 배포·업데이트 연기·긴급차단·rollback |
| 2 | FP-053 / GAP-062 | 용량·비용 job·70/85/95/100%·35일 백업·격리 복원 절차 |
| 3 | FP-052 / GAP-061 | 운영 대시보드·단계 알림·자동차단·호출·비상 runbook |
| 4 | FP-054 / GAP-063 | 안전종료·신규차단·migration·rollback·서비스 종료·자료 이관/삭제 |

## 검증

- 부분 rollout·혼합 세대 거부
- 활성 보행 중 업데이트 연기
- 신규 보행 차단과 기존 보행 안전종료
- backup manifest·복원 dry-run·rollback 순서
- 알림 기준과 runbook link
- 서비스 종료 계획과 실제 폐기 권한 분리
- 실제 production·복구훈련은 `NOT_RUN`

## 완료 기준

4개 정책의 릴리스·운영·복구 구현과 내부 dry-run/검증이 준비되고, 실제 환경 실행은 EPIC-12와 Phase C·D에 이관된다. 이 EPIC은 실제 릴리스나 운영 완료가 아니다.

## 질문·중단 조건

내부 manifest·runbook·dry-run은 자율 수행한다. 실제 서명키·생산계정·비용·배포·복원·종료가 필요한 순간에만 외부 권한을 요청한다.

## 완료 후 인계

EPIC-01~11의 exit를 종합 검증한다. 모두 `IMPLEMENTATION_READY`면 Phase A를 완료하고 [`../20-phase-b-formal-verification.md`](../20-phase-b-formal-verification.md)와 EPIC-12를 자동 시작한다.
