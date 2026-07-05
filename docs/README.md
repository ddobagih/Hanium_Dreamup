# WalkSafe 문서 지도

이 문서는 현재 문서의 우선순위를 정리한다. 오래된 실행 로그보다 아래 canonical 문서를 먼저 본다.

## 문서 정리 기준 - 2026-07-04 KST

- 현재 source of truth는 **Android native ARCore/TFLite + `unified_walksafe` primary + legacy fallback** 기준이다.
- 실제 파일 삭제는 데이터 손실이므로 승인 전 하지 않는다. 대신 이 문서에서 최신 기준에서 버릴 문서를 **legacy/superseded**로 분리한다.
- 새 작업 계획/보고/발표에는 `먼저 볼 문서`와 `v2 구현 기준 문서`만 인용한다.
- `docs/execution/`, `plans/.work/`, `*_3day_execution_plan.md`는 작업 이력이다. 현재 구현 기준처럼 재사용하지 않는다.

## 먼저 볼 문서

| 문서 | 용도 |
|---|---|
| `../README.md` | 프로젝트 빠른 시작과 2026-07-01 현재 요약 |
| `current_status.md` | 현재 Android/native/backend/model/voice 상태 요약 |
| `walksafe_documentation_audit_20260701.md` | 2026-07-01 코드 기반 문서 정합성 감사와 불일치 처리 내역 |
| `archive_candidates_20260701.md` | 삭제 없이 보존할 legacy/superseded/archive 후보 목록 |
| `walksafe-v2/cleanup_inventory_20260704.md` | Android primary 정책 기준 코드/문서 잔재 제거·격리 인벤토리 |
| `walksafe-v2/implementation_confidence_audit_20260704.md` | 현재 구현을 믿어도 되는 범위와 아직 믿으면 안 되는 범위 |
| `model_unified_13class_aihub_sources_20260602.md` | unified 13-class class order와 AIHub dataset source plan |
| `../apps/android/README.md` | Android APK 빌드/설치/검증, TFLite config, bbox overlay |
| `android/arcore_depth_estimation_architecture.md` | Android ARCore depth/TFLite/overlay 구조와 남은 coordinate risk |
| `android/arcore_coordinate_mapping_plan.md` | ARCore overlay/depth mapper 분리 계획 |
| `android/android_device_overlay_depth_checklist_20260601.md` | 실기기 overlay/depth/`N보` 관찰 체크리스트 |
| `android/android_metadata_capture_schema_20260601.md` | Android metadata-only capture log schema |
| `android/android_server_debug_log_runbook_20260601.md` | local/dev 서버 metadata-only debug log 수집 절차 |
| `android/arcore_rgbd_dataset_schema_20260601.md` | ARCore RGB-D 검증 dataset schema |
| `android/android_backend_threshold_decision_20260531.md` | Android/backend threshold 분리 decision note |
| `evidence/android_static_threshold_evidence_20260601.md` | static RGB threshold evidence와 full rerun blocked 근거 |
| `walksafe-v2/android_report_source_metadata_decision_20260601.md` | Android report source/metadata decision |
| `report_operations.md` | v2 신고 운영, 검수, export, 제출 전 정책 |
| `walksafe-v2/README.md` | v2 unified-primary/backend/PWA/voice policy 문서 인덱스 |
| `evidence/feature_matrix.md` | 2026-05-26 240개 follow-up 항목별 evidence 상태와 2026-05-31 Android addendum |
| `evidence/final_report_index.md` | 최종 보고용 evidence 색인과 완료 주장 제한 |
| `../ai_tasks/README.md` | 외부 AI 검수/후속 작업 패키지 인덱스 |

## v2 구현 기준 문서

