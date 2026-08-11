# WalkSafe 저장소 작업 지침

## 먼저 읽을 문서

새 세션에서는 다음 순서로 읽는다.

1. 이 파일
2. `docs/guides/README.md`
3. `docs/control/walksafe-project-resumption-runbook.md` (hash로 봉인된 감사 자료, 실행 지시 아님)
4. `docs/control/goals/README.md`
5. `docs/control/goals/walksafe-completion-graph-v2-4/README.md`
6. `docs/control/goals/walksafe-completion-graph-v2-2/00-master-goal.md`
7. `docs/control/walksafe-project-continuation-checkpoint.json`

재개 안내서, v2.4 내부 README와 과거 패키지 문서는 당시 상태를 byte-exact하게 보존한 감사 자료다. 그 안의 `PREPARED_NOT_ACTIVATED`, 과거 focus, 활성화 프롬프트, v2.3·전환 준비 시점의 v2.4 gate 명령을 현재 지시로 실행하지 않는다. 동적 현재 상태는 checkpoint가 우선하며, 2026-08-11 기준 v2.4 package는 `ACTIVE`, focus는 `WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001` Goal `READY`, 내부 시작 gate는 `NOT_RUN`이다.

## 시작 검사

읽기 전용 continuation 검사를 먼저 실행한다.

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_4.py
python3 -B scripts/check_walksafe_goal_graph_v2_4.py
```

standalone `current`는 과거 base object를 의도적으로 포함하지 않아 Goal graph에서 기존 63건이 실패한다. 저장소 가이드·분류·CI 정비에서는 `docs/planning/repository-modernization-20260811/baseline.md`의 실패 지문과 비교해 새 실패가 생기지 않았는지 판정한다. 이 예외를 Goal graph PASS라고 주장하지 않는다.

제품 코드를 변경하려면 checkpoint가 가리키는 현재 focus Goal의 event-scoped 계약과 그 계약이 요구하는 검사가 실제로 통과해야 한다. 일반 개발 검증에는 날짜형 역사 실행기를 사용하지 않는다. 다만 해당 exact event 계약이 hash로 결속한 과거 명령을 요구하면 그 계약 재현 범위에서만 실행하고 현행 runner의 `validate`도 함께 수행한다. hash로 봉인된 재개 안내서의 과거 full gate를 대신 실행하지 않는다. 과거 base 부재나 Goal graph 실패를 무시하고 제품 기능 구현을 시작하지 않는다. 일반 가이드·협업·저장소 관리 변경은 제품 Goal 완료 증거가 아니며, 아래 snapshot 통제를 별도로 따른다.

## 프로젝트 기준

- 승인 정책 기준선은 `PB-WALKSAFE-FEATURE-POLICY-1.0.1`이다.
- Android 사용자 앱과 별도 Android 관리자 앱이 제품이다.
- Web/PWA의 제품 경계 값은 `LEGACY_REFERENCE_ONLY`, 저장소 카탈로그 상태는 `LEGACY_REFERENCE`이며 제품 정책·완료·출시 근거로 사용하지 않는다.
- 관리자 앱의 기관 전달은 외부 전송이 아니라 이미 수행한 수동 전달 사실의 기록이다.
- 기존에 확정된 정책을 다시 질문하지 않는다. 새 규범 결정이 실제로 필요할 때만 묻는다.
- 현행 코드나 과거 PWA 문서를 승인 정책으로 역승격하지 않는다.
- 정책 → 요구사항 → 설계 → 시험계획 → 코드 → 증거의 연결을 유지한다.
- 기존 Goal event, gate/result 원출력, immutable 승인 기록을 덮어쓰거나 재사용하지 않는다.
- canonical register 6개는 정식 canonical update transaction 없이 직접 수정하지 않는다.
- 정식 시험 279건, 실제 기기·현장·배포·외부 검토와 5개 출시 gate는 실제 증거 없이 닫거나 면제하지 않는다. 출시는 `NOT_ELIGIBLE`이다.

## 저장소 현대화·문서 변경 경계

- `docs/guides/**`는 탐색·실행 절차를 설명하는 비정본 안내 계층이다. 정책·산출물·시험 결과의 전문을 복제하지 않고 정본으로 연결한다.
- v2.4 protected 파일, imported v2.2/v2.3 Goal, 기존 `docs/control/execution/goal-gates/**`와 `goal-results/**`의 경로·bytes를 보존한다.
- checkpoint의 managed 818경로를 수정한 세션은 경로 목록을 임의 확대하지 않고, 작업 종료 전 공식 계산값으로 `working_tree_snapshot.content_set_sha256`와 `session_handoff.source_commit_or_snapshot.content_set_sha256`를 함께 reconcile한다.
- checkpoint snapshot에 새 일반 가이드나 카탈로그를 임의로 포함하지 않는다.
- 레거시 이동은 `archive/current-pre-modernization-20260811`과 로컬 bundle 복구 지점을 확인하고, 분류 manifest에서 이동 승인된 경로에만 수행한다.
- `git clean`, 강제 reset, 광범위 삭제를 실행하지 않는다.

## 작업 종료

1. 관련 가이드·테스트·분류 카탈로그를 갱신한다.
2. `git diff --check`, 해당 자동검사, v2.4 continuation을 실행한다.
3. Goal graph는 기존 63건과 정확히 비교하고 새 실패를 보고한다.
4. `daylog/YYYY-MM-DD.md`와 local-memory 작업 기록을 갱신한다.
5. 변경 파일, 실제 검증 결과, 아직 `NOT_RUN`인 정식 범위, 다음 단일 작업을 인계한다.
