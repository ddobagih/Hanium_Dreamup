# Python 테스트

루트 `tests/`는 Python 제품 회귀, WalkSafe Goal 제어·역사 adapter와 AIHub189 depthprediction offline evaluator 테스트를 담습니다.

| 파일 | 검증 범위 |
| --- | --- |
| `test_walksafe_epic01_phase_c_trace_20260722.py` | Phase C 11개 Android 구현 경로, 생산 승인 프로필 0개, GAP-018 `PARTIAL`, r003 단일 재평가, 기존 r001/r002·Phase B 불변, actual-device·279개 정식시험·5개 gate `NOT_RUN`과 `NOT_ELIGIBLE` 경계를 검증 |
| `test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py` | Phase D 불변 지문, 독립 Gateway 구현 snapshot, r005 영향 재평가, Next allowlist 0, Active successor와 배포·실기기·279개 시험·5개 gate 비승격을 변조 거부로 검증 |
| `test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py` | 네 사용자 표면의 목적문·안전 한계, 손상 점자블록 전용 신고 범위, r006의 GAP-010 `PARTIAL`, 다음 목적지 없는 위험안내 작업과 정식시험·gate·출시 비승격을 변조 거부로 검증 |
| `test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py` | 목적지 없는 일반 위험안내와 경로 의존 점자블록 방향안내의 분리, Phase F 불변 선행근거, r007·Active successor와 실기기·279개 정식시험·5개 gate·출시 비승격을 변조 거부로 검증 |
| `test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py` | FP-017 상태기계·background 정지·재검사·정확한 재개 확인의 13개 구현 경로, Phase G 10개 불변 선행자료, GAP-026 `PARTIAL`, EPIC-02 `IN_PROGRESS`, r008·Active successor와 실기기·279개 정식시험·5개 gate·출시 비승격을 변조 거부로 검증 |
| `test_walksafe_fp005_official_environment_trace_20260724.py` | FP-005의 20개 구현 경로·v2.3 시작 게이트·실행 세션을 정확히 결속하고, GPS/카메라 fail-closed·횡단보도 비권위 경계, GAP-014 `MISSING → PARTIAL`, r012·Active overlay, FP-006/GAP-015 후속 선택과 생산 프로필·현장·사용자·실기기·정식 시험 비승격을 검증 |
| `test_walksafe_goal_graph_v2_4.py` | 승인 전 v2.4 후보와 FP-011 READY 시점의 Goal graph를 재현하는 역사 baseline. 현재 runner의 historical inventory로 보존하며 현재 checkpoint gate로 실행하지 않음 |
| `test_walksafe_project_continuation_v2_4.py` | v2.3·v2.4 선행 경계부터 현재 seq57까지의 transition lifecycle을 검증하는 현재 singleton. `active-session-control`은 이 테스트 전에 strict continuation checker로 현재 checkpoint와 managed bytes를 먼저 확인함 |
| `test_walksafe_goal_graph_v2_3_history.py` | frozen `test_walksafe_goal_graph_v2_3.py`를 v2.4의 byte-exact v2.3 archive에만 재생하고 현재 v2.4 checker가 유효한지 확인하는 historical adapter |
| `test_walksafe_epic02_trace_v2_3_history.py` | 완료된 FP-005·FP-006·FP-010 원본 trace와 builder hash를 고정하고 v2.3 archive를 대상으로 재생하는 historical adapter |
| `test_walksafe_goal_graph_v2_3.py`, `test_walksafe_project_continuation_v2_3.py` | frozen v2.3 predecessor suite. 현재 v2.4 후보에 직접 적용하거나 수정하지 않고 `HISTORICAL_CONTROL_PYTHON_TESTS`에 보존 |
| `test_walksafe_project_continuation.py` | 과거 unversioned checkpoint 1.9.0과 당시 EPIC-02 연결을 재현하는 역사 검사. 현재 checkpoint에는 직접 실행하지 않고 `HISTORICAL_CONTROL_PYTHON_TESTS`에 보존 |
| `test_walksafe_product_quality_receipt.py`, `test_walksafe_operator_attestation.py`, `test_release_evidence_gate.py`, `test_submission_toolchain_host_lock_20260713_history.py` | Web을 필수 제품으로 묶은 2026-07-13 Full-RC 품질·operator·release evidence와 당시 LibreOffice host bytes를 재현하는 역사 검사. 현재 Android 사용자·관리자·Gateway의 commit-stable `all`에는 포함하지 않음 |
| `test_walksafe_goal_package.py` | 활성화되지 않고 대체된 A~D Goal package 후보를 재현하는 역사 검사. 현재 Goal graph·세션 통제로 사용하지 않고 history inventory에만 보존 |
| `test_walksafe_android_product_boundary.py` | EPIC-01의 Android 사용자·관리자 앱 ID·역할·배포·세션 분리 계약, 잠긴 관리자 앱, 제품 목적·지원기기·출시 fail-closed 설정 |
| `test_walksafe_android_gateway_boundary_20260723.py` | Phase E 당시 독립 Gateway 5-route snapshot을 재현하는 역사 검사. 현재 9 path/13 operation 계약에는 직접 실행하지 않음 |
| `test_walksafe_legacy_web_boundary_20260722.py` | Phase D의 전환형 Next BFF 4개 보존 경계를 재현하는 역사 검사이며 Phase E 현재성 판정에는 사용하지 않음 |
| `test_voice_intents.py` | 한국어 음성 intent 분류, 실행 정책, telemetry schema |
| `test_voice_tts.py` | TTS cache/fallback, API 오류 처리, phrase 계약 |
| `test_aihub189_depthprediction_offline.py` | nested zip을 추출하지 않는 ZED depth 입력 파싱과 offline 평가 |
| `test_walksafe_feature_policy_resolution.py` | 종합 검토 답변의 hash·76/54 완전성, 수정/후속 메모 분리, 용량·보존·자동신고·경로·cascade, 승인 경계와 결정적 재생성 |
| `test_walksafe_feature_policy_document.py` | 기능별 종합 정책서의 54개 기능·9개 공통정책·22개 기존 검토정책·76개 적용기록·135개 결정 추적, 과거 문구 제거, 정상/실패/자료/임시 안전규칙, 원천·승인·구현상태 위조 거부, 63개 검토 UI와 정적 HTML 접근성 |
| `test_walksafe_feature_policy_baseline_review.py` | 최종 답변 63/63 확정·원본/통제본·검토 문서 지문 결속, 무변경 재생성 판단, 별도 승인·5개 gate·출시·정식 산출물 경계와 변조 거부 |
| `test_walksafe_feature_policy_baseline_approval.py` | 사용자 승인 원문·승인 대상·결정 지문·정책 내용 지문, 승인 기록·기준선 manifest, 5개 미실행 gate와 출시 제한의 변조 거부 |
| `test_walksafe_effective_decision_register_alignment.py` | 승인 기준선과 135개 결정·428개 기능 연결·9개 공통정책·5개 gate 정렬, 기존 역사 원장 보존과 승인 과장 방지 |
| `test_walksafe_control_bootstrap.py` | 최초 DOC~CLS 257개 bootstrap bytes를 재현하는 역사 검사. 현재 승인·successor 산출물에는 직접 실행하지 않으며 current runner의 history inventory로만 보존 |
| `test_walksafe_formal_management_discovery.py` | MGT 18·DSC 15의 path/anchor/manifest 완전성, 54개 backlog, 5개 열린 gate, 조사·예산·일정 결과 미조작과 Android/Web 경계 |
| `test_walksafe_formal_dev_test.py` | DEV 21·TST 23의 형상·모듈·증거 경계, 279개 실행 가능한 계획 구조, append-only 실행 원장, 전부 NOT_RUN인 결과와 5개 gate·출시 제한 |
| `test_walksafe_requirements_draft.py` | REQ 19, 상위 요구 68·clause 1,460·인수조건/예정시험 279, 결정 135·연결 428, standalone RTM와 Draft/NOT_RUN 경계 |
| `test_walksafe_design_deliverables.py` | DES 27의 canonical 문서·anchor·추적 원장, 정책·요구·흐름·gate 완전성, 후보 구현과 설계 완료 주장의 분리 |
| `test_walksafe_trace_integration_report.py` | 68개 요구↔27개 설계 정·역방향, 279개 인수조건↔시험, 시험·모듈의 설계 연결, 입력 파일 지문, FP-035 차단, 5개 gate와 승인·출시 경계를 leaf 보고서에서 검증 |
| `test_walksafe_formal_deliverables_0_6.py` | 0~6 전체 128개·21개 묶음과 REQ→DES→DEV/TST 종합 추적, source/generated hash, 조건부 판정, gate·승인·출시 경계를 한 번에 검증 |
| `test_walksafe_formal_sec_ws.py` | SEC 19개·WS 22개의 Draft/Planned 분리, 6개 문서 묶음·anchor·정책·보존·용량·권한·안전 계약과 실제 보안·현장·기기·E2E 증거 0건 경계를 검증 |
| `test_walksafe_formal_aiml.py` | AIML 26개의 4개 묶음·원장·정책·결정 추적과 실제 학습·평가·동등성·기기 측정·통제 source 증거가 Planned/NOT_RUN인지 검증 |
| `test_walksafe_formal_rel_ops_cls.py` | REL 22개·OPS 24개·CLS 16개의 9개 묶음, 릴리스·운영·종료 경계, 실제 배포·서명·사건·훈련·종료 증거 0건과 NOT_ELIGIBLE/ACTIVE_NOT_CLOSED를 검증 |
| `test_walksafe_formal_trace_7_12.py` | SEC~CLS 129개·19개 묶음의 Draft/Planned 경계, 모든 section anchor·의존성·정책·결정·gate·file hash와 실행 증거 과장 금지를 종합 검증 |
| `test_walksafe_artifact_baseline_candidate.py` | 257개 산출물의 기준선 2·Active 2·Planned 75·보류 121·조건·값 대기 57 전수 분류, 선행 승인 폐쇄집합과 3단계 원자적 전환, 복합 승인 단위·파일 지문, FP-035·5개 gate·NOT_ELIGIBLE 및 승인 전 상태 불변을 검증 |
| `test_walksafe_fp035_correction_candidate.py` | FP-035 정정 후보의 정확한 문구·영향 범위·미승인 상태와 5개 gate·출시 제한을 검증 |
| `test_walksafe_artifact_content_readiness_audit.py` | 257개 재평가 결과 129개 승인 후보와 128개 대기 항목, 파일 결속, 승인 과장 금지를 검증 |
| `test_walksafe_artifact_independent_review_record_20260722.py` | 세 기술검토의 독립 범위·PASS·source binding과 사람 승인·외부 전문검토·실행증거 경계를 검증 |
| `test_walksafe_artifact_baseline_candidate_20260722.py` | 새 후보의 102+27 승인 후보, 53+75 미승인, FP-035 0단계, 의존성 순서, 해시, exact 승인문과 상태 불변을 검증 |
| `test_walksafe_artifact_baseline_approval_20260722.py` | exact 승인문·승인 준비 기록·전환 전 snapshot·정책 1.0.1·257개 상태 전환 계획의 지문과 승인 경계를 검증 |
| `test_walksafe_artifact_baseline_materialization_20260722.py` | 102+27 승인 반영, 53+75 불변, 단계 실패 rollback, receipt 마지막 기록과 재실행 멱등성을 검증 |
| `test_walksafe_artifact_temporal_provenance_supplement_20260722.py` | COMMITTED receipt의 효력시각 표기를 보정한 별도 부록의 내용 지문·원본 결속·승인 유효성·출시 제한을 검증 |
| `test_walksafe_implementation_gap_analysis_20260722.py` | 승인 정책·요구·설계·시험과 동결 구현의 68개 전수 Gap, 직접 근거·해시, 279개 NOT_RUN, 5개 BLOCKED gate, 수정 백로그 완전성, HTML 필터와 NOT_ELIGIBLE 경계를 검증 |

