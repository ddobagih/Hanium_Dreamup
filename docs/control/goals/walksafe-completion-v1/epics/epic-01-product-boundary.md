+++
schema_version = "1.0"
goal_id = "WS-GOAL-A-EPIC-01"
goal_kind = "EPIC"
document_version = "1.0.0"
phase_id = "A"
parent_goal_id = "WS-GOAL-PHASE-A"
sequence = 101
initial_status = "COMPLETE_AT_TARGET"
source_status_at_creation = "IMPLEMENTATION_READY"
target_completion_level = "IMPLEMENTATION_READY"
next_goal_id = "WS-GOAL-A-EPIC-02"
return_goal_id = "WS-GOAL-PHASE-A"
work_item_id = ""
dependencies = []
child_goal_ids = []
source_policy_ids = ["FP-003", "FP-002", "FP-007", "FP-009", "FP-001"]
gap_ids = ["GAP-010", "GAP-011", "GAP-012", "GAP-016", "GAP-018"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# EPIC-01 Goal — 제품 경계와 Web 앱 오염 제거

## 목표

Android 사용자 앱과 별도 Android 관리자 앱을 제품으로 고정하고 Web/PWA를 `LEGACY_REFERENCE_ONLY`로 제한한다. 저장소 내부 구현 목표는 이미 `IMPLEMENTATION_READY`에 도달했다.

## 정본 입력

- FP-003·002·007·009·001
- GAP-012·011·016·018·010
- Phase A~G append-only 구현 기록과 r008 현재 진단
- Android 제품 경계 설정, 독립 Gateway, 사용자 목적·안전 한계 문서

## 범위와 제외

완료된 내부 범위에는 제품 경계, 관리자 앱 분리, runtime metric fail-closed, Web 기술 폐쇄, Android Gateway 추출, 사용자 표면 정합화, 목적지 없는 위험안내가 포함된다.

다음은 Phase B의 정식 검증으로 남아 있다.

- 관리자 실제 등록·분실 복구훈련
- 실제 서명·분리 배포 채널
- 과거 외부 URL·DNS·캐시 PWA 폐기 확인
- 승인 기기 등록과 실제 기기 시험
- 20개 직접 연결 정식 시험

## 단계별 실행

| 순서 | 정책 / Gap | 현재 내부 판정 | 남은 정식·외부 작업 |
|---:|---|---|---|
| 1 | FP-003 / GAP-012 | PARTIAL | 운영 자격정보·외부 복구수단·키 백업을 실제 프로비저닝하고 복구훈련 |
| 2 | FP-002 / GAP-011 | EVIDENCE_MISSING | 시연·제한시험·정식 공개 단계와 같은 후보 묶음의 실제 승인 기록 |
| 3 | FP-007 / GAP-016 | PARTIAL | 사용자·관리자 앱 실제 서명과 분리 배포·지원 기기 Gateway 연결 |
| 4 | FP-009 / GAP-018 | PARTIAL | 과거 외부 자원과 기존 설치·캐시 PWA 비활성 확인 |
| 5 | FP-001 / GAP-010 | PARTIAL | 목적지 없는 ARCore metric·CameraX 제한모드 실기기·접근성 시험 |

내부 구현을 다시 시작하지 않는다. 새 successor가 명시적인 회귀를 발견한 경우에만 해당 범위를 재개한다.

## 검증

- 최신 체크포인트에서 EPIC-01 `IMPLEMENTATION_READY`
- Web/PWA 제품 경로 차단과 Android/Gateway 내부 회귀 PASS
- Phase A~G 과거 기록 불변
- 실제 기기·배포·정식 시험·복구훈련 `NOT_RUN` 경계 유지

## 완료 기준

현재 Goal은 `COMPLETE_AT_TARGET/IMPLEMENTATION_READY`다. 정식 시험이나 출시 완료로 승격하지 않는다. Phase B는 위 외부 작업을 동일 release candidate에서 수행한다.

## 질문·중단 조건

Master 정책을 상속한다. 새 내부 Gap이 없으면 질문하거나 재작업하지 않는다.

## 완료 후 인계

[`epic-02-safe-walk-state-and-permissions.md`](epic-02-safe-walk-state-and-permissions.md)가 현재 후속 Goal이다.
