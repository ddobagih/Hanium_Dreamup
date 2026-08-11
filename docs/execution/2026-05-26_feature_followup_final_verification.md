# 2026-05-26 240개 Feature Follow-up 최종 통합 검증

## 범위

- 기준 문서: `plans/features/2026-05-26_feature_followup_implementation_list.md`
- 원문 항목 수: 240개
- 사용자 승인 반영:
  - 진행: live TMAP 자동 재탐색 코드 경로 + GPS 튐 제한, depth/ARCore/WebXR capability/interface gate, DeviceMotion calibration dry-run/local UI
  - 제외: 실제 전화/SMS 발신, 실제 LLM/Cloud NLU 연결
- 검증 범위: local/mock/static/headless/integration. 배포/외부 제출/destructive 작업 없음.

## 통합 구현 요약

| 영역 | 구현 요약 | 완료 주장 범위 |
|---|---|---|
| Navigation/Reroute | 목적지 검색 debounce/cancel/더보기/거리, 후보 선택 차단, 도착/진행률, confirmed off-route 자동 재탐색 코드 경로, GPS accuracy/jump/interval/in-flight/cooldown/max-count gate | Unit/Headless/Static PASS. live TMAP 호출은 dry-run |
| Risk/Depth/ROI/Tracking | browser depth/WebXR capability gate, trusted distance source/confidence, ROI debug gate/motion shift/lower-half, tracking key/stale reset/TTC/jitter negative, 하단/발밑/high-risk 문구 | Unit/Headless/Static PASS. 실제 depth sensor/field는 미검증 |
| DeviceMotion/Settings/PWA | DeviceMotion permission/calibration dry-run log, 보폭 confidence/outlier/TTL/reset, 복수 긴급 연락처 local-only, PWA install/update/offline shell, PNG 192/512 icons, opt-in offline report queue TTL/용량/수동 재시도 helper | Unit/Headless/Static PASS. 실기기 calibration/TalkBack은 미검증 |
| Admin/Reports/Privacy | report trace/hash, export deep fields/manifest/grid/redacted, summary cluster breakdown, fake/demo/performance_excluded, review note/history/conflict, EXIF re-encode fixture, retention dry-run | Unit/Integration/GIS-local PASS. 운영 DB/외부 제출/삭제 실행 없음 |
| Voice/TTS/NLU | TTS cache header unit, fallback=false, cache key inputs, text length, CORS/OPTIONS contract, intent telemetry privacy schema, phrase catalog/cache-status | Unit/Integration-local PASS. 실제 Cloud NLU 제외 |
| Evidence | feature matrix, templates, privacy checklist, final report index, data retention policy, daylog | Static PASS |

## 부모 통합 재검증 결과

| 명령 | 결과 |
|---|---|
| `PYTHONPATH=. .venv/bin/python -m pytest backend/tests -q -rs` | 88 passed |
| `PYTHONPATH=. .venv/bin/python -m pytest tests model -q` | 85 passed |
| `bash scripts/check_frontend_risk_evaluator_policy_20260523.sh` | PASS |
| `bash scripts/check_frontend_motion_roi_policy_20260526.sh` | PASS |
| `bash scripts/check_frontend_navigation_destination_policy_20260525.sh` | PASS |
| `bash scripts/check_frontend_navigation_guidance_policy_20260524.sh` | PASS |
| `bash scripts/check_frontend_route_progress_policy_20260525.sh` | PASS |
| `bash scripts/check_frontend_step_length_policy_20260525.sh` | PASS |
| `bash scripts/check_frontend_admin_report_summary_policy_20260525.sh` | PASS |
| `bash scripts/check_frontend_settings_privacy_20260526.sh` | PASS |
| `bash scripts/check_frontend_pwa_policy_20260526.sh` | PASS, offline report queue fixture 포함 |
| `python scripts/check_frontend_accessibility_static.py` | PASS |
| `node --check apps/web/public/sw.js` | PASS |
| `cd apps/web && npm run typecheck` | PASS |
| `cd apps/web && npm run lint` | PASS |
| `cd apps/web && npm run build` | PASS |
| `.venv/bin/python -m py_compile scripts/check_voice_contract.py scripts/check_voice_tts_http_cache_20260525.py scripts/check_frontend_accessibility_static.py scripts/check_report_retention_dry_run.py scripts/check_detect_report_export_trace_20260524.py voice/server.py voice/tts.py voice/telemetry.py` | PASS |
| local uvicorn + `scripts/check_voice_contract.py --base-url http://127.0.0.1:19001` | PASS |
| `python3 scripts/check_tmap_pedestrian_route_smoke_20260524.py` | DRY-RUN PASS, live request not sent |
| `python3 scripts/check_detect_report_export_trace_20260524.py --dry-run-policy` | PASS |
| `PYTHONPATH=. .venv/bin/python scripts/check_detect_report_export_trace_20260524.py --artifact-json /tmp/detect_report_trace_live.json --artifact-md /tmp/detect_report_trace_live.md` | PASS |
| `python3 scripts/check_report_retention_dry_run.py --input-json <fixture> --as-of 2026-05-26T00:00:00Z --output-md /tmp/report_retention_dry_run.md` | PASS |
| `python3 scripts/check_report_retention_dry_run.py --execute-delete` | exit 2, 삭제 차단 PASS |
| `git diff --check` | PASS |
| diff review fixes: reports fake_source=false/model_class_id, TMAP timing dry-run, frontend OUT_DIR cleanup guard | PASS |
| relevant conflict marker grep | PASS |

## 남은 완료 주장 제한

- 실제 전화/SMS 발신과 실제 LLM/Cloud NLU는 사용자 지시에 따라 구현 대상에서 제외했다.
- live TMAP 자동 재탐색은 런타임 코드 경로와 GPS 튐 제한을 구현했지만, live provider 요청은 dry-run으로만 검증했다.
- depth/ARCore/WebXR은 capability/interface gate와 fallback을 구현했지만, 실제 센서 세션/거리값 field evidence는 없다.
- DeviceMotion calibration은 local UI/log/dry-run과 fixture까지이며 Android 실폰 목걸이 착용 현장 calibration 완료가 아니다.
- 운영 DB retention/delete, 외부 기관 제출, 배포/secret/domain 작업은 하지 않았다.
- Android 실폰 카메라/GPS/TTS/진동/mic/TalkBack/PWA install/offline 현장 evidence는 아직 별도 필요하다.
