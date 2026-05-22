# tactile_damage_area review decision workflow

Date: 2026-05-22 KST

## Current status

The error review pack is generated, but label decisions are still pending. This document prepares the decision/application step; it does not finalize labels by itself.

## Inputs

- Review queue: `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_error_review_queue_2026-05-22.csv`
- Decision template: `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_template_2026-05-22.csv`
- Summary JSON: `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_template_summary_2026-05-22.json`
- Review images/contact sheets: `ai_tasks/walksafe_tactile_damage_area_review_20260522/`

## Rows

```json
{
  "mixed_fn_fp_damage_area": 24,
  "damage_block_ok_area_missed": 36,
  "false_negative_damage_area": 36,
  "hard_negative_false_positive_damage_area": 24
}
```

## Decision options

Fill `review_decision` with one of:

- `accept_existing_labels`: existing YOLO label is usable; no label edit needed.
- `fix_tactile_damage_area_bbox`: replace class-2 `tactile_damage_area` boxes with `manual_damage_area_boxes_xywhn`.
- `remove_false_damage_area_label`: remove all class-2 boxes only when `confirm_remove_all_damage_area=yes`.
- `add_missing_tactile_damage_area`: append manually confirmed class-2 boxes from `manual_damage_area_boxes_xywhn`.
- `exclude_unclear`: keep this image out of the reviewed materialized dataset.

## Box format

`manual_damage_area_boxes_xywhn` uses semicolon-separated normalized YOLO boxes:

```text
2:x_center,y_center,width,height;2:x_center,y_center,width,height
```

Only class id `2` is accepted for manual damage-area boxes.

## Important guardrails

- Suggestions in `suggested_default_decision` are not final labels.
- Red prediction boxes are review aids only; do not copy them blindly.
- For `fix_tactile_damage_area_bbox` and `add_missing_tactile_damage_area`, manual boxes are required.
- For `remove_false_damage_area_label`, set `confirm_remove_all_damage_area=yes` to avoid accidental deletion.
- The current review pack is from the `val` split. It can improve validation label quality and reveal failure modes, but additional train-split review may still be needed before meaningful retraining.

## Apply command after decisions are filled

```bash
cd /home/ddobagi/Code/hanium-dreamup
.venv/bin/python data_sources/scripts/apply_tactile_damage_area_review_decisions.py --build
```
