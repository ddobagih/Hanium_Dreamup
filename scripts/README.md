# 프로젝트 실행·검증 스크립트

이 폴더는 여러 앱과 모델 영역을 연결하는 실행 wrapper, 정적 계약 검사, smoke/evaluation, export 도구를 모읍니다. 도메인 내부 데이터 변환은 `data_sources/scripts/`가 담당합니다.

## 분류

| 이름 | 책임 |
| --- | --- |
| `build_walksafe_epic01_phase_c_trace_20260722.py` | EPIC-01 runtime metric 사전검사 내부 구현 11개 경로를 고정하고, 기존 r001/r002·Phase B를 바꾸지 않은 채 Phase C 기록·Gap/Backlog r003·Active overlay를 재생성·검증. 실기기·정식시험·출시 증거로 승격하지 않음 |
| `build_walksafe_epic01_phase_e_android_gateway_trace_20260723.py` | 독립 Android API Gateway 추출, Android 8081 전환, Legacy Next allowlist 0과 Phase D 불변 선행근거를 묶어 Phase E 기록·Gap/Backlog r005·Active overlay를 재생성·검증. 배포·실기기·정식시험·출시 완료를 주장하지 않음 |
| `build_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py` | Android 첫 화면·동의·사용설명·미게시 릴리스 설명의 제품 목적·안전 한계와 손상 점자블록 전용 신고 범위를 묶어 Phase F 기록·Gap/Backlog r006·Active overlay를 재생성·검증. 정식시험·출시 완료로 승격하지 않음 |
| `build_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py` | 목적지 없는 일반 위험안내와 경로가 필요한 점자블록 방향안내의 의존성을 분리한 구현을 묶어 Phase G 기록·Gap/Backlog r007·Active overlay를 재생성·검증. 실기기·정식시험·gate·출시 상태를 승격하지 않음 |
| `build_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py` | FP-017 보행 상태기계·background 정지·재검사·정확한 재개 확인의 13개 구현 경로와 Phase G 불변 선행자료를 묶어 EPIC-02 Phase A 기록·Gap/Backlog r008·Active overlay를 재생성·검증. GAP-026은 `PARTIAL`, EPIC-02는 `IN_PROGRESS`로 유지하고 실기기·정식시험·gate·출시 상태를 승격하지 않음 |
| `build_walksafe_fp005_official_environment_trace_20260724.py` | FP-005 공식 사용환경·GPS/카메라 품질·횡단보도 비권위 안내의 정확한 20개 구현 경로와 v2.3 시작 게이트를 결속해 실행 증거, Gap/Backlog r012, Active overlay를 재생성·검증. GAP-014는 `PARTIAL`로 재평가하고 생산 프로필·현장·사용자·실기기·정식 시험은 미승인·`NOT_RUN`으로 유지 |
| `build_walksafe_goal_graph_v2_4.py` | 활성 v2.3 seq17 checkpoint와 Goal 문서 20개를 원래 경로·bytes 그대로 가져오고 native support 6개만 생성해 v2.4 successor를 `PREPARED_NOT_ACTIVATED`/`READY_NOT_ACTIVATED` 후보로 준비. seq1 `PACKAGE_PREPARED`만 만들며 최종 manifest hash 승인, package 활성화, FP-011 `GOAL_STARTED`는 수행하지 않음 |
| `check_walksafe_project_continuation_v2_4.py` | byte-exact v2.3 seq17 archive와 승인 전 v2.4 후보의 준비·활성화·FP-011 시작 경계를 읽기 전용으로 검증하고, 이후 append-only event를 Goal ID에 종속되지 않은 replay 계약으로 검사. `--print-working-snapshot-hashes`와 `--print-gate-repository-state`도 v2.4 계약으로 제공 |
| `check_walksafe_goal_graph_v2_4.py` | v2.3 archive의 20개 Goal projection, v2.4 native support 6개, Master→Workstream→동적 Work Item DAG와 ready frontier를 검증. v2.3 checker는 archive 감사에만 사용하고 새 제어 로직은 v2.4 경로에서 검사 |
| `build_walksafe_goal_graph_v2_3.py`, `check_walksafe_project_continuation_v2_3.py`, `check_walksafe_goal_graph_v2_3.py` | frozen v2.3 predecessor 제어 도구. 현재 후보의 활성 진입점으로 직접 사용하거나 수정하지 않고 v2.4 archive와 `test_walksafe_goal_graph_v2_3_history.py`를 통한 역사 감사에만 사용 |
| `check_walksafe_project_continuation.py`, `check_walksafe_goal_graph.py` | frozen v2.2 checker. 현재 후보의 활성 진입점으로 사용하지 않고 `test_walksafe_goal_graph_v2_2_history.py` adapter를 통한 predecessor 감사에만 사용 |
| `tests/test_walksafe_goal_graph_v2_3_history.py`, `tests/test_walksafe_epic02_trace_v2_3_history.py` | v2.4가 보존한 v2.3 archive를 대상으로 각각 frozen Goal 제어 suite와 완료된 FP-005·FP-006·FP-010 trace를 재생하는 history adapter. adapter와 원본 v2.3 제어·trace tests 모두 current runner의 historical inventory로만 유지 |
| `tests/test_walksafe_epic02_trace_v2_2_history.py` | v2.2 archive를 기준으로 frozen FP-018·NPC·FP-004 trace 23개를 재생하는 history adapter. adapter와 원본 trace tests 모두 current runner의 historical inventory이며 현재 checkpoint gate로 실행하지 않음 |
| `check_walksafe_goal_package.py` | 실행되지 않은 v1 A~D checker source를 역사 호환용으로 보존. v2 checkpoint에서 직접 실행하면 expected fail이며 archive 감사·현재 실행·활성화 진입점으로 사용하지 않음 |
| `check_walksafe_android_gateway_boundary_20260723.py` | Phase E 당시의 5-route Gateway snapshot을 재현하는 역사 검사. 현재 9 path/13 operation Gateway 계약에는 직접 실행하지 않음 |
| `check_walksafe_legacy_web_boundary_20260722.py` | Phase D 당시 정확한 Next BFF 4개 예외를 검증하는 불변 역사 검사. 두 날짜형 boundary checker는 history inventory로만 보존하고 현재 Gateway는 package tests·OpenAPI currentness로 검사 |
| `check_frontend_*`, `check_pwa_server_e2e.py`, `check_pwa_browser_lifecycle_20260711.py`, `check_pwa_release_update_20260717.py` | `LEGACY_REFERENCE_ONLY` Web/PWA의 과거 계약·회귀 snapshot 검사. Android 정식 제품이나 현재 출시 증거로 사용하지 않음 |
| `check_walksafe_trusted_proxy_20260716.py` | 과거 Web gateway nginx 예제의 재현 검사. 현행 Android API gateway 배포 검사가 아님 |
| `build_walksafe_web_release_20260711.sh`, `create_walksafe_web_build_manifest_20260711.py`, `check_walksafe_release_evidence_20260711.py`, `walksafe_external_check_receipt.py` | `LEGACY_REFERENCE_ONLY` Web 출시 묶음의 역사적 재현 자료. builder와 release evidence의 `web-release`·`full` CLI는 역사 코드를 불러오기 전에 78로 종료하며 현재 CI·배포·출시 후보에서 사용 금지 |
| `walksafe_release_integrity.py`, `walksafe_android_dex_binding.py`, `run_walksafe_isolated_python_20260713.py`, `run_walksafe_product_quality_20260713.py`, `build_walksafe_full_rc_20260713.py`, `validate_walksafe_full_rc_20260713.py`, `verify_walksafe_operator_attestation_20260713.py`, `verify_walksafe_signed_android_release_20260713.py` | Web을 필수 구성요소로 묶었던 2026-07-13 RC 계약의 역사적 재현 자료. Web quality 분기와 full-RC builder·validator CLI는 78로 종료하며, Android 사용자·관리자 앱과 독립 API gateway를 반영한 새 릴리스 계약으로 대체하기 전까지 정식 산출물을 만들 수 없음 |
| `run_walksafe_submission_python_20260714.py` | 제출 builder·validator·visual audit·promotion의 canonical 7개 진입점만 `-I -S -B`에서 실행한다. private Git 환경에서 승인 HEAD/index/tracked bytes와 generated-output 외 untracked closure, 정확한 8개 distribution set, RECORD 소유 site closure와 toolchain lock의 per-distribution file bundle을 제3자 import 전후에 검증하며 공용 격리 bootstrap을 SHA-pin해 재사용한다. |
| `check_frontend_voice_report_wiring_20260711.py` | Web STT intent→신고 callback→동일 분석 프레임 v2 제출과 background/audio-focus 취소 경로 정적 검사 |
| `check_detect_*`, `evaluate_predictions_*`, `evaluate_yolo_*` | backend detection/report 계약과 저장 예측 평가 |
| `check_navigation_*`, `check_tmap_*` | 길안내·reroute·TTS 타이밍 검사 |
| `check_voice_*`, `test_stt.py`, `test_tts.py` | 음성 계약과 로컬 STT/TTS smoke |
| `check_android_*`, `export_android_tflite_models_20260531.py` | Android model asset 정적 계약과 legacy export. cleartext debug를 요구하던 `check_android_depth_scaffold_20260531.py`는 대체된 역사 snapshot |
| `check_android_apk_model_asset_20260713.py` | 빌드 APK의 runtime config가 source와 byte-identical인지, 설정된 3개 model asset만 있고 hash가 맞는지, env/log/test/fixture payload가 없는지 검사 |
| `prepare_android_field_device_20260710.sh`, `pull_android_field_sessions_20260710.py`, `summarize_android_field_sessions_20260710.py` | `--apk`로 고정한 same-commit Android debug field APK의 로컬/기기 SHA를 대조해 설치하고, 앱 전용 현장 로그를 회수해 개인정보 제한 요약·strict 출발 gate를 수행. CameraX mode는 실제 analyzed frame을 요구하고 `--require-arcore-unsupported`로 debug 강제/Depth 제한 기능 증거와 실제 ARCore 미지원 증거를 분리한다. field APK와 서명 release APK는 별도 SHA이며 field 결과는 release binary 실행 근거가 아니다 |
| `run_cloudflare_field_test_services_20260711.sh` | `LEGACY_REFERENCE_ONLY` Web 현장 stack의 역사자료. 부작용 전에 78로 종료하며 외부 공개나 현행 제품 실행에 사용 금지 |
| `run_walksafe_remote_field_stack_20260711.sh` | FP-009에 따라 시작 즉시 fail-closed로 종료되는 과거 Cloudflare 공개 launcher |
| `check_walksafe_remote_field_browser_20260711.py` | 인증된 headless 모바일 viewport에 fixture camera/GPS를 주입하는 통제 합성 E2E. server-v2 처리 동의를 카메라 전에 확인하고 actor-bound sessionStorage JSON과 person 위험 또는 damaged 자동 신고 JSONL을 검증하며 실폰 근거로 쓰지 않음 |
| `summarize_web_field_session_20260711.py` | 최신 Web field JSONL의 class·confidence·latency·GPS·risk·비계량 advisory 방향/TMAP 동시 활성·navigation·report 상태를 Markdown/CSV로 요약. `--require-non-metric-advisory`는 server-v2·camera·foreground/online·GPS threshold·3프레임/700ms 계약을, `--expected-source-commit`은 전 record의 서버 build source를 fail-closed 검증 |
| `check_frontend_field_test_gateway_20260711.sh` | 추출 전 Next BFF의 HttpOnly field/admin session과 `/api` 계약을 재현하는 역사 검사. 현재 Gateway는 독립 package tests·Gateway OpenAPI와 Backend provider OpenAPI currentness로 검사 |
| `check_static_dataset_readiness_20260531.py`, `check_walksafe_unified_training_plan_20260601.py` | 모델·데이터·export 계획 정적 점검 |
| `run_walksafe_*`, `resume_walksafe_*`, `post_*`, `summarize_walksafe_*` | 날짜별 모델 학습 실행과 결과 요약 |
| `evaluate_aihub*_depth*.py` | AIHub depth 데이터의 offline/Android 비교 평가 |
| `aihub_label_first_download_20260602.sh` | AIHub label/probe 다운로드 명령 생성. 기본은 dry-run |
| `walksafe_admin_high_risk_gate.py` | 배포환경의 출시승인·권한변경·자료삭제가 DB 관리자 상태 `NORMAL`, 기기 결속 세션, 최근 TOTP 재확인을 모두 통과할 때만 시작되게 하는 공통 fail-closed 경계. DB 연결이 정상인 동안 실제 변경 범위에 관리자 control transaction 잠금을 유지하고 성공 시 관리자·세션 결속 감사기록을 commit하며, 세션값은 실행 프로세스에만 주입하고 파일·로그에 저장하지 않음. 연결 상실 fencing과 부분 실패 durable journal은 후속 보완 항목 |
| `provision_walksafe_admin_device_key.py` | 신뢰할 수 있는 로컬 DB 절차에서 관리자 Android 기기의 P-256 공개 SPKI DER 또는 canonical unpadded Base64url만 등록·회전한다. 개인키·네트워크 등록은 받지 않으며 동일 버전 재실행은 같은 공개키일 때만 멱등 처리함 |
| `build_walksafe_fp008_admin_review_delivery_trace_20260803.py`, `build_walksafe_fp008_gap_backlog_r024_20260803.py`, `build_walksafe_fp008_artifact_trace_successor_20260803.py`, `build_walksafe_phase1_exact257_successor_r013_20260803.py`, `build_walksafe_fp008_strict_review_gate_20260803.py`, `apply_walksafe_fp008_goal_completed_seq49_50_20260809.py` | FP-008 내부 구현 관측을 결속하고 GAP-017의 `MISSING→PARTIAL`, 정확히 6개 live 산출물, 257개 산출물 successor, 독립검수 gate, 완료 receipt와 seq49/50 전이를 순서대로 생성·검증한다. 실제 기기·기관 전달·외부/정식 시험·배포·출시 상태는 승격하지 않음 |
| `check_report_retention_dry_run.py` | 기본은 report 보존 후보만 계산. 명시적 `--apply`·확인 문구·actor·DB/upload·manifest와 최근 관리자 재확인이 모두 있을 때만 image 격리→DB transaction→정리를 수행하고 실패 전 rollback |
| `manage_field_telemetry_retention_20260711.py` | 서버 수신일 기준 7일 field telemetry/test-capture 보존을 scope별로 기본 preview하고, apply 시 정상 DB 연결 범위에서 관리자 control 잠금을 유지하며 관리자·세션 ID가 결속된 결과 생성 |
| `record_walksafe_agency_submission_20260711.py` | agency export·manifest hash, 기관·채널·외부 접수번호·actor를 실제 제출 후 receipt로 기록. 외부 제출 자체는 수행하지 않음 |
| `backup_walksafe_data_20260711.sh`, `restore_walksafe_backup_drill_20260711.sh` | 명시적 단일 PostgreSQL URL(원격은 `sslmode=verify-full&gssencmode=disable`)의 PostGIS dump·upload을 OpenPGP로 암호화하고 hash manifest를 남기며, 명시 대상에 복구 drill receipt 생성. 두 스크립트는 직접 실행해야 하며(`bash script` 금지), upload/output/lock parent는 미리 만든 현재 사용자 소유 0700 canonical directory여야 한다. 복원 전용 사용자·호스트에는 암호문과 평문의 합계만큼 sealed memfd/tmpfs 여유를 확보해야 함 |
| `prune_walksafe_backups_20260711.py` | 기본은 서명된 백업의 보존 후보만 계산. 명시적 apply·확인 문구에 더해 DB 관리자 상태, 기기 결속 세션, 최근 TOTP 재확인이 모두 유효해야 하며, 정상 DB 연결 범위에서 해당 잠금을 유지하고 관리자·세션 ID를 결과에 결속 |
| `check_walksafe_backup_source_20260713.py`, `walksafe_backup_integrity.py`, `walksafe_environment_identity.py` | 고정된 upload directory FD의 실제 파일 SHA-256과 report `metadata.image_sha256`를 대조하고, flat snapshot 정합, backup artifact/manifest hash, secret을 제외한 DB·경로 환경 identity를 검사 |
| `check_frontend_motion_projection_policy_20260711.sh` | Web GPS/step/route bearing의 3~5초 미래 ROI와 점자블록 보조 안내 계약 검사 |
| `manage_local_model_registry.py` | 로컬 model hash 검증, manifest 승격·rollback, 비파괴 retention 계획과 승인 기반 격리 |
| `audit_project_classification_20260708.py`, `validate_project_classification_20260708.py` | 문서·코드 분류 manifest 생성과 검증 |
| `check_code_documentation_20260710.py` | 책임 경계별 README 누락/빈 파일 검사 |
| `build_latest_model_report_20260710.py` | 최종 manifest·300 epoch 결과·클래스 평가표에서 최신 모델 Markdown/DOCX와 그래프를 재생성·검증 |
| `build_design_documents_20260710.py` | 요구사항·유스케이스·추적표·설계 원문에서 공식 DOCX 8종과 manifest를 재생성·검증 |
| `build_walksafe_feature_policy_resolution.py` | 종합 검토 답변 통제 사본과 intake·proposals·이전 후보의 hash를 검증하고, 76개 검토와 6개 cascade를 54개 기능의 다음 작성 입력으로 결정적으로 연결. 정식 산출물·HTML·기준선 승인은 생성하지 않음 |
| `build_walksafe_feature_policy_document.py` | 최신 resolution과 작성 규칙을 검증해 54개 기능의 정상 흐름·실패 복구·권한·자료 생명주기·시험·추적을 JSON과 독립 실행 HTML로 생성. 공통정책 9개와 기능 54개의 검토 입력은 자동저장·결속 JSON으로 내보내지만 기준선 승인이나 0~6 정식 산출물은 만들지 않음 |
| `build_walksafe_feature_policy_baseline_review_20260721.py` | 최종 검토 답변 63개와 통제 반입 manifest, 검토된 JSON·HTML의 지문을 검증하고 정책 본문을 바꾸지 않은 r001 별도 기준선 승인 준비 기록을 생성. 이 파일은 r001 재현용으로 동결하고 후속 검토는 새 날짜의 생성기를 사용함. 검증항목 면제·출시 승인·0~6 정식 산출물은 만들지 않음 |
| `build_walksafe_feature_policy_baseline_approval_20260721.py` | 사용자의 명시 승인 원문을 좁은 줄바꿈 정규화 규칙으로 검증해 기능 정책 기준선 1.0.0 승인 기록과 manifest를 생성. 5개 미실행 검증을 면제하지 않고 출시를 `NOT_ELIGIBLE`로 유지함 |
| `build_walksafe_effective_decision_register_alignment_20260721.py` | 승인된 기능 정책 기준선에 135개 유효 결정·428개 기능 연결을 정렬한 In Review 원장을 생성. 정책 승인과 결정 원장 자체의 정식 승인을 구분함 |
| `build_walksafe_control_bootstrap.py` | 최초 DOC~CLS 257개 bootstrap을 재현하는 역사 생성기. 현재 승인·successor 산출물과 bytes가 달라 `--check`도 예상 실패하므로 현행 정본에 실행하지 않고 새 revision의 소유 successor 절차를 확인함 |
| `build_walksafe_formal_management_discovery_20260721.py` | MGT-01~18과 DSC-01~15 정식 Draft를 6개 읽기 문서와 WBS·RACI·RAID·backlog 등 통제 원장으로 생성. 조사·예산·일정·시험 결과가 없는 부분은 만들지 않고 미정 또는 0건으로 유지함 |
| `build_walksafe_formal_dev_test_20260721.py` | DEV-01~21과 TST-01~23 정식 Draft, 구현 후보 형상·모듈 원장, 279개 실행계획과 빈 증거 snapshot을 생성. 실제 실행은 별도 append-only instance로 분리하고 모든 결과를 `NOT_RUN`으로 유지함 |
| `build_walksafe_requirements_draft_20260721.py` | 기능 54개·공통정책 9개·미실행 gate 5개를 68개 요구, 1,460개 clause, 279개 인수조건과 RTM JSON/HTML로 생성하고 135개 결정·428개 연결을 추적함 |
| `build_walksafe_design_deliverables_20260721.py` | DES-01~27의 아키텍처·인터페이스/데이터·UX/접근성·보안/운영 Draft와 설계 추적 원장을 생성. 현재 코드·OpenAPI·DB는 구현 완료가 아닌 재검증 후보로만 분류함 |
| `build_walksafe_trace_integration_report_20260721.py` | REQ·DES·DEV/TST를 모두 생성한 뒤 68개 요구, 279개 인수조건·시험, 27개 설계와 구현 모듈의 ID·상대경로·문서 위치·파일 지문·역참조를 검사한 leaf JSON과 쉬운 README를 생성. 구조 검증만 수행하며 시험 PASS·문서 승인·출시 허용으로 올리지 않음 |
| `build_walksafe_formal_sec_ws_20260721.py` | SEC 19개와 WS 22개의 보안·개인정보·WalkSafe 특화 계획·정책·절차·사전개설 원장을 6개 문서 묶음으로 생성하고 실제 scan·침투·기기·현장·E2E·외부 동의 증거는 Planned/NOT_RUN으로 분리함 |
| `build_walksafe_formal_aiml_20260721.py` | AIML 26개의 데이터·모델 계획·명세·원장을 4개 문서 묶음과 보조 원장으로 생성하고 실제 학습·분할·평가·동등성·기기성능·통제 source 증거는 Planned/NOT_RUN으로 분리함 |
| `build_walksafe_formal_rel_ops_cls_20260721.py` | REL 22개·OPS 24개·CLS 16개의 릴리스·운영·종료 계획·절차·사전개설 원장을 9개 문서 묶음으로 생성하고 실제 배포·서명·운영사건·훈련·종료·통제환경 증거는 Planned/NOT_RUN으로 분리함 |
| `build_walksafe_formal_trace_7_12_20260721.py` | SEC~CLS 129개·19개 묶음의 Draft/Planned 경계, 문서 anchor, 정책·결정·gate 연결과 manifest/file hash를 종합 검사한다. 구조 통과를 실제 실행·승인·출시로 해석하지 않음 |
| `build_walksafe_artifact_baseline_candidate_20260721.py` | DOC~CLS 257개를 기준선 적격·Active 최초본·Planned/NOT_RUN·Draft 보류·조건평가 대기로 전수 분류하고, 파일 bytes와 artifact anchor별 SHA-256을 결속한 JSON·Markdown·HTML 일괄 승인 후보를 생성한다. 승인 원문·승인 기록·실제 기준선은 만들지 않으며 현행 구현을 정책 기준으로 승격하지 않음 |
| `build_walksafe_fp035_correction_candidate_20260722.py` | 기존 답변의 FP-035를 보행 정지 후 명시적 이동통신망 선택 또는 Wi-Fi 전송으로 정규화한 미승인 정정 후보를 만들고, 영향 산출물·예정 시험과 출시 제한을 결속함 |
| `build_walksafe_artifact_content_readiness_audit_20260722.py` | 257개를 내용 기준선 후보 102개·Active 최초본 후보 27개·외부값/실행근거 대기 53개·Planned/NOT_RUN 75개로 재평가하되 승인이나 상태 전환은 수행하지 않음 |
| `build_walksafe_artifact_independent_review_record_20260722.py` | 서로 다른 세 기술검토의 범위·명령·파일 지문·결과를 결속한다. 사람 승인, 외부 전문검토, 미실행 증거를 대체하지 않음 |
| `build_walksafe_artifact_baseline_candidate_20260722.py` | 독립검토와 FP-035 정정 후보가 현재 파일에 결속된 경우에만 102개 기준선·27개 Active 최초본의 새 일괄 승인 후보와 쉬운 HTML을 생성한다. 생성만으로 승인·상태 전환은 일어나지 않음 |
| `build_walksafe_artifact_baseline_approval_20260722.py` | 사용자의 exact 일괄 승인문과 후보·입력·분류·단계 지문을 검증하고 FP-035 정책 1.0.1, 승인 기록, 전환 전 불변 snapshot과 상태 전환 계획을 준비한다. 이 단계에서는 live DOC-01·DOC-05를 바꾸지 않음 |
| `materialize_walksafe_artifact_baseline_approval_20260722.py` | 준비된 승인 패키지를 한 snapshot에서 검증한 뒤 102개를 `APPROVED_BASELINED`, 27개를 `ACTIVE`로 원자적으로 반영하고 COMMITTED receipt를 마지막에 기록한다. 실패 시 전부 복원하며 재실행은 멱등 검증만 수행함 |
| `build_walksafe_implementation_gap_analysis_20260722.py` | 승인 정책 1.0.1의 공통정책 9개·기능 54개·미실행 gate 5개를 동결된 구현 commit과 비교해 68개 Gap, 파일·줄·SHA-256 근거, P0/P1 수정 백로그와 쉬운 HTML 종합보고서를 만든다. 진단만 수행하며 기준선·구현·시험·출시 상태는 바꾸지 않음 |
| `validate_walksafe_formal_deliverables_0_6.py` | DOC~TST 128개·21개 묶음, 68 요구·279 인수조건/시험·135 결정·27 설계의 경로·지문·역추적을 종합 검사하고 승인 과장, gate 면제, 출시 허용, 임의 N/A를 거부함 |
| `build_submission_assets_20260710.py`, `build_submission_forms_20260710.py` | 검증된 근거 도식과 공식 서식1 DOCX·서식2 PPTX 작업본을 재생성 |
| `promote_submission_final_20260713.py` | staging에서 검증한 제출 산출물 전체를 원자적으로 final에 승격하고 final manifest를 생성 |
| `run_walksafe_test_layers_current.sh` | 현재 Python test를 중복·누락 없이 분류하는 정본이다. frozen 제어·완료 snapshot은 `HISTORICAL_CONTROL_PYTHON_TESTS`, 활성 v2.4 checkpoint의 managed snapshot·transition lifecycle 결속 검사는 `ACTIVE_SESSION_CONTROL_PYTHON_TESTS`, CPython 3.14.6/Linux 전용 백업 검사는 `BACKUP_INTEGRITY_PYTHON_TESTS`로 분리한다. `validate`는 실행 없이 분류만 확인하며, 이 분류나 PASS가 승인·출시 권한을 만들지는 않음 |
| `run_walksafe_test_layers_20260711.sh` | 과거 Goal·checkpoint가 SHA-256으로 결속한 byte-exact 역사 실행기다. 수정하거나 일반 개발 명령에 사용하지 않고 `run_walksafe_test_layers_current.sh`를 사용한다. 현재 focus의 exact event 계약이 hash-bound 과거 명령을 직접 요구할 때만 계약 재현 범위에서 실행하고 현행 `validate`도 함께 수행함 |
| `check_walksafe_test_database_20260713.py` | `postgresql+psycopg` test URL, 보호 DB명과의 분리, 실제 연결/current database 일치를 runner 전에 확인 |
| `check_web_runtime_trace_scope_20260713.py` | 모든 Next NFT trace가 build/node_modules/package 허용 범위 안인지와 env/log/DB/key payload 부재를 검사 |
| `smoke_backend_768_runtime_20260711.py`, `smoke_android_tflite_runtime_20260711.py` | canonical img768 PT warm-up과 Android TFLite 실제 invoke/tensor 출력 smoke |
| `validate_submission_materials_20260710.py`, `validate_submission_forms_20260710.py` | 제출 근거·원본 hash·Office 구조·범위·최신 검증 수치·개인정보를 독립 검사 |
| `validate_downloads_cleanup_readiness_20260710.py` | Downloads에서 보존한 프로젝트 문서의 hash와 삭제 준비 판정을 재검증 |

