# Detect Adapter Implementation

Date: 2026-05-14

## Scope

Implemented the first backend `/detect` adapter for local Ultralytics YOLO `.pt` artifacts.

Changed files:

- `backend/app/detector.py`
- `backend/tests/test_detect.py`
- `backend/requirements.txt`
- `docs/execution/2026-05-14_detect_adapter_implementation.md`

No model weights, generated images, `runs/` files, dataset files, PWA code, voice code, or model scripts were modified.

## Adapter Behavior

- `MODEL_ARTIFACT_PATH` empty, missing, or not a file remains `model_status="unavailable"` with `reason="model_not_configured"`.
- Existing non-`.pt` artifacts return `reason="model_artifact_unsupported"`.
- Existing `.pt` artifacts lazy-load through `ultralytics.YOLO`.
- If Ultralytics or Pillow is not importable, health remains unavailable with `reason="model_dependency_missing"`.
- Health reports `model_status="ready"` only after the artifact exists, loads successfully, has detect task metadata when present, and class mapping is compatible with the backend class contract.
- Loaded adapters are cached by artifact path, file mtime, file size, and configured class order.
- `/detect` runs inference from uploaded image bytes and returns `DetectResponse` detections with normalized bbox fields and `source="server"`.
- Inference uses `MODEL_CONFIDENCE_THRESHOLD`, `MODEL_IOU_THRESHOLD`, and `MODEL_IMAGE_SIZE`.

## Class Mapping

The full backend class order remains:

| id | class_name |
| ---: | --- |
| 0 | `damaged_tactile_block` |
| 1 | `parked_kickboard_bicycle` |
| 2 | `construction_obstacle` |
| 3 | `pothole` |

Supported mappings:

- Exact model names matching the backend four-class order map by identity.
- A single-class model named `damaged_tactile_block` maps only model class `0` to backend class `0`.
- A generic single-class model can map to class `0` only if a local training `data.yaml` found beside the run, or through the run `args.yaml`, proves the single name is `damaged_tactile_block`.

The adapter does not synthesize detections for missing classes. For single-class mappings, outputs for unmapped class ids are skipped.

Local v2 note: `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt` exposes the four backend class names in model metadata, but the current training status documents the practical data coverage as `damaged_tactile_block` centered. The adapter therefore returns only what the model actually emits; it does not generate kickboard, construction, or pothole detections on behalf of the current tactile model.

## Verification

Commands run:

```bash
.venv/bin/python -m py_compile backend/app/config.py backend/app/detector.py backend/app/schemas.py
.venv/bin/python -m pytest backend/tests/test_detect.py -q
.venv/bin/python -m pytest backend/tests -q
```

Results:

- `py_compile`: passed
- `backend/tests/test_detect.py`: `11 passed`
- `backend/tests`: `11 passed, 11 skipped`

Manual local smoke:

- artifact: `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt`
- input: in-memory 32x32 blank PNG, not written to disk
- health: `ready`
- detections: `0`

## Limitations

- The adapter is synchronous model inference wrapped in a threadpool from the async route.
- Runtime image decode failures currently surface through the existing `/detect` 503 path because the route already maps detector runtime failures to `model_unavailable`.
- `MODEL_VERSION` is still optional for health. If a ready `/detect` response is produced without `MODEL_VERSION`, the response uses the artifact stem as the response version label to satisfy the existing `DetectResponse` contract.
