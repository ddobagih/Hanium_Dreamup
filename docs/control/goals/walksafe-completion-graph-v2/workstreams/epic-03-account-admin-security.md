+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-03"
goal_kind = "WORKSTREAM"
document_version = "2.0.0"
parent_goal_id = "WS-GOAL-WALKSAFE-COMPLETION-GRAPH-V2"
external_key = "EPIC-03"
workstream_type = "IMPLEMENTATION"
priority_rank = 3
initial_status = "READY"
source_status_at_creation = "PLANNED"
target_completion_level = "IMPLEMENTATION_READY"
work_item_id = ""
start_requires = ["WS-GOAL-EPIC-01"]
completion_requires = ["WS-GOAL-EPIC-01"]
child_goal_ids = []
source_policy_ids = ["FP-047", "FP-008", "FP-046", "NPC-SINGLE-ADMIN-RECOVERY", "FP-048"]
gap_ids = ["GAP-008", "GAP-017", "GAP-055", "GAP-056", "GAP-057"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# EPIC-03 Goal — 계정·관리자 앱·보안

## 목표

일반 사용자와 관리자의 앱·역할·세션·추가 인증을 분리하고, 개인정보 권리·삭제·암호화·단일 관리자 복구를 실제 구현 구조에 연결한다.

## 정본 입력

- 선행: EPIC-01
- 정책: FP-047·008·046, NPC-SINGLE-ADMIN-RECOVERY, FP-048
- Gap: GAP-056·017·055·008·057
- 직접 연결 정식 시험: 24개, 전부 `NOT_RUN`

## 범위와 제외

포함:

- 계정 기반 사용자·관리자 역할검사와 주기적으로 교체되는 로그인 정보(회전 세션)
- 별도 Android 관리자 앱
- 개인정보 열람·동의·철회·삭제 연계
- 패스키/MFA·복구수단·고위험 재인증·세션 폐기·동결
- 단말·서버·DB·백업 암호화와 키 분리·회전·감사

제외:

- 실제 관리자 기기 등록과 분실 복구훈련
- 실제 TLS·키 보관 운영과 독립 보안검토
- gate와 정식 시험 PASS

## 실행 절차

| 순서 | 정책 / Gap | r008 | 내부 구현 결과 |
|---:|---|---|---|
| 1 | FP-047 / GAP-056 | CONFLICTING | 일반 사용자 회전 세션과 관리자 추가 인증·고위험 재확인·감사 통합 |
| 2 | FP-008 / GAP-017 | MISSING | 별도 앱 ID·서명·세션의 Android 관리자 앱과 검수·기관전달 흐름 |
| 3 | FP-046 / GAP-055 | MISSING | 권리·삭제 요청, 저장소별 연계·부분실패 재시도·삭제영수증 |
| 4 | NPC-SINGLE-ADMIN-RECOVERY / GAP-008 | MISSING | 별도 복구수단·세션폐기·고위험 작업 동결·감사 |
| 5 | FP-048 / GAP-057 | PARTIAL | 저장 위치별 암호화와 키 분리·회전·감사 |

## 검증

- 사용자 앱과 관리자 앱 ID·서명·권한·세션 분리
- 역할 우회·재사용 refresh token·원격폐기 경쟁
- 삭제 부분실패·재시도·백업 복원 뒤 삭제표식 재적용
- 복구수단이 같은 휴대전화에만 남지 않는 구성 경계
- 고위험 작업의 재인증·동결·감사
- 내부 보안·계약·회귀검사

## 완료 기준

5개 정책의 코드·인터페이스·내부 인수조건과 추적자료가 준비되고, 실제 기기·독립검토·복구훈련은 EPIC-12에 이관된다.

## 질문·중단 조건

구현 방식은 자율 결정한다. 실제 패스키 공급자·운영 자격정보·서명키·복구수단을 만들거나 사용할 때만 외부 권한을 요청한다.

## 완료 후 인계

완료 뒤 [`epic-04-navigation-arrival-deviation.md`](epic-04-navigation-arrival-deviation.md)를 자동 시작한다.