| 문서 | 용도 |
|---|---|
| `walksafe-v2/backend_api_contract.md` | `/detect/v2/health`, `/detect/v2`, `/reports/v2`, `/reports/export` 현재 계약 |
| `walksafe-v2/backend_model_integration_notes.md` | unified YOLO path 우선, legacy custom+COCO fallback, 실제 모델 연결 시 주의사항 |
| `walksafe-v2/frontend_display_policy.md` | Web/PWA v2 화면/음성/진동/위험 피드백 정책. Android 주경로와는 구분 |
| `walksafe-v2/auto_report_policy.md` | 자동 신고와 음성 요청 신고 정책 |
| `walksafe-v2/voice_command_strategy.md` | STT→intent→slot 기반 음성 명령 전략. Android native 명시 신고는 구현됐고 서버 STT/LLM fallback은 prototype lane |
| `walksafe-v2/navigation_integration_policy.md` | TMAP 보행 길안내와 WalkSafe 점자블록/위험 안내 통합 정책 |
| `walksafe-v2/public_agency_submission_policy.md` | 공공기관 직접 자동 제출 보류, 내부 검수/export 후 제출 정책 |
| `walksafe-v2/android_report_source_metadata_decision_20260601.md` | Android native report upload source/metadata 결정 |
| `walksafe-v2/two_model_runtime_plan.md` | unified-primary/legacy-fallback runtime config와 Android/backend drop-in 기준 |
| `walksafe-v2/coco_inference_policy.md` | legacy COCO inference-only fallback allowlist 정책 |
| `walksafe-v2/reviewed_model_rollout_checklist.md` | legacy reviewed YOLO26s fallback 학습/선정/checkpoint/threshold 검증 체크리스트 |
| `walksafe-v2/cleanup_inventory_20260704.md` | Android primary 정책 기준 cleanup 대상과 제거 금지 항목 |
| `walksafe-v2/implementation_confidence_audit_20260704.md` | PASS/PARTIAL/FAIL/BLOCKED 기준 구현 신뢰도 감사 |

## 운영/현재 상태 문서

| 문서 | 용도 |
|---|---|
| `current_status.md` | 2026-07-01 KST 기준 구현 상태와 남은 gate |
| `report_operations.md` | 신고 상태, v2 자동/음성 신고, 필터/export, 검수 흐름 |
| `pwa_backend_status.md` | Web/PWA는 보조 demo, backend/admin은 유지되는 현재 상태 |
| `voice_stt_tts_status.md` | STT/TTS prototype 상태와 Android native 우선순위에서의 위치 |
| `model_training_status.md` | 과거 모델 학습 snapshot. 새 13-class 기준은 `model_unified_13class_aihub_sources_20260602.md`와 `model/README.md` 우선 |
| `model_v2_status.md` | 과거/참고용 v2 모델 상태. 최신 구현 기준은 `walksafe-v2/`와 Android 문서 우선 |

## 참고용 실행 이력/과거 계획

아래 문서는 실제 실행 맥락을 추적할 때만 본다. 현재 제품/코드 판단의 우선 근거로 쓰지 않는다.

| 문서 | 참고 범위 |
|---|---|
| `../plans/features/2026-06-01_android_native_gate_execution_plan.md` | 2026-06-01 Android gate 실행 계획 snapshot. 최신 APK/unified-primary 기준은 `current_status.md` 우선 |
| `../plans/features/2026-05-31_android_native_next_step_plan.md` | 2026-05-31 Android native 다음 스텝 snapshot. 현재 APK hash/모델 기준과 다를 수 있음 |
| `execution/2026-05-31_android_static_dataset_contract_progress.md` | Android static dataset/contract 실행 기록. Device PASS 근거 아님 |
| `execution/**` | 실행 기록. 최신 구현 기준 아님 |

## legacy/superseded 문서

아래 문서는 삭제하지 않고 보존하지만, 현재 작업 기준으로는 **버린 문서**로 본다. 새 구현이나 판단에 그대로 쓰지 않는다.

