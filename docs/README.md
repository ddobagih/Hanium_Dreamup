# WalkSafe 문서 지도

현재 정본은 다음 순서로 본다.

1. `control/baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json`
2. `control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json`
3. `deliverables/00-control/artifact-register.json`과 `artifact-change-log.json`
4. `control/walksafe-project-resumption-runbook.md`와 `control/walksafe-project-continuation-checkpoint.json`
5. `control/audits/walksafe-implementation-gap-analysis-20260722-r001.json`과 수정 백로그

현재 제품은 Android 사용자 앱과 별도 Android 관리자 앱이다. Web/PWA는 `LEGACY_REFERENCE_ONLY`이며 외부 실행·정식 배포 대상이 아니다. 상태는 102개 `Approved/Baselined`, 27개 `Active`, 53개 `Draft`, 75개 `Planned/NOT_RUN`이고 출시는 `NOT_ELIGIBLE`이다.

아래의 2026-07-11 문서 지도와 Web/PWA 설명은 역사 자료의 위치를 찾기 위해 보존한다. 현재 제품 정책이나 다음 작업을 판단하는 근거로 쓰지 않는다.

## 역사 문서 정리 기준 - 2026-07-11 KST

- 최신 코드 기반 판정은 `status/implementation_audit_20260710.md`, 상세 운영 상태는 `status/current_status.md`를 먼저 본다.
- 2026-07-10 감사 이후 원거리 field 구현은 `status/current_status.md`와 `testing/web_remote_field_test_20260711.md`가 우선한다.
- `status/walksafe_team_current_status_20260708.md`와 `review/requirements/walksafe_requirement_traceability_matrix_20260701.md`는 작성일 기준 스냅샷이며 최신 모델/검증 상태를 대체하지 않는다.
- 당시 source of truth는 Web/PWA 주 사용자 앱 전제였다. 이 전제는 정책 기준선 1.0.1로 대체됐다.
- 원안 PDF와 당시 PWA 제출 자료는 `submission/source_materials/` 등에 역사 자료로 보존한다.
- 실제 파일 삭제는 데이터 손실이므로 승인 전 하지 않는다. 2026-07-08에 승인된 legacy/superseded 후보는 `_archive_candidates/2026-07-08/`로 이동했다.
- 새 작업 계획/보고/발표에는 `먼저 볼 문서`와 `v2 구현 기준 문서`만 인용한다.
- 초기 `docs/execution/2026-05-14*`~`2026-05-18*`, `plans/.work/`, `plans/daily/`, `plans/catchup/`, `plans/weekly/`, `*_3day_execution_plan.md`는 archive staging으로 이동한 작업 이력이다. 현재 구현 기준처럼 재사용하지 않는다.
- 제출 문서/초안/QA/과제 원문은 `submission/` 아래에 성격별로 둔다. 루트에는 제출 문서를 두지 않는다.
- 디자인 토큰/원칙 문서는 `design/DESIGN.md`에 둔다.

## 문서 폴더 구조

| 폴더 | 내용 |
|---|---|
| `status/` | 최신 코드 기반 감사, 상세 현재 상태, 날짜별 팀 공유 snapshot, PWA/backend/voice status |
| `backend/` | API, backend 환경, DB reset, error contract, sensor log schema |
| `operations/` | 신고 운영, 보존 정책, export/검수 정책 |
| `model-data/` | 모델 학습 brief, AIHub source plan, dataset contract, 데이터 전략 |
| `design/` | 현재 DESIGN 원칙과 HISTORICAL PWA/v1 UI inventory/handoff/review |
| `review/requirements/` | 요구사항 구현 계획, gap prompt, traceability matrix |
| `inventory/` | 문서/Downloads/repo 분류 manifest와 감사 결과 |
| `submission/` | 제출 최종본, 분리 산출문서, 초안, QA, 체크리스트, 원본 자료 |
| `testing/` | 연결 폰 정지 결과, 2026-07-11 원거리 테스트, 로그 회수 체크리스트 |

## 먼저 볼 문서

