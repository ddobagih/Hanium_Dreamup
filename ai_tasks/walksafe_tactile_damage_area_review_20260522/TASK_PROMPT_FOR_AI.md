# Task Prompt: tactile_damage_area C-mode external review

You are reviewing `tactile_damage_area` label quality for WalkSafe.

## Goal

Review all 120 rows in this package and produce an independent final decision CSV.

Start with:

```text
ai_tasks/walksafe_tactile_damage_area_review_20260522/GUIDE.md
```

## Inputs

- Full-image sheets: `contact_sheets/full/`
- Crop sheets: `contact_sheets/crops/`
- Review template with non-final AI suggestions:
  - `manifests/walksafe_tactile3_damage_area_review_decision_template_with_ai_suggestions_2026-05-22.csv`

## Output columns

```csv
review_id,review_order,final_reviewer_decision,reviewer_confidence,reviewer_reason,manual_damage_area_boxes_xywhn,confirm_remove_all_damage_area
```

## Allowed decisions

- `accept_existing_labels`
- `fix_tactile_damage_area_bbox`
- `remove_false_damage_area_label`
- `add_missing_tactile_damage_area`
- `exclude_unclear`

## Rules

- Review all 120 rows; do not sample only low-confidence rows.
- Treat local AI suggestions as hints, not final truth.
- Do not copy red prediction boxes blindly.
- If exact normalized bbox coordinates cannot be provided, write `manual_bbox_required` in the reason.
- Do not modify source code, datasets, model weights, or local-only artifact paths.
