# WalkSafe 산출물·기준선 통제

## 2026-08-14 current override

> 이 절이 아래의 v2.4 전환 준비·FP011 활성화 지시보다 우선한다. 아래 `PREPARED_NOT_ACTIVATED`, `READY_NOT_ACTIVATED`, FP011과 과거 활성화 승인문은 감사 이력이며 현행 작업 지시가 아니다.

현재 checkpoint의 package는 v2.4 `ACTIVE`다. focus는 `WS-GOAL-EPIC-04`이고 Goal은 `READY`, 내부 시작 gate는 `NOT_RUN`이다. 정식 시험 279개와 release gate 5개는 모두 `NOT_RUN`, 출시는 `NOT_ELIGIBLE`이다. 현재 탐색·재개 절차는 저장소 [`AGENTS.md`](../../AGENTS.md), [프로젝트 가이드](../guides/project-guide.md), 현재 [`walksafe-project-continuation-checkpoint.json`](walksafe-project-continuation-checkpoint.json)을 따른다.

이 문서의 아래 본문은 당시 통제 계약을 보존한다. 현재 checkpoint와 충돌하는 상태·focus·명령을 재실행하지 않는다.

> 새 터미널·새 세션에서는 저장소 `AGENTS.md` → [산출물 기반 프로젝트 재개·진행 안내서](walksafe-project-resumption-runbook.md) → `goals/README.md`와 graph-v2.4 `README.md` → imported Master Goal → `walksafe-project-continuation-checkpoint.json`과 v2.4 static manifest 순서로 읽는다. `check_walksafe_project_continuation_v2_4.py`와 `check_walksafe_goal_graph_v2_4.py`는 빠른 준비 상태 무결성 검사다. 제품 코드·기능 구현은 v2.4의 최종 manifest와 seq1에 결속한 사용자 활성화 승인 뒤 전체 구현 시작 검사 블록을 모두 통과한 경우에만 시작한다. Gradle 단계는 소스를 바꾸지 않지만 로컬 `build/` 산출물을 만들 수 있다.

전환 준비 정본은 `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4` v2.4.0이며 package는 `PREPARED_NOT_ACTIVATED`, activation은 `READY_NOT_ACTIVATED`다. v2.4는 활성 v2.3 sequence 17의 Goal 문서 20개를 원래 경로와 bytes 그대로 가져오고 native support 파일 6개만 소유한다. 새 package의 manifest `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07`과 seq1 `58c2b31637b5355609db14bf9e0779d4fd33d99f97a692866718a51fa64875d9`에 대한 명시적 승인 전에는 v2.4 활성화와 FP011 `GOAL_STARTED`를 기록하지 않는다. v2.3 이하 패키지는 감사 이력으로만 사용한다.

