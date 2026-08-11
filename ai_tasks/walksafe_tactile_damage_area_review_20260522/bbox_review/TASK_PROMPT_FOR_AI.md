# Task prompt: fill 40 tactile_damage_area manual boxes

You are reviewing 40 `tactile_damage_area` rows that already have final reviewer decisions but still need exact YOLO-normalized bbox coordinates.

## Input

Use:

```text
ai_tasks/walksafe_tactile_damage_area_review_20260522/bbox_review/manual_bbox_required_40_template_2026-05-22.csv
```

## Output

Return the same CSV with these columns updated:

- `manual_damage_area_boxes_xywhn`
- `bbox_review_status`
- `bbox_review_notes`

Do not reorder rows. Do not change `review_id`, `review_order`, `final_reviewer_decision`, `image_path`, or reference box columns.

## Bbox format

Use original full-image YOLO-normalized coordinates:

```text
2:x_center,y_center,width,height
```

Multiple boxes are separated with semicolons:

```text
2:0.512300,0.641200,0.120000,0.083000;2:0.330000,0.710000,0.090000,0.050000
```

Rules:

- Class id must be `2`.
- All coordinates must be between `0` and `1`.
- Use original full image coordinates, not contact-sheet coordinates and not crop coordinates.
- Prefer tight boxes around clear material loss/cracks/broken tactile surface.
- Do not copy red prediction boxes blindly.
- Do not box stains, shadows, tar marks, normal seams, or intact worn ridges as damage.

## Decision behavior

- `fix_tactile_damage_area_bbox`: provide the corrected class-2 damage-area boxes that should replace existing class-2 boxes.
- `add_missing_tactile_damage_area`: provide only the missing class-2 damage-area boxes that should be added.
- If the row is too unclear to box safely, set:
  - `bbox_review_status=exclude_unclear`
  - `manual_damage_area_boxes_xywhn=` blank
  - `bbox_review_notes=` explain why.
- Otherwise set `bbox_review_status=done`.

## Reference columns

The CSV includes existing GT/prediction references:

- `gt_damage_area_boxes`
- `pred_damage_area_boxes`
- `fn_damage_area_boxes`
- `fp_damage_area_boxes`

These are hints, not final truth. The visual decision must come from the original image and review context.

## Contact sheets

Use the contact sheet columns to find the row visually:

- `contact_sheet_full`
- `contact_sheet_crop`
- `contact_sheet_number`
- `row_in_sheet`
- `sheet_review_order_range`

Contact sheets are for context only. Exact bbox coordinates must map to the original full image.

## Completion check

The task is complete when every row either has valid `manual_damage_area_boxes_xywhn` or is explicitly marked `bbox_review_status=exclude_unclear` with a note.