| 문서 | 용도 |
|---|---|
| `../README.md` | 프로젝트 빠른 시작과 2026-07-11 현재 요약 |
| `status/implementation_audit_20260710.md` | 원안 PDF와 현재 코드를 대조한 최신 구현/부분/미구현/검증/범위변경 판정 |
| `status/current_status.md` | 현재 Web/PWA/backend/admin/Android 보조 연구/voice/model 상세 상태와 다음 gate |
| `status/walksafe_team_current_status_20260708.md` | 2026-07-08 팀 공유 snapshot. 최신 판정 문서가 아님 |
| `submission/source_materials/README.md` | 과제 원안 PDF의 역할과 현재 구현 문서와의 구분 |
| `submission/README.md` | 제출용 Office 파일, 분리 산출문서, 초안, QA, 체크리스트, 원본 참고자료의 실제 위치 |
| `design/DESIGN.md` | UI 색상, 타이포그래피, 접근성, 모바일 우선 원칙 |
| `inventory/walksafe_documentation_audit_20260701.md` | 2026-07-01 코드 기반 문서 정합성 감사와 불일치 처리 내역 |
| `_archive_candidates/2026-07-08/README.md` | 실제 이동한 legacy/superseded/archive staging 목록과 사용 금지 기준 |
| `inventory/document_inventory_20260707.md` | 2026-07-07 문서 분류 인벤토리와 Downloads 후보 판단 |
| `inventory/project_structure_map_20260708.html` | 사용자가 직접 볼 필요가 있는 폴더와 AI/개발/대형 산출물 폴더를 구분한 HTML 구조 지도 |
| `inventory/code_documentation_coverage_20260710.md` | 코드 책임 폴더 README와 핵심 주석 보강 범위, 자동 검사 기준 |
| `inventory/classification_audit_summary_20260708.md` | 2026-07-10에 재생성한 machine-readable repo/Downloads 분류 manifest 요약 |
| `inventory/validation_results_20260708.md` | 재생성된 분류 schema/path/canonical/tracking/snapshot/index 검증 결과 |
| `inventory/downloads_cleanup_readiness_20260710.md` | Downloads 프로젝트 문서의 보존 위치, 해시, 삭제 가능·별도 유지 판정 |
| `inventory/document_consolidation_plan_20260707.md` | 문서 통합 실행 선택지, 단계별 계획, 사용자 결정 질문 |
| `submission/design_documents/README.md` | 요구사항·유스케이스·추적표·구성·UIUX·ERD·알고리즘·프로그램/환경 공식 DOCX 8종 |
| `model-data/latest_model_report_20260710/WalkSafe_최신_모델_종합보고서_20260710.docx` | 최신 300 epoch 모델의 AIHub 출처·사용 범위·그래프·클래스별 성능·개선 방향 공식 보고서 |
| `testing/android_stationary_test_report_20260710.md` | 현재 연결 폰의 실제 정지 테스트 PASS/BLOCKED 결과 |
| `testing/phone_field_test_master_checklist_20260710.md` | 2026-07-11 다른 폰 설치, 원거리 현장 수집, 귀가 후 로그 회수 절차 |
| `testing/web_remote_field_test_20260711.md` | production Web/PWA 접속, 실제 모델·음성 TMAP·경고·자동 신고, 1 Hz 로그의 2026-07-11 실행 절차 |
| `walksafe-v2/cleanup_inventory_20260704.md` | 2026-07-04 당시 Android-primary 전제의 cleanup snapshot. 플랫폼 전제는 superseded |
| `walksafe-v2/implementation_confidence_audit_20260704.md` | 현재 구현을 믿어도 되는 범위와 아직 믿으면 안 되는 범위 |
| `model-data/model_unified_13class_aihub_sources_20260602.md` | unified 13-class class order와 AIHub dataset source plan |
| `../apps/web/README.md` | Web/PWA 주 앱 실행, 탐지·음성·길안내·신고·admin 구조와 release gap |
| `../apps/android/README.md` | Android 보조 연구 APK 빌드/설치/검증, TFLite config, bbox overlay |
| `../apps/README.md` | Web/PWA 주 앱과 Android 보조 연구 앱 역할 구분 |
| `android/arcore_depth_estimation_architecture.md` | Android ARCore depth/TFLite/overlay 구조와 남은 coordinate risk |
| `android/arcore_coordinate_mapping_plan.md` | mapper 구현 전 계획과 현재 실기기 검증 기준을 보존한 snapshot |
| `android/android_device_overlay_depth_checklist_20260601.md` | 실기기 overlay/depth/`N보` 관찰 체크리스트 |
| `android/android_metadata_capture_schema_20260601.md` | Android metadata-only capture log schema |
| `android/android_server_debug_log_runbook_20260601.md` | local/dev 서버 metadata-only debug log 수집 절차 |
| `android/arcore_rgbd_dataset_schema_20260601.md` | ARCore RGB-D 검증 dataset schema |
| `android/android_backend_threshold_decision_20260531.md` | Android/backend threshold 분리 decision note |
| `evidence/android_static_threshold_evidence_20260601.md` | static RGB threshold evidence와 full rerun blocked 근거 |
| `walksafe-v2/android_report_source_metadata_decision_20260601.md` | Android report source/metadata decision |
| `operations/report_operations.md` | v2 신고 운영, 검수, export, 운영자 내부 export 정책 |
| `walksafe-v2/README.md` | v2 unified-primary/backend/PWA/voice policy 문서 인덱스 |
| `evidence/feature_matrix.md` | 2026-05-26 240개 follow-up 항목별 evidence 상태와 2026-05-31 Android addendum |
| `evidence/final_report_index.md` | 최종 보고용 evidence 색인과 완료 주장 제한 |
| `../ai_tasks/README.md` | 외부 AI 검수/후속 작업 패키지 인덱스 |

