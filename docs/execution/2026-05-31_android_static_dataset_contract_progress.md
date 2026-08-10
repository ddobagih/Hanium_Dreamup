# Android native static-dataset/contract progress (2026-05-31)

> **문서 상태(2026-06-02): 과거 실행 기록.** 이 문서의 APK hash/좌표계/모델 전제는 2026-05-31 snapshot이다. 현재 기준은 `docs/status/current_status.md`, `apps/android/README.md`, `docs/README.md`를 우선한다.


## Scope

- User direction: proceed with everything that does not require user-side action.
- Main product path: Android native ARCore APK, not PWA "forced steps" demo.
- This pass focused on verification scaffolding and debug observability before adding more product features.

## Team findings integrated

- Static RGB image datasets are appropriate for detector/class/threshold validation.
- Static RGB images are not sufficient for ARCore metric depth, `N보` guidance, bbox-depth alignment, or approach/TTC validation.
- Follow-up change: Android `apps/android/app/src/main/assets/model-config/two_model_runtime.json` is now the runtime source of truth for asset path, input size, custom classes, COCO classes, allowlist, and thresholds.
- The Android pipeline still assumes camera-image normalized bbox and ARCore depth normalized coordinates are aligned.
- The selected reviewed tactile dataset root is not present locally, so full static-image metric reruns are blocked until that dataset is restored.

## Changes made

### Android debug observability and bbox overlay

Updated:

```text
apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/DebugBboxOverlayView.kt
apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt
```

Added UI debug detail and a developer bbox overlay:

- latest detector result count
- detector result age
- detector frame timestamp
- top detection class/score
- top detection bbox center/width/height
- best depth output bbox center/width/height
- depth valid sample count/ratio
- depth median
- camera overlay bbox rectangle/center point/class/confidence label
- best depth output highlighted separately from raw detections

Purpose:

- Make "box is not drawn" no longer block basic coordinate verification.
- Let a device tester see whether detection exists, whether stale detections are being reused, and whether depth sampling has usable samples.
- Note: overlay currently uses simple normalized bbox-to-view mapping. If it is visibly shifted/rotated, that is evidence that ARCore display transform/camera image/depth mapping needs a stricter coordinate mapper.

### Android JSON runtime source of truth

Added/updated:

```text
apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/TwoModelRuntimeConfig.kt
apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/TfliteAndroidFrameDetector.kt
apps/android/app/src/main/assets/model-config/two_model_runtime.json
apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/inference/TwoModelRuntimeConfigTest.kt
scripts/export_android_tflite_models_20260531.py
```

Changes:

- `TfliteAndroidFrameDetector.createOrNull()` loads `model-config/two_model_runtime.json`.
- Detector asset path, input size, custom class order, COCO class order, allowlist, and thresholds now come from JSON.
- COCO 80-class order was added to JSON so the class-id map is not partially hidden in Kotlin runtime code.
- The TFLite export helper now reads output asset names and image sizes from the same JSON config.
- JVM test covers parser behavior against the asset JSON.

### Android TFLite contract check

Added:

```text
scripts/check_android_tflite_contract_20260531.py
```

Checks:

- Android model-config JSON exists.
- TFLite assets exist and are non-empty.
- Kotlin class maps match Android config.
- Detector runtime loads `TwoModelRuntimeConfig` instead of using stale asset/input/threshold constants.
- TFLite export script reads config-derived asset names/input sizes.
- Backend runtime threshold differences are reported as warnings by default.

Current result:

```text
ok=true
warning: Android TFLite thresholds differ from backend stage1 runtime config
```

The Android app is internally consistent, but Android thresholds and backend `/detect/v2` thresholds must not be mixed as the same evaluation condition.

### Static dataset readiness check

Added:

```text
scripts/check_static_dataset_readiness_20260531.py
```

Checks:

- selected reviewed tactile3 dataset presence
- local placeholder dataset counts
- saved Stage1 prediction labels
- saved image-level presence summary
- backend PT model artifacts
- Android TFLite artifacts

Current result:

```text
status=blocked_for_full_static_eval_dataset_missing
blocker=selected reviewed tactile3 dataset is not present with test images/labels
ready_now=saved Stage1 prediction labels, existing presence summary, backend PT models, Android TFLite assets
```

### Saved Stage1 FP/FN candidate extraction

Added:

```text
scripts/summarize_presence_error_candidates_20260531.py
```

This uses the already saved Stage1 per-image presence CSVs and prediction labels, without requiring the original dataset images/GT labels.

Output:

```text
reports/runs/evaluations/stage1_yolo26s_reviewed_test_presence_error_candidates_20260531/
```

Summary:

| threshold | total | FP | FN |
|---:|---:|---:|---:|
| 0.50 | 2347 | 62 | 119 |
| 0.60 | 2347 | 50 | 137 |
| 0.70 | 2347 | 42 | 160 |
| 0.75 | 2347 | 40 | 177 |
| 0.80 | 2347 | 37 | 208 |
| 0.85 | 2347 | 34 | 231 |

