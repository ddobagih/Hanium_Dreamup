# 2026-05-21 WalkSafe v3 pos-heavy dataset variant

## 목적

v3 AI relabel 통합 train staging manifest에서 hard-negative(empty label) 비율을 낮춘 positive 중심 실험용 YOLO dataset을 만들었다. 학습은 실행하지 않았다.

## 입력 분포

- 입력 manifest: `data_sources/manifests/walksafe_kr_v3_train_staging_after_ai_relabel_unique_manifest_2026-05-21.csv`
- 전체 이미지: 60
- positive 이미지: 34
- empty label 이미지: 26
- 전체 bbox: 130

## 선택 정책

- positive: 34개 모두 유지
- empty label: 26개 중 10개 유지
- empty 선택 방식: `sha256(unique_id)` 오름차순 기준 deterministic selection
- split: positive/empty 각각 `sha256("split:" + unique_id)` 기준 deterministic 80/20 stratified split
- 근거: 원본은 empty 26/60 (43.3%)로 hard-negative 비율이 높아, positive recall 개선 실험에는 34 positive / 10 empty (77.3% positive)가 더 적합하다고 판단했다. 단, empty를 완전히 제거하지 않고 hard-negative 신호를 일부 유지했다.

## 결과 분포

| 항목 | 값 |
| --- | ---: |
| total images | 44 |
| positive images | 34 |
| empty images | 10 |
| total boxes | 130 |
| train images | 35 |
| train positive / empty | 27 / 8 |
| train boxes | 108 |
| val images | 9 |
| val positive / empty | 7 / 2 |
| val boxes | 22 |
| exact holdout image path overlap | 0 |
| exact holdout label path overlap | 0 |

## 산출물

- Unique manifest: `data_sources/manifests/walksafe_kr_v3_train_staging_ai_relabel_posheavy_unique_manifest_2026-05-21.csv`
- Materialized manifest: `data_sources/manifests/walksafe_kr_v3_ai_relabel_posheavy_materialized_manifest_2026-05-21.csv`
- Summary: `data_sources/manifests/walksafe_kr_v3_ai_relabel_posheavy_summary_2026-05-21.json`
- Dataset: `datasets/walksafe_kr_v3_ai_relabel_posheavy_20260521`
- Data YAML: `datasets/walksafe_kr_v3_ai_relabel_posheavy_20260521/data.yaml`
- Validator log: `logs/walksafe_kr_v3_posheavy_validator.log`

## Materialization

- 이미지는 `sanitized_image_path` 원본으로 symlink했다.
- positive label은 manifest의 `source_label`을 복사했다.
- empty label은 빈 `.txt` 파일로 생성했다.
- holdout candidate manifest와 exact path overlap이 없는지 확인했다.

## 검증

```bash
python3 data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_kr_v3_ai_relabel_posheavy_20260521 | tee logs/walksafe_kr_v3_posheavy_validator.log
```