## 현재 모델 관련 주의

- epoch270 기록이 참조하는 학습 입력은 `datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml`이지만 현재 저장소에는 없으며 후보는 `CANDIDATE_REVALIDATION_REQUIRED`입니다.
- 위 경로의 `aihub183`은 레거시 내부 별칭이며, 해당 전동킥보드 원천의 현재 AIHub 공식 식별은 `AIHub 572`입니다.
- 현재 평가 후보는 epoch 270의 13-class YOLO26n img768 weight입니다.
- `run_walksafe_unified_aihub183_png_yolo26n_768_20260627.sh --print-only`도 먼저 `yolo` 실행파일을 확인하고 로컬 `TMPDIR`을 만듭니다. 이 전제를 갖춘 격리 환경에서 생성될 학습 명령만 출력하며, `--print-only` 없이 실행하면 GPU 학습을 시작합니다.
- `export_walksafe_unified_tflite_20260601.py`의 기본값은 과거 640 실험 경로입니다. 현재 후보 export 때는 `--model`, `--data`, `--imgsz`, `--output-name`을 모두 명시해야 합니다.
- epoch270 후보의 unified float32 img768 13-class TFLite는 Android primary로 반영됐습니다. asset SHA-256은 `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19`, tensor 계약은 `[1,768,768,3]`→`[1,300,6]`입니다. PT warm-up과 TFLite 단일 이미지 invoke는 통과했으며, PyTorch/TFLite box·class 동등성·실기기 FPS·실외 Device Field는 아직 완료되지 않았습니다.

## 실행 원칙

- 날짜가 붙은 스크립트는 당시 계약의 재현 snapshot일 수 있으므로 현재 기본값으로 간주하지 않습니다.
- `--help`, `--dry-run`, `--print-only`가 있으면 실제 실행 전에 사용합니다.
- `check_*`의 PASS는 그 스크립트가 검사한 정적·smoke 범위만 증명합니다.
- 현재 Android 통합검증에서는 canonical img768 PT warm-up과 Android APK asset 검사를 사용한다. Web NFT 검사는 legacy 회귀 범위일 뿐 Android 출시 필수 증거가 아니다. TFLite host invoke와 연결 실기기 계측은 필요한 runtime/device를 명시했을 때 추가 실행한다.
- 분류 audit 스크립트는 manifest 문서를 갱신할 수 있으므로 읽기 전용 검사로 간주하지 않습니다.
