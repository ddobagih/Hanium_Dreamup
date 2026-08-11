# Model v3 Holdout Candidate Materialization

Date: 2026-05-20

## Output

- Dataset: `datasets/walksafe_kr_v3_holdout_candidate/`
- Images: `datasets/walksafe_kr_v3_holdout_candidate/images/test`
- Labels: `datasets/walksafe_kr_v3_holdout_candidate/labels/test`
- Data config: `datasets/walksafe_kr_v3_holdout_candidate/data.yaml`
- Materialized manifest: `data_sources/manifests/walksafe_kr_v3_holdout_materialized_manifest_2026-05-20.csv`
- Summary JSON: `data_sources/manifests/walksafe_kr_v3_holdout_materialized_summary_2026-05-20.json`

## Dataset contract

This is a symlink-based YOLO evaluation candidate dataset. Images and labels are linked from the source paths; images are not copied.

`data.yaml` sets `train`, `val`, and `test` to `images/test` only so Ultralytics validation commands can load the dataset. This dataset is **not for training**.

Class names keep the existing 4-class contract:

0. `damaged_tactile_block`
1. `parked_kickboard_bicycle`
2. `construction_obstacle`
3. `pothole`

## Counts

- Positive: 2,082 images / 5,326 boxes
- Negative: 200 images / 0 boxes
- Total: 2,282 image/label pairs / 5,326 boxes

## Caveats

- These candidates were already used in v2 external validation/error analysis, so they are weak as a final blind test.
- Keep this holdout candidate dataset separated from v3 training and threshold tuning.

## Validation recorded by worker

- Test image/label pairs: 2,282
- Dangling symlinks: 0
- Box count from materialized label lines: 5,326
- Train staging exact image path overlap: 0 (`source_image` and `sanitized_image_path` checked)