검사 명령 계약 버전은 `2026-07-25.4`다. quick 계약은 `CONTINUATION_QUICK → GOAL_GRAPH_QUICK`이며 SHA-256은 `7b66c4610e0ad2c84e1937915704b0635b1762dc65dc06762b36e4afacdd813a`다.

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_4.py
python3 -B scripts/check_walksafe_goal_graph_v2_4.py
```

full 계약 순서는 `CONTINUATION → GOAL_GRAPH → BASELINE_MATERIALIZATION → ANDROID_GATEWAY_BOUNDARY → NODE_TOOLCHAIN_PRE → GATEWAY_TYPECHECK → GATEWAY_TEST → GATEWAY_BUILD → WEB_TEST → WEB_LINT → WEB_TYPECHECK → WEB_BUILD → NODE_TOOLCHAIN_POST → ANDROID_UNIT_ASSEMBLE_LINT → TEST_LAYER_REGISTRY_VALIDATE → FIELD_AND_RELEASE_PYTEST → GOAL_CONTROL_PYTEST → CONTROL_AND_TRACE_PYTEST → REPOSITORY_STATE`이고, SHA-256은 `8c7e16f13a66398e5ba067cba0df9ba00258018f630256f6abc5b881b0467b7c`다. 실행 전에 `WALKSAFE_NODE_BIN_DIR`과 이번 gate의 add-only event ID인 `WALKSAFE_GATE_EVENT_ID`를 지정한다. 아래 19개 명령은 checker의 exact 계약 순서다.

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_4.py
python3 -B scripts/check_walksafe_goal_graph_v2_4.py
python3 -B scripts/materialize_walksafe_artifact_baseline_approval_20260722.py --check
python3 -B scripts/check_walksafe_android_gateway_boundary_20260723.py --root .
: "${WALKSAFE_NODE_BIN_DIR:?required}" && WALKSAFE_NODE_ROOT="$(/usr/bin/dirname -- "${WALKSAFE_NODE_BIN_DIR}")" && test "${WALKSAFE_NODE_BIN_DIR}" = "${WALKSAFE_NODE_ROOT}/bin" && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root "${WALKSAFE_NODE_ROOT}" --lock configs/walksafe_node_toolchain_lock_20260715.json
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway run typecheck
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway test
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway run build
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web test
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run lint
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run typecheck
: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run build
: "${WALKSAFE_NODE_BIN_DIR:?required}" && WALKSAFE_NODE_ROOT="$(/usr/bin/dirname -- "${WALKSAFE_NODE_BIN_DIR}")" && test "${WALKSAFE_NODE_BIN_DIR}" = "${WALKSAFE_NODE_ROOT}/bin" && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root "${WALKSAFE_NODE_ROOT}" --lock configs/walksafe_node_toolchain_lock_20260715.json
(cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon)
WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && PYTHON_BIN="${WALKSAFE_LOCKED_TEST_PYTHON}" bash scripts/run_walksafe_test_layers_current.sh validate
WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && "${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest tests/test_android_field_session_summary.py tests/test_release_evidence_gate.py -q
WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && "${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest tests/test_walksafe_goal_graph_v2_4.py tests/test_walksafe_project_continuation_v2_4.py tests/test_walksafe_goal_graph_v2_3_history.py tests/test_walksafe_goal_graph_v2_2_history.py -q
WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python}" && test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && "${WALKSAFE_LOCKED_TEST_PYTHON}" -m pytest tests/test_walksafe_epic01_phase_b_trace_20260722.py tests/test_walksafe_epic01_phase_c_trace_20260722.py tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py tests/test_walksafe_epic02_trace_v2_2_history.py tests/test_walksafe_epic02_trace_v2_3_history.py tests/test_walksafe_android_gateway_boundary_20260723.py tests/test_walksafe_artifact_baseline_materialization_20260722.py --deselect=tests/test_walksafe_epic01_phase_b_trace_20260722.py::WalkSafeEpic01PhaseBTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_c_trace_20260722.py::WalkSafeEpic01PhaseCTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py::WalkSafeEpic01PhaseETraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py::WalkSafeEpic01PhaseFTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py::WalkSafeEpic01PhaseGTraceTest::test_generated_files_are_current_and_deterministic --deselect=tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py::WalkSafeEpic02PhaseATraceTest::test_generated_files_are_current_and_deterministic -q
: "${WALKSAFE_GATE_EVENT_ID:?required}" && python3 -B scripts/check_walksafe_project_continuation_v2_4.py --root . --checkpoint docs/control/walksafe-project-continuation-checkpoint.json --print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"
```

`WALKSAFE_NODE_BIN_DIR`은 잠금파일과 일치하는 공식 Node 22.23.1 배포본의 실제 `bin` 절대경로여야 하며, 시스템 기본 `node`·`npm`은 재개 검증에 사용하지 않는다. 통합 suite는 Phase B·C·E·F·G와 EPIC-02 Phase A의 과거 생성물 currentness 6건만 deselect하고 나머지 의미 회귀를 실행한다. Phase D live builder도 suite 전체에서 제외된 `EXPECTED_STALE` 역사 도구다. r008과 EPIC-02 Phase A record·overlay·builder·test·고정 출력은 현재 checkpoint가 참조하는 bootstrap predecessor snapshot이지 현재 제품 bytes의 live currentness 생성물이 아니다. 과거 출력을 다시 만들거나 덮어쓰지 않고 successor와 continuation checker가 보존한 불변 SHA-256으로 확인한다.

v2.4가 byte-exact로 가져오는 v2.3 scheduler와 증거 계약은 다음 경계를 유지한다.

