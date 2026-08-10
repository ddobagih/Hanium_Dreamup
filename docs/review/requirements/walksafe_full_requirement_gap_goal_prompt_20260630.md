# WalkSafe full requirement gap audit and goal prompt - 2026-06-30

> Status: SNAPSHOT / PLATFORM SUPERSEDED. 이 감사와 goal prompt는 Android-primary 가정을 사용했다. 2026-07-10 이후 현재 기준은 Web/PWA 주 앱이며 `docs/status/implementation_audit_20260710.md`를 우선한다.

## Scope

- Source documents: `docs/review/requirements/walksafe_implementation_plan_20260630.html`, `docs/status/current_status.md`, `product/done-criteria.md`, `product/backlog.md`, `product/decisions.md`, Android docs, `docs/walksafe-v2/*`, `docs/evidence/*`.
- Source code: `apps/android`, `backend`, `apps/web`, `model`, `configs`, `scripts`.
- Method: 10 parallel read-only agents plus local audit. No implementation was changed during this audit.
- Important assumption: physical Android device validation is deferred. Do not convert deferred device items into PASS; keep them as `DEFERRED` or `BLOCKED_BY_DEVICE`.

## Verdict

The required features are not fully implemented.

The repository has meaningful Android native scaffolding and several static/unit/build checks, but the product path still has gaps in permission flow, detector asset contract, feedback priority, risk-policy wiring, Android navigation UX, step calibration, Android report upload, backend/admin Android source compatibility, Web/PWA isolation, privacy gates, and evidence consistency.

The accurate status is:

- Android ARCore/depth/TFLite/overlay/debug metadata: partially implemented, device same-object validation deferred.
- Android TTS/vibration: connected, but priority, TalkBack/audio focus, and actuator tests are incomplete.
- Android location/navigation/steps: scaffolded, but UX and runtime calibration are incomplete.
- Android report: candidate creation only; `/reports/v2` upload is not connected.
- Backend route/report: core APIs exist, but PostGIS no-skip evidence, Android metadata, privacy/security, and admin compatibility remain incomplete.
- Web/PWA: still exposed as the root user path and can look like the main app, contrary to Android-native primary-path policy.
- Unified model/TFLite: Android config points to unified primary, but the unified TFLite asset is missing and 640/768 runtime drift remains unresolved.
- Evidence/docs: APK hashes and status statements are inconsistent across README, evidence docs, HTML plan, and daylog.

## Key Gaps To Fix

### P0 Android Startup And Device Gate

- Hazard-only mode currently requests camera, location, and activity-recognition permissions together. It should start with camera-only; location/activity permissions should be requested only when route, report GPS, or step features are enabled.
- Main manifest now includes `INTERNET`, location, activity recognition, and vibration permissions. Docs still say release networking is absent/debug-only in places. Resolve the policy and update docs/tests.
- Device Gate exists, but the gate meanings need to be enforced consistently:
  - startup readiness
  - risk alert readiness
  - navigation speech readiness
  - report upload readiness
  - debug/local-dev readiness
- Debug JPEG frame capture conflicts with strict metadata-only P0 language. Either hide it behind an explicit debug/dev flag and doc it as local-dev only, or remove it from the default surface.

### P0 Detector And Model Contract

- `two_model_runtime.json` uses `unified_walksafe` primary, but `walksafe_unified_yolo26n_640_float32.tflite` is missing. Only legacy custom tactile and COCO TFLite assets are present.
- The project has 768-oriented model training scripts, while Android config still references 640. Decide one runtime contract before updating config.
- Android report metadata currently synthesizes `source_model = "android/<modelKey>"` instead of reading an explicit config/source model field.
- Android report `threshold_used` is a constant and can drift from `two_model_runtime.json`.
- Existing TFLite checks are static. Add tensor signature validation for configured assets: input shape, dtype, output `[1,300,6]`, and NMS contract.

### P1 Feedback And Risk

