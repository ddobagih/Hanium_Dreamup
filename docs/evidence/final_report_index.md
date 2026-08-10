# Final Report Evidence Index

- 작성일: 2026-07-02 KST
- 범위: Web/PWA local/mock/static/headless/build, backend integration과 Android 보조 모듈 build 검증 근거 색인
- 제외: 실제 전화/SMS, 실제 LLM/Cloud NLU, 운영 배포, 외부 기관 자동 API 제출, destructive retention, Web/PWA Release 및 Android field 완료 주장

> Status: HISTORICAL EVIDENCE INDEX / SUPERSEDED. 아래 명령 결과·APK hash·문서 우선순위는 2026-07-02 당시 기록이다. 현행 Web/PWA 주 앱, unified img768 Android runtime, 최신 APK·실기기 instrumentation·회귀 수치는 `docs/status/current_status.md`와 `apps/android/README.md`를 우선한다.

## 핵심 evidence

| Evidence | 경로 | 범위 |
|---|---|---|
| Web/PWA README | `apps/web/README.md` | 주 사용자 앱 구조, 실행, 현재 브라우저·offline·Release 한계 |
| Team current status | `docs/status/walksafe_team_current_status_20260708.md` | 2026-07-08 팀 공유용 현재 상태/source-of-truth |
| Current status | `docs/status/current_status.md` | 2026-07-02 상세 상태 snapshot. 최신 해석은 2026-07-08 팀 공유 문서 우선 |
| Android README | `apps/android/README.md` | APK path/hash, build/install, TFLite config, overlay |
| Android architecture | `docs/android/arcore_depth_estimation_architecture.md` | ARCore depth/TFLite/coordinate risk |
| Android gate execution plan | `plans/features/2026-06-01_android_native_gate_execution_plan.md` | 과거 실행 계획 snapshot. 최신 기준은 Current status 우선 |
| Android next-step plan | `plans/features/2026-05-31_android_native_next_step_plan.md` | 과거 next-step snapshot. 최신 APK/hash/model 기준은 Current status 우선 |
| Android coordinate mapping plan | `docs/android/arcore_coordinate_mapping_plan.md` | overlay/depth mapper 분리 설계 |
| Android device overlay/depth checklist | `docs/android/android_device_overlay_depth_checklist_20260601.md` | 실기기 overlay/depth/`N보` 관찰 기록 양식 |
| Android metadata capture schema | `docs/android/android_metadata_capture_schema_20260601.md` | metadata-only log 필드와 원본 저장 금지 기준 |
| Android server debug log runbook | `docs/android/android_server_debug_log_runbook_20260601.md` | local/dev 서버 metadata-only debug log 수집/확인 절차 |
| Android RGB-D dataset schema | `docs/android/arcore_rgbd_dataset_schema_20260601.md` | ARCore RGB-D 검증 dataset/metric/privacy schema |
| Android/backend threshold note | `docs/android/android_backend_threshold_decision_20260531.md` | threshold warning/분리 유지 결정 |
| Android static threshold evidence | `docs/evidence/android_static_threshold_evidence_20260601.md` | saved static RGB threshold tradeoff와 full rerun blocked 근거 |
| Android report source/metadata decision | `docs/walksafe-v2/android_report_source_metadata_decision_20260601.md` | `/reports/v2` Android source/metadata/upload/fallback fields 결정 |
| Android execution record | `docs/execution/2026-05-31_android_static_dataset_contract_progress.md` | Android contract/static dataset 실행 기록 |
| AIHub189 original full offline validation | `docs/evidence/aihub189_depthprediction_original_full_20260702.md` | 실제 `Depth_001~005.zip` 원본 full offline ZED reference run. `arcore_pass=false` |
| Feature matrix | `docs/evidence/feature_matrix.md` | 240개 항목 evidence 등급/상태 + Web/PWA 주경로와 Android 보조 모듈 evidence 구분 |
| Evidence templates | `docs/evidence/templates.md` | PASS/BLOCKED/미검증 표준 |
| Privacy checklist | `docs/evidence/privacy_checklist.md` | local-only/보존/no-store/export/privacy 검증 |
| Report operations | `docs/operations/report_operations.md` | 신고/검수/필터/CSV 다운로드 후 관리자 수동 외부 신고 정책 |
| Backend v2 contract | `docs/walksafe-v2/backend_api_contract.md` | v2 backend 계약 |
| Safe slice 통합 실행 기록 | `docs/execution/2026-05-26_feature_followup_safe_slice.md` | 2026-05-26 Web/backend/voice safe slice |