- FP-017/GAP-026 bootstrap consumed pair는 EPIC-02의 다음 정책 선택과 `IMPLEMENTATION_READY` Workstream coverage에만 쓰며, `GAP-026=PARTIAL`, 정식 검증 `NOT_RUN`, 실제 Work Item 미완료 경계를 바꾸지 않는다.
- 결정적 focus가 USER/EXTERNAL 조건에 막히면 owner별 typed request를 기록하되 다른 내부 ready branch를 계속 선택한다. USER 질문은 내부 frontier가 남아 있으면 `DEFERRED_INTERNAL_FRONTIER`, 모두 소진된 뒤에만 `READY_FOR_USER`다.
- USER/EXTERNAL request는 각각 `USER_DECISION`/`EXTERNAL_ACTION_EVIDENCE` kind와 `USER_AUTHORIZATION`/`EXTERNAL_ATTESTATION` authority를 쓰고, owner·kind·action·대상 Goal 경로/내용 hash·Work Item 유형·정확히 하나인 해제 증거 role·authority의 canonical logical basis SHA-256을 전역 고유 request key로 사용한다. 각 request는 고유 `request_event_id`와 source `blocker_id`·불변 blocker snapshot을 직접 결속한다.
- 저장소 내부 범위가 소진된 `COMPLETE_AWAITING_EXTERNAL` 진입이나 요청 basis 변경 때만 event-scoped add-only `EXTERNAL_ACTION_PACKET`을 만들며, `ISSUED_NOT_EVIDENCE` packet은 요청서일 뿐 완료·외부 attestation 증거가 아니다.
- 내부 frontier가 소진되면 artifact queue의 결정적 `next_assessment_target_id` 하나를 먼저 평가한다. 정확히 한 `ARTIFACT_WORK`를 materialize하거나 허용된 terminal/inactive disposition을 canonical transaction으로 기록하기 전에는 외부 대기 경계로 넘어가지 않는다.
- `POLICY_GAP_WORK`는 implementation record와 verification result를 먼저 확정해 그 두 hash만 새 Gap `evidence_catalog`에 넣고, Gap·Backlog bytes를 확정한 뒤 successor trace를 만든다. 이어 canonical update와 completion receipt가 세 결과를 모두 결속한다.
- `ACTIVE_EVENT_UPDATE` materialization은 정렬된 비어 있지 않은 trigger ref와 각 원자료의 exact direct binding을 가져야 한다. full gate의 19번째 `REPOSITORY_STATE`는 같은 event ID로 검사 후 저장소 상태를 다시 캡처하며, 제품 파일 변경 전에 receipt·event의 snapshot과 exact 일치해야 한다.

구현 중단 뒤 현재 bytes를 재개하는 snapshot reconcile, 새 통제 경로 membership 반영, 역사 `GOAL_STARTED/WORK_SESSION_RESUMED` 보존 순서는 `walksafe-project-resumption-runbook.md` §2.1·§9를 따른다.

이 디렉터리는 사용자 결정에서 정책 기준선, 257개 정식 산출물, 승인 적용, 구현 Gap, 후속 실행 기록까지 이어지는 통제 자료를 관리한다. 과거 문서나 현행 코드를 정책으로 역승격하지 않고, 승인된 기준에서 구현과 증거를 추적하는 것이 목적이다.

## 이번 단계의 산출물

