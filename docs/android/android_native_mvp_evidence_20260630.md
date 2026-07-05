# Android Native MVP Evidence - 2026-07-01

## Summary

- Date: 2026-07-01 Asia/Seoul
- Scope: WalkSafe Assist Android native primary path MVP wiring
- APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`
- APK sha256: `1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9`
- APK size: 69M
- Device model: `SM-G981N`
- Android version: `13`
- ARCore Depth support: NOT VERIFIED - stationary launch smoke only
- Test place: stationary install/launch smoke only
- Device log path: N/A - smoke used adb command output and AndroidRuntime:E filter only

## Implemented Evidence

- Purpose-less hazard mode remains the default app path: route is requested only when destination latitude/longitude is entered.
- Startup flow now keeps TFLite loading after permission/ARCore/Depth checks and before AR session resume. TTS is also lazy and remains off until the Device Gate allows speech.
- Device Gate state now separates general actuator readiness from risk alert readiness. General TTS requires camera permission, ARCore support, Depth support, TFLite config/detector load, running AR session, and no stale detection. Risk TTS/vibration additionally requires a fresh metric depth object.
- MessagePolicy output is connected to Android TextToSpeech and VibrationEffect only after the risk alert gate passes.
- Risk feedback has per-track 2.5s and global 700ms rate limits, stable 3-frame/700ms gating, stale/untrusted depth suppression, TTC-assisted approach warning, route-bearing alignment as motion context quality, and missing/safe reset.
- FusedLocationProviderClient is wired for trusted GPS fixes; null/poor accuracy and jumpy fixes are excluded from route/reroute/arrival decisions.
- Step tracking prefers TYPE_STEP_COUNTER and falls back to accelerometer. Step length defaults to 0.65m and accepts only bounded calibration samples.
- Android route client calls only backend `/navigation/destinations/search` and `/navigation/walking` proxies with default `STAIR_AVOID`; no TMAP key is stored in Android.
- Route navigator implements fallback instruction text, accuracy-overlap off-route candidate gating, consecutive off-route sample confirmation, cooldown, in-flight guard, max reroute count, and arrival radius.
- Android prepares report candidates only for Device Gate-passed damaged tactile block detections with trusted GPS and `custom_tactile`/`unified_walksafe` model metadata, then uploads metadata + JPEG to `/reports/v2` as multipart. Device and PostGIS no-skip validation are still blocked in this evidence.
- `/reports/v2` keeps damaged tactile block + GPS + `custom_tactile`/`unified_walksafe` policy and now accepts allowlisted `source=android` metadata.

## Verification

- `cd apps/android && ./gradlew test assembleDebug --no-daemon`: PASS
- `python3 scripts/check_android_tflite_contract_20260531.py`: PASS with warnings
  - unified primary asset missing, legacy custom/COCO fallback assets present
  - Android/backend threshold differences are intentional and must not be mixed in reports
- `python3 scripts/check_android_depth_scaffold_20260531.py`: PASS
- `PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -p no:cacheprovider backend/tests/test_report_policy.py backend/tests/test_android_debug_logs.py backend/tests/test_reports_v2.py backend/tests/test_navigation_routes.py -q`: PASS/SKIP, 24 passed and 32 skipped because PostGIS test database was not reachable
- `cd apps/web && npm run typecheck`: PASS
- `cd apps/web && npm run lint`: PASS
- `bash scripts/check_frontend_admin_report_summary_policy_20260525.sh`: PASS
- `bash scripts/check_frontend_walksafe_test_log_policy_20260701.sh`: PASS
- `PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -m pytest -p no:cacheprovider tests/test_aihub189_depthprediction_offline.py -q`: PASS, 5 passed and 8 warnings
- `scripts/evaluate_aihub189_depthprediction_offline.py --full-original-zip-run`: PASS as offline ZED reference, `Depth_001~005.zip`, frames/evaluated 2461, buckets stop 883 / warning 652 / ignore 926, `arcore_pass=false`
- `adb devices`: `R3CN50F4APH device`
- `adb -s R3CN50F4APH install -r apps/android/app/build/outputs/apk/debug/app-debug.apk`: Success
- `adb -s R3CN50F4APH shell am start -W -n kr.co.hanium.dreamup.walksafe/.MainActivity`: Status ok, LaunchState COLD, TotalTime 480, WaitTime 484
- `adb -s R3CN50F4APH shell pidof kr.co.hanium.dreamup.walksafe`: 16571
- `adb -s R3CN50F4APH shell dumpsys activity activities`: `MainActivity` RESUMED/visible/reportedDrawn
- `adb -s R3CN50F4APH logcat -d AndroidRuntime:E *:S`: no output

## Device Scenarios

- APK install: PASS - stationary smoke only
- App launch/crash-free: PASS - stationary smoke only
- Permission request flow: DEFERRED_DEVICE - not exercised
- ARCore/Depth support check: DEFERRED_DEVICE - not exercised
- Camera start and overlay/depth/stale gate: DEFERRED_DEVICE - not exercised
- TTS/vibration after Device Gate PASS: DEFERRED_DEVICE - not exercised
- Short outdoor route GPS accuracy, N-step guidance, route start, off-route, reroute, arrival: OUT_OF_SCOPE - excluded from this pass

## Notes

- No raw image, raw depth, raw audio, GPS route, API key, token, or secret was recorded in this evidence.
- Debug metadata upload remains explicit/local-dev only. The Android route client uses backend proxy HTTP(S) and does not include TMAP appKey.