- Risk feedback is emitted from a single `bestOutput` selected by `confidence.finalScore`. A high-confidence INFO/path object can suppress a lower-confidence STOP/WARNING object. Select feedback candidates by safety severity first, then freshness/confidence.
- `TrackingRiskPolicy`, TTC, and ID-switch suspicion have tests but are not clearly wired into the production depth-to-feedback path. Either connect them with integration tests or mark them as non-runtime scaffolding.
- ROI-only alert prevention is not covered by a direct regression test.
- Safe/stale release behavior needs tests for re-alert after safe 2 frames or missing/stale 1.5s.
- Add or isolate TalkBack/audio focus behavior: `AudioFocusRequest`, accessibility/TalkBack detection policy, focus labels, and no duplicate/conflicting speech.

### P2 Location, Steps, Navigation

- `StepLengthEstimator.calibrate()` exists but is not wired to trusted GPS movement plus step deltas. Runtime `N보` guidance still uses default policy.
- On Android Q+, activity recognition permission currently blocks both step-counter and accelerometer fallback. Review fallback policy.
- `TrustedLocation` has no heading/bearing. Route bearing and heading are not used as an auxiliary signal.
- Off-route detection can reroute from a single trusted sample. Add consecutive-sample or duration gate and accuracy-adjusted thresholds.
- Android route UX is still raw latitude/longitude input. Backend POI search exists; Android needs a practical search/select/start/cancel flow or a clearly deferred status.
- Default Android backend URL is `127.0.0.1:8000`, which is not usable on a physical device without manual override or `adb reverse`. Add a safer dev setting/runbook or UI.

### P3 Report Upload And Privacy

- Android only prepares report candidates. It does not upload to `/reports/v2`.
- Android `captured_at` candidate metadata uses a numeric frame timestamp, but backend expects datetime-compatible metadata.
- Android candidate tests need more cases: `unified_walksafe`, `tactile_damage_area`, missing `sourceModel`, missing `threshold`, gate false/true, and GPS trust boundaries.
- If report upload is in scope, implement it with:
  - Device Gate PASS only
  - `damaged_tactile_block` only
  - `custom_tactile` or `unified_walksafe` only
  - trusted GPS only
  - no COCO/general/normal tactile/tactile damage area reports
  - cooldown/duplicate/failure status
  - explicit raw-image privacy policy
- Backend allows sanitized image and precise GPS storage. If the requirement means "no raw original" this is partly aligned; if it means "no image/GPS storage by default" it is not. The next goal must decide and document the policy.

### P4 Backend, Admin, Web/PWA

- Backend `/reports/v2` and navigation proxy exist, but `test_reports_v2.py` is currently skipped when PostGIS is unavailable. Runtime completion needs disposable PostGIS no-skip evidence.
- Backend `BBox` validates individual values but not `x + width <= 1` or `y + height <= 1`.
- `/detect/v2/health` exposes local model paths; redact or protect before production.
- Admin/Web report types and source counts are still centered on `fake | onnx | server`; add `android`.
- PWA WalkSafe is still the root `/` app and `manifest.webmanifest` starts at `/`. If Android native is the primary app, move or gate the PWA as demo/admin/support.
- PWA fake/default mode, browser TTS/vibration, and auto-report can look like a production path. Add production/demo separation and ensure PWA PASS is never counted as Android Device PASS.
- `TestCapturePanel` can save raw image plus GPS metadata through a Next API route. Hide it behind dev/auth/explicit-consent controls or remove it from production.
- Admin/report/export lacks auth/rate limit/RBAC/actor audit. Treat as release/security gate unless explicitly out of scope.

### P5 Evidence And Documentation

- APK hash differs across docs: older `305f...` vs latest `8815...`.
- HTML plan says Android TTS/GPS/report/navigation are missing, while later code/daylog adds partial implementations. Update status to "partial/scaffolded" rather than "missing" or "done".
- README still says not to add TTS/haptic/report before bbox-depth gate, but code now has TTS/haptic and report candidates behind gates. Reconcile the intended policy.
- Keep device evidence deferred, not failed, if the current goal excludes physical device validation.
- Update evidence index/current status/done criteria with verification grade: Static, Unit, Integration, Headless, Android Device, Model, GIS/Ops, Release.

## Goal Prompt

Use this as the next implementation goal prompt.