| 파일 | 역할 | 편집 원칙 |
|---|---|---|
| `goals/README.md`, `goals/walksafe-completion-graph-v2-4/`, `goals/walksafe-completion-graph-v2-3/` | 고정 단계 수 없이 산출물·정책·Gap의 dependency DAG와 ready frontier로 이어지는 v2.4 전환 준비 패키지와 imported v2.3 Goal 20개 | v2.4는 native support 6개만 소유하고 `PREPARED_NOT_ACTIVATED`/`READY_NOT_ACTIVATED`를 유지한다. 원래 Goal 경로·bytes는 바꾸지 않으며 hash-bound 승인 전 활성화와 FP011 시작을 금지한다. v2.3 이하는 감사 이력이다 |
| `walksafe-project-resumption-runbook.md` | 문맥 유실 뒤 산출물 기반으로 프로젝트를 재개하는 사람용 안내서 | 정책을 새로 만들지 않고 정본·작업순서·중단조건·인계방법을 설명 |
| `walksafe-project-continuation-checkpoint.json` | 현재 EPIC, 변경 파일 snapshot, Goal·산출물 queue, 완료 경계, 검증 결과, blocker와 다음 행동을 담은 기계 인계자료 | 세션 종료와 중단 복구 reconcile 때 실제 상태와 함께 갱신하고 전용 검사기로 검증 |
| `execution/walksafe-epic-01-phase-a-implementation-record-20260722.*` | EPIC-01 1차 경계 구현의 정책·요구·Gap 연결과 내부 검증·남은 작업 | 정식 시험·출시 완료를 주장하지 않는 append-only 실행 증거 |
| `execution/walksafe-epic-01-phase-c-runtime-metric-preflight-implementation-record-20260722.*` | runtime metric preflight의 Android 11개 통제 경로와 내부 검증·운영 프로필 0·실기기 미실행 경계 | r001·r002·Phase B를 바꾸지 않는 append-only Phase C 실행 증거 |
| `execution/walksafe-epic-01-phase-c-active-ledger-overlay-20260722-r001.*` | Phase C 사건을 기존 Active 산출물에 연결하는 successor overlay | lifecycle·승인 상태를 바꾸지 않음 |
| `execution/walksafe-epic-01-phase-d-legacy-web-closure-implementation-record-20260723.*` | 공식 저장소의 Legacy UI·release·deploy·공개 launcher 기술 폐쇄와 정확히 4개 loopback BFF 임시 예외 | 외부 URL·기존 캐시 PWA·임의 수동 Next 차단을 완료로 주장하지 않는 append-only Phase D 실행 증거 |
| `execution/walksafe-epic-01-phase-d-active-ledger-overlay-20260723-r001.*` | Phase D 사건을 기존 Active 산출물에 연결하는 successor overlay | lifecycle·승인 상태를 바꾸지 않음 |
| `execution/walksafe-epic-01-phase-e-android-gateway-implementation-record-20260723.*` | 정확히 4개 API를 독립 Node Gateway로 추출하고 Android를 8081로 전환한 내부 구현 기록 | 배포·실기기 연결·정식 시험 완료를 주장하지 않는 append-only Phase E 증거 |
| `audits/walksafe-implementation-gap-analysis-20260723-r005.*`, `audits/walksafe-implementation-remediation-backlog-20260723-r005.*` | Phase E 영향 8개 Gap을 재평가했던 append-only 선행 진단 | Phase F 이후 직접 수정하거나 최신 정본으로 사용하지 않음 |
| `execution/walksafe-epic-01-phase-e-active-ledger-overlay-20260723-r001.*` | Phase E 사건을 기존 Active 산출물에 연결하는 successor overlay | lifecycle·승인 상태를 바꾸지 않음 |
| `execution/walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.*` | 목적·안전 한계 네 사용자 표면과 손상 점자블록 전용 신고 범위를 맞춘 내부 구현 기록 | 사용자 문서·릴리스 상태, 정식 시험·출시 상태를 승격하지 않는 append-only Phase F 증거 |
| `audits/walksafe-implementation-gap-analysis-20260723-r006.*`, `audits/walksafe-implementation-remediation-backlog-20260723-r006.*` | Phase F에서 `GAP-010`을 재평가하고 Phase G 작업을 정했던 append-only 선행 진단 | Phase G 이후 직접 수정하거나 최신 정본으로 사용하지 않음 |
| `execution/walksafe-epic-01-phase-f-active-ledger-overlay-20260723-r001.*` | Phase F 사건을 기존 Active 산출물에 연결하는 successor overlay | lifecycle·승인 상태를 바꾸지 않음 |
| `execution/walksafe-epic-01-phase-g-no-destination-hazard-implementation-record-20260723.*` | 목적지 없는 상태의 일반 위험안내와 경로 의존 점자블록 방향안내를 분리한 내부 구현 기록 | 실기기·정식 시험·출시 완료를 주장하지 않는 append-only Phase G 증거 |
| `audits/walksafe-implementation-gap-analysis-20260723-r007.*`, `audits/walksafe-implementation-remediation-backlog-20260723-r007.*` | Phase G 영향 Gap을 재평가하고 FP-017을 다음 작업으로 정했던 append-only 선행 진단 | EPIC-02 Phase A 이후 직접 수정하거나 최신 정본으로 사용하지 않음 |
| `execution/walksafe-epic-01-phase-g-active-ledger-overlay-20260723-r001.*` | Phase G 사건을 기존 Active 산출물에 연결하는 successor overlay | lifecycle·승인 상태를 바꾸지 않음 |
| `execution/walksafe-epic-02-phase-a-walk-session-lifecycle-implementation-record-20260723.*` | FP-017 보행 세션 상태기계와 background 안전중지·재검사·명시적 재개 확인의 내부 구현 기록 | 실기기·정식 시험·EPIC 완료·출시 가능을 주장하지 않는 append-only 증거 |
| `audits/walksafe-implementation-gap-analysis-20260723-r008.*` | FP-017 내부 slice 뒤 `GAP-026`을 `PARTIAL`로 재평가한 append-only bootstrap predecessor snapshot이자 successor 전까지의 canonical Gap binding | 현재 제품 bytes의 live currentness 생성물이 아니며 279개 정식 시험·실기기·5개 gate 미실행과 출시 제한을 유지 |
| `audits/walksafe-implementation-remediation-backlog-20260723-r008.*` | 구현 백로그 EPIC-02를 `IN_PROGRESS`로 전환하고 FP-018/GAP-027을 다음 단일 작업으로 정한 append-only bootstrap predecessor snapshot이자 successor 전까지의 canonical Backlog binding | graph Workstream은 `READY`; 현재 제품 bytes를 재생성하지 않고 정책을 새로 만들거나 부분 구현을 완료로 승격하지 않음 |
| `execution/walksafe-epic-02-phase-a-active-ledger-overlay-20260723-r001.*` | FP-017 내부 구현 사건을 기존 Active 산출물에 연결하는 successor overlay | lifecycle·승인 상태를 바꾸지 않음 |
| `artifact-types.json` | DOC~CLS 257개 산출물 유형별 목적·필수 내용·입력·승인·갱신·완료 기준과 40개 묶음 | 승인 정책 기준선에 결속된 machine-readable 작성계획 |
| `documentation-authoring-preparation-plan.md` | 산출물 통합 방식, 작성 순서, 검토·승인·기준선 계획 | 사람이 읽는 준비계획 |
| `questionnaire/walksafe-project-decision-questions.json` | 제품·정책·스펙·운영 결정을 묻는 질문 기준 데이터 | 질문의 canonical source |
| `questionnaire/source-conflict-audit.md` | 코드·설정·테스트와 현행·과거 문서의 충돌 후보 | 질문 생성 근거이며 제품 결정 자체가 아님 |
| `questionnaire/questionnaire-template.html` | 독립 실행 질문지의 UI 원본 | 질문 데이터와 분리해 관리 |
| `questionnaire/walksafe-project-decision-questionnaire.html` | 사용자가 답변하는 단일 HTML | 생성물이므로 직접 수정 금지 |
| `questionnaire/source-records/walksafe-project-decisions-20260717-answers.json` | 원본 286개 답변의 통제 사본 | 외부 수령 파일과 hash가 같은 불변 입력 |
| `questionnaire/source-records/walksafe-android-baseline-delta-review.json` | Android 후속 75개 답변 delta의 통제 사본 | 외부 수령 파일과 hash가 같은 불변 입력 |
| `questionnaire/walksafe-answer-review-analysis.json` | 제출 답변의 16개 영역별 의미와 핵심 불일치 분석 | 원답변을 바꾸지 않는 검토 자료 |
| `questionnaire/walksafe-answer-review-followups.json` | Android 전환과 남은 모호성을 해소하는 추가 질문 기준 데이터 | 추가 결정 질문의 canonical source |
| `questionnaire/answer-review-template.html` | 답변 검토·근거 보완·추가 질문 UI 원본 | 답변·분석 데이터와 분리해 관리 |
| `questionnaire/walksafe-project-decision-answer-review-20260718.html` | 사용자가 기존 답변을 검토하고 보완분을 내보내는 단일 HTML | 생성물이므로 직접 수정 금지 |
| `questionnaire/walksafe-integrated-baseline-analysis.json` | 원본·Android delta·최신 사용자 설명을 통합한 분야별 정의와 실제 성능 근거 | 확정·조건·충돌·출시 검토 상태를 분리 |
| `questionnaire/walksafe-integrated-baseline-questions.json` | 구현 자의성을 없애기 위한 분야·기능별 최종 확인 질문 | 추천은 자동 선택하지 않음 |
| `questionnaire/integrated-baseline-template.html` | 통합 정의·전체 기존 답변·최종 질문 UI 원본 | 외부 실행 의존성 없는 template |
| `questionnaire/walksafe-integrated-baseline-questionnaire-20260718.html` | 사용자가 현재 정의를 검토하고 새 답변 JSON을 만드는 단일 HTML | 생성물이므로 직접 수정 금지 |
| `decision-interview/walksafe-feature-policy-baseline.json` | 기존 답변을 54개 기능별 정책·작동·구현 상태로 합친 기준 데이터 | 틀린 해석은 기능 수정 기록으로 교정 |
| `decision-interview/walksafe-decision-responsibility.json` | 통합 감사 144개를 결정·기술제안·실측·전문가검토·증거·확정으로 분리 | IBQ 144개를 정확히 한 번씩 분류 |
| `decision-interview/walksafe-owner-decision-questions.json` | 사용자에게 실제로 남은 중복 없는 제품 결정 10개 | 이미 확정된 값과 기술 수치는 재질문하지 않음 |
| `decision-interview/walksafe-feature-policy-and-decision-review-20260718.html` | 현재 기능 정책을 쉬운 말로 읽고 수정·남은 답변을 내보내는 단일 HTML | 생성물이므로 직접 수정 금지 |
| `decision-interview/walksafe-feature-policy-decisions-20260718-answers.json` | 사용자가 제출한 10개 답변의 통제 사본 | 답변 원본을 수정하지 않음 |
| `decision-interview/walksafe-effective-baseline-rules.json` | 선택값을 정책문과 기능 영향으로 바꾸는 명시 규칙 | 해석 변경은 근거·검토와 함께 수행 |
| `decision-interview/walksafe-canonical-decision-trace.json` | 135개 결정·gate와 54개 기능의 추적표 | 독립 범위 검토 결과를 통합한 기준 입력 |
| `decision-interview/review-fragments/walksafe-decision-trace-*.json` | 추적표를 45개씩 독립 검토한 원기록 3개 | trace의 상대경로·hash와 함께 보존 |
| `decision-interview/walksafe-effective-decision-register.json` | 확정 후보와 기술·실측·전문가·증거 queue를 분리한 결정 원장 후보 | 생성물이므로 직접 수정 금지 |
| `decision-interview/walksafe-feature-policy-effective-candidate.json` | 사용자 답변을 반영한 54개 기능 정책 후보 | 생성물이므로 직접 수정 금지 |
| `decision-interview/walksafe-effective-policy-approval-review-20260719.md` | 1~6단계 결과와 추적성을 보존한 이전 요약 검토서 | 승인 전 감사용 생성물 |
| `decision-interview/comprehensive-report-fragments/walksafe-policy-proposals-fp-*.json` | 54개 기능과 151개 세부사항의 쉬운 추천 기준을 나눈 원본 | 종합보고서 생성 입력 |
| `decision-interview/walksafe-feature-policy-comprehensive-proposals.json` | 기능별 151개와 기능 간 공통 15개를 합친 166개 입력·결과·설계·금지·시험·추천안 자료 | 생성물이므로 직접 수정 금지 |
| `decision-interview/walksafe-feature-policy-comprehensive-review-20260719.html` | 기능별 전체 정책과 추천안을 비전공자가 검토하는 주 종합보고서 | 생성물이므로 직접 수정 금지 |
| `decision-interview/source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.json` | 종합 검토 76개 최종 답변의 byte-for-byte 통제 사본 | 수정하지 않고 후속본은 새 버전·새 hash로 등록 |
| `decision-interview/source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.intake.json` | 답변의 수령·통제 경로, hash, 버전, 변경 이유, 보고서 결속과 승인 경계 | Downloads는 provenance로만 기록 |
| `decision-interview/walksafe-feature-policy-review-resolution-rules.json` | 후속 확정의 우선순위·공통 상수·연쇄 영향·충돌 대체·남은 gate | 적용 의미가 바뀔 때 검토 후 편집 |
| `decision-interview/walksafe-feature-policy-review-resolution.json` | 76개 검토를 54개 기능에 연결한 다음 정책 작성 단계의 통제 입력 | 생성물이므로 직접 수정 금지, 정식 산출물 아님 |
| `decision-interview/walksafe-feature-policy-document-rules.json` | 후속 확정과 충돌한 과거 문장 억제·교체 및 승인 경계를 고정한 작성 규칙 | 정책 문장을 바꿀 때 근거와 함께 편집 |
| `decision-interview/walksafe-feature-policy-comprehensive-draft.json`, `walksafe-feature-policy-comprehensive-draft-20260720.html` | 공통정책 9개와 기능 54개의 전체 작동·실패·데이터·시험 정책과 최종 검토 화면 | 사용자가 실제 검토한 근거 원문, 직접 수정 금지 |
| `decision-interview/source-records/walksafe-feature-policy-baseline-review-20260720-r001-answers.json`, `.intake.json` | 최종 63개 검토 답변의 byte-for-byte 통제 사본과 반입 manifest | 새 접수는 새 revision으로 보존 |
| `decision-interview/walksafe-feature-policy-baseline-review-resolution-20260721-r001.json` | 63/63 확정 결과, 검토 원문·결정 지문, 남은 검증 5개와 별도 승인 경계를 고정한 검토 종결 기록 | 기준선 승인 기록은 아니며 r001 파일을 직접 수정하지 않음 |
| `baselines/walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json` | 사용자의 명시적 정책 승인 원문과 승인 범위를 결속한 기록 | r001 승인 기록을 직접 수정하지 않음 |
| `baselines/walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json` | 승인 정책 본문·검토·답변·승인 기록의 hash와 남은 gate를 결속한 기준선 manifest | 정책 변경은 새 승인 입력과 r002 이상 manifest로 처리 |
| `baselines/walksafe-artifact-baseline-candidate-20260721-r001.json` | 완료성·선행승인 감사를 거쳐 257개를 기준선 적격 2·Active 최초본 2·Planned/NOT_RUN 75·Draft 보류 121·조건·값 대기 57로 나누고, DOC-01→DOC-03·04→DOC-05 전환 순서와 복합 승인 단위·파일 지문을 결속한 기계 판독 정본 | 승인 후보일 뿐 승인 기록이 아니며 직접 수정하지 않음 |
| `baselines/walksafe-artifact-baseline-candidate-review-20260721-r001.md`, `.html` | 비전공자가 분류 근거와 정확한 일괄 승인문을 읽고 복사할 수 있는 파생 검토서 | 필터·복사로 상태가 바뀌지 않으며 JSON을 정본으로 삼음 |
| `../deliverables/` | DOC~CLS 전체 257개 관리대장·40개 문서 묶음과 0~12 정식 산출물 | 승인 적용 뒤 현재 상태는 102 Approved/Baselined, 27 Active, 53 Draft, 75 Planned/NOT_RUN. 작성 시점의 과거 Draft 표기는 COMMITTED 영수증과 DOC-01 상태 overlay로 대체됨 |

