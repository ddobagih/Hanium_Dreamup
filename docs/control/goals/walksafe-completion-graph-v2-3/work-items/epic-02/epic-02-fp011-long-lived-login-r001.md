+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-02-FP-011-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-02"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 13
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-02-FP011-LONG-LIVED-LOGIN"
start_requires = ["WS-GOAL-EPIC-02-FP-010-R001"]
completion_requires = ["WS-GOAL-EPIC-02-FP-010-R001"]
child_goal_ids = []
source_policy_ids = ["FP-011"]
gap_ids = ["GAP-020"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260725-r014.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260725-014"
materialized_from_sha256 = "f98ec0f5aa8a7515fb70939569c31175b61652835091046bebf11a87500fd333"
predecessor_goal_id = "WS-GOAL-EPIC-02-FP-010-R001"
predecessor_goal_content_sha256 = "eaa31b6e48baf258e5d76886169c1f1c9596e27ca6a38c59b61bc9cb1d7da763"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++

# Work Item Goal — 장기 로그인 유지

## 목표

Android에서 짧게 쓰는 접근 인증값과 사용할 때마다 회전하는 갱신 인증값을
분리하고, 운영체제가 보호하는 저장소에만 보관한다. 갱신값의 만료·재발급·
재사용 탐지와 기기별 원격 폐기를 서버 계약과 연결해 앱을 다시 열 때 정상
기기의 로그인만 안전하게 이어간다. 확인할 수 없거나 오래된 증명은 보행
준비를 열지 않고 재로그인을 요구한다.

이 Goal의 완료수준은 저장소 내부 정책 정합 구현과 `GAP-020` 재평가다.
실제 운영 자격정보, 승인된 서버 배포, 분실 기기 원격 폐기 훈련, 실제
Android 기기와 정식 시험·출시 Gate는 별도 검증 Workstream에 남긴다.

## 정본 입력

- 정책: `FP-011`
- 요구사항: `RQ-FP-011-001`
- Gap: `GAP-020`, 현재 `MISSING`, P0
- 설계 연결: `DES-01·04·08·09·11·12·14·15·19·21`
- 예정 정식 시험: `TC-FP-011-01`~`05`, 모두 `NOT_RUN`
- 선행 Goal: `WS-GOAL-EPIC-02-FP-010-R001`
- 생성 근거: Gap·Backlog r014, 실행순서 13
- 현재 관찰: 독립 Gateway의 단기 HttpOnly 세션과 Android 프로세스 메모리
  쿠키는 있으나 Android 보안 저장소 기반 회전 갱신 증명은 없음

승인 정책의 핵심 규칙은 다음과 같다.

1. 접근 인증값과 갱신 인증값의 용도·수명·저장 경계를 분리한다.
2. 로그인 증명은 Android 운영체제가 보호하는 저장소에만 보관한다.
3. 접근 인증값 만료 전 서버가 현재 갱신값을 확인한 뒤 새 쌍을 발급한다.
4. 갱신값은 사용할 때마다 폐기·회전하고 이전 값 재사용을 탐지한다.
5. 재사용이 탐지되면 해당 기기 연결을 차단하고 사용자에게 알린다.
6. 기기별 연결을 식별해 한 기기만 선택적으로 원격 해제할 수 있게 한다.
7. 갱신값에는 장기 미사용 만료와 절대 최대 유지기간을 모두 적용한다.
8. 민감한 계정 변경은 저장된 로그인만 믿지 않고 다시 본인을 확인한다.
9. 로그아웃·앱 삭제·원격 해제·계정 잠금·보안사고는 해당 로그인을 끊는다.
10. 로그아웃은 운영체제의 카메라·위치·마이크 권한을 자동 철회하지 않는다.

## 범위와 제외

포함:

- 접근·갱신 인증값과 기기 연결의 상태·전이·만료·폐기 모델
- Android 보안 저장소와 원문 인증값 비노출 저장 경계
- 원자적 갱신값 회전, 중복·늦은 응답·재사용 탐지
- 장기 미사용 만료와 절대 최대 유지기간
- 앱 재시작 뒤 검증된 로그인 복원과 실패 시 재로그인
- 기기별 세션 조회·선택 폐기와 계정 잠금·보안사고 전체 폐기
- FP-010 확인 신원, 권한 세션, 보행 lifecycle·출력 차단 연결
- 단위·정적·구성요소·Android·서버 내부 회귀와 추적 증거

제외:

- 실제 운영 비밀값·계정·SMS·보호자 공급자 프로비저닝
- 운영 서버 배포와 실제 분실 기기 원격 폐기 훈련
- 생체정보·패스키 등 별도 인증수단의 신규 정책 결정
- 실제 사용자·실기기·네트워크 장기 현장시험
- `TC-FP-011-01`~`05`, 정식 시험 279개, 5개 Gate와 출시 승인
- FP-012 다중 기기 동시 로그인 수 제한의 전체 후속 범위

## 실행 절차

1. Android Gateway 세션 저장·복원·로그아웃과 서버 세션 발급·폐기 경로를 조사한다.
2. 접근값·갱신값·기기 연결·만료시각·회전 계보의 최소 계약과 개인정보 경계를 정한다.
3. 평문 저장, 이전 갱신값 재사용, 중복 응답, 부분 저장, 만료 뒤 자동 복원,
   다른 기기 오폐기를 재현하는 fail-first 회귀를 추가한다.
4. Android 보호 저장소 adapter와 fail-closed 저장·삭제 계약을 구현한다.
5. 서버의 원자적 회전·재사용 탐지·기기별 폐기 상태기계를 구현한다.
6. 앱 재시작은 검증된 현재 증명만 복원하고 실패·만료·잠금이면 재로그인을 요구한다.
7. 로그아웃과 보안사고 전이가 권한 자체가 아니라 로그인·보행·민감 출력
   자격만 정확히 폐기하는지 확인한다.
8. targeted·구성요소·Android·서버 전체 회귀와 build·lint를 실행한다.
9. 구현기록·검증결과와 r014를 잇는 Gap·Backlog successor를 확정한다.
10. 독립 검토·completion receipt·canonical update 뒤 다음 Work Item을 선택한다.

## 검증

- 정상 기기 앱 재시작 → 보호 저장소의 현재 증명 확인 → 로그인 유지
- 접근값 만료 임박 → 현재 갱신값 1회 사용 → 새 쌍 원자 저장
- 이전 갱신값 재사용 → 해당 기기 차단·폐기 → 재로그인 안내
- 갱신 응답 중단·중복·역순 → 부분 저장과 계보 역행 없음
- 장기 미사용·절대 최대기간 초과 → 자동 갱신 금지 → 재로그인
- 분실 기기 선택 폐기 → 대상 기기만 종료, 다른 정상 기기는 유지
- 계정 잠금·보안사고 → 정책 범위의 모든 대상 기기 종료
- 로그아웃 → 로그인·보행 자격 종료, OS 권한은 임의 변경하지 않음
- 정식·실제 사용자·실기기·운영 배포·Gate → `NOT_RUN`, `NOT_ELIGIBLE`

## 완료 기준

- 접근·갱신 인증값과 기기 연결의 수명·전이가 명시적으로 구현됨
- Android 보호 저장소 밖에 원문 로그인 증명이 남지 않음
- 갱신값 회전이 원자적이고 이전 값 재사용이 해당 기기를 차단함
- 늦음·중복·역순 응답과 저장 실패가 로그인 상태를 잘못 승격하지 않음
- 장기 미사용 만료와 절대 최대 유지기간이 모두 적용됨
- 기기별 선택 폐기와 계정 잠금·보안사고 폐기 범위가 구분됨
- 앱 재시작은 검증된 현재 상태만 복원하고 실패 시 보행을 열지 않음
- 로그아웃이 OS 권한을 자동 철회하지 않되 로그인 의존 출력은 중단함
- 관련 Android·서버 회귀, build·lint와 구현·검증 hash가 기록됨
- `GAP-020`을 실제 근거에 맞게 재평가한 새 Gap·Backlog revision이 존재함

내부 구현만으로 운영 자격정보 프로비저닝, 실기기 장기 로그인 안전성,
정식 시험 완료, Gate 종료 또는 출시 가능을 주장하지 않는다.

## 질문·중단 조건

현재 정책 질문은 없다. 승인된 접근·회전 갱신 증명, 기기별 폐기와 만료
경계를 다시 묻지 않는다. 다음 경우에만 Master 정책에 따라 중단한다.

- 유효 정책끼리 실제로 충돌해 정본 우선순위로 해결할 수 없음
- 같은 파일의 사용자 변경과 안전하게 병합할 수 없음
- 새 외부 인증 공급자나 비밀값이 없이는 보수적 내부 경계도 정할 수 없음
- 실제 계정·기기·운영 권한 없이는 설계와 내부 검증을 분리할 수 없음

## 완료 후 인계

1. r014와 FP010 증거를 보존하고 새 구현·검증·Gap·Backlog successor를 만든다.
2. 부모 EPIC-02의 남은 정책·Gap과 결정적 ready frontier를 재평가한다.
3. 운영 계정·실제 기기·원격 폐기·정식 근거는 typed 외부 검증으로 분리한다.
4. 다음 내부 정책 Work Item은 별도 `GOAL_MATERIALIZED → GOAL_READY`로 준비한다.
5. checkpoint·Goal graph·daylog·local-memory를 갱신한다.