```text
/home/ddobagi/Code/hanium-dreamup 프로젝트에서 WalkSafe Assist Android-native primary path의 요구사항 구현 갭을 닫아줘.

중요 전제:
- 주 사용자 앱은 Android native ARCore/TFLite APK다.
- Web/PWA는 demo/admin/API 검증 보조 경로이며 Android Device PASS를 대체하지 않는다.
- 이번 goal에서 실제 Android 실기기 검증은 실행하지 않는다. 실기기 항목은 DEFERRED 또는 BLOCKED_BY_DEVICE로 기록하고 PASS라고 쓰지 않는다.
- 모델 학습 자체는 제외한다. 단, TFLite/runtime/config 계약은 최종 모델이 들어왔을 때 바로 연결되도록 정리한다.
- 기존 사용자 변경을 되돌리지 않는다.
- raw image/depth/audio/GPS 원본 저장/외부전송은 명시된 debug/local-dev 또는 신고 정책 범위 밖에서 하지 않는다.
- 공공기관 자동 제출 connector, release 배포, cloud storage, auth 인프라 대형 작업은 별도 승인 전 구현하지 않는다.

참고 기준:
- docs/review/requirements/walksafe_implementation_plan_20260630.html
- docs/review/requirements/walksafe_full_requirement_gap_goal_prompt_20260630.md
- product/done-criteria.md
- docs/android/android_native_mvp_evidence_20260630.md
- docs/walksafe-v2/*
- apps/android, backend, apps/web, model, configs, scripts 전체

P0 - Android startup/gate 정리:
1. Hazard-only ARCore/depth/detector 시작은 CAMERA 권한만으로 가능하게 분리한다.
2. Location/activity recognition 권한은 route/report GPS/step 기능을 켤 때만 요청한다.
3. Device Gate를 startup, alert, navigation speech, report upload, debug/local-dev gate로 명확히 분리하고 테스트한다.
4. main manifest 권한 정책과 README/docs 설명을 일치시킨다.
5. debug frame capture는 production/default surface에서 제거하거나 explicit debug/dev flag 뒤로 숨기고, metadata-only P0 원칙과 충돌하지 않게 문서화한다.

P1 - detector/model/runtime 계약:
1. unified TFLite asset 부재를 완료로 처리하지 않는다. 640/768 runtime 결정을 문서화하고 config를 실제 asset 상태와 일치시킨다.
2. Android runtime config에 source_model 또는 동등한 traceable model metadata를 추가하고, report metadata가 합성 문자열 대신 config 값을 쓰게 한다.
3. report threshold_used는 상수 대신 Android runtime config의 해당 class threshold에서 가져오게 한다.
4. configured TFLite asset의 input/output tensor signature를 검사하는 script/test를 추가한다. unified asset이 없으면 명확한 warning/fail policy를 둔다.

P2 - feedback/risk:
1. Android feedback 후보 선택을 confidence max 하나가 아니라 STOP/WARNING/CAUTION/INFO 안전 우선순위 기반으로 바꾼다.
2. TrackingRiskPolicy/TTC/ID-switch 정책을 production path에 연결하거나, 미사용 scaffold라면 제품 판단에서 제외되도록 분리하고 문서화한다.
3. ROI-only alert 금지, TTC null 단독 경고 금지, stale/untrusted 미발화, safe 2 frames 또는 missing/stale 1.5s 해제 회귀 테스트를 추가한다.
4. TTS/VibrationEffect는 Device Gate 이후만 동작하고 위험 안내가 길안내보다 우선하도록 검증한다.
5. TalkBack/audio focus/accessibility label/focus order의 최소 구현과 테스트 가능한 정책을 추가한다.

P3 - location/navigation/steps:
1. trusted GPS movement와 step delta로 StepLengthEstimator calibration을 실제 runtime에 연결하고, MessagePolicy/N보 안내에 주입한다.
2. TYPE_STEP_COUNTER 우선, accelerometer fallback, ACTIVITY_RECOGNITION 권한 정책을 테스트 가능하게 정리한다.
3. heading/GPS bearing 또는 route segment bearing을 보조 신호로 추가하되, 단독 STOP/TTS trigger로 쓰지 않는다.
4. off-route/reroute는 accuracy gate와 연속 sample/duration gate를 거친 뒤 수행한다.
5. Android route UX를 raw lat/lng 입력에서 최소 목적지 검색/선택/시작/취소 흐름으로 개선하거나, 이번 scope에서 명확히 deferred로 문서화한다.
6. Android에는 TMAP appKey를 절대 넣지 않고 backend /navigation/walking proxy만 호출한다. 목적지 없으면 route 호출 0회를 테스트한다.

P4 - Android report/backend/admin:
1. Android /reports/v2 upload client를 구현할지 여부를 이 goal에서 결정한다. 구현한다면 Device Gate PASS 후 damaged_tactile_block + trusted GPS + custom_tactile/unified_walksafe만 업로드한다.
2. Android report metadata의 captured_at을 ISO-8601 UTC로 맞추고, source=android, model_key, source_model, threshold, trigger, distance/depth quality, trace_id allowlist를 backend와 일치시킨다.
3. COCO/general/normal_tactile_block/tactile_damage_area는 Android에서 candidate/upload가 생성되지 않게 테스트한다.
4. Backend BBox validator에 x+width/y+height bounds를 추가하고 Android fixture로 /reports/v2 계약 테스트를 보강한다.
5. Admin/Web report type/source summary/filter/export에 android source를 추가한다.
6. PostGIS가 없으면 /reports_v2 runtime 완료라고 쓰지 말고 BLOCKED로 기록한다. 가능하면 disposable PostGIS로 skipped=0 테스트를 실행한다.

P5 - Web/PWA/demo/privacy 정리:
1. PWA WalkSafe가 production root app처럼 보이지 않게 demo/support/admin 경로 또는 env gate로 분리한다.
2. PWA fake/server/browser TTS/vibration/report 결과를 Android Device PASS로 쓰지 않도록 문서와 테스트 이름을 분리한다.
3. TestCapturePanel/raw image/GPS 저장 API는 dev/auth/explicit-consent 뒤로 숨기거나 production에서 제거한다.
4. /detect/v2/health의 local model path 노출을 redaction 또는 admin-only로 줄인다.
5. debug endpoint는 기본 off를 유지하고, frame capture/raw media 저장은 local-dev 명시 조건과 테스트를 갖춘다.

P6 - evidence/docs/verification:
1. README, apps/android/README.md, docs/status/current_status.md, docs/evidence/*, docs/android/android_native_mvp_evidence_20260630.md, HTML 계획서 상태를 최신 코드 기준으로 일치시킨다.
2. 최신 APK를 빌드했다면 APK sha256을 한 곳의 source of truth로 갱신하고 다른 문서와 맞춘다.
3. 모든 완료 판단은 verification grade를 붙인다: Static, Unit, Integration, Headless, Android Device, Model, GIS/Ops, Release.
4. 실기기 검증은 이번 scope에서 DEFERRED로 남긴다. build/static/headless/PWA 결과를 Android Device PASS로 표현하지 않는다.
5. 작업 후 daylog와 local-memory log-work를 남긴다.

필수 검증:
- python3 scripts/check_android_depth_scaffold_20260531.py
- python3 scripts/check_android_tflite_contract_20260531.py
- cd apps/android && ./gradlew test --no-daemon
- cd apps/android && ./gradlew assembleDebug --no-daemon
- PYTHONPATH=. .venv/bin/python -m pytest backend/tests/test_report_policy.py backend/tests/test_navigation_routes.py -q
- 가능하면 PostGIS 준비 후 backend/tests/test_reports_v2.py skipped=0 확인
- apps/web의 관련 policy/type tests 또는 npm lint/typecheck/build 중 변경 범위에 맞는 검증
- git diff --check

완료 금지:
- unified TFLite asset이 없는데 unified detector 완료라고 쓰지 말 것.
- Android 실기기 없이 bbox-depth same-object, N보 정확도, TTS/진동 체감, route field PASS라고 쓰지 말 것.
- PWA/fake/headless 결과를 Android native PASS로 쓰지 말 것.
- 신고 기능만 고치고 전체 요구사항을 완료했다고 쓰지 말 것.
```

## Short Next-Step Order

1. Fix permission/gate split and debug-frame-capture policy.
2. Fix detector/report metadata drift: source model, threshold from config, captured_at ISO.
3. Fix feedback priority and missing risk-policy regressions.
4. Wire step calibration and strengthen route/reroute gates.
5. Decide and implement Android report upload or explicitly defer it.
6. Add Android source support to Web/Admin types and isolate PWA demo path.
7. Reconcile docs/evidence and run scoped verification.
