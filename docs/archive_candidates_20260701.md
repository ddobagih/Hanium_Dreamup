# WalkSafe archive candidates - 2026-07-01

## 목적

오래되었거나 현재 코드 기준을 대체하지 못하는 문서를 삭제하지 않고 분류한다. 이번 정리에서는 링크 파손 가능성이 있어 파일 이동은 하지 않는다.

## 현재 상태

- 현재 source of truth는 `README.md`, `docs/current_status.md`, `docs/README.md`, `apps/android/README.md`, `docs/walksafe-v2/`, `docs/report_operations.md`, `model/README.md`다.
- `docs/execution/**`, `plans/**`, 초기 `*_3day_execution_plan.md`는 실행 이력 또는 과거 계획으로 본다.
- 과거 문서의 히스토리는 보존하되, 새 구현 판단이나 완료 주장에는 사용하지 않는다.

## archive 후보

| 후보 | 분류 이유 | 대체/우선 문서 | 처리 |
|---|---|---|---|
| `PROJECT_PLAN.md` | 2026-05-13 PWA/fake/v1 중심 계획 | `README.md`, `docs/current_status.md`, `product/vision.md` | 이동 보류, superseded header 유지 |
| `docs/project_3day_execution_plan.md` 및 `docs/*_3day_execution_plan.md` | 2026-05-14~16 단기 실행 계획으로 만료 | `docs/current_status.md`, `daylog/`, `docs/README.md` | 이동 보류 |
| `docs/inference_contract.md` | v1/PWA 4-class 계약 중심 | `docs/walksafe-v2/backend_api_contract.md`, `docs/walksafe-v2/two_model_runtime_plan.md` | 이동 보류 |
| `docs/model_integration_plan.md`, `docs/model_placeholder_systems.md`, `docs/pre_model_backend_todo.md` | placeholder/v1 `/detect` 중심 | `docs/walksafe-v2/backend_model_integration_notes.md`, `model/README.md` | 이동 보류 |
| `docs/frontend_handoff_without_model.md`, `docs/frontend_api_examples.md` | PWA/v1 handoff 중심 | `docs/walksafe-v2/frontend_display_policy.md`, `docs/api_reference.md` | 이동 보류 |
| `docs/model_v2_status.md`, `docs/model_training_status.md` | 과거 YOLO tactile/v2 snapshot | `model/README.md`, `docs/model_unified_13class_aihub_sources_20260602.md`, `docs/execution/2026-06-27_walksafe_13class_aihub183_png_training_plan.md` | 이동 보류 |
| `docs/neck_worn_phone_test_checklist.md` | PWA/Chrome 목걸이 테스트 기준 | `docs/android/android_device_overlay_depth_checklist_20260601.md` | 이동 보류 |
| `docs/stt_tts_current_status.md`, `docs/local_stt_tts_ai_prompt.md`, `docs/local_voice_server_plan.md` | voice prototype/과거 기록 | `docs/voice_stt_tts_status.md`, `docs/walksafe-v2/voice_command_strategy.md` | 이동 보류 |
| `docs/execution/2026-05-14*` ~ `docs/execution/2026-05-18*` | 초기 PWA/fake/v1 실행 기록 | `docs/current_status.md`, `docs/evidence/final_report_index.md` | 이동 보류 |
| `plans/.work/**`, `plans/daily/**`, `plans/catchup/**`, `plans/weekly/**` | 작업 로그/중간 산출물 | `daylog/`, canonical docs | 이동 보류 |

## 관련 파일/경로

- 문서 지도: `docs/README.md`
- 현재 상태: `docs/current_status.md`
- 문서 정합성 감사: `docs/walksafe_documentation_audit_20260701.md`
- 작업 로그: `daylog/`

## 검증 방법

```bash
rg -n "PROJECT_PLAN.md|3day_execution_plan|inference_contract.md|model_v2_status.md|neck_worn_phone_test_checklist.md" docs README.md product
rg -n "archive_candidates_20260701|walksafe_documentation_audit_20260701" docs/README.md
```

## 남은 리스크

- 위 후보를 실제로 이동하면 기존 문서 링크, daylog, 과거 보고서 참조가 깨질 수 있다.
- 이동이 필요하면 `docs/_archive_candidates/2026-07-01/` 같은 별도 위치로 옮기기 전에 링크 검사를 먼저 해야 한다.
- 데이터셋, 모델 weight, 실행 로그, secret, 운영 DB 산출물은 archive 문서 정리 대상이 아니며 삭제하지 않는다.