```bash
python3.12 -m venv .venv-tests
export PYTHON_BIN="$PWD/.venv-tests/bin/python"
"$PYTHON_BIN" -m pip install --require-hashes --only-binary=:all: --no-compile \
  -r tests/general-quality-cp312-linux-x86_64-cpu.lock
test -x "${WALKSAFE_NODE_BIN_DIR:?}/node"
PYTHON_BIN="$PYTHON_BIN" scripts/run_walksafe_test_layers_current.sh validate
PYTHON_BIN="$PYTHON_BIN" scripts/run_walksafe_test_layers_current.sh unit
```

`unit` 실행 전에 [개발 환경 가이드의 exact Node.js 절차](../docs/guides/development-environment-guide.md#nodejs)로 만든 `WALKSAFE_NODE_BIN_DIR`과 Web·Gateway의 `npm ci`, Java 21·Android SDK가 준비돼 있어야 한다. runner는 Node 22.23.1이라는 버전 문자열만 보지 않고 지정한 root의 전체 toolchain hash를 검사하므로 ambient `command -v node` 경로를 대신 사용하지 않는다.

`integration`과 `all`은 `tests/test_walksafe_backup_integrity.py`를 일반 CPython 3.12 묶음과 분리해 실행한다. `tests/backup-integrity-cp314.lock`만 설치한 정확한 CPython 3.14.6/Linux venv의 절대경로를 `WALKSAFE_BACKUP_PYTHON_BIN`에 지정해야 하며, 누락되거나 memfd sealing preflight가 실패하면 일반 `PYTHON_BIN`으로 대체하지 않고 실패한다.

프로젝트 전체의 Unit/Functional/Integration 계층 실행 방법은
`docs/testing/test_layers_20260711.md`와 `scripts/run_walksafe_test_layers_current.sh`를 따른다. 루트 `tests/` 전체를 직접 수집하면 역사·활성 세션·helper가 보존되지 않은 테스트까지 현재 계층으로 섞이므로 현행 runner를 우회하지 않는다.
`backend/requirements.txt`와 `tests/requirements.txt`는 직접 의존성 source input이다. GitHub hosted CI의 CPython 3.12/Linux x86_64 환경은 두 입력과 정확한 CPU-only PyTorch wheel로 생성한 단일 `tests/general-quality-cp312-linux-x86_64-cpu.lock`을 `--require-hashes --only-binary=:all: --no-compile`로 설치한다. 백업 검사 전용 `.in`·lock은 일반 환경과 분리한다. 이 플랫폼 lock을 다른 Python/OS/architecture의 로컬 환경에 재사용하지 않는다. 다른 환경에서 모듈 lock을 사용할 때는 Pillow pin이 충돌하는 `backend/requirements.lock`과 `tests/requirements.lock`을 한 환경에 함께 설치하지 말고 Backend·root 테스트 환경을 분리한다. 일반 테스트 의존성을 가진 `PYTHON_BIN`, 백업 검사 전용 `WALKSAFE_BACKUP_PYTHON_BIN`, 제출물용 bytecode 없는 exact-8 `SUBMISSION_PYTHON`은 서로 다른 환경이다. 후자는 artifact clean-room 절차에서 production runner `--verify-only`로 builder보다 먼저 검증하며, 누락이나 불일치는 skip하지 않고 실패한다.

`scripts/run_walksafe_test_layers_current.sh`는 frozen v2.3 제어·완료된 Goal snapshot, Git에서 제외된 기존 제출 후보 자료에 직접 결속된 검사, 5-route Gateway·cleartext depth scaffold·Legacy Web Full-RC의 대체된 snapshot 검사를 `HISTORICAL_CONTROL_PYTHON_TESTS`에 둔다. 활성 v2.4 checkpoint의 managed snapshot·transition lifecycle을 재검증하는 검사는 `ACTIVE_SESSION_CONTROL_PYTHON_TESTS`에 둔다. 날짜가 붙은 `scripts/run_walksafe_test_layers_20260711.sh`는 과거 해시 결속용 동결 자료이며 현행 실행기가 아니다. active-session PASS는 현재 `package_status=ACTIVE`, NPC single-admin-recovery focus `READY`인 checkpoint와 managed content·transition 검사 코드가 일치한다는 뜻일 뿐 focus 완료, 279개 정식 시험, release gate나 출시 상태를 승격하지 않는다.

CI는 Web 출시 artifact를 생성하지 않는다. Web/PWA 검사는 `LEGACY_REFERENCE_ONLY` 회귀 범위로만 실행하며 Android 출시·실폰·현장 증거로 승격하지 않는다.

모델 runtime helper 테스트는 `model/test_two_model_runtime.py`에, Android 테스트는 `apps/android/app/src/test/`와 `apps/android/app/src/androidTest/`에 있습니다. depthprediction 테스트는 저장된 ZED 형식의 offline 계약을 검증하며 Android ARCore 현장 동작을 대신하지 않습니다.