## 신뢰 우선순위

1. 사용자가 질문지에 답하고 충돌 검사를 통과한 뒤 승인한 결정 기준선
2. 사용자가 별도로 명시적으로 확정한 결정
3. 현재 코드·설정·계약·실행 증거
4. 현재 문서의 후보 정책과 상태 설명
5. 날짜가 지난 계획·실행 기록·과거 플랫폼 기준 문서
6. 제출용 DOCX·PPTX·PDF와 자동 생성 요약

코드가 현재 동작을 보여줄 수는 있지만 사용자가 원하는 제품 정책을 결정하지는 않는다. 기존 문서는 질문 후보와 충돌 근거로 사용하며, 사용자 답변 전에는 그 내용을 새 문서의 확정 정책으로 복사하지 않는다.

## 질문 근거의 관찰 상태

아래 값은 질문 JSON의 `evidence.classification`에 쓰는 현재 관찰 상태다. DOC-01의 `source_class`(자료의 출처·통제 형태)와 `adoption_trust`(실제 채택 신뢰도 A~U)는 별도 축이며 서로 대체하지 않는다. 예를 들어 source class가 `IMPLEMENTATION_EVIDENCE`인 코드도 제품 의도와 충돌하면 질문 근거 관찰 상태는 `CONFLICTING`일 수 있다.

