+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-02-FP-013-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-02"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 14
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-02-FP013-FIRST-RUN-INTEGRATED-CONSENT"
start_requires = ["WS-GOAL-EPIC-02-FP-011-R001"]
completion_requires = ["WS-GOAL-EPIC-02-FP-011-R001"]
child_goal_ids = []
source_policy_ids = ["FP-013"]
gap_ids = ["GAP-022"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260725-r015.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260725-015"
materialized_from_sha256 = "9b561c89bb870fb15480e1fcb7e39cd32a119eb2286da0413b1d988642aec703"
predecessor_goal_id = "WS-GOAL-EPIC-02-FP-011-R001"
predecessor_goal_content_sha256 = "55dbce00b3092053a2766c078f1de83ef79e9193bfb372c7bcf30836c596e56f"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++

# Work Item Goal — 첫 실행 통합 동의

## 목표

민감한 원본 수집, 자동신고, 이동통신망 전송, 학습 재사용을 서로 다른
버전의 동의 항목으로 설명하고 사용자가 항목별로 승인하거나 거부하게 한다.
각 항목은 수집자료·목적·전송시점·보존기간·제공대상·철회방법을 같은
접근 가능한 화면에서 보여 주며, 서버가 현재 문서 버전과 선택 결과의
저장을 확인하기 전에는 해당 수집·전송·재사용 경로를 열지 않는다.

이 Goal의 완료수준은 저장소 내부 정책 정합 구현과 `GAP-022` 재평가다.
법률·개인정보 검토, 승인된 최종 동의 문구, 실제 사용자·보호자·TalkBack·
Android 기기, 운영 배포와 정식 시험·출시 Gate는 별도 검증 Workstream에
남긴다.

## 정본 입력

- 정책: `FP-013`
- 요구사항: `RQ-FP-013-001`
- Gap: `GAP-022`, 현재 `MISSING`, P0
- 설계 연결: `DES-01·04·08·09·11·12·13·14·15·16·18·19·20·21`
- 예정 정식 시험: `TC-FP-013-01`~`05`, 모두 `NOT_RUN`
- 선행 Goal: `WS-GOAL-EPIC-02-FP-011-R001`
- 생성 근거: Gap·Backlog r015, 실행순서 14
- 현재 관찰: 신고 동의는 압축 이미지·GPS와 앱 화면이 켜진 메모리
  세션에 한정되고, 네 가지 목적을 각각 버전 관리하는 통합 동의와
  서버 관리대장 및 전체 fail-closed 집행 근거는 없음

승인 정책의 핵심 규칙은 다음과 같다.

1. 서비스 필수 처리와 선택 목적을 구분하고 선택 거부를 필수 서비스
   전체 거부로 확대하지 않는다.
2. 원본 수집·자동신고·이동통신망 전송·학습 재사용을 각각 독립된
   버전과 선택 결과로 관리한다.
3. 각 항목에서 자료·목적·보관·제공·철회 방법을 같은 화면에 표시한다.
4. 현재 동의가 서버에 저장됐다고 확인하기 전에는 해당 자료를
   수집·전송·재사용하지 않는다.
5. 동의 증거에는 사용자·필요한 보호자·문서 버전·항목별 선택·시각과
   확인 결과를 남기고 비밀번호·토큰 같은 인증 비밀은 넣지 않는다.
6. 목적·자료·보관·제공 조건이나 문서 버전이 바뀌면 과거 동의를 새
   목적에 자동 적용하지 않고 재동의를 요구한다.
7. 보호자 확인이 필요한 가입은 유효한 보호자 증거 없이 계정이나
   민감 기능을 활성화하지 않는다.
8. 철회는 해당 항목에 의존하는 새 처리와 전송을 즉시 막되, 무관한
   동의·로그인·운영체제 권한을 임의로 변경하지 않는다.
9. 모든 고지·선택·상태·철회 방법은 TalkBack으로 읽고 조작할 수 있어야 한다.
10. 동의 상태를 확인할 수 없거나 저장·버전 검증이 실패하면 허용으로
    추측하지 않고 해당 민감 경로를 닫는다.

## 범위와 제외

포함:

- Android 첫 실행의 항목별 동의 화면·상태·재동의·철회 흐름
- 원본 수집·자동신고·이동통신망·학습 재사용별 버전·선택 모델
- 서버 동의 관리대장의 현재성·중복·늦은 응답·원자 저장 계약
- 동의 전·거부·철회·저장 실패 시 수집·전송·민감 기능 차단
- FP-010 확인 신원, FP-011 로그인, 권한 세션과 보행 lifecycle 연결
- 접근성 문구와 선택 결과, 허용·차단 기능의 사용자 안내
- Android·서버 단위·정적·구성요소 회귀와 추적 증거

제외:

- 최종 법률 문구와 개인정보·보호자 동의 적법성 승인
- 실제 운영 계정·비밀값·배포·네트워크와 외부 공급자 프로비저닝
- 실제 원본 수집 또는 실제 자동신고를 이용한 검증
- 실제 미성년 사용자·보호자·TalkBack 사용자·Android 기기 시험
- `TC-FP-013-01`~`05`, 정식 시험 279개, 출시 Gate와 출시 승인
- 데이터 보존·삭제 전체 lifecycle 및 외부 학습 제공의 별도 출시 승인

## 실행 절차

1. Android 가입·동의·권한·보행 시작·신고와 서버 저장·전송 경로를 조사한다.
2. 네 가지 동의 항목의 문서 버전, 선택, 증거, 현재성, 철회와 허용 기능
   계약을 정책·요구·설계·시험에 연결한다.
3. 동의 전 수집·전송, 선택 거부의 전체 서비스 차단, 이전 버전 자동승인,
   서버 저장 실패 뒤 승격, 늦음·중복 응답, 보호자 증거 없는 활성화를
   재현하는 fail-first 회귀를 추가한다.
4. Android에 접근 가능한 항목별 고지·선택·재동의·철회 상태기계를 구현한다.
5. 서버에 항목별 버전 관리대장과 원자 저장·현재성 조회 계약을 구현한다.
6. 수집·신고·전송·학습 진입점이 정확한 현재 동의 없이는 fail-closed가
   되도록 공통 집행 경계를 연결한다.
7. 철회·버전 변경·로그아웃·앱 재시작이 다른 권한과 동의를 오염시키지
   않으면서 의존 경로를 즉시 닫는지 확인한다.
8. targeted·구성요소·Android·서버 전체 회귀와 build·lint를 실행한다.
9. 구현기록·검증결과와 r015를 잇는 Gap·Backlog successor를 확정한다.
10. 독립 검토·completion receipt·canonical update 뒤 ready frontier를
    결정적으로 다시 계산한다.

## 검증

- 필수·선택 항목별 승인·거부 → 정책에 맞는 기능만 허용·차단
- 동의 전 카메라·마이크·정확 위치·활동 원본 → 수집·전송 시작 안 함
- 서버 저장 실패·무응답·오래된 응답 → 동의 상태 승격 안 함
- 목적·수집항목·보관·제공 또는 문서 버전 변경 → 재동의 요구
- 현재 동의 철회 → 의존 처리 즉시 중단, 무관한 권한·로그인은 유지
- 보호자 증거가 필요한 가입에서 증거 없음 → 계정·민감 기능 활성화 안 함
- 중복·늦음·역순 응답 → 최신 선택과 서버 대장이 역행하지 않음
- TalkBack → 모든 항목·필수 여부·선택 결과·철회 방법을 읽고 조작 가능
- 정식·법률·실제 사용자·실기기·운영 배포·Gate → `NOT_RUN`,
  `NOT_ELIGIBLE`

## 완료 기준

- 네 가지 동의 항목의 버전·선택·증거·현재성·철회가 명시적으로 구현됨
- 각 항목의 자료·목적·보관·제공·철회 방법이 접근 가능한 화면에 표시됨
- 현재 동의의 서버 저장 확인 전에는 해당 수집·전송·재사용이 시작되지 않음
- 거부·철회·버전 변경·저장 실패가 허용으로 추측되지 않음
- 선택 거부가 정책상 무관한 필수 서비스나 운영체제 권한까지 막지 않음
- 보호자 증거가 필요한 범위에서 증거 없는 활성화가 차단됨
- 늦음·중복·역순 응답이 최신 동의 상태와 관리대장을 역행시키지 않음
- 관련 Android·서버 회귀, build·lint와 구현·검증 hash가 기록됨
- `GAP-022`를 실제 근거에 맞게 재평가한 새 Gap·Backlog revision이 존재함

내부 구현만으로 최종 법률·개인정보 승인, 실제 사용자·보호자·TalkBack·
실기기 안전성, 정식 시험 완료, Gate 종료 또는 출시 가능을 주장하지 않는다.

## 질문·중단 조건

현재 정책 질문은 없다. 승인된 항목별 동의, 동의 전 차단, 현재성·재동의·
철회와 접근성 경계를 다시 묻지 않는다. 다음 경우에만 Master 정책에 따라
중단한다.

- 유효 정책끼리 실제로 충돌해 정본 우선순위로 해결할 수 없음
- 같은 파일의 사용자 변경과 안전하게 병합할 수 없음
- 새 법률 판단이나 외부 공급자·비밀값 없이는 보수적 내부 경계도 정할 수 없음
- 실제 사용자·보호자·기기·민감 원본 없이는 설계와 내부 검증을 분리할 수 없음

## 완료 후 인계

1. r015와 FP011 증거를 보존하고 새 구현·검증·Gap·Backlog successor를 만든다.
2. 부모 EPIC-02의 남은 정책·Gap과 결정적 ready frontier를 재평가한다.
3. 법률·개인정보·실제 사용자·보호자·기기·정식 근거는 typed 외부 검증으로 분리한다.
4. 후속 내부 정책 Work Item은 별도 `GOAL_MATERIALIZED → GOAL_READY`로 준비한다.
5. checkpoint·Goal graph·daylog·local-memory를 갱신한다.
