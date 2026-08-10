+++
schema_version = "2.0"
goal_id = "WS-GOAL-EPIC-02"
goal_kind = "WORKSTREAM"
document_version = "2.1.0"
parent_goal_id = "WS-GOAL-WALKSAFE-COMPLETION-GRAPH-V2"
external_key = "EPIC-02"
workstream_type = "IMPLEMENTATION"
priority_rank = 2
initial_status = "READY"
source_status_at_creation = "IN_PROGRESS"
target_completion_level = "IMPLEMENTATION_READY"
work_item_id = ""
start_requires = ["WS-GOAL-EPIC-01"]
completion_requires = ["WS-GOAL-EPIC-01"]
child_goal_ids = ["WS-GOAL-EPIC-02-FP-018-R001"]
source_policy_ids = ["FP-017", "FP-018", "NPC-PERMISSION-SESSION-LIFECYCLE", "FP-004", "FP-005", "FP-006", "FP-010", "FP-011", "FP-013", "FP-015", "FP-014", "FP-016", "FP-012"]
gap_ids = ["GAP-006", "GAP-013", "GAP-014", "GAP-015", "GAP-019", "GAP-020", "GAP-021", "GAP-022", "GAP-023", "GAP-024", "GAP-025", "GAP-026", "GAP-027"]
canonical_input_roles = ["POLICY_BASELINE", "ARTIFACT_APPLICATION_RECEIPT", "ARTIFACT_REGISTER", "ARTIFACT_CHANGE_LOG", "REQUIREMENTS_TRACEABILITY", "DESIGN_TRACEABILITY", "MODULE_REGISTER", "PLANNED_TEST_CASES", "IMPLEMENTATION_GAP", "IMPLEMENTATION_BACKLOG"]
question_policy = "INHERIT_MASTER"
stop_policy = "INHERIT_MASTER"
+++

# EPIC-02 Goal — 안전한 보행 상태와 권한

## 목표

사용자 가입부터 보행 시작·일시정지·복구·종료까지 권한·로그인·동의·기기·기능 모드를 분리해 관리하고, 불완전한 상태에서 보행이 자동 재개되지 않게 한다.

## 정본 입력

- 현재 Backlog EPIC 집계 상태: `IN_PROGRESS`
- 현재 graph Workstream 상태: `READY`
- 선행: EPIC-01 `IMPLEMENTATION_READY`
- 완료된 내부 slice: FP-017 핵심 lifecycle, GAP-026 `PARTIAL`
- 현재 Work Item: `EPIC-02-FP018-WALK-STATE-RECOVERY`, FP-018/GAP-027
- 직접 연결 정식 시험: 56개, 전부 `NOT_RUN`

## 범위와 제외

포함:

- 보행 lifecycle·복구·권한·로그인·동의 상태
- 지원 사용자·장착·환경·온보딩
- 가입·장기세션·통합동의·철회·삭제
- 권한별 기능 제한과 접근 가능한 안전정지
- 정상 보행 UI와 다중기기 단일 활성보행

제외:

- 실제 기기에서 잠금·통화·앱 전환 검증
- 서버·원본·관리자 기능의 후속 EPIC 전체 구현
- 56개 정식 시험과 gate 종료

## 실행 절차

아래 순서를 최신 successor가 명시적으로 바꾸지 않는 한 유지한다.

| 순서 | 정책 / Gap | r008 | 내부 구현 결과 |
|---:|---|---|---|
| 1 | FP-017 / GAP-026 | PARTIAL | lifecycle을 가입·동의·인증과 연결하고 정식 검증 이관을 유지 |
| 2 | FP-018 / GAP-027 | CONFLICTING | 전체 재검사·명시 재개, 비정상 종료 뒤 새 보행 |
| 3 | NPC-PERMISSION-SESSION-LIFECYCLE / GAP-006 | CONFLICTING | 권한·로그인·동의를 분리하고 철회·만료·이탈·재부팅 시 정확히 중지 |
| 4 | FP-004 / GAP-013 | MISSING | 지원 사용자와 안전 제한을 온보딩·안전정지에 연결 |
| 5 | FP-005 / GAP-014 | MISSING | 지원 장착 방식과 실패 안내 |
| 6 | FP-006 / GAP-015 | MISSING | 지원 환경과 제한 조건 |
| 7 | FP-010 / GAP-019 | MISSING | 연령·전화·가입·동의·권한·훈련 첫 실행 상태기계 |
| 8 | FP-011 / GAP-020 | MISSING | Android 보안 저장소의 접근·회전 갱신용 인증과 원격 폐기 |
| 9 | FP-013 / GAP-022 | MISSING | 원본·자동신고·이동통신망·학습재사용 통합 동의와 서버 원장 |
| 10 | FP-015 / GAP-024 | MISSING | 철회·삭제 즉시 중단, 휴대전화 미전송 삭제, 서버 삭제 완료 연결 |
| 11 | FP-014 / GAP-023 | PARTIAL | 카메라·위치·마이크 권한별 허용·중지와 재검사·명시 재개 |
| 12 | FP-016 / GAP-025 | CONFLICTING | 카메라·안전상태 중심 UI, 개발 입력 제거, 뒤로가기 일시중지 |
| 13 | FP-012 / GAP-021 | MISSING | 여러 기기 로그인과 서버 단일 활성보행 잠금·원격 로그아웃 |

각 행은 별도 Work Item으로 처리하고 완료 뒤 새 Gap·Backlog에서 다음 행을 다시 선택한다.

## 검증

- 상태 조합별 허용 카메라·음성·길안내·신고·전송 행동
- 권한·로그인·동의의 독립 철회·만료
- background·재부팅·비정상 종료 후 자동재개 금지
- 지원하지 않는 조건의 fail-closed 안전정지
- 보안 저장소·세션 회전·동시보행 경쟁
- TalkBack으로 상태·이유·다음 행동 확인
- targeted·Android JVM·assemble·lint 회귀
- 실제 기기·정식시험·gate 비승격

## 완료 기준

- 13개 정책의 내부 구현과 인수조건 검토 완료
- 관련 요구·설계·모듈·시험계획 연결 최신화
- 모든 영향 Gap이 최신 구현 근거로 재평가됨
- 실제 기기·정식 검증을 EPIC-12에 명시적으로 이관
- EPIC-02가 `IMPLEMENTATION_READY`

## 질문·중단 조건

이미 확정된 지원 범위·원본수집·자동신고·이동통신망·복구 규칙을 다시 묻지 않는다. 새 정책 변경이나 외부 인증 서비스 선택이 임계경로에 처음 필요할 때만 Master 조건으로 질문한다.

## 완료 후 인계

현재 Work Item은 [`../work-items/epic-02/epic-02-fp018-walk-state-recovery-r001.md`](../work-items/epic-02/epic-02-fp018-walk-state-recovery-r001.md)다. 각 Work Item 완료 뒤 최신 Gap·Backlog에서 이 Workstream의 다음 내부 작업과 전체 준비 집합을 다시 계산한다. EPIC-03은 EPIC-01 의존성이 이미 끝났으므로 이 Workstream 완료를 기다리는 직렬 후속이 아니라 독립적으로 준비된 분기다.