| 판정 | 의미 |
|---|---|
| `USER_CONFIRMED` | 사용자가 명시적으로 확정했으며 이후 변경 결정이 없음 |
| `CONFIRMED_BY_CODE` | 현재 코드·설정·테스트에서 동작 또는 계약이 확인됨 |
| `CURRENT_CANDIDATE` | 현재 문서나 구현이 제안하는 값이지만 사용자 승인 전 |
| `CONFLICTING` | 둘 이상의 문서·코드·결정이 서로 다름 |
| `STALE` | 과거 기준 또는 superseded 전제를 사용함 |
| `UNKNOWN` | 판단에 필요한 근거가 없음 |

`UNKNOWN` 항목도 확인한 저장소 경로는 기록한다. 연결할 출처 자체가 없으면 빈 경로의 가짜 evidence를 만들지 않고 해당 질문의 `evidence` 배열을 비워 둔다.

## 완료된 질문·작성·승인 이력

아래 1~11번 활동은 이미 완료된 이력이다. 기존 결정을 다시 묻거나 날짜가 붙은 생성기를 현재 상태 전환용으로 재실행하지 않는다. 현재는 12번의 구현·검증·새 revision 기록 활동을 의존성 그래프에 따라 수행한다.

1. 257개 산출물 유형의 작성 요구를 계획한다.
2. 기존 자료를 신뢰 등급과 현재 역할로 분류한다.
3. 사용자 결정을 원자적인 질문으로 제시한다.
4. 필수 질문 100% 응답과 오류 수준 충돌 0건을 확인한다.
5. 답변 JSON의 선택값·결정 메모·플랫폼 전환 영향을 영역별로 검토한다.
6. 누락된 결정 근거와 추가 질문을 별도 delta로 보완한다.
7. 원답변·delta·최신 직접 설명을 기능별 현재 정책과 실제 작동 흐름으로 합친다.
8. 통합 감사 144개를 사용자 결정·기술 제안·실측·전문가 검토·생성 증거·이미 확정으로 분류한다.
9. 이미 확정된 내용은 다시 묻지 않고, 제품책임자에게 남은 중복 없는 결정만 한 번씩 확인한다.
10. 사용자 수정과 답변, 기술·실측·전문가 gate를 합친 effective 결정 원장을 사람이 승인한다.
11. 승인된 결정만 사용해 제품·요구사항·설계·시험 문서를 의존성 순서대로 작성한다.
12. 코드·설정·테스트와 문서가 달라지면 변경요청과 영향 분석을 거쳐 새 기준선을 만든다.

