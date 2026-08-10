+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-09"
goal_kind = "WORKSTREAM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-WALKSAFE-COMPLETION-GRAPH-V2"
external_key = "EPIC-09"
workstream_type = "IMPLEMENTATION"
priority_rank = 10
initial_status = "PLANNED"
source_status_at_creation = "PLANNED"
target_completion_level = "IMPLEMENTATION_READY"
work_item_id = ""
start_requires = ["WS-GOAL-EPIC-03", "WS-GOAL-EPIC-07", "WS-GOAL-EPIC-08"]
completion_requires = ["WS-GOAL-EPIC-03", "WS-GOAL-EPIC-07", "WS-GOAL-EPIC-08"]
child_goal_ids = []
source_policy_ids = ["FP-043", "FP-044", "FP-045", "NPC-SERVER-CAPACITY-STATE-SYNC", "NPC-SERVER-STORAGE-CAPACITY", "FP-040", "FP-041", "FP-042"]
gap_ids = ["GAP-003", "GAP-009", "GAP-049", "GAP-050", "GAP-051", "GAP-052", "GAP-053", "GAP-054"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# EPIC-09 Goal — 서버 저장·용량·장애대응

## 목표

안전 요청과 대용량 원본 작업을 분리하고, 계정·동의·신고 metadata와 암호화 원본 저장소를 일관된 Gateway·idempotency·용량상태·비용·장애 계약으로 운영 준비한다.

## 정본 입력

- 선행: EPIC-03·07·08
- 정책: FP-043·044·045, NPC-SERVER-CAPACITY-STATE-SYNC, NPC-SERVER-STORAGE-CAPACITY, FP-040·041·042
- Gap: GAP-052·053·054·009·003·049·050·051
- 직접 연결 정식 시험: 30개, 전부 `NOT_RUN`

## 범위와 제외

포함:

- 중앙 안전상태와 서버·단말 장애 전파
- 30일 영속 queue와 정보 유효시간
- 저장공간·배터리·발열·영상 age·추론지연 감시
- 서버 70/85/95/100% 용량정책과 단말 동기화
- DB metadata·KMS·대용량 원본 저장소 분리
- 앱이 서버 기능을 이용하는 단일 통로(Gateway)의 인증·중복 방지(idempotency)·오류 계약
- 안전 요청과 대용량 작업 자원 분리·비용 한도

제외:

- 실제 서버 저장용량·비용·장애훈련 결과
- 만료되지 않은 원본을 비용 때문에 임의 삭제
- 실제 계정·클라우드 생성

## 실행 절차

| 순서 | 정책 / Gap | r008 | 내부 구현 결과 |
|---:|---|---|---|
| 1 | FP-043 / GAP-052 | CONFLICTING | 카메라·거리·위치·위험·음성 실패 중앙화와 핵심 불신 시 안전정지 |
| 2 | FP-044 / GAP-053 | CONFLICTING | 30일 queue·보행 중 전송금지·중복방지·유효시간·용량 계약 |
| 3 | FP-045 / GAP-054 | MISSING | 단말 자원 감시·축소순서·전체정지와 측정가능 설정 |
| 4 | NPC-SERVER-CAPACITY-STATE-SYNC / GAP-009 | MISSING | 서버 용량상태를 단말 정책과 동기화 |
| 5 | NPC-SERVER-STORAGE-CAPACITY / GAP-003 | MISSING | 대용량 저장소·DB 분리와 70/85/95/100% 처리 |
| 6 | FP-040 / GAP-049 | PARTIAL | 운영 계정 역할검사·Gateway·idempotency·오류 계약 |
| 7 | FP-041 / GAP-050 | PARTIAL | DB·KMS·원본 저장소·manifest·수신확인·삭제표식 |
| 8 | FP-042 / GAP-051 | PARTIAL | 안전/대용량 자원 분리·일일 사용량·월 비용 한도·대체 안내 |

서버 용량 기준은 사용자 휴대전화가 아니라 서버 원본 저장소 기준이다. 70% 관리자 경고, 85% 신규 현장시험 참여자 추가 중단, 95% 만료자료 정리 뒤 새 원본수집 세션 보류, 100% 새 학습자료·자동신고 후보 생성 조용한 보류를 적용한다.

용량 계획은 주 원본 저장소 300 GiB와 백업 저장소 300 GiB, 물리 합계 600 GiB다. 주 저장소 상태값은 70%=210 GiB, 85%=255 GiB, 95%=285 GiB, 100%=300 GiB로 계산하고 운영체제·DB·임시파일용 여유공간은 이 300 GiB와 별도로 확보한다. 공간이 확보되면 보류했던 자료 생성·전송만 자동 재개하며, 사용자의 보행 자체를 자동 재개하지 않는다.

## 검증

- 안전 API가 대용량 작업 포화와 분리됨
- idempotency·부분실패·재시도·응답유실
- DB/원본/KMS 경계와 삭제표식
- 70/85/95/100% 상태전이와 단말 반영
- 기존 암호화 자료·실시간 탐지·길안내 유지
- 만료되지 않은 기존 원본 임의 삭제 없음
- 실제 비용·용량·장애훈련은 `NOT_RUN`

## 완료 기준

8개 정책의 서버·저장·자원·오류·비용 내부 구조와 검증이 준비되고, 실제 클라우드 비용·용량 계약·훈련은 EPIC-12에 이관된다.

## 질문·중단 조건

용량 단계와 원본 보존 규칙은 다시 묻지 않는다. 실제 공급자·계정·요금제·생산 비밀값이 필요한 순간에만 외부 권한을 요청한다.

## 완료 후 인계

완료 뒤 새로 충족된 `PLANNED` Goal을 `GOAL_READY`로 전환하고 ready frontier를 다시 계산한다. 특정 Workstream을 자동 시작하지 않으며 선택 규칙이 다음 leaf를 결정한다.
