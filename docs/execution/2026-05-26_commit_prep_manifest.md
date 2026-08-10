# 2026-05-26 Commit Prep Manifest

목적: 240개 follow-up 구현분을 커밋/PR 준비용으로 선별한다. 이 문서는 **커밋/푸시를 수행하지 않는다**. 현재 working tree에는 이전 작업 산출물과 대량 untracked 파일이 섞여 있으므로 `git add .`는 금지한다.

## 원칙

- 포함: 240개 follow-up 구현/테스트/문서/evidence/daylog와 직접 연결된 파일.
- 제외: 데이터셋/모델 산출물/캐시/이전 날짜 bulk PM 산출물/실기기 field 산출물/secret.
- 실제 전화/SMS, 실제 LLM/Cloud NLU, 배포/외부 제출/삭제 실행은 제외 상태 유지.
- 커밋 전에는 아래 그룹별로 `git add -- <path...>` 또는 `git add -p`를 사용한다.

## 추천 커밋 묶음

### 1. backend-navigation-detect-reports

```text
backend/.env.example
backend/app/main.py
backend/app/config.py
backend/app/schemas.py
backend/app/api/detect.py
backend/app/api/navigation.py
backend/app/api/reports.py
backend/app/api/uploads.py
backend/app/services/detect_v2.py
backend/app/services/tmap_pedestrian.py
backend/app/services/yolo_inference_adapter.py
backend/app/services/report_policy.py
backend/app/services/report_serialization.py
backend/app/uploads.py
backend/tests/test_detect_v2.py
backend/tests/test_navigation_routes.py
backend/tests/test_reports_v2.py
backend/tests/test_uploads.py
backend/tests/test_yolo_inference_adapter.py
```

### 2. frontend-navigation-risk-pwa-admin

```text
apps/web/.env.example
apps/web/app/layout.tsx
apps/web/app/page.tsx
apps/web/app/globals.css
apps/web/app/admin/page.tsx
apps/web/app/_walksafe/components/AssistPanel.tsx
apps/web/app/_walksafe/components/CameraSurface.tsx
apps/web/app/_walksafe/config.ts
apps/web/app/_walksafe/feedback.ts
apps/web/app/_walksafe/hooks/useAutoReportV2.ts
apps/web/app/_walksafe/hooks/useAutoStepLength.ts
apps/web/app/_walksafe/hooks/useNavigationGuidance.ts
apps/web/app/_walksafe/hooks/usePwaStatus.ts
apps/web/app/_walksafe/hooks/useRiskFeedback.ts
apps/web/app/_walksafe/hooks/useTwoModelRiskHistory.ts
apps/web/app/_walksafe/hooks/useVoiceCommands.ts
apps/web/app/_walksafe/hooks/useWalkSafeSettings.ts
apps/web/app/_walksafe/navigation-destination.ts
apps/web/app/_walksafe/risk-depth.ts
apps/web/app/_walksafe/risk-evaluator.ts
apps/web/app/_walksafe/risk-guidance.ts
apps/web/app/_walksafe/risk-roi.ts
apps/web/app/_walksafe/route-progress.ts
apps/web/app/_walksafe/step-length.ts
apps/web/app/_walksafe/voice-priority.ts
apps/web/lib/auto-report-v2.ts
apps/web/lib/detect-api-v2.ts
apps/web/lib/detector-v2.ts
apps/web/lib/navigation-api.ts
apps/web/lib/offline-report-queue.ts
apps/web/lib/report-api.ts
apps/web/lib/report-api-v2.ts
apps/web/lib/two-model-priority.ts
apps/web/lib/voice-api.ts
apps/web/public/manifest.webmanifest
apps/web/public/sw.js
apps/web/public/icons/icon-192.png
apps/web/public/icons/icon-512.png
apps/web/types/inference-v2.ts
apps/web/types/navigation.ts
apps/web/tests/admin-report-summary-policy.test.ts
apps/web/tests/auto-report-v2-policy.test.ts
apps/web/tests/navigation-destination-policy.test.ts
apps/web/tests/navigation-guidance-policy.test.ts
apps/web/tests/offline-report-queue-policy.test.ts
apps/web/tests/pwa-status-policy.test.ts
apps/web/tests/risk-evaluator-policy.test.ts
apps/web/tests/route-progress-policy.test.ts
apps/web/tests/settings-privacy.test.ts
apps/web/tests/step-length-policy.test.ts
```

### 3. voice-tts-nlu-model-runtime

```text
model/two_model_runtime.py
model/test_two_model_runtime.py
configs/walksafe_two_model_runtime_stage1_mvp_20260523.json
voice/intents.py
voice/phrases.py
voice/server.py
voice/telemetry.py
voice/tts.py
tests/test_voice_intents.py
tests/test_voice_tts.py
```