## v2 구현 기준 문서

| 문서 | 용도 |
|---|---|
| `walksafe-v2/backend_api_contract.md` | `/detect/v2/health`, `/detect/v2`, `/reports/v2`, `/reports/export` 현재 계약 |
| `walksafe-v2/backend_model_integration_notes.md` | unified YOLO path 우선, legacy custom+COCO fallback, 실제 모델 연결 시 주의사항 |
| `walksafe-v2/frontend_display_policy.md` | Web/PWA 주 앱의 v2 화면/음성/진동/위험 피드백 정책 |
| `walksafe-v2/auto_report_policy.md` | 자동 신고와 음성 요청 신고 정책 |
| `walksafe-v2/voice_command_strategy.md` | STT→intent→slot 기반 음성 명령 전략. Android native 명시 신고는 구현됐고 서버 STT/LLM fallback은 prototype lane |
| `walksafe-v2/navigation_integration_policy.md` | TMAP 보행 길안내와 WalkSafe 점자블록/위험 안내 통합 정책 |
| `walksafe-v2/public_agency_submission_policy.md` | 공공기관 직접 자동 제출 없음, 관리자 검수·CSV export 후 수동 신고 정책 |
| `walksafe-v2/android_report_source_metadata_decision_20260601.md` | Android native report upload source/metadata 결정 |
| `walksafe-v2/two_model_runtime_plan.md` | unified-primary/legacy-fallback runtime config와 Android/backend drop-in 기준 |
| `walksafe-v2/coco_inference_policy.md` | legacy COCO inference-only fallback allowlist 정책 |
| `walksafe-v2/reviewed_model_rollout_checklist.md` | legacy reviewed YOLO26s fallback 학습/선정/checkpoint/threshold 검증 체크리스트 |
| `walksafe-v2/cleanup_inventory_20260704.md` | 과거 Android-primary 전제의 cleanup 기록. 현재 플랫폼 기준으로 재사용 금지 |
| `walksafe-v2/implementation_confidence_audit_20260704.md` | PASS/PARTIAL/FAIL/BLOCKED 기준 구현 신뢰도 감사 |

## 운영/현재 상태 문서

