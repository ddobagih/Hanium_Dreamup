+++
schema_version = "1.0"
goal_id = "WS-GOAL-A-EPIC-07"
goal_kind = "EPIC"
document_version = "1.0.0"
phase_id = "A"
parent_goal_id = "WS-GOAL-PHASE-A"
sequence = 107
initial_status = "PLANNED"
source_status_at_creation = "PLANNED"
target_completion_level = "IMPLEMENTATION_READY"
next_goal_id = "WS-GOAL-A-EPIC-08"
return_goal_id = "WS-GOAL-PHASE-A"
work_item_id = ""
dependencies = ["WS-GOAL-A-EPIC-02", "WS-GOAL-A-EPIC-03"]
child_goal_ids = []
source_policy_ids = ["NPC-RAW-ORIGINAL-COLLECTION", "NPC-DATA-LIFECYCLE"]
gap_ids = ["GAP-001", "GAP-002"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# EPIC-07 Goal — 원본 수집·보존·삭제

## 목표

사용자가 동의한 활성 보행에서 영상·음성·정확 위치·센서·경로·탐지·신고·성능 원본을 승인된 범위로 암호화 수집하고, 저장 위치별 보존·삭제기한과 수신확인서를 실행 가능한 구조로 만든다.

## 정본 입력

- 선행: EPIC-02·03
- 정책: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE
- Gap: GAP-001 `MISSING`, GAP-002 `PARTIAL`
- 직접 연결 정식 시험: 2개, 전부 `NOT_RUN`
- 원본은 얼굴·번호판·주변 목소리를 가리지 않고 수집한다는 승인 정책

## 범위와 제외

포함:

- 원본 항목·세션·구간·consent version·purpose metadata
- 단말 암호화와 서버 전송용 조각·해시·수신확인
- 단말·검역·신고·학습·백업별 보존기간에 따라 자동 삭제하는 작업(scheduler)
- 철회·전체삭제·용량 보류의 중단 처리

제외:

- 원본 수집 정책을 다시 검토하거나 가림처리로 변경
- 실제 참여자 원본 수집
- 출시 전 독립 개인정보·안전 검토 완료 주장

## 단계별 실행

| 순서 | 정책 / Gap | 내부 구현 결과 |
|---:|---|---|
| 1 | NPC-RAW-ORIGINAL-COLLECTION / GAP-001 | 승인 원본 schema·동의 결속·암호화 수집·manifest·수신확인 |
| 2 | NPC-DATA-LIFECYCLE / GAP-002 | 저장 위치별 만료·삭제·삭제요청·백업 반영과 감사 기록 |

보존 상수는 승인 정책의 단말 수신확인 사본 24시간, 미전송 원본 최대 30일, 서버 수신·검역 중 원본 최대 14일, 서버 일반 활동원본과 자동신고 원본 최대 180일, 승인 학습자료·라벨·고정 검증자료 3년, 운영 백업 35일을 사용한다.

전체 삭제 요청을 받으면 새 수집·전송을 즉시 중단하고 휴대전화 자료 24시간, 서버 원본·검역본·복사본 7일, 학습자료·라벨·가공본 30일, 백업 최대 35일 안에 삭제 또는 제외한다. 원본을 담지 않은 삭제 확인기록은 식별값 hash·처리시각·결과만 3년 보존하고, 백업 복원 때 삭제 완료표식을 먼저 재적용한다.

## 검증

- 동의 없는 수집·전송 차단
- 원본 조각과 manifest SHA-256 검증
- 저장 위치별 만료·철회·전체삭제 상태기계
- 수신확인 전 단말 삭제 금지, 확인 뒤 기한 내 삭제
- 삭제 실패 재시도와 완료표식
- 민감 원본 Git·로그·fixture 저장 금지

## 완료 기준

두 공통정책의 schema·암호화·scheduler·감사 구조와 내부 검증이 준비되고, 실제 원본 수집과 독립검토는 EPIC-12에 이관된다.

## 질문·중단 조건

원본 수집 여부와 보존기간은 다시 묻지 않는다. 실제 KMS·스토리지·독립검토자·참여자 원본이 필요할 때만 외부 권한을 요청한다.

## 완료 후 인계

완료 뒤 [`epic-08-auto-report-queue.md`](epic-08-auto-report-queue.md)를 자동 시작한다.