## 현재 위치

질문·검토·정책 승인을 마쳤고, FP-035 정정을 포함한 현재 정책 기준선은 `PB-WALKSAFE-FEATURE-POLICY-1.0.1`이다. 257개 산출물 승인 적용 거래도 `COMMITTED`됐다. 현재 상태는 102개 `Approved/Baselined`, 27개 계속 갱신형 `Active`, 53개 `Draft`, 75개 `Planned/NOT_RUN`이다. 이 수량과 각 파일 위치는 [257개 최상위 안내](../deliverables/README.md), DOC-01, COMMITTED 승인 영수증을 정본으로 삼는다.

산출물 작성·최초 승인 뒤 FP-017·FP-018·NPC permission session·FP-004·FP-005·FP-006·FP-010의 저장소 내부 작업을 순차 완료했다. 활성 v2.3 predecessor는 sequence 17, focus `WS-GOAL-EPIC-02-FP-011-R001` `READY`에서 byte-exact 동결됐다. 이를 가져오는 v2.4 successor는 imported Goal 20개와 native support 6개로 준비됐지만 `PREPARED_NOT_ACTIVATED`/`READY_NOT_ACTIVATED`다. 새 package ID, 최종 manifest와 seq1 hash에 대한 명시적 승인 전에는 활성화나 FP011 `GOAL_STARTED`를 수행하지 않는다. 실제 기기와 정식 시험 279개는 모두 `NOT_RUN`, 남은 gate 5개는 모두 `NOT_RUN`·미면제이고 출시는 `NOT_ELIGIBLE`이다.

