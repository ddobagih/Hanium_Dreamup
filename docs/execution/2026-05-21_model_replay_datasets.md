# 2026-05-21 WalkSafe replay datasets materialization

## 목적

`walksafe_kr_v2` train/val을 기준으로 v3 AI relabel integrated 데이터를 replay 형태로 섞은 YOLO dataset 3종을 materialize했다. GPU 학습/평가는 실행하지 않았다.

## 공통 입력/제외

- v2 source: `datasets/walksafe_kr_v2`
  - train: 16,433 images / 28,096 boxes
  - val: 4,695 images / 8,086 boxes
  - test: 제외
- v3 integrated source: `datasets/walksafe_kr_v3_ai_relabel_integrated_20260521`
  - train positive: 27 images / 103 boxes
  - train hard-negative(empty): 21 images / 0 boxes
  - val: 12 images / 27 boxes
- v3 manifest: `data_sources/manifests/walksafe_kr_v3_ai_relabel_integrated_materialized_manifest_2026-05-21.csv`
- 제외: `datasets/walksafe_kr_v2/images/test`, `datasets/walksafe_kr_v3_holdout_candidate`
- 이미지: symlink materialization
- 라벨: copy materialization
- duplicate row: `__<component>__dupNN` suffix로 고유 stem 생성
- `data.yaml`: 4-class names 유지

## 결과 요약

| dataset | train images | train boxes | val images | val boxes | v3 train 구성 | validator |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| `datasets/walksafe_replay_v2train_v3pos_x2_20260521` | 16,487 | 28,302 | 4,707 | 8,113 | positive x2, hard-negative 0 | ok |
| `datasets/walksafe_replay_v2train_v3balanced_2to1_20260521` | 16,514 | 28,302 | 4,707 | 8,113 | positive x2 + hard-negative 27 rows (2:1) | ok |
| `datasets/walksafe_replay_v2train_v3posheavy_4to1_20260521` | 16,568 | 28,508 | 4,707 | 8,113 | positive x4 + hard-negative 27 rows (4:1) | ok |

## 산출물

- Materialization script: `data_sources/scripts/materialize_walksafe_replay_datasets.py`
- Summary JSON:
  - `data_sources/manifests/walksafe_replay_v2train_v3pos_x2_summary_2026-05-21.json`
  - `data_sources/manifests/walksafe_replay_v2train_v3balanced_2to1_summary_2026-05-21.json`
  - `data_sources/manifests/walksafe_replay_v2train_v3posheavy_4to1_summary_2026-05-21.json`
- Materialized manifest:
  - `data_sources/manifests/walksafe_replay_v2train_v3pos_x2_materialized_manifest_2026-05-21.csv`
  - `data_sources/manifests/walksafe_replay_v2train_v3balanced_2to1_materialized_manifest_2026-05-21.csv`
  - `data_sources/manifests/walksafe_replay_v2train_v3posheavy_4to1_materialized_manifest_2026-05-21.csv`
- Validator logs:
  - `logs/walksafe_replay_v2train_v3pos_x2_20260521_validator_20260521.log`
  - `logs/walksafe_replay_v2train_v3balanced_2to1_20260521_validator_20260521.log`
  - `logs/walksafe_replay_v2train_v3posheavy_4to1_20260521_validator_20260521.log`

## 검증 명령

```bash
python data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_replay_v2train_v3pos_x2_20260521
python data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_replay_v2train_v3balanced_2to1_20260521
python data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_replay_v2train_v3posheavy_4to1_20260521
```

세 dataset 모두 `validation: ok`를 확인했다.