## 대표 검증 명령

| 명령 | 결과 | 주의 |
|---|---|---|
| `python scripts/check_android_tflite_contract_20260531.py` | PASS, `check_scope=static_contract_only`, `unified_asset_present=false`, `expected_runtime_model=legacy_two_model`, `expected_fallback=true` | Android 내부 계약 기준. unified 완료나 backend threshold 일치로 주장 금지 |
| `python scripts/check_android_depth_scaffold_20260531.py` | PASS | static scaffold 기준. Device depth 정합 PASS 아님 |
| `python scripts/export_android_tflite_models_20260531.py --dry-run` | PASS | export dry-run, 모델 재학습 아님 |
| `cd apps/android && ./gradlew test assembleDebug --no-daemon` | BUILD SUCCESSFUL, APK sha256 `1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9` | JVM/unit/APK build 기준, field PASS 아님 |
| `PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -p no:cacheprovider backend/tests/test_report_policy.py backend/tests/test_android_debug_logs.py backend/tests/test_reports_v2.py backend/tests/test_navigation_routes.py -q` | 24 passed, 32 skipped | navigation/debug/report policy PASS. PostGIS 미기동 skip 포함 |
| `cd apps/web && npm run typecheck` | PASS | web type contract |
| `cd apps/web && npm run lint` | PASS | web lint |
| `bash scripts/check_frontend_admin_report_summary_policy_20260525.sh` | PASS | Android source/admin export policy |
| `bash scripts/check_frontend_walksafe_test_log_policy_20260701.sh` | PASS | test capture default-off/auth/consent/validation/retention/delete policy |
| `PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -p no:cacheprovider tests/test_aihub189_depthprediction_offline.py -q` | 5 passed, 8 warnings | offline ZED reference evaluator unit 기준 |
| `scripts/evaluate_aihub189_depthprediction_offline.py --full-original-zip-run` | frames=2461, evaluated=2461, buckets stop=883/warning=652/ignore=926 | 실제 `Depth_001~005.zip` 원본 offline ZED reference full run. `arcore_pass=false`, detector 성능 검증 아님 |
| `scripts/check_static_dataset_readiness_20260531.py` | completed, full rerun blocked | reviewed tactile3 dataset 부재 |
| Web/backend 2026-05-26 suite | PASS 기록 있음 | local/mock/static/headless 기준 |

## Android APK 기준

```text
apps/android/app/build/outputs/apk/debug/app-debug.apk
sha256=1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9
```

이 APK hash는 Android build artifact 식별용이다. `SM-G981N` / Android 13 / `R3CN50F4APH`에서 install Success, `am start -W` Status ok/COLD/TotalTime 480ms, PID 16571, `MainActivity` RESUMED/visible/reportedDrawn, AndroidRuntime:E empty를 확인했다. 실기기에서 bbox/depth가 정확하다는 증거는 아니다.

## 완료 주장 제한

- `[Android Device PASS]`는 정지 install/launch/crash-free smoke로 제한한다. bbox overlay 정합과 실측 depth/`N보` 정확도는 별도 기록이 필요하다.
- `[Web/PWA Release PASS]`는 아직 없다. 배포 URL, service worker 정책, browser/phone E2E, secret/domain/storage/rollback과 외부 공개 승인이 필요하다.
- static RGB dataset은 detector/threshold 근거일 수 있지만 ARCore metric depth 근거가 아니다.
- 실제 전화/SMS와 실제 LLM/Cloud NLU는 이번 구현 범위에서 제외했다.
- live TMAP 자동 재탐색은 Web/PWA 주 사용자 경로의 미검증 기능이며 후순위로 낮추지 않는다. Android route 결과는 별도 보조 모듈 evidence다.
