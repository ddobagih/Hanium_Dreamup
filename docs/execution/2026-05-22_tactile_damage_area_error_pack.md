# tactile_damage_area chunked error review pack

Date: 2026-05-22 KST

## Scope

- Model: `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/weights/best.pt`
- Dataset: `datasets/walksafe_kr_tactile_3class_20260521`
- Split: `val`
- Device: `0`
- Inference: `imgsz=960`, `conf=0.05`, `batch=1`, `chunk_size=128`
- Match rule: same-class `tactile_damage_area` IoU >= `0.5` is counted as matched.
- No training was run.
- This pack is for label/error review. It is not final metric reporting.

## Summary

| Item | Value |
| --- | ---: |
| completed chunks | 37 |
| images scanned | 4695 |
| GT `tactile_damage_area` boxes | 5703 |
| Pred `tactile_damage_area` boxes at conf floor | 12538 |
| TP boxes @ IoU50 | 3937 |
| FN boxes @ IoU50 | 1766 |
| FP boxes @ IoU50 | 8601 |
| image rows with any damage-area error | 2575 |
| image rows with near miss IoU 0.25~0.50 | 943 |
| review pool rows | 2575 |
| final review queue rows | 120 |

Computed at the low review confidence floor:

- Precision: `0.3140`
- Recall: `0.6903`

## Issue counts

```json
{
  "no_target_error": 2120,
  "hard_negative_false_positive_damage_area": 372,
  "false_positive_extra_damage_area": 1020,
  "mixed_fn_fp_damage_area": 995,
  "damage_block_ok_area_missed": 132,
  "false_negative_damage_area": 56
}
```

## Outputs

- Final review queue CSV: `data_sources/manifests/walksafe_tactile3_damage_area_error_review_queue_2026-05-22.csv`
- All-image summary CSV: `data_sources/manifests/walksafe_tactile3_damage_area_val_image_error_summary_2026-05-22.csv`
- Summary JSON: `data_sources/manifests/walksafe_tactile3_damage_area_error_review_summary_2026-05-22.json`
- Chunk outputs: `docs/review/walksafe_tactile3_damage_area_20260522/chunks`
- Chunk manifests: `data_sources/manifests/walksafe_tactile3_damage_area_error_chunks_2026-05-22`
- Final selected overlays: `docs/review/walksafe_tactile3_damage_area_20260522/final_overlays`
- Final selected crops: `docs/review/walksafe_tactile3_damage_area_20260522/final_crops`

Intermediate chunk visual files were pruned after copying the final 120 selected review images to reduce local disk usage. The per-chunk CSV/JSON summaries are kept.

### Final contact sheets

- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_01.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_02.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_03.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_04.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_05.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_06.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_07.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_08.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_09.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_10.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_11.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_12.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_13.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_14.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_contact_sheets/damage_area_final_review_sheet_15.jpg`

### Final crop contact sheets

- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_01.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_02.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_03.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_04.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_05.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_06.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_07.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_08.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_09.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_10.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_11.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_12.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_13.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_14.jpg`
- `docs/review/walksafe_tactile3_damage_area_20260522/final_crop_contact_sheets/damage_area_final_crop_sheet_15.jpg`

## Review decision options

Fill `review_decision` in the final review queue with one of:

- `accept_existing_labels`
- `fix_tactile_damage_area_bbox`
- `remove_false_damage_area_label`
- `add_missing_tactile_damage_area`
- `exclude_unclear`

## Notes

- Yellow boxes are GT `tactile_damage_area`.
- Red boxes are predicted `tactile_damage_area`.
- Green means matched class-2 boxes.
- Cyan boxes are GT `damaged_tactile_block` context.
- The low `conf=0.05` floor intentionally keeps weak candidates so humans can inspect borderline misses. It should not be interpreted as the final app threshold.
