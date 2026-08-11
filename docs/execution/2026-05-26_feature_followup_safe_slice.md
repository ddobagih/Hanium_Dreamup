# 2026-05-26 Feature Follow-up Safe Slice 검증 기록

## 범위

- 기준: `plans/features/2026-05-26_feature_followup_implementation_list.md` 240개 항목.
- 이번 범위: 로컬/mock/static/dry-run으로 안전하게 닫을 수 있는 P0/P1 일부 구현과 검증.
- 제외: 배포, secret/계정 설정, 운영 DB, 외부 기관 전송, 실제 전화/SMS, 실기기 field/TalkBack, 장시간 학습, 반복 live TMAP 호출.
- 안전 원칙: 외부 공개/비용 발생/삭제/초기화 없이 로컬 테스트와 문서 evidence만 수행.

## 구현 요약

| 영역 | 반영 내용 | evidence 등급 |
|---|---|---|
| #1 목적지 검색 | search health endpoint, TMAP POI mock provider, origin 거리 계산, result type/alias, API 문서 갱신 | Static, Unit, Integration |
| #3/#5/#20 음성 내비게이션/NLU | 자연어 목적지 `requested_start`, `reroute_navigation`, `stop_navigation`, 부정 신고 safe no-op, intent schema endpoint | Unit, Integration |
| #7/#8 거리/방향 안내 | `distance_m` 상한, distance source/confidence, runtime/API/frontend 전달, bbox overflow drop, 거리 없을 때 보폭 문구 금지 | Static, Unit, Integration |
| #12~#16 신고/운영 | v2 payload allowlist/size limit, export no-store/redacted/BOM/GeoJSON props, summary endpoint, fake flag, status transition/history/optimistic guard | Unit, Integration, GIS/Ops-local |
| #17/#18 PWA/settings/accessibility | 보호자 연락처 local-only validation/masking, localStorage 예외 처리, offline preflight, viewport zoom 허용, manifest/SW audit, skip/offline/status UI | Static, Unit, Headless-local |
| #19 TTS | phrase catalog, TTS cache-status dry-run endpoint, HTTP cache smoke script batch/JSON 옵션 보강 | Unit, Static |
| 공통 evidence/privacy | feature matrix, evidence templates, privacy checklist, done criteria badge 규칙 | Static |

## 실행 검증

| 명령 | 결과 | 범위/주의 |
|---|---|---|
| `python - <<'PY' ... count feature bullets ... PY` | `240` | 원문 항목 수 확인 |
| `PYTHONPATH=. .venv/bin/python -m pytest backend/tests -q` | `81 passed` | 백엔드 ASGI/DB fixture/local integration |
| `PYTHONPATH=. .venv/bin/python -m pytest tests model -q` | `80 passed` | voice + model runtime unit |
| `PYTHONPATH=. .venv/bin/python -m pytest backend/tests/test_detect_v2.py backend/tests/test_navigation_routes.py model/test_two_model_runtime.py -q` | `32 passed` | detect/navigation/runtime targeted |
| `PYTHONPATH=. .venv/bin/python -m pytest backend/tests/test_reports_v2.py -q -rs` | `20 passed` | report v2/export/status targeted |
| `bash scripts/check_frontend_risk_evaluator_policy_20260523.sh` | PASS | risk/auto-report TS fixture |
| `bash scripts/check_frontend_navigation_destination_policy_20260525.sh` | PASS | destination policy fixture |
| `bash scripts/check_frontend_navigation_guidance_policy_20260524.sh` | PASS | guidance/voice priority/report export policy |
| `bash scripts/check_frontend_route_progress_policy_20260525.sh` | PASS | route progress fixture |
| `bash scripts/check_frontend_step_length_policy_20260525.sh` | PASS | step length fixture |
| `bash scripts/check_frontend_admin_report_summary_policy_20260525.sh` | PASS | admin summary/export policy |
| `bash scripts/check_frontend_settings_privacy_20260526.sh` | PASS | local-only settings privacy fixture |
| `python scripts/check_frontend_accessibility_static.py` | PASS | static accessibility/PWA audit |
| `node --check apps/web/public/sw.js` | PASS | service worker syntax |
| `cd apps/web && npm run typecheck && npm run lint` | PASS | full frontend type/lint |
| `.venv/bin/python -m py_compile scripts/check_voice_tts_http_cache_20260525.py scripts/check_frontend_accessibility_static.py` | PASS | script syntax |
| `git diff --check -- <safe-slice files>` | PASS | whitespace check for touched slice |

## 완료로 주장하지 않는 범위

- 240개 전체를 `Device`, `Release`, `Model`, 외부 기관 제출까지 완료한 것은 아니다.
- TMAP live 자동 재요청, Cloud STT/TTS, 실제 전화/SMS, 운영 DB, 외부 제출, 배포 URL, Android 실폰/TalkBack/현장 보행 검증은 별도 승인과 환경이 필요하다.
- `distance_m`는 계약/전달/표시 정책을 닫은 것이며 실제 depth 센서 거리 추정 런타임은 C-gate/후속 과제다.
- fake/mock/local 결과는 실제 보행 안전성, 모델 정확도, field 성능 근거로 쓰지 않는다.