### Saved predictions.json + manifest image-level sweep

Added:

```text
scripts/evaluate_predictions_manifest_presence_20260531.py
```

This evaluates old v2 external-validation `predictions.json` against the holdout materialized manifest using `label_box_count > 0` as image-level GT positive. It does not compute bbox IoU/mAP and does not run fresh inference.

Output:

```text
reports/runs/evaluations/predictions_manifest_presence_20260531/
```

Summary:

| threshold | total | TP | FP | FN | TN | precision | recall | f1 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.30 | 2082 | 1482 | 48 | 96 | 456 | 0.968627 | 0.939163 | 0.953668 |
| 0.40 | 2082 | 1463 | 38 | 115 | 466 | 0.974684 | 0.927123 | 0.950309 |
| 0.50 | 2082 | 1439 | 31 | 139 | 473 | 0.978912 | 0.911914 | 0.944226 |
| 0.60 | 2082 | 1404 | 22 | 174 | 482 | 0.984572 | 0.889734 | 0.934754 |
| 0.70 | 2082 | 1377 | 18 | 201 | 486 | 0.987097 | 0.872624 | 0.926337 |

## Verification run

```bash
python scripts/check_android_tflite_contract_20260531.py
python scripts/check_static_dataset_readiness_20260531.py
python scripts/summarize_presence_error_candidates_20260531.py
python scripts/evaluate_predictions_manifest_presence_20260531.py
python scripts/export_android_tflite_models_20260531.py --dry-run
python data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_kr_v1/data.yaml
python data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_v1/data.yaml
python scripts/check_android_depth_scaffold_20260531.py
python -m py_compile scripts/check_android_tflite_contract_20260531.py scripts/export_android_tflite_models_20260531.py scripts/check_android_depth_scaffold_20260531.py
cd apps/android && ./gradlew test --no-daemon
cd apps/android && ./gradlew assembleDebug --no-daemon
```

Results:

- Android TFLite contract check: PASS with backend-threshold warning.
- Android TFLite export dry-run: PASS; uses JSON-derived imgsz/output asset names.
- Static dataset readiness check: completed; full metric rerun blocked because reviewed dataset is absent.
- Stage1 FP/FN candidate extraction: completed.
- Saved predictions.json + manifest sweep: completed.
- `walksafe_kr_v1` validator: PASS but dataset is empty.
- `walksafe_v1` validator: PASS but dataset is empty.
- Android depth scaffold static check: PASS.
- Android unit tests: BUILD SUCCESSFUL.
- Android debug APK assemble: BUILD SUCCESSFUL.

APK:

```text
apps/android/app/build/outputs/apk/debug/app-debug.apk
sha256=dbc75e61eb44c68b7162b5658938e55935aa2f2c52361945b0b0c6747e606897
```

Follow-up APK after JSON runtime/overlay changes:

```text
apps/android/app/build/outputs/apk/debug/app-debug.apk
sha256=2b49f488b332e52943e3e949b615aa00366742aba656ea561194cdd022aeb283
```

## Follow-up: next-step plan safe execution

Implemented safe, non-device tasks from `plans/features/2026-05-31_android_native_next_step_plan.md`:

- Removed obsolete push worktrees outside the main working tree:
  - `/home/ddobagi/Code/hanium-dreamup-push-20260525`
  - `/home/ddobagi/Code/hanium-dreamup-pushprep`
- Added `docs/android/arcore_coordinate_mapping_plan.md` for P1 overlay/depth mapper separation.
- Added `docs/android/android_backend_threshold_decision_20260531.md`; current decision is to keep Android/backend thresholds separate and not mix reports.
- Added Android `MetadataCaptureLog` ring buffer. It records frame/detection/depth metadata only and does not save RGB/depth files.
- Added stale detector guard: detections older than `MAX_DETECTION_AGE_MS=2500` are suppressed from depth pipeline input.
- Latest APK after metadata log/stale guard rebuild:

```text
apps/android/app/build/outputs/apk/debug/app-debug.apk
sha256=55ecfda2205a91d317c879e353119798299ace25bff7f6c7501381761e013b56
```

Still not claimed:

- overlay/preview/depth coordinate Device PASS
- measured ARCore RGB-D distance or `N보` accuracy
- report upload/TTS/haptic product connection

## Remaining work that does not require user-side measurement

1. Decide whether Android thresholds should intentionally remain separate from backend thresholds.
2. Design ARCore image/display/depth coordinate mapping and add unit/static tests around mapper boundaries.
3. Optional: add metadata-only capture-log mode for repeatable bbox/depth alignment review without saving camera/depth files.

## Work that does require user/device-side data

- Restore or provide the reviewed static image dataset to rerun full detector metrics.
- Capture ARCore RGB-D samples with measured distances to validate `N보` output.
- Field-check depth/bbox alignment under real camera angle, lighting, and movement.
- If overlay shows systematic offset/rotation on device, use that observation to implement and verify the stricter ARCore coordinate mapper.
