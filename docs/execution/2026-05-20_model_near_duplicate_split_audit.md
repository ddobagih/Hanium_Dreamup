# 2026-05-20 v3 near-duplicate / dHash split audit

## Scope

- Input review queue: `data_sources/manifests/walksafe_kr_v3_review_queue_2026-05-20.csv`
- Input leakage groups: `data_sources/manifests/walksafe_kr_v3_leakage_groups_2026-05-20.csv`
- Output CSV: `data_sources/manifests/walksafe_kr_v3_near_duplicate_groups_2026-05-20.csv`
- Output summary: `data_sources/manifests/walksafe_kr_v3_near_duplicate_summary_2026-05-20.json`
- No model training was run. No image or label files were modified.

## Method

- Implemented a 64-bit dHash directly with `.venv/bin/python` + PIL: grayscale resize to 9x8, then 8 horizontal adjacent-pixel comparisons per row.
- Candidate edge rule: exact hash match or Hamming distance <= 5.
- Near-duplicate groups are connected components over those candidate edges.
- `existing_leakage_group_key` was joined from `walksafe_kr_v3_leakage_groups_2026-05-20.csv` by `source_candidate_id` / `source_image`.

## Results

- Input rows: 196
- Output rows: 196
- Empty hash count: 0
- Unique dHash count: 120
- Candidate pairs with distance <= 5: 133
- Near-duplicate groups: 44
- Rows in near-duplicate groups: 120
- Existing filename/leakage-group covered groups: 7
- Not fully covered by existing filename/leakage groups: 37

Status counts:

- `covered_by_existing_leakage_group`: 14
- `new_near_duplicate_candidate_not_in_filename_group`: 106
- `no_near_duplicate_candidate`: 76

## Comparison with existing leakage_group_key

The dHash audit found candidate groups that the existing filename-based `leakage_group_key` does not fully cover. These are marked in the CSV as `new_near_duplicate_candidate_not_in_filename_group`. Some rows have no existing leakage mapping because the leakage-group input covers only a subset of the 196-row review queue.

Largest not-fully-covered candidate groups:

| near_duplicate_group | size | min/max distance | review_ids | existing_leakage_group_keys |
|---|---:|---|---|---|
| `ndh-dhash64-t5-020` | 8 | 0/0 | v3-review-089, v3-review-090, v3-review-091, v3-review-092, v3-review-093, v3-review-094, v3-review-095, v3-review-096 | <missing>:8 |
| `ndh-dhash64-t5-012` | 5 | 0/0 | v3-review-033, v3-review-177, v3-review-178, v3-review-179, v3-review-180 | <missing>:4, filedraft:aihub513_tactile:aihub513_tactile_2_09_1_1_4_2:date_20211101:1 |
| `ndh-dhash64-t5-001` | 4 | 0/0 | v3-review-001, v3-review-010, v3-review-067, v3-review-068 | <missing>:2, filedraft:aihub513_tactile:aihub513_tactile_2_09_0_1_1_2:date_20210728:2 |
| `ndh-dhash64-t5-008` | 4 | 0/0 | v3-review-024, v3-review-122, v3-review-123, v3-review-124 | <missing>:3, filedraft:aihub513_tactile:aihub513_tactile_2_09_1_1_1_1:date_20211021:1 |
| `ndh-dhash64-t5-017` | 4 | 0/0 | v3-review-080, v3-review-081, v3-review-082, v3-review-083 | <missing>:4 |
| `ndh-dhash64-t5-022` | 4 | 0/0 | v3-review-103, v3-review-104, v3-review-105, v3-review-106 | <missing>:4 |
| `ndh-dhash64-t5-033` | 4 | 0/0 | v3-review-139, v3-review-140, v3-review-141, v3-review-142 | <missing>:4 |
| `ndh-dhash64-t5-041` | 4 | 0/0 | v3-review-167, v3-review-168, v3-review-169, v3-review-170 | <missing>:4 |
| `ndh-dhash64-t5-044` | 4 | 0/0 | v3-review-192, v3-review-193, v3-review-194, v3-review-195 | <missing>:4 |
| `ndh-dhash64-t5-004` | 3 | 0/0 | v3-review-012, v3-review-069, v3-review-070 | <missing>:2, filedraft:aihub513_tactile:aihub513_tactile_2_09_0_1_4_1:date_20210831:1 |
| `ndh-dhash64-t5-015` | 3 | 0/0 | v3-review-075, v3-review-076, v3-review-077 | <missing>:3 |
| `ndh-dhash64-t5-019` | 3 | 0/0 | v3-review-086, v3-review-087, v3-review-088 | <missing>:3 |

## Use guidance

- Treat this as an automatic near-duplicate candidate list only; it does not replace final split decisions.
- Before assigning v3 train/val/test, manually inspect `new_near_duplicate_candidate_not_in_filename_group` rows and keep confirmed near-duplicates in the same split or exclude them from evaluation splits.
- dHash is intentionally lightweight and may miss crops/rotations or flag visually simple but distinct images; use it as a split-risk signal, not ground truth.