## 질문지 개인정보·보존 원칙

- 질문 HTML은 외부 네트워크 요청 없이 동작한다.
- 답변은 기본적으로 현재 브라우저의 `localStorage`에만 저장한다.
- 다른 기기나 브라우저로 옮길 때는 사용자가 JSON을 명시적으로 내보내고 가져온다.
- 내보낸 답변에는 비밀번호, API key, 인증서 private key나 서비스 최종사용자 개인정보를 입력하지 않는다.
- 검토자·승인자 이름은 책임 추적에 필요한 내부 통제정보로만 허용하며 `INTERNAL`·개인정보 포함 표시와 보존등급을 함께 기록한다.
- 추천안은 자동 선택하지 않으며, 사용자의 명시적 응답만 결정 후보로 기록한다.
- 비추천안·사용자 정의·수치 결정·경고 수용은 구체 값·예외·근거·책임자를 결정 메모로 남겨야 완료된다.

## 과거 준비 단계의 완료 조건

아래 조건은 질문지와 산출물 작성 준비 단계에서 사용한 역사적 완료조건이며 현재 할 일 목록이 아니다. 현재 진행 기준은 재개 안내서와 체크포인트를 따른다.

이 역사적 준비 단계에서는 다음을 완료조건으로 사용했다.

- 산출물 유형 257개가 중복 없이 등록됨
- 모든 산출물 유형에 목적·필수 내용·입력·책임·갱신·완료 기준이 있음
- 질문 데이터의 ID·범주·의존성·추천안·충돌 규칙·근거 경로가 유효함
- 조사된 기존 충돌과 상태기계·운영 gap이 빠짐없이 질문 ID로 추적됨
- 필수 제품 결정 영역에 고아 범주가 없음
- 생성 HTML이 데스크톱과 모바일에서 열리고 저장·가져오기·충돌 검사·내보내기가 동작함
- 사용자 답변 전에는 새 제품 정책을 확정했다고 주장하지 않음
