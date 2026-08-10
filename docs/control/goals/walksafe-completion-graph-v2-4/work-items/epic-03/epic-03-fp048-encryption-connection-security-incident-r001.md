+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-03-FP-048-R001"
goal_kind = "WORK_ITEM"
document_version = "2.2.0"
parent_goal_id = "WS-GOAL-EPIC-03"
work_item_type = "POLICY_GAP_WORK"
priority_rank = 23
initial_status = "PLANNED"
target_completion_level = "INTERNAL_POLICY_CONFORMANCE_REASSESSED"
work_item_id = "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT"
start_requires = ["WS-GOAL-EPIC-03-FP-047-R001"]
completion_requires = ["WS-GOAL-EPIC-03-FP-047-R001"]
child_goal_ids = []
source_policy_ids = ["FP-048"]
gap_ids = ["GAP-057"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
materialized_from_role = "IMPLEMENTATION_BACKLOG"
materialized_from_path = "docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r021.json"
materialized_from_document_id = "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-021"
materialized_from_sha256 = "bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0"
predecessor_goal_id = "WS-GOAL-EPIC-03-FP-047-R001"
predecessor_goal_content_sha256 = "2ff79dde64cb113d755855f05dafb6bab1393717f060b4db387bb2d48bc53b06"
supersedes_goal_id = ""
supersedes_goal_content_sha256 = ""
reopen_reason = ""
reopen_evidence_refs = []
artifact_work_reason = ""
artifact_trigger_evidence_refs = []
+++
# FP-048 암호화·접속정보·보안사고

## 목표

휴대전화 대기자료, 통신, 서버 원본, 데이터베이스와 백업의 민감자료를 승인된 방식으로 암호화하고 열쇠·복구자료를 데이터 저장소와 분리한다. 원본 접근과 열쇠 수명주기, 보안사고 대응은 기본 차단과 감사 가능한 전이 기록을 적용한다.

## 정책 기준

- 로그인 증명과 민감파일은 휴대전화 보호 저장소와 앱 전용 암호화를 사용한다.
- 통신은 검증된 최신 암호화 연결만 허용하고 인증서 검증 실패나 암호화 실패 때 평문으로 낮추지 않는다.
- 서버 원본·데이터베이스·백업은 저장 위치별로 암호화하고 열쇠는 데이터 저장소와 분리된 관리 경계에 둔다.
- 암호화 승인표에는 허용 통신 버전, 저장 방식, 열쇠 길이·소유자·회전·복구·폐기 방법을 기록한다.
- 원본 열람·다운로드·내보내기는 기본 차단하고 승인 목적·최소 범위·시간제한·추가 인증·감사를 요구한다.
- 사고는 탐지·격리·영향판단·법률검토·복구·재발방지를 기록하고, 법률상 의무나 즉각적인 사용자 안전조치가 필요할 때 정해진 기한 안에 알린다.

## 구현 범위

- Android 미전송 민감자료와 로그인 상태의 기기 보호 열쇠 기반 암호화
- Android와 Gateway의 HTTPS 전용 연결, 인증서 실패·평문 downgrade 차단
- 서버 원본·데이터베이스·백업의 암호화 경계와 데이터·열쇠 분리 계약
- 열쇠 생성·활성·회전·폐기·복구 실패와 사용 이력 감사
- 원본 접근·복사·다운로드·내보내기의 목적·최소범위·만료·감사 통제
- 보안사고 상태 전이와 통지 필요성 검토 경계
- 정책→요구→설계→코드→내부 시험→GAP-057 successor 추적

## 성공 기준

1. 휴대전화에 평문 로그인 증명이나 평문 미전송 민감자료를 남기지 않고 열쇠 사용 불가 때 안전하게 차단한다.
2. 인증서 검증 실패나 비암호화 endpoint에서 전송하지 않으며 평문 fallback이 없다.
3. 서버 원본·데이터베이스·백업 암호화와 열쇠 저장 경계가 분리되고 회전·폐기 상태가 감사 가능하다.
4. 관리자 원본 접근은 기본 거부되며 승인 목적·최소 범위·시간제한·추가 인증·감사를 모두 만족할 때만 허용된다.
5. 보안사고가 격리부터 영향·통지 검토·복구까지 순서와 책임 경계를 유지한다.
6. 내부 구현·회귀·보안 검토 증거와 GAP-057 재평가가 정본 해시로 결속된다.

## 계획 검증

- `TC-FP-048-01`: 잠긴 분실 휴대전화의 로그인 증명·미전송 원본 보호
- `TC-FP-048-02`: 만료·위조 인증서와 평문 downgrade 차단
- `TC-FP-048-03`: 유출 열쇠 폐기·회전과 영향범위 감사
- `TC-FP-048-04`: 열쇠·복구자료 손실 시 승인 복구 또는 접근 차단
- `TC-FP-048-05`: 분리된 암호화 백업의 승인 열쇠 복원
- `TC-FP-048-06`: 관리자 원본 접근의 승인·최소범위·시간제한·감사
- `TC-FP-048-07`: 보안사고 격리·영향판단·법률검토·통지·복구 순서

## 제외 범위

- 실제 운영 열쇠·인증서·비밀값 생성, 외부 보관서비스 연결과 프로덕션 배포
- 실제 분실 휴대전화·운영 백업 복구와 관리자 복구훈련
- 실제 사용자·관리자·기기·외부 TLS 환경 시험
- 독립 보안·법률·개인정보 검토와 사고 통지 실행
- 정식 279개 시험, 두 관련 release Gate, 출시 적격성

## 완료 경계

이 Goal의 목표 완료 수준은 `INTERNAL_POLICY_CONFORMANCE_REASSESSED`다. 저장소 내부 코드·설정·자동 검증·증거 사슬과 `GAP-057` 재평가까지만 완료로 간주한다. 실제 열쇠 운영, 기기·외부 TLS 시험, 독립 검토, 복구훈련과 정식 시험은 `NOT_RUN`으로 유지하며 내부 검증으로 대체하지 않는다.
