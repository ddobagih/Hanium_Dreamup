+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-08"
goal_kind = "WORKSTREAM"
document_version = "2.0.0"
parent_goal_id = "WS-GOAL-WALKSAFE-COMPLETION-GRAPH-V2"
external_key = "EPIC-08"
workstream_type = "IMPLEMENTATION"
priority_rank = 8
initial_status = "PLANNED"
source_status_at_creation = "PLANNED"
target_completion_level = "IMPLEMENTATION_READY"
work_item_id = ""
start_requires = ["WS-GOAL-EPIC-02", "WS-GOAL-EPIC-03", "WS-GOAL-EPIC-07"]
completion_requires = ["WS-GOAL-EPIC-02", "WS-GOAL-EPIC-03", "WS-GOAL-EPIC-07"]
child_goal_ids = []
source_policy_ids = ["FP-031", "FP-032", "FP-035", "NPC-AUTO-REPORT", "FP-034", "FP-036", "NPC-PHONE-QUEUE-CAPACITY", "FP-033"]
gap_ids = ["GAP-004", "GAP-005", "GAP-040", "GAP-041", "GAP-042", "GAP-043", "GAP-044", "GAP-045"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# EPIC-08 Goal — 자동신고 암호화 전송 대기함과 전송

## 목표

자동신고 후보를 보행 중에는 전송하지 않고 암호화된 영속 대기함에 보관한 뒤, 보행이 멈춘 상태에서 Wi-Fi 또는 사용자가 명시적으로 허용한 이동통신망으로 중복 없이 이어보낸다.

## 정본 입력

- 선행: EPIC-02·03·07
- 정책: FP-031·032·035, NPC-AUTO-REPORT, FP-034·036, NPC-PHONE-QUEUE-CAPACITY, FP-033
- Gap: GAP-040·041·044·005·043·045·004·042
- 직접 연결 정식 시험: 27개, 전부 `NOT_RUN`
- FP-035는 정책 기준선 1.0.1 overlay가 정본

## 범위와 제외

포함:

- 자동신고 지속 동의와 설정에서 끄기
- 반복관측 후보·고정 신고번호·암호화 영속 queue
- 보행 중 전송 금지와 이동 재개 즉시 중단
- Wi-Fi 우선·이동통신망 별도 선택
- 같은 신고를 여러 번 보내도 한 건만 저장하는 중복 방지(idempotency)·상태조회·수신확인·재부팅 이어보내기
- 압축 원본 묶음·조각 SHA-256·단말 사본 삭제
- 조용한 용량 보류와 관리자 검수 상태

제외:

- 신고 후보마다 사용자 알림·개별 취소 제공
- 자동신고를 안전 보장 기능으로 표현
- 실제 queue byte 한도와 서버 전송 현장 결과

## 실행 절차

| 순서 | 정책 / Gap | r008 | 내부 구현 결과 |
|---:|---|---|---|
| 1 | FP-031 / GAP-040 | CONFLICTING | 지속 동의·반복 후보·종료 뒤 조용한 queue |
| 2 | FP-032 / GAP-041 | CONFLICTING | 고정 ID·영속 queue·idempotency·상태조회·부분실패 복구 |
| 3 | FP-035 / GAP-044 | CONFLICTING | 보행 중 전송 금지, 정지+Wi-Fi/명시 이동통신망, 움직임 즉시 중단 |
| 4 | NPC-AUTO-REPORT / GAP-005 | CONFLICTING | 전송망·재부팅·조용한 용량 보류 공통상태 |
| 5 | FP-034 / GAP-043 | MISSING | 영상·깊이·음성·위치·센서·경로·탐지·신고·성능 압축 원본 묶음 |
| 6 | FP-036 / GAP-045 | MISSING | 조각 수신확인·서버 영속 확인·단말 삭제·보존 scheduler |
| 7 | NPC-PHONE-QUEUE-CAPACITY / GAP-004 | MISSING | queue 용량 상태·조용한 생성 보류·자동 재개 |
| 8 | FP-033 / GAP-042 | PARTIAL | 관리자 접수·검수·기각·기관제출·수신·해결과 판본 감사 |

사용자가 자동신고를 끄면 새 후보 생성을 즉시 중단하고 미전송 자동신고 후보를 24시간 안에 삭제한다. 전송 중 건은 서버 처리 여부를 확인하며 서버에 저장된 자동신고 원본은 삭제요청 상태로 바꿔 7일 안에 삭제한다. 일반 활동원본은 자동신고를 껐다는 이유만으로 삭제하지 않고 별도의 보존·철회·전체삭제 정책을 따른다.

휴대전화 저장공간이 부족하면 ① 다시 만들 수 있는 임시 cache, ② 서버 수신확인이 끝난 로컬 사본, ③ 보존기간이 끝난 선택적 학습자료, ④ 보존기간이 끝난 낮은 신뢰도의 미전송 신고 후보 순서로 삭제한다. 그래도 부족하면 새 학습자료·자동신고 후보 생성을 조용히 보류한다. 미전송 신고, 동의·철회·삭제·보안기록은 먼저 지우지 않는다. 기존 암호화 자료와 실시간 탐지·길안내를 유지하되, 기기 상태 때문에 실시간 안전기능도 신뢰할 수 없을 때만 이유를 알리고 안전정지한다.

## 검증

- ACTIVE walk에서 network send 0
- 이동통신망 미선택 사용자의 cellular send 0
- 움직임 재개 시 in-flight 전송 중단·재시도 가능 상태
- 응답 유실·동시 retry에서도 server duplicate 0
- 재부팅 뒤 queue 복구와 상태조회
- 용량 가득 참에서 조용한 보류·기존 암호화 자료 유지·자동 재개
- 자동신고 끄기 뒤 미전송 24시간·서버 자동신고 원본 7일 삭제와 일반 활동원본 비삭제
- 휴대전화 삭제 우선순위와 보호기록 보존

## 완료 기준

8개 정책의 queue·전송·원본·수신확인·관리자 검수 내부 구조가 준비되고 실제 byte 한도·통신망·기기·서버 정식 검증은 EPIC-12에 이관된다.

## 질문·중단 조건

보행 중 전송 금지, 이동통신망 별도 선택, 사용자별 알림 없음, 원본 수집은 확정되어 있다. 실제 저장소·비밀키·네트워크 계정이 필요할 때만 외부 권한을 요청한다.

## 완료 후 인계

숫자 순서와 달리 다음 Goal은 [`epic-10-ai-model-lifecycle.md`](epic-10-ai-model-lifecycle.md)다.
