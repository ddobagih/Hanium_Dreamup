# tactile_damage_area AI-assisted visual pass

Date: 2026-05-22 KST

## Status

AI-assisted suggestions have been generated as a **non-final** review aid.

## Outputs

- Suggestions CSV: `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_ai_suggestions_2026-05-22.csv`
- Summary JSON: `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_ai_suggestions_summary_2026-05-22.json`
- Source review queue: `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_error_review_queue_2026-05-22.csv`

## Counts

```json
{
  "date": "2026-05-22",
  "status": "ai_suggestions_written_non_final",
  "source_queue": "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_error_review_queue_2026-05-22.csv",
  "suggestions_input": "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_ai_suggestion_input_2026-05-22.csv",
  "suggestions_out": "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_ai_suggestions_2026-05-22.csv",
  "rows": 120,
  "decision_counts": {
    "fix_tactile_damage_area_bbox": 11,
    "add_missing_tactile_damage_area": 37,
    "accept_existing_labels": 48,
    "exclude_unclear": 12,
    "remove_false_damage_area_label": 12
  },
  "confidence_counts": {
    "medium": 49,
    "high": 59,
    "low": 12
  },
  "issue_decision_counts": {
    "mixed_fn_fp_damage_area|fix_tactile_damage_area_bbox": 5,
    "mixed_fn_fp_damage_area|add_missing_tactile_damage_area": 17,
    "mixed_fn_fp_damage_area|accept_existing_labels": 1,
    "mixed_fn_fp_damage_area|exclude_unclear": 1,
    "damage_block_ok_area_missed|accept_existing_labels": 17,
    "damage_block_ok_area_missed|exclude_unclear": 5,
    "damage_block_ok_area_missed|remove_false_damage_area_label": 11,
    "damage_block_ok_area_missed|fix_tactile_damage_area_bbox": 2,
    "damage_block_ok_area_missed|add_missing_tactile_damage_area": 1,
    "false_negative_damage_area|accept_existing_labels": 24,
    "false_negative_damage_area|fix_tactile_damage_area_bbox": 4,
    "false_negative_damage_area|exclude_unclear": 5,
    "false_negative_damage_area|remove_false_damage_area_label": 1,
    "false_negative_damage_area|add_missing_tactile_damage_area": 2,
    "hard_negative_false_positive_damage_area|accept_existing_labels": 6,
    "hard_negative_false_positive_damage_area|add_missing_tactile_damage_area": 17,
    "hard_negative_false_positive_damage_area|exclude_unclear": 1
  },
  "guardrail": "AI suggestions are not final review_decision values and require human confirmation before dataset build."
}
```

## Issue/decision breakdown

```json
{
  "mixed_fn_fp_damage_area|fix_tactile_damage_area_bbox": 5,
  "mixed_fn_fp_damage_area|add_missing_tactile_damage_area": 17,
  "mixed_fn_fp_damage_area|accept_existing_labels": 1,
  "mixed_fn_fp_damage_area|exclude_unclear": 1,
  "damage_block_ok_area_missed|accept_existing_labels": 17,
  "damage_block_ok_area_missed|exclude_unclear": 5,
  "damage_block_ok_area_missed|remove_false_damage_area_label": 11,
  "damage_block_ok_area_missed|fix_tactile_damage_area_bbox": 2,
  "damage_block_ok_area_missed|add_missing_tactile_damage_area": 1,
  "false_negative_damage_area|accept_existing_labels": 24,
  "false_negative_damage_area|fix_tactile_damage_area_bbox": 4,
  "false_negative_damage_area|exclude_unclear": 5,
  "false_negative_damage_area|remove_false_damage_area_label": 1,
  "false_negative_damage_area|add_missing_tactile_damage_area": 2,
  "hard_negative_false_positive_damage_area|accept_existing_labels": 6,
  "hard_negative_false_positive_damage_area|add_missing_tactile_damage_area": 17,
  "hard_negative_false_positive_damage_area|exclude_unclear": 1
}
```

## Guardrails

- `ai_suggested_decision` is not copied into `review_decision` automatically.
- All rows keep `ai_is_final_decision=no` and `needs_human_confirmation=yes`.
- Before building a reviewed dataset, confirm or edit the official decision template:
  `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_template_2026-05-22.csv`.

## Decision-template helper

A convenience copy of the official decision template with AI suggestion columns was also written:

- `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_template_with_ai_suggestions_2026-05-22.csv`

This file still keeps `review_decision` blank. Use the AI columns only as a review aid before copying confirmed choices into the official decision field.