| 문서 | 용도 |
|---|---|
| `status/implementation_audit_20260710.md` | 2026-07-10 코드/PDF/테스트/모델 산출물 재감사와 요구사항 gap |
| `status/current_status.md` | 2026-07-11 KST 기준 구현 상태와 남은 gate |
| `status/walksafe_team_current_status_20260708.md` | 2026-07-08 기준 팀 공유 snapshot |
| `operations/report_operations.md` | 신고 상태, v2 자동/음성 신고, 필터/export, 운영자 내부 검수 흐름 |
| `status/pwa_backend_status.md` | Web/PWA 주 앱과 backend/admin의 구현·release gap |
| `status/voice_stt_tts_status.md` | Web/PWA 음성 흐름, local STT/TTS와 Android 보조 경로 상태 |
| `_archive_candidates/2026-07-08/docs/model_training_status.md` | 과거 모델 학습 snapshot. 새 13-class 기준은 `model-data/model_unified_13class_aihub_sources_20260602.md`와 `model/README.md` 우선 |
| `_archive_candidates/2026-07-08/docs/model_v2_status.md` | 과거/참고용 v2 모델 상태. 최신 구현 기준은 `walksafe-v2/`, Web/backend, model 문서 우선 |

## 참고용 실행 이력/과거 계획

아래 문서는 실제 실행 맥락을 추적할 때만 본다. 현재 제품/코드 판단의 우선 근거로 쓰지 않는다.

| 문서 | 참고 범위 |
|---|---|
| `../plans/features/2026-06-01_android_native_gate_execution_plan.md` | 2026-06-01 Android gate 실행 계획 snapshot. 최신 APK/unified-primary 기준은 `status/current_status.md` 우선 |
| `../plans/features/2026-05-31_android_native_next_step_plan.md` | 2026-05-31 Android native 다음 스텝 snapshot. 현재 APK hash/모델 기준과 다를 수 있음 |
| `execution/2026-05-31_android_static_dataset_contract_progress.md` | Android static dataset/contract 실행 기록. Device PASS 근거 아님 |
| `execution/**` | 실행 기록. 최신 구현 기준 아님 |

## 원안/제출 문서

| 범위 | 분류 | 사용 기준 |
|---|---|---|
| `submission/**` 기존 자료 | LEGACY_PRIVATE_REFERENCE | Web/PWA 중심 옛 제출 후보와 검수자료. 공개 `current`에서 제외하고 구현 근거로 사용하지 않음 |
| 공식 Word·PowerPoint 서식 | EXTERNAL_REFERENCE | 저장소에 넣지 않고 제출 시점에 한이음 현행 배포처에서 새로 받음 |
| 기존 개발보고서·제작설계서 원본·제출 후보 | LEGACY_PRIVATE_REFERENCE | 개인정보·출처·원본 문서 식별 메타데이터 때문에 공개 `current`에서 제외 |

## legacy/superseded 문서

아래 문서는 삭제하지 않고 `_archive_candidates/2026-07-08/` 아래에 보존했지만, 현재 작업 기준으로는 **버린 문서**로 본다. 새 구현이나 판단에 그대로 쓰지 않는다.

