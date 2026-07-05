# Reviewed YOLO26s final candidate selection (2026-05-23)

## Scope

- Dataset: `datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522`
- Candidate family: reviewed YOLO26s tactile 3-class
- This document uses already-created `results.csv`, test-validation log output, and filesystem metadata only.
- No new train/val/predict/inference was run for this selection pass.

## Completion status

The reviewed YOLO26s pipeline completed at `2026-05-23 19:28:17 KST`.

Both stages stopped by early stopping:

| stage | run | planned epochs | completed rows | best epoch | best val mAP50-95 |
|---|---|---:|---:|---:|---:|
| Stage1 | `walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522` | 200 | 112 | 62 | 0.73982 |
| Stage2 | `walksafe_tactile3_reviewed_yolo26s_img1280_ft80_nomosaic_20260522` | 80 | 39 | 14 | 0.72586 |

## Test validation snapshot

The pipeline produced test-validation output for both stages.

| stage | all precision | all recall | all mAP50 | all mAP50-95 | `damaged_tactile_block` precision | `damaged_tactile_block` recall | `damaged_tactile_block` mAP50-95 | `tactile_damage_area` precision | `tactile_damage_area` recall | `tactile_damage_area` mAP50-95 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Stage1 best | 0.802 | 0.797 | 0.824 | 0.730 | 0.905 | 0.937 | 0.934 | 0.607 | 0.502 | 0.298 |
| Stage2 best | 0.792 | 0.799 | 0.816 | 0.720 | 0.905 | 0.932 | 0.929 | 0.588 | 0.504 | 0.288 |

## Decision

Use **Stage1 `best.pt`** as the current MVP/backend integration candidate.

Selected checkpoint:

```text
runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt
```

Reason:

- Stage1 has higher best validation mAP50-95: `0.73982` vs Stage2 `0.72586`.
- Stage1 has higher test all mAP50-95: `0.730` vs Stage2 `0.720`.
- Stage1 has slightly better `damaged_tactile_block` recall/mAP50-95 and better `tactile_damage_area` test precision/mAP50-95.
- Stage2 high-res fine-tune did not improve enough to justify switching the MVP candidate.
- Current report policy uses `damaged_tactile_block` as the automatic/voice report target. `tactile_damage_area` is kept as auxiliary bbox information only.

## Runtime config prepared

Prepared a conservative first-pass runtime config for `/detect/v2` wiring:

```text
configs/walksafe_two_model_runtime_stage1_mvp_20260523.json
```

Initial thresholds are first-pass MVP wiring values, not final field-calibrated operating thresholds:

| model_key | class | threshold |
|---|---|---:|
| `custom_tactile` | `normal_tactile_block` | 0.55 |
| `custom_tactile` | `damaged_tactile_block` | 0.50 |
| `custom_tactile` | `tactile_damage_area` | 0.80 |
| `coco_general` | default | 0.40 |

Rationale:

- Auto reporting is currently for `damaged_tactile_block`, and the chosen first operating threshold is `0.50` to favor image-level F1/recall during admin-reviewed collection.
- If admin review shows too many false positives, raise `damaged_tactile_block` to `0.60` first, then `0.75` if needed.
- `tactile_damage_area` is not a report target, but the threshold stays high because it is only auxiliary display/debug information.
- General COCO objects are warning/risk inputs only and remain blocked from `/reports/v2`.

## Local `/detect/v2` candidate environment

Use the selected Stage1 checkpoint with the COCO helper and runtime config:

```bash
export DETECT_V2_MODE=yolo
export DETECT_V2_CUSTOM_TACTILE_MODEL_PATH=/home/ddobagi/Code/hanium-dreamup/runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt
export DETECT_V2_COCO_MODEL_PATH=/home/ddobagi/Code/hanium-dreamup/yolo26n.pt
export DETECT_V2_RUNTIME_CONFIG_PATH=/home/ddobagi/Code/hanium-dreamup/configs/walksafe_two_model_runtime_stage1_mvp_20260523.json
```

Health-only smoke:

```bash
bash scripts/check_detect_v2_stage1_candidate_health_20260523.sh
```

This smoke checks env/path readiness and `/detect/v2/health` only. It does not load YOLO weights and does not run inference.

## Image-level report-target evaluation

After the initial candidate selection, a GPU prediction pass generated saved YOLO txt predictions for the reviewed test split and evaluated the service-level question:

> Does this image contain at least one `damaged_tactile_block`?

Prediction labels:

```text
runs/predict/stage1_yolo26s_reviewed_test_labels_20260523_203422/labels
```

Evaluation report:

```text
runs/reports/stage1_yolo26s_reviewed_test_presence_20260523_203422/
```

Summary:

| threshold | total | TP | FP | FN | TN | precision | recall | f1 | accuracy |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.50 | 2347 | 1072 | 62 | 119 | 1094 | 0.945326 | 0.900084 | 0.922151 | 0.922880 |
| 0.60 | 2347 | 1054 | 50 | 137 | 1106 | 0.954710 | 0.884971 | 0.918519 | 0.920324 |
| 0.70 | 2347 | 1031 | 42 | 160 | 1114 | 0.960857 | 0.865659 | 0.910777 | 0.913933 |
| 0.75 | 2347 | 1014 | 40 | 177 | 1116 | 0.962049 | 0.851385 | 0.903341 | 0.907542 |
| 0.80 | 2347 | 983 | 37 | 208 | 1119 | 0.963725 | 0.825357 | 0.889190 | 0.895611 |
| 0.85 | 2347 | 960 | 34 | 231 | 1122 | 0.965795 | 0.806045 | 0.878719 | 0.887090 |

Interpretation:

- Threshold `0.50` currently gives the best F1 in this sweep and is the selected initial admin-reviewed auto-report threshold.
- Threshold `0.75` is more conservative for false-positive control, but recall drops from `0.900084` to `0.851385`; keep it as an escalation option if false positives are too costly.
- This is image-level presence evaluation, not bbox mAP and not field/demo accuracy.

## Real `/detect/v2` ASGI image smoke

After fixing the adapter iteration over Ultralytics `Boxes`, a one-image real-mode ASGI smoke succeeded with the selected Stage1 checkpoint.

Observed response:

```text
schema_version=detect.v2
detections=2
custom_tactile damaged_tactile_block confidence=0.9738378524780273 threshold_used=0.50
custom_tactile tactile_damage_area confidence=0.9509809017181396 threshold_used=0.80
```

Notes:

- The smoke loaded both local YOLO models and ran actual inference.
- It is a plumbing smoke only; it does not replace field latency/accuracy validation.

## Remaining before real field/demo use

- Start with `damaged_tactile_block` threshold `0.50`; raise to `0.60`/`0.75` only if admin review shows too many false positives.
- Sample false positives/false negatives around the chosen threshold.
- Confirm real phone latency for `server-v2` mode.
- Keep public-agency submission behind internal review/export until false-positive behavior is acceptable.
