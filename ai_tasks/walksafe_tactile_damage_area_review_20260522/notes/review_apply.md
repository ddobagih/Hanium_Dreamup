# tactile_damage_area review decision apply

Date: 2026-05-22 KST

## Status

`pending_decisions` — no reviewed dataset has been materialized yet.

## Inputs

- Source dataset: `datasets/walksafe_kr_tactile_3class_20260521`
- Decision CSV: `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_template_2026-05-22.csv`
- Planned target dataset: `datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522` (not created yet)

## Summary

```json
{
  "date": "2026-05-22",
  "status": "pending_decisions",
  "source_dataset": "datasets/walksafe_kr_tactile_3class_20260521",
  "planned_target_dataset": "datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522",
  "decision_csv": "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_template_2026-05-22.csv",
  "rows": 120,
  "validation_status_counts": {
    "pending": 120
  },
  "decision_counts": {
    "blank": 120
  },
  "applied_rows": "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_applied_2026-05-22.csv",
  "blocked_rows": "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_blocked_2026-05-22.csv",
  "materialized_manifest": "",
  "built_dataset": false,
  "errors_preview": [
    "tactile3-da-final-001: review_decision is blank",
    "tactile3-da-final-002: review_decision is blank",
    "tactile3-da-final-003: review_decision is blank",
    "tactile3-da-final-004: review_decision is blank",
    "tactile3-da-final-005: review_decision is blank",
    "tactile3-da-final-006: review_decision is blank",
    "tactile3-da-final-007: review_decision is blank",
    "tactile3-da-final-008: review_decision is blank",
    "tactile3-da-final-009: review_decision is blank",
    "tactile3-da-final-010: review_decision is blank",
    "tactile3-da-final-011: review_decision is blank",
    "tactile3-da-final-012: review_decision is blank",
    "tactile3-da-final-013: review_decision is blank",
    "tactile3-da-final-014: review_decision is blank",
    "tactile3-da-final-015: review_decision is blank",
    "tactile3-da-final-016: review_decision is blank",
    "tactile3-da-final-017: review_decision is blank",
    "tactile3-da-final-018: review_decision is blank",
    "tactile3-da-final-019: review_decision is blank",
    "tactile3-da-final-020: review_decision is blank"
  ]
}
```

## Notes

- If status is `pending_decisions`, fill `review_decision` in the decision template first.
- `fix_tactile_damage_area_bbox` and `add_missing_tactile_damage_area` require `manual_damage_area_boxes_xywhn`.
- `remove_false_damage_area_label` requires `confirm_remove_all_damage_area=yes`.
- This script does not train a model.

- The planned target dataset path is documented for the future apply step only; it does not indicate that the dataset exists.
- External/AI review decisions still need human confirmation and exact normalized bbox coordinates where required before any reviewed dataset build.