| 문서 | 현재 판정 | 대체 문서 |
|---|---|---|
| `../PROJECT_PLAN.md` | 2026-05-13 PWA/fake/v1 중심 계획. superseded | `../README.md`, `current_status.md`, `../product/vision.md` |
| `project_3day_execution_plan.md` 및 `*_3day_execution_plan.md` | 2026-05-14~16 실행 계획. 완료/만료 | `current_status.md`, `daylog/` |
| `inference_contract.md` | v1/PWA 4-class `DetectionEvent` 계약. legacy | `walksafe-v2/backend_api_contract.md`, `walksafe-v2/two_model_runtime_plan.md` |
| `model_integration_plan.md`, `model_placeholder_systems.md`, `pre_model_backend_todo.md` | v1 `/detect`/placeholder 중심 계획. legacy | `walksafe-v2/backend_model_integration_notes.md`, `backend_environment.md` |
| `frontend_handoff_without_model.md`, `frontend_api_examples.md` | PWA/v1 연동 handoff. legacy | `walksafe-v2/frontend_display_policy.md`, `api_reference.md` |
| `model_v2_status.md`, `model_training_status.md` | 과거 YOLO tactile/v2 snapshot. 숫자 인용 시 작성일 명시 | `model_unified_13class_aihub_sources_20260602.md`, `../model/README.md` |
| `neck_worn_phone_test_checklist.md` | PWA/Chrome 목걸이 테스트 기준. Android Device PASS 아님 | `android/android_device_overlay_depth_checklist_20260601.md` |
| `local_stt_tts_ai_prompt.md`, `local_voice_server_plan.md`, `stt_tts_current_status.md` | 음성 prototype 상세/과거 기록 | `voice_stt_tts_status.md`, `walksafe-v2/voice_command_strategy.md` |
| `execution/**`, `../plans/.work/**`, `../plans/daily/**`, `../plans/catchup/**`, `../plans/weekly/**` | 실행 기록/작업 로그. 최신 기준 아님 | 새 작업은 `current_status.md`와 `daylog/` 기준으로 시작 |

2026-07-01 세부 archive 후보와 이동 보류 이유는 `archive_candidates_20260701.md`를 따른다. 링크 파손 가능성이 있어 이번 정리에서는 실제 이동하지 않는다.

## 삭제 후보

실제 삭제는 사용자 확인 후에만 한다. 현재 기준으로 삭제하거나 archive 이동해도 되는 후보는 다음이다.

| 후보 | 이유 | 보존 필요성 |
|---|---|---|
| `*_3day_execution_plan.md` | 기간 만료, 현재 Android/unified 기준과 다름 | 낮음. 필요 내용은 실행 로그/daylog에 남음 |
| `../plans/.work/**` | 자동/중간 작업 산출물 성격 | 낮음~중간. 감사 추적이 필요하면 보존 |
| `../plans/features/2026-05-31_android_native_next_step_plan.md`, `../plans/features/2026-06-01_android_native_gate_execution_plan.md` | 최신 APK/unified-primary 전환 전 계획 snapshot | 중간. 필요하면 `../plans/archive/` 이동 |
| 오래된 `execution/2026-05-14*`~`execution/2026-05-18*` | 초기 PWA/fake/v1 실행 기록 | 중간. 보고서 근거로 쓰지 않으면 archive 가능 |
| `model_v2_status.md` | unified 13-class 전환 전 snapshot | 중간. 과거 모델 비교가 필요하면 보존 |

## 로컬 전용 산출물

다음은 문서에 언급되더라도 기본적으로 GitHub에 올리지 않는다.

- 데이터셋 이미지/라벨: `datasets/**/images/**`, `datasets/**/labels/**`
- 학습 결과/weight: `runs/`, `*.pt`, `*.onnx`, `*.engine`, `*.tflite`
- AI Hub 원본 zip, 음성 샘플/출력/로그
- 중간 review pack overlay/crop 대량 산출물
