# manual bbox required 40-row package

- Date: 2026-05-22 KST
- Purpose: finish the remaining `tactile_damage_area` review rows that need exact manual bbox coordinates before building the reviewed dataset.

## What is in this folder

| File | Purpose |
|---|---|
| `manual_bbox_required_40_template_2026-05-22.csv` | The only CSV that needs human/AI bbox filling. Fill `manual_damage_area_boxes_xywhn`. |
| `manual_bbox_required_40_summary_2026-05-22.json` | Row counts and decision-count summary for the 40-row queue. |
| `TASK_PROMPT_FOR_AI.md` | Prompt to hand this bbox-only task to another AI/reviewer. |

## Why this exists

The external review CSV has 120 final decisions. Of those, 80 rows can already validate without new boxes, but 40 rows need exact manual `tactile_damage_area` boxes:

| decision | rows | why blocked |
|---|---:|---|
| `fix_tactile_damage_area_bbox` | 14 | existing damage-area boxes must be replaced with exact manual boxes |
| `add_missing_tactile_damage_area` | 26 | missing damage-area boxes must be added |

Until all 40 rows have `manual_damage_area_boxes_xywhn`, `apply_tactile_damage_area_review_decisions.py` should keep them blocked.

## Required output format

Fill this column only unless a row is truly impossible:

```text
manual_damage_area_boxes_xywhn
```

Format:

```text
2:x_center,y_center,width,height
```

Multiple boxes:

```text
2:0.512300,0.641200,0.120000,0.083000;2:0.330000,0.710000,0.090000,0.050000
```

Rules:

- Coordinates are YOLO-normalized floats in `[0, 1]`.
- Coordinates are relative to the **original full image**, not the contact sheet and not a crop tile.
- Class id must be `2` for `tactile_damage_area`.
- Use six decimals when possible.
- Draw tight boxes around actual broken/lost tactile surface area, not around dirt, shadows, red prediction boxes, or broad seams.
- For `fix_tactile_damage_area_bbox`, the manual boxes replace current class-2 damage-area boxes for that image.
- For `add_missing_tactile_damage_area`, the manual boxes are added to existing class-2 boxes.

## Reference materials

The queue CSV includes:

- `image_path`: local source image path under `datasets/walksafe_kr_tactile_3class_20260521/`
- `label_path`: local YOLO label path
- `contact_sheet_full`: full-scene contact sheet for context
- `contact_sheet_crop`: crop contact sheet for closer visual context
- `contact_sheet_number`, `row_in_sheet`, `sheet_review_order_range`: where to find the row in the contact sheets
- `gt_damage_area_boxes`, `pred_damage_area_boxes`, `fn_damage_area_boxes`, `fp_damage_area_boxes`: existing/predicted box references

Important: contact sheets are for review context only. Exact coordinates should be determined on the original full image or a tool that maps back to the original full-image pixel space.

## If a row cannot be boxed safely

Do not invent a bbox. Instead:

1. Set `bbox_review_status` to `exclude_unclear`.
2. Leave `manual_damage_area_boxes_xywhn` blank.
3. Explain why in `bbox_review_notes`.

The merge helper can convert `exclude_unclear` rows into a no-bbox decision later.

## After the 40 rows are filled

Run the merge helper to create an apply-ready official decision template:

```bash
python3 data_sources/scripts/merge_tactile_damage_area_manual_bbox_queue.py \
  --manual-bbox-queue ai_tasks/walksafe_tactile_damage_area_review_20260522/bbox_review/manual_bbox_required_40_template_2026-05-22.csv \
  --out /tmp/walksafe_tactile3_damage_area_review_decision_template_for_apply.csv \
  --summary-out /tmp/walksafe_tactile3_damage_area_manual_bbox_merge_summary.json \
  --require-complete
```

Then validate with the existing apply script:

```bash
python3 data_sources/scripts/apply_tactile_damage_area_review_decisions.py \
  --decisions /tmp/walksafe_tactile3_damage_area_review_decision_template_for_apply.csv \
  --applied-out /tmp/walksafe_tactile3_damage_area_review_decision_applied.csv \
  --blocked-out /tmp/walksafe_tactile3_damage_area_review_decision_blocked.csv \
  --summary-out /tmp/walksafe_tactile3_damage_area_review_decision_apply_summary.json \
  --doc-out /tmp/walksafe_tactile3_damage_area_review_apply.md
```

Acceptance before dataset build:

- `validation_status_counts.ok == 120`
- no blocked rows
- no blank `review_decision`

Only after that should `--build` be used.

## Rebuild this queue

```bash
python3 data_sources/scripts/prepare_tactile_damage_area_manual_bbox_queue.py
```
