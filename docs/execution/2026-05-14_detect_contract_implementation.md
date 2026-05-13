# Detect Pre-Adapter Contract Implementation

Date: 2026-05-14

## Scope

This change freezes the backend `/detect` pre-adapter configuration contract only. It does not connect a YOLO/ONNX model and does not change datasets, training runs, PWA code, or voice code.

## Environment Contract

The backend reads these model settings from `backend/.env` or the process environment:

```env
MODEL_ARTIFACT_PATH=
MODEL_VERSION=
MODEL_CLASS_ORDER=damaged_tactile_block,parked_kickboard_bicycle,construction_obstacle,pothole
MODEL_CONFIDENCE_THRESHOLD=0.35
MODEL_IOU_THRESHOLD=0.7
MODEL_IMAGE_SIZE=640
```

`MODEL_ARTIFACT_PATH` may point to a local `.pt` or future adapter-supported artifact, but a readable file does not make `/detect` ready until the adapter is implemented. `MODEL_VERSION` is surfaced when configured but does not imply readiness.

## Class Order

The frozen class order is the canonical backend `CLASS_NAMES` order:

| class_id | class_name |
| ---: | --- |
| 0 | `damaged_tactile_block` |
| 1 | `parked_kickboard_bicycle` |
| 2 | `construction_obstacle` |
| 3 | `pothole` |

`MODEL_CLASS_ORDER` must match this order exactly. The adapter should fail load rather than remap a model with a different order.

## Defaults

| Field | Default |
| --- | ---: |
| `MODEL_CONFIDENCE_THRESHOLD` | `0.35` |
| `MODEL_IOU_THRESHOLD` | `0.7` |
| `MODEL_IMAGE_SIZE` | `640` |

These values are now included in `/detect/health` together with `model_artifact_path` and `model_class_order`.

## Current Health Behavior

`/detect/health` remains conservative:

- no readable `MODEL_ARTIFACT_PATH`: `model_status="unavailable"`, `reason="model_not_configured"`
- readable `MODEL_ARTIFACT_PATH`: `model_status="unavailable"`, `reason="model_adapter_not_implemented"`

`POST /detect` still returns `503 model_unavailable` until the adapter exists.

## Next Adapter Step

Implement the model adapter behind `backend/app/detector.py`, validate that the artifact class order matches `CLASS_NAMES`, apply the configured confidence/IOU/image-size settings, and only then change health to `model_status="ready"` after a successful load.