| 문서 | 현재 판정 | 대체 문서 |
|---|---|---|
| `_archive_candidates/2026-07-08/root/PROJECT_PLAN.md` | 2026-05-13 PWA/fake/v1 계약과 기간이 낡아 superseded. PWA 플랫폼 자체가 폐기된 것은 아님 | `../README.md`, `status/current_status.md`, `../product/vision.md` |
| `_archive_candidates/2026-07-08/docs/*_3day_execution_plan.md` | 2026-05-14~16 실행 계획. 완료/만료 | `status/current_status.md`, `daylog/` |
| `_archive_candidates/2026-07-08/docs/inference_contract.md` | v1/PWA 4-class `DetectionEvent` 계약. legacy | `walksafe-v2/backend_api_contract.md`, `walksafe-v2/two_model_runtime_plan.md` |
| `_archive_candidates/2026-07-08/docs/model_integration_plan.md`, `_archive_candidates/2026-07-08/docs/model_placeholder_systems.md`, `_archive_candidates/2026-07-08/docs/pre_model_backend_todo.md` | v1 `/detect`/placeholder 중심 계획. legacy | `walksafe-v2/backend_model_integration_notes.md`, `backend/backend_environment.md` |
| `_archive_candidates/2026-07-08/docs/frontend_handoff_without_model.md`, `_archive_candidates/2026-07-08/docs/frontend_api_examples.md` | PWA/v1 연동 handoff. legacy | `walksafe-v2/frontend_display_policy.md`, `backend/api_reference.md` |
| `_archive_candidates/2026-07-08/docs/model_v2_status.md`, `_archive_candidates/2026-07-08/docs/model_training_status.md` | 과거 YOLO tactile/v2 snapshot. 숫자 인용 시 작성일 명시 | `model-data/model_unified_13class_aihub_sources_20260602.md`, `../model/README.md` |
| `_archive_candidates/2026-07-08/docs/neck_worn_phone_test_checklist.md` | 과거 PWA/Chrome 목걸이 테스트 snapshot. 최신 Web field 기준으로 재검토 필요 | `status/current_status.md`, `android/android_device_overlay_depth_checklist_20260601.md` |
| `_archive_candidates/2026-07-08/docs/local_stt_tts_ai_prompt.md`, `_archive_candidates/2026-07-08/docs/local_voice_server_plan.md`, `_archive_candidates/2026-07-08/docs/stt_tts_current_status.md` | 음성 prototype 상세/과거 기록 | `status/voice_stt_tts_status.md`, `walksafe-v2/voice_command_strategy.md` |
| `_archive_candidates/2026-07-08/docs/execution/2026-05-14*`~`2026-05-18*`, `_archive_candidates/2026-07-08/plans/.work/**`, `_archive_candidates/2026-07-08/plans/daily/**`, `_archive_candidates/2026-07-08/plans/catchup/**`, `_archive_candidates/2026-07-08/plans/weekly/**` | 실행 기록/작업 로그. 최신 기준 아님 | 새 작업은 `status/current_status.md`와 `daylog/` 기준으로 시작 |

2026-07-01 세부 archive 후보 원문은 `_archive_candidates/2026-07-08/docs/archive_candidates_20260701.md`에 보존했다.

## 이미 archive staging으로 이동한 후보

실제 삭제는 사용자 확인 후에만 한다. 아래 후보는 2026-07-08에 `_archive_candidates/2026-07-08/`로 이동했으며, 현재 기준으로는 새 작업에 쓰지 않는다.

| 후보 | 이유 | 보존 필요성 |
|---|---|---|
| `_archive_candidates/2026-07-08/docs/*_3day_execution_plan.md` | 기간 만료, 현재 Web/PWA·unified 기준과 다름 | 낮음. 필요 내용은 실행 로그/daylog에 남음 |
| `_archive_candidates/2026-07-08/plans/.work/**` | 자동/중간 작업 산출물 성격 | 낮음~중간. 감사 추적이 필요하면 보존 |
| `../plans/features/2026-05-31_android_native_next_step_plan.md`, `../plans/features/2026-06-01_android_native_gate_execution_plan.md` | 최신 APK/unified-primary 전환 전 계획 snapshot | 중간. 필요하면 `../plans/archive/` 이동 |
| `_archive_candidates/2026-07-08/docs/execution/2026-05-14*`~`2026-05-18*` | 초기 PWA/fake/v1 실행 기록 | 중간. 보고서 근거로 쓰지 않으면 archive 가능 |
| `_archive_candidates/2026-07-08/docs/model_v2_status.md` | unified 13-class 전환 전 snapshot | 중간. 과거 모델 비교가 필요하면 보존 |

## 로컬 전용 산출물

다음은 문서에 언급되더라도 기본적으로 GitHub에 올리지 않는다.

- 데이터셋 이미지/라벨: `datasets/**/images/**`, `datasets/**/labels/**`
- 학습 결과/weight: `runs/`, `model/artifacts/**/*.pt`, `model/artifacts/**/*.onnx`, `*.engine`, `*.tflite`
- 실행 리포트/평가 산출물: `reports/runs/`
- AI Hub 원본 zip, 음성 샘플/출력/로그
- 중간 review pack overlay/crop 대량 산출물
