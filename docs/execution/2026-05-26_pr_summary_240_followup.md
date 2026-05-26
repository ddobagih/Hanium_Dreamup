# PR Summary Draft - 240개 Follow-up Local/Headless Implementation

## Summary

- Implemented the 2026-05-26 240-item follow-up safe/local scope across navigation, risk/depth/ROI, DeviceMotion/PWA/settings, reports/admin/privacy, and voice/TTS/NLU.
- Added live TMAP automatic reroute runtime path with GPS accuracy/jump/interval, in-flight, cooldown, and max-count safety gates. Live provider calls were not sent during verification; dry-run only.
- Added browser depth/WebXR capability/interface gate and trusted distance source policy without claiming real sensor field completion.
- Added DeviceMotion calibration dry-run UI/log schema, step-length confidence/outlier/TTL/reset, PWA install/update/offline shell, PNG PWA icons, and opt-in offline report queue helper.
- Added report trace/hash/export/summary/fake-demo separation/review workflow/retention dry-run and deletion guard.
- Excluded actual phone/SMS sending and actual LLM/Cloud NLU integration per user instruction.

## Major areas

### Navigation / reroute

- Destination search health/mock provider/origin distance.
- Debounce/cancel/retry/more candidates/candidate distance UX.
- Route progress arrival/progress display.
- Automatic reroute safety gates for GPS noise and duplicate/loop prevention.

### Risk / depth / ROI / tracking

- `distance_m` source/confidence contract and parser validation.
- Browser depth capability gate with disabled/unsupported fallback.
- ROI debug gate, motion shift, lower-half/center-band config, non-blocking class exclusion.
- Tracking key, stale reset, TTC boundary, jitter/constant/decreasing negative fixtures.

### DeviceMotion / settings / PWA

- DeviceMotion permission/calibration dry-run state.
- Step length confidence/outlier/min-sample/TTL/reset.
- Multiple guardian contacts, local-only validation/masking/no-send checks.
- PWA install/update status, cached root shell fallback, manifest PNG icons.
- Offline report queue helper requiring opt-in with TTL/capacity/manual retry.

### Reports / admin / privacy

- v2 payload allowlist/size limit and trace/image/payload hashes.
- CSV/JSON/GeoJSON export deep fields, redacted/public mode, manifest, grid aggregate.
- Summary cluster bounds/status/source breakdown.
- Fake/demo/performance-excluded flags and Admin badges.
- Review note/resolution reason/status history/optimistic conflict.
- EXIF re-encode fixture and retention dry-run script; destructive delete blocked.

### Voice / TTS / NLU

- Reroute/stop/safe no-op intents and intent schema.
- TTS phrase catalog, cache-status dry-run, cache header unit, fallback=false, cache key inputs, max length.
- Local voice contract checks for metadata endpoints and CORS/OPTIONS.
- Privacy-minimized intent telemetry schema without raw audio/transcript text.

## Verification

- `PYTHONPATH=. .venv/bin/python -m pytest backend/tests -q -rs` → 88 passed
- `PYTHONPATH=. .venv/bin/python -m pytest tests model -q` → 85 passed
- Frontend policy scripts all PASS, including risk, motion ROI, navigation, route progress, step length, admin, settings privacy, PWA/offline queue.
- `python scripts/check_frontend_accessibility_static.py` → PASS
- `node --check apps/web/public/sw.js` → PASS
- `cd apps/web && npm run typecheck && npm run lint && npm run build` → PASS
- `scripts/check_voice_contract.py` against local uvicorn → PASS
- `scripts/check_tmap_pedestrian_route_smoke_20260524.py` → DRY-RUN PASS, live request not sent
- `scripts/check_detect_report_export_trace_20260524.py` dry-run and local ASGI trace → PASS
- `scripts/check_report_retention_dry_run.py` fixture → PASS; `--execute-delete` exits 2 as deletion guard
- `git diff --check` → PASS

## Safety / not included

- No commit, push, deploy, external agency submission, live TMAP quota-consuming request, destructive retention/delete, actual phone/SMS, or actual LLM/Cloud NLU was executed.
- Device/field validation remains separate: Android camera/GPS/DeviceMotion/TTS/vibration/mic/TalkBack/PWA install/offline.
- Operational DB/release evidence remains separate from local/integration PASS.

## Commit prep

Use `docs/execution/2026-05-26_commit_prep_manifest.md` for recommended commit groups and exclusion patterns. Do not use `git add .` in this dirty working tree.
