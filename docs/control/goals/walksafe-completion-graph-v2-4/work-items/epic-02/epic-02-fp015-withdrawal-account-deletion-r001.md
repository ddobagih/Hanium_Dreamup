+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-02-FP-015-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-02"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 15
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-02-FP015-WITHDRAWAL-ACCOUNT-DELETION"
start_requires = ["WS-GOAL-EPIC-02-FP-013-R001"]
completion_requires = ["WS-GOAL-EPIC-02-FP-013-R001"]
child_goal_ids = []
source_policy_ids = ["FP-015"]
gap_ids = ["GAP-024"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260725-r016.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260725-016"
materialized_from_sha256 = "8c06265e4a721634d27ff82f57284a3c8ba9e033ecfe64d489194f82b80936e8"
predecessor_goal_id = "WS-GOAL-EPIC-02-FP-013-R001"
predecessor_goal_content_sha256 = "0c3ac09bb4541fa0a881995f49d701cc7dc0c520009b1b68f382e2a54030e217"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++

# Work Item Goal — 사용 중 철회·계정 삭제

## 목표

사용자가 동의를 철회하거나 계정 삭제를 요청하면 해당 목적에 의존하는 새
수집·자동신고·이동통신망 전송·학습 재사용을 즉시 차단한다. 휴대전화의 미전송
자료 삭제와 서버 원본·검역본·복사본·가공본·백업의 삭제 요청, 진행 상태,
완료 확인을 서로 구분해 추적하며, 실패나 법적 보존 대상을 완료로 표시하지 않는다.

이 Goal의 완료수준은 저장소 내부 정책 정합 구현과 `GAP-024` 재평가다. 실제
사용자·기기·운영 계정·외부 저장소에서의 삭제 수행, 법률 검토, 정식 시험과
출시 승인은 별도 외부 검증 Workstream에 남긴다.

## 정본 입력

- 정책: `FP-015`
- 요구사항: `RQ-FP-015-001`
- Gap: `GAP-024`, 현재 `MISSING`, P0
- 설계 연결: `DES-01·04·08·09·11·12·13·14·15·19·20·21·23·25·26`
- 예정 정식 시험: `TC-FP-015-01`~`05`, 모두 `NOT_RUN`
- 선행 Goal: `WS-GOAL-EPIC-02-FP-013-R001`
- 생성 근거: Gap·Backlog r016, 실행순서 15
- 현재 관찰: 신고 동의는 현재 앱 화면의 메모리 세션에 한정되며, 권한 철회,
  동의 철회, 계정 삭제, 단말·서버·학습·백업 삭제를 하나의 검증 가능한 흐름으로
  연결한 근거는 확인되지 않음

철회·삭제 정책의 핵심 규칙은 다음과 같다.

1. 철회나 전체 개인정보 삭제 요청을 받으면 새 수집과 전송을 즉시 막는다.
2. 동의 철회는 해당 목적만 닫고 무관한 로그인·동의·운영체제 권한을 임의로 변경하지 않는다.
3. 로그아웃은 로그인 세션만 끝내며 동의 철회나 운영체제 권한 철회로 간주하지 않는다.
4. 앱 삭제는 로컬 세션과 로컬 자료를 없애지만 서버 동의·서버 자료 삭제 요청으로 추측하지 않는다.
5. 앱 밖에서도 이용 가능한 별도 열람·철회·계정 삭제 요청 경로를 제공한다.
6. 단말 미전송 자료, 서버 원본·검역본·복사본, 학습 데이터셋·라벨·가공본,
   신고자료와 백업을 서로 다른 삭제 항목과 상태로 추적한다.
7. 휴대전화 자료 24시간, 서버 원본·검역본·복사본 7일, 데이터셋·라벨·가공본
   30일, 백업 최대 35일의 정책 기한을 상태기계와 확인 증거에 연결한다.
8. 삭제 실패, 재시도 대기, 법적 보존이 있으면 완료로 표시하지 않고 남은 항목,
   이유, 예정 시각과 문의 경로를 제공한다.
9. 계정 삭제 뒤 재가입은 새 계정과 새 동의로 처리하고 과거 선택을 자동 승계하지 않는다.
10. 삭제 상태를 확인할 수 없거나 응답이 늦음·중복·역순이면 성공으로 추측하지 않는다.

## 범위와 제외

포함:

- Android 동의 철회·계정 삭제 요청, 재확인과 접근 가능한 진행 상태 화면
- 철회 즉시 수집·자동신고·전송·학습 재사용 경로를 닫는 공통 집행 경계
- 휴대전화 미전송 자료의 목적별 삭제와 결과 확인
- 서버 삭제 요청의 멱등키, 원자 상태 전이, 재시도, 늦은·중복 응답 처리
- 원본·검역본·복사본·가공본·데이터셋·라벨·신고자료·백업별 상태와 기한
- 삭제 불가·법적 보존 항목의 제한 상태, 사유, 예정 시각과 문의 경로
- 로그아웃·앱 삭제·동의 철회·계정 삭제를 혼동하지 않는 회귀
- Android·Gateway 단위·정적·구성요소 회귀와 추적 증거

제외:

- 실제 운영 계정·비밀값·외부 저장소·백업에서의 삭제 실행
- 실제 사용자·보호자·TalkBack 사용자·Android 기기 시험
- 법률상 보존 근거의 최종 판단과 개인정보 검토 승인
- `TC-FP-015-01`~`05`, 정식 시험 279개, 출시 Gate와 출시 승인
- 운영 배포, 외부 문의 채널 프로비저닝과 실제 삭제 SLA 인증

## 실행 절차

1. Android 동의·로그인·보행·신고·로컬 큐와 Gateway 저장·전송·학습 경로를 조사한다.
2. 철회·계정 삭제 요청과 단말·서버·가공본·백업별 상태·기한 계약을 정책과 연결한다.
3. 철회 뒤 새 수집·전송, 앱 삭제를 서버 삭제로 오인, 삭제 실패의 성공 표시,
   늦은·중복·역순 응답과 재가입 자동승계를 재현하는 fail-first 회귀를 추가한다.
4. Android에 목적별 철회, 계정 삭제 확인, 진행·실패·문의 상태기계를 구현한다.
5. Gateway에 멱등 삭제 요청, 항목별 상태, 재시도와 완료 확인 계약을 구현한다.
6. 철회 요청이 해당 목적의 모든 새 처리 진입점을 즉시 fail-closed로 닫게 한다.
7. 로컬 미전송 자료를 목적별로 삭제하고 서버·가공본·백업 상태와 혼동하지 않는지 확인한다.
8. targeted·구성요소·Android·Gateway 전체 회귀와 build·lint·typecheck를 실행한다.
9. 구현기록·검증결과와 r016을 잇는 Gap·Backlog successor를 확정한다.
10. 독립 검토·completion receipt·canonical update 뒤 ready frontier를 다시 계산한다.

## 검증

- 목적별 동의 철회 → 해당 새 수집·전송·학습 경로 즉시 차단
- 계정 삭제 시작 → 전체 개인정보 새 처리 차단, 항목별 삭제 상태 생성
- 로그아웃 → 로그인 세션만 종료, 동의·운영체제 권한은 임의 변경 안 함
- 앱 삭제·재설치 → 서버 삭제 완료로 표시하지 않고 별도 요청 경로 유지
- 휴대전화 미전송 자료 → 목적별 삭제와 완료 확인
- 서버 실패·무응답·늦음·중복·역순 응답 → 성공 승격 안 함, 재시도 상태 유지
- 법적 보존·삭제 불가 → 제한 상태·사유·예정 시각·문의 경로 표시
- 계정 삭제 뒤 재가입 → 새 계정·새 동의, 과거 동의 자동승계 안 함
- TalkBack → 철회·삭제 범위·진행·실패·문의 상태를 읽고 조작 가능
- 정식·법률·실사용자·실기기·운영 배포·Gate → `NOT_RUN`, `NOT_ELIGIBLE`

## 완료 기준

- 동의 철회와 계정 삭제가 서로 다른 명시적 상태기계로 구현됨
- 철회 즉시 해당 목적의 새 수집·자동신고·전송·학습 재사용이 차단됨
- 단말·서버·가공본·신고자료·백업별 삭제 요청과 결과가 분리 추적됨
- 삭제 실패·법적 보존·재시도 대기가 완료로 오인되지 않음
- 로그아웃·앱 삭제가 동의 철회·서버 삭제로 잘못 승격되지 않음
- 늦음·중복·역순 응답이 최신 철회·삭제 상태를 역행시키지 않음
- 삭제 뒤 재가입이 새 계정·새 동의로 처리됨
- 관련 Android·Gateway 회귀, build·lint·typecheck와 구현·검증 hash가 기록됨
- `GAP-024`를 실제 근거에 맞게 재평가한 새 Gap·Backlog revision이 존재함

내부 구현만으로 실제 외부 저장소 삭제, 법률 승인, 실제 사용자·기기 안전성,
정식 시험 완료, Gate 종료 또는 출시 가능을 주장하지 않는다.

## 질문·중단 조건

현재 정책 질문은 없다. 철회 즉시 차단, 삭제 항목 분리, 실패 비승격과
로그아웃·앱 삭제 구분을 다시 묻지 않는다. 다음 경우에만 Master 정책에 따라 중단한다.

- 유효 정책끼리 실제로 충돌해 정본 우선순위로 해결할 수 없음
- 같은 파일의 사용자 변경과 안전하게 병합할 수 없음
- 새 법률 판단이나 외부 공급자·비밀값 없이는 보수적 내부 경계도 정할 수 없음
- 실제 외부 저장소·사용자·기기 없이 내부 계약과 외부 완료를 분리할 수 없음

## 완료 후 인계

1. r016과 FP013 증거를 보존하고 새 구현·검증·Gap·Backlog successor를 만든다.
2. 부모 EPIC-02의 남은 정책·Gap과 결정적 ready frontier를 재평가한다.
3. 법률·실사용자·실기기·운영 삭제·정식 근거는 typed 외부 검증으로 분리한다.
4. 후속 내부 정책 Work Item은 별도 `GOAL_MATERIALIZED → GOAL_READY`로 준비한다.
5. checkpoint·Goal graph·daylog·local-memory를 갱신한다.
