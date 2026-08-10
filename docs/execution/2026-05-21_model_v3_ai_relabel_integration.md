# 2026-05-21 WalkSafe v3 AI relabel integration

## 목적

PR #2의 `labels_final` 20개를 기존 v3 reviewed staging에 통합했다.

## 입력

- PR: https://github.com/ddobagih/Hanium_Dreamup/pull/2
- PR head: `cf4a67e346a4090fe6a500eb6197f3f5669034fb`
- Base manifest: `data_sources/manifests/walksafe_kr_v3_train_staging_after_manual_review_unique_manifest_2026-05-21.csv`
- Relabel package manifest: `ai_tasks/walksafe_v3_relabel_20260521/image_manifest.csv`
- Relabel labels: `ai_tasks/walksafe_v3_relabel_20260521/labels_final`

## 결과

| 항목 | 값 |
| --- | ---: |
| base unique images | 40 |
| AI relabeled unique images added | 20 |
| total unique images | 60 |
| total label boxes | 130 |
| AI relabel boxes | 76 |
| empty labels | 26 |
| train images | 48 |
| val images | 12 |
| train boxes | 103 |
| val boxes | 27 |
| exact holdout path overlap | 0 |

## 산출물

- Stored relabel labels: `data_sources/labels/walksafe_kr_v3_manual_relabels_20260521`
- Train unique manifest: `data_sources/manifests/walksafe_kr_v3_train_staging_after_ai_relabel_unique_manifest_2026-05-21.csv`
- Materialized manifest: `data_sources/manifests/walksafe_kr_v3_ai_relabel_integrated_materialized_manifest_2026-05-21.csv`
- Dataset: `datasets/walksafe_kr_v3_ai_relabel_integrated_20260521`
- Data YAML: `datasets/walksafe_kr_v3_ai_relabel_integrated_20260521/data.yaml`
- Summary: `data_sources/manifests/walksafe_kr_v3_ai_relabel_integration_summary_2026-05-21.json`

## 주의

- red prediction box를 그대로 라벨로 복사한 것이 아니라, PR의 `labels_final`을 최종 라벨 소스로 사용했다.
- PR package 이미지는 aspect ratio 유지 resize라 normalized YOLO label은 원본 sanitized image에도 호환된다.
- 이 데이터셋은 다음 interim/full training 후보이며, holdout 후보는 학습에 섞지 않았다.
