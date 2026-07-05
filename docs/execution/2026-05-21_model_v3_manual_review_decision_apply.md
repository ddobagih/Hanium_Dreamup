# 2026-05-21 v3 manual review decision apply

## 목적

사용자가 채운 70개 manual review decision을 검증하고 v3 staging 후보에 보수적으로 반영했다.

자동 bbox 생성은 하지 않았다. 특히 `needs_bbox_relabel`은 실제 YOLO label 수정 전까지 학습에 넣지 않는다.

## 입력

- Decision CSV: `data_sources/manifests/walksafe_kr_v3_manual_review_70_decision_rechecked_2026-05-20_1.csv`
- Original review manifest: `data_sources/manifests/walksafe_kr_v3_manual_review_70_manifest_2026-05-20.csv`

## Decision 분포

| decision | rows |
| --- | ---: |
| `accept_existing_gt_positive` | 8 |
| `exclude_unclear_or_policy` | 21 |
| `needs_bbox_relabel` | 32 |
| `hard_negative_empty_label` | 9 |

## 반영 결과

| 항목 | 값 |
| --- | ---: |
| manual review rows | 70 |
| manual review unique images | 47 |
| 추가 train-ready unique images | 5 |
| manual relabel rows | 32 |
| conflict/blocked rows | 10 |
| revised train unique images | 40 |
| revised total label boxes | 54 |
| exact holdout path overlap | 0 |

Image-level status:

| status | images |
| --- | ---: |
| `needs_manual_relabel` | 20 |
| `blocked_hard_negative_empty_conflicts_source_gt` | 4 |
| `excluded_or_mixed_unclear` | 18 |
| `ready_existing_gt_user_accept` | 5 |

## 생성한 staging dataset

- Dataset root: `datasets/walksafe_kr_v3_reviewed_20260521`
- Data YAML: `datasets/walksafe_kr_v3_reviewed_20260521/data.yaml`
- Materialized manifest: `data_sources/manifests/walksafe_kr_v3_reviewed_materialized_manifest_2026-05-21.csv`
- Train images: 32
- Val images: 8
- Train boxes: 42
- Val boxes: 12

## 보수적 처리 기준

- `needs_bbox_relabel`: `data_sources/manifests/walksafe_kr_v3_manual_relabel_queue_2026-05-21.csv`로 분리. 실제 박스 수정 전까지 not train-ready.
- `hard_negative_empty_label`인데 source label box가 1개 이상인 경우: empty label로 바꾸지 않고 `data_sources/manifests/walksafe_kr_v3_manual_review_conflict_queue_2026-05-21.csv`로 분리.
- red prediction box는 triage용이며 label로 복사하지 않음.

## 산출물

- Applied row CSV: `data_sources/manifests/walksafe_kr_v3_manual_review_70_decision_applied_2026-05-21.csv`
- Image-level decision CSV: `data_sources/manifests/walksafe_kr_v3_manual_review_image_decisions_2026-05-21.csv`
- Manual relabel queue: `data_sources/manifests/walksafe_kr_v3_manual_relabel_queue_2026-05-21.csv`
- Conflict queue: `data_sources/manifests/walksafe_kr_v3_manual_review_conflict_queue_2026-05-21.csv`
- Readiness CSV: `data_sources/manifests/walksafe_kr_v3_readiness_after_manual_review_2026-05-21.csv`
- Train unique manifest: `data_sources/manifests/walksafe_kr_v3_train_staging_after_manual_review_unique_manifest_2026-05-21.csv`
- Summary JSON: `data_sources/manifests/walksafe_kr_v3_manual_review_decision_apply_summary_2026-05-21.json`

## 검증

```bash
python3 data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_kr_v3_reviewed_20260521
```

결과:

```text
dataset_root: /home/ddobagi/Code/hanium-dreamup/datasets/walksafe_kr_v3_reviewed_20260521
data_yaml: datasets/walksafe_kr_v3_reviewed_20260521/data.yaml
classes: 4
train: images=32 labels=32 boxes=42
val: images=8 labels=8 boxes=12
validation: ok
```

추가 assertion:

- decision row 70개 확인.
- revised train unique manifest 40행 확인.
- revised total label boxes 54개 확인.
- exact holdout path overlap 0 확인.

## 다음 선택지

1. `needs_bbox_relabel` 32행의 YOLO label을 실제로 수동 수정한 뒤 v3 reviewed dataset을 다시 생성한다.
2. 또는 현재 생성된 40장 reviewed dataset으로 interim training을 실행한다. 단, 32행 재라벨 대상은 빠진 상태라 성능 개선 폭은 제한적일 수 있다.