### 4. verification-scripts

```text
scripts/check_detect_report_export_trace_20260524.py
scripts/check_detect_v2_contract_smoke_20260523.py
scripts/check_detect_v2_stage1_candidate_health_20260523.sh
scripts/check_detect_v2_stage1_image_smoke_20260524.py
scripts/check_frontend_accessibility_static.py
scripts/check_frontend_admin_report_summary_policy_20260525.sh
scripts/check_frontend_motion_roi_policy_20260526.sh
scripts/check_frontend_navigation_destination_policy_20260525.sh
scripts/check_frontend_navigation_guidance_policy_20260524.sh
scripts/check_frontend_pwa_policy_20260526.sh
scripts/check_frontend_risk_evaluator_policy_20260523.sh
scripts/check_frontend_route_progress_policy_20260525.sh
scripts/check_frontend_settings_privacy_20260526.sh
scripts/check_frontend_step_length_policy_20260525.sh
scripts/check_navigation_guidance_timing_20260524.py
scripts/check_navigation_reroute_gate_20260525.py
scripts/check_report_retention_dry_run.py
scripts/check_tmap_pedestrian_route_smoke_20260524.py
scripts/check_voice_contract.py
scripts/check_voice_tts_http_cache_20260525.py
```

### 5. docs-evidence-reporting

```text
docs/README.md
docs/backend/api_reference.md
docs/backend/backend_environment.md
docs/operations/data_retention_policy.md
docs/operations/report_operations.md
docs/backend/sensor_log_schema.md
docs/evidence/feature_matrix.md
docs/evidence/final_report_index.md
docs/evidence/privacy_checklist.md
docs/evidence/templates.md
docs/execution/2026-05-26_admin_reports_privacy_evidence_worker.md
docs/execution/2026-05-26_commit_prep_manifest.md
docs/execution/2026-05-26_devicemotion_settings_pwa_worker.md
docs/execution/2026-05-26_feature_followup_final_verification.md
docs/execution/2026-05-26_feature_followup_safe_slice.md
docs/execution/2026-05-26_pr_summary_240_followup.md
docs/walksafe-v2/backend_api_contract.md
docs/walksafe-v2/backend_model_integration_notes.md
docs/walksafe-v2/frontend_display_policy.md
docs/walksafe-v2/navigation_integration_policy.md
docs/walksafe-v2/public_agency_submission_policy.md
docs/walksafe-v2/reviewed_model_rollout_checklist.md
docs/walksafe-v2/voice_command_strategy.md
product/done-criteria.md
daylog/2026-05-26.md
```

## 보류/제외 후보

아래는 현재 working tree에 보이더라도 이번 240개 구현 커밋에 자동 포함하지 않는다.

```text
ai_tasks/**
data_sources/**
datasets/**
runs/**
outputs/**
*.pt
*.onnx
*.engine
*.tflite
apps/web/.next/**
apps/web/node_modules/**
backend/app/**/__pycache__/**
backend/tests/**/__pycache__/**
plans/**
plans/.work/**
plans/daily/**
plans/weekly/**
daylog/2026-05-19.md
daylog/2026-05-20.md
daylog/2026-05-21.md
daylog/2026-05-22.md
daylog/2026-05-23.md
daylog/2026-05-24.md
daylog/2026-05-25.md
scripts/run_walksafe_tactile3_*.sh
scripts/resume_walksafe_tactile3_*.sh
scripts/post_*yolo26s*_report_*.sh
scripts/summarize_walksafe_tactile3_*.py
scripts/evaluate_yolo_image_level_presence_20260523.py
```

## 별도 리뷰 후 포함 여부 결정

아래 문서는 현재 수정되어 있지만 240개 구현 커밋에 넣기 전 내용 리뷰가 필요하다. 최신 상태 문서 전체 갱신 커밋으로 따로 묶는 것을 권장한다.

```text
docs/status/current_status.md
docs/model_v1_dataset.md
docs/status/pwa_backend_status.md
docs/stt_tts_current_status.md
docs/status/voice_stt_tts_status.md
docs/walksafe-v2/README.md
docs/walksafe-v2/auto_report_policy.md
docs/walksafe-v2/two_model_runtime_plan.md
product/backlog.md
product/decisions.md
product/roadmap.md
product/vision.md
```

## 커밋 직전 검증 기준

- `PYTHONPATH=. .venv/bin/python -m pytest backend/tests -q -rs`
- `PYTHONPATH=. .venv/bin/python -m pytest tests model -q`
- frontend policy scripts 전체
- `cd apps/web && npm run typecheck && npm run lint && npm run build`
- `git diff --check`
- 민감/대용량 파일 최종 확인: `git status --short --untracked-files=all`
