# v3 PM Master Manifest Gate Summary (2026-05-20)

## Scope

- Output: `data_sources/manifests/walksafe_kr_v3_master_manifest_2026-05-20.csv`
- Base input: `data_sources/manifests/walksafe_kr_v3_review_queue_2026-05-20.csv`
- Base row count: 196
- PM policy: conservative hold. No row is marked train-ready in this manifest.

## Gate Counts

| Check | Count |
| --- | ---: |
| output rows | 196 |
| row_id duplicates | 0 |
| blank original_image_path | 0 |
| blank final_decision | 0 |
| train-ready rows | 0 |
| rows with dhash_group_id not_available | 76 |
| rows with split_group_id pending | 135 |

## Final Decision

- `hold_not_train_ready`: 196
- 즉시 학습 가능 행: 0

## Label Action Counts

- empty_label_candidate: 37
- hold_min_box_policy: 9
- hold_small_or_far_policy: 50
- manual_bbox_relabel_required: 12
- manual_duplicate_or_box_review_required: 47
- manual_hard_negative_review_required: 18
- manual_positive_review_required: 23

## Privacy Status Counts

- automated_face_candidates_detected_needs_human_review: 60
- automated_no_face_candidates_detected_not_final: 136

## Target Split Counts

- hard_negative_pool: 21
- pending: 135
- train_candidate: 40

## BBox Status Counts

- gt_and_pred_box_present_review_required: 79
- gt_box_present_pred_missing_review_required: 15
- no_bbox_available: 21
- pred_box_present_not_training_label: 81

## Gate Status

- Label gate: not train-ready; label actions remain review/hold/candidate states from label decision manifest.
- Privacy/location gate: not final; automated audit results require human review or location review.
- Split/leakage gate: not final; near-duplicate and leakage groups are audit inputs, not final split approval.
- PM final gate: all rows are held as `hold_not_train_ready` until manual review, privacy/location clearance, and split-group approval are complete.

## Notes

- Missing joined values were filled with explicit `pending` or `not_available`.
- `target_split=train_candidate` is retained only as a proposed split from leakage input, not as training approval.
- `reviewer` and `reviewed_at` remain `pending` because no human final review was performed in this task.
