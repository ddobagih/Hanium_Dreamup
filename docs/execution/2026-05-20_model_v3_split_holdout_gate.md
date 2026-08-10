# Model v3 Split/Holdout Gate (2026-05-20)

## Gate status

- Gate: **closed**.
- Reason: independent holdout/test data is not available yet.
- The 196-row review queue has only automatic split group drafts and candidate pool assignments.
- No row is assigned to v3 final test or independent holdout.

## Outputs produced

- `data_sources/manifests/walksafe_kr_v3_split_policy_2026-05-20.md`
- `data_sources/manifests/walksafe_kr_v3_split_groups_final_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_split_assignment_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_holdout_manifest_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_test_manifest_2026-05-20.csv`

## Current assignment summary

| assignment_status | rows |
| --- | ---: |
| excluded_from_eval_until_independent_holdout | 59 |
| hard_negative_pool | 37 |
| hold_for_manual_review | 97 |
| train_candidate_pool | 3 |


All assignments are candidate-pool or review-hold statuses. They are not train/val/test finalization.

## Why the gate is closed

1. Image-level random split is disallowed, and group-level split requires manual confirmation.
2. Filename leakage groups and dHash near-duplicate groups have been unioned only as an automatic draft.
3. Existing v2 test failure metrics are contaminated for v3 evaluation use and must not be reused as final evidence.
4. Hard-negative mining/tuning candidates must remain separate from final test evidence.
5. Independent holdout/test manifests currently contain no evaluation image rows.

## Next work

- Review `split_group_id` drafts for cross-group dHash near duplicates.
- Resolve rows held for manual, privacy, minimum-box, and relabel review.
- Collect independent holdout/test data with provenance separated from v2 test failure mining.
- Assign final group-level train/validation/test only after the independent evaluation source is approved.
- Re-run manifest validation before opening the holdout/test gate.

## 사용자 추가 승인 반영 - 2026-05-20

- 3번 bbox/파손 검토는 사용자 정성 기준 90%+로 수용했다.
- 기존 `manual_bbox_relabel_required` 12행은 `bbox_user_review_pass_existing_gt_label`로 전환한다.
- `pred_box_xywhn`는 라벨로 복사하지 않고, 기존 source GT label을 사용한다.
- hard-negative 55행은 파손 없음으로 확인됐지만, `VL1+VS1 hard-negative 200` holdout 후보 보존을 위해 그 subset에서 온 21행은 train staging에서 제외한다.
- v3 train staging 후보는 46행이다: hard-negative 34행 + positive existing GT 12행.
- 남은 label blocker는 duplicate/positive/manual review 70행, small/far 제외 50행, min-box 제외 9행이다.
