# Model Training AI Brief - 2026-05-21

## 현재 상태

사용자 수동검수 CSV 70행을 반영했다.

입력:

- `data_sources/manifests/walksafe_kr_v3_manual_review_70_decision_rechecked_2026-05-20_1.csv`

결과:

| decision | rows |
| --- | ---: |
| `accept_existing_gt_positive` | 8 |
| `needs_bbox_relabel` | 32 |
| `hard_negative_empty_label` | 9 |
| `exclude_unclear_or_policy` | 21 |

보수적 반영 기준:

- red prediction box는 label로 복사하지 않았다.
- `needs_bbox_relabel` 32행은 실제 YOLO label 수정 전까지 학습 제외다.
- `hard_negative_empty_label` 9행은 source label에 기존 GT box가 있어서 empty label로 변환하지 않았다. 충돌 큐로 분리했다.

## 산출물

- `docs/execution/2026-05-21_model_v3_manual_review_decision_apply.md`
- `data_sources/manifests/walksafe_kr_v3_manual_review_70_decision_applied_2026-05-21.csv`
- `data_sources/manifests/walksafe_kr_v3_manual_review_image_decisions_2026-05-21.csv`
- `data_sources/manifests/walksafe_kr_v3_manual_relabel_queue_2026-05-21.csv`
- `data_sources/manifests/walksafe_kr_v3_manual_review_conflict_queue_2026-05-21.csv`
- `data_sources/manifests/walksafe_kr_v3_readiness_after_manual_review_2026-05-21.csv`
- `data_sources/manifests/walksafe_kr_v3_train_staging_after_manual_review_unique_manifest_2026-05-21.csv`
- `data_sources/manifests/walksafe_kr_v3_reviewed_materialized_manifest_2026-05-21.csv`
- `data_sources/manifests/walksafe_kr_v3_manual_review_decision_apply_summary_2026-05-21.json`
- `datasets/walksafe_kr_v3_reviewed_20260521/`
- `data_sources/scripts/apply_v3_manual_review_decisions.py`

## Reviewed dataset

- Dataset: `datasets/walksafe_kr_v3_reviewed_20260521`
- Data YAML: `datasets/walksafe_kr_v3_reviewed_20260521/data.yaml`
- Total unique images: 40
- Train images: 32
- Val images: 8
- Total label boxes: 54
- Empty labels: 26
- Exact holdout path overlap: 0

Validator result:

```text
train: images=32 labels=32 boxes=42
val: images=8 labels=8 boxes=12
validation: ok
```

## Next

- 성능을 최대화하려면 먼저 `data_sources/manifests/walksafe_kr_v3_manual_relabel_queue_2026-05-21.csv`의 32행 bbox를 실제 YOLO label로 수정해야 한다.
- 빠른 중간 실험이면 현재 40장 reviewed dataset으로 interim training 가능하다. 단, 공식 v3 성능으로 보고하면 안 된다.

## PR #2 AI relabel 통합

사용자가 전달한 PR #2 최신 head를 확인했다.

- PR: https://github.com/ddobagih/Hanium_Dreamup/pull/2
- head: `cf4a67e346a4090fe6a500eb6197f3f5669034fb`
- commit: `cf4a67e Add missing WalkSafe v3 crack label`
- Draft 유지

검증:

```text
images=20 labels_final=20 excluded=0
validation: ok
```

`labels_final` 20개를 기존 reviewed staging 40장에 통합해 새 dataset을 생성했다.

- Dataset: `datasets/walksafe_kr_v3_ai_relabel_integrated_20260521`
- Data YAML: `datasets/walksafe_kr_v3_ai_relabel_integrated_20260521/data.yaml`
- Total unique images: 60
- Train images: 48
- Val images: 12
- Total label boxes: 130
- AI relabel boxes: 76
- Empty labels: 26
- Exact holdout path overlap: 0

Validator:

```text
train: images=48 labels=48 boxes=103
val: images=12 labels=12 boxes=27
validation: ok
```

산출물:

- `data_sources/labels/walksafe_kr_v3_manual_relabels_20260521/`
- `data_sources/manifests/walksafe_kr_v3_train_staging_after_ai_relabel_unique_manifest_2026-05-21.csv`
- `data_sources/manifests/walksafe_kr_v3_ai_relabel_integrated_materialized_manifest_2026-05-21.csv`
- `data_sources/manifests/walksafe_kr_v3_ai_relabel_integration_summary_2026-05-21.json`
- `docs/execution/2026-05-21_model_v3_ai_relabel_integration.md`

다음 단계는 이 dataset으로 v3 interim/full training을 실행하는 것이다. 단, 최종 blind test는 여전히 별도 확보가 필요하다.

## v3 AI relabel interim training/eval

병렬 에이전트로 학습/평가 준비를 분리해 진행했다.

### Training

- Dataset: `datasets/walksafe_kr_v3_ai_relabel_integrated_20260521/data.yaml`
- Initial weight: `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt`
- Run: `runs/detect/walksafe_kr_v3_ai_relabel_interim_20260521`
- Epochs: 10
- imgsz: 640
- batch: 2
- device: CUDA:0

Internal val, `results.csv` 기준 best epoch:

| metric | value |
| --- | ---: |
| precision(B) | 0.68947 |
| recall(B) | 0.66667 |
| mAP50(B) | 0.62800 |
| mAP50-95(B) | 0.42720 |

주의: internal val metric은 공식 성능이 아니다.

### Holdout candidate eval

Holdout 후보셋 `datasets/walksafe_kr_v3_holdout_candidate`의 `test` split에서 v2 baseline과 v3 interim을 같은 조건으로 비교했다.

| model | precision | recall | mAP50 | mAP50-95 |
| --- | ---: | ---: | ---: | ---: |
| v2 baseline | 0.743 | 0.605 | 0.676 | 0.499 |
| v3 AI relabel interim | 0.549 | 0.438 | 0.446 | 0.285 |
| delta v3-v2 | -0.194 | -0.167 | -0.230 | -0.214 |

결론:

- 이번 10 epoch v3 AI relabel interim weight는 holdout candidate에서 v2 baseline보다 낮다.
- 현 상태로 v2 baseline을 대체하면 안 된다.
- 이 holdout candidate는 최종 blind test가 아니지만, 현재 학습 설정/데이터 균형이 충분하지 않다는 신호로는 볼 수 있다.

산출물:

- `docs/execution/2026-05-21_model_v3_ai_relabel_interim_training.md`
- `docs/execution/2026-05-21_model_v3_ai_relabel_holdout_eval_plan.md`
- `docs/execution/2026-05-21_model_v3_ai_relabel_holdout_eval.md`
- `data_sources/manifests/walksafe_kr_v3_ai_relabel_interim_train_summary_2026-05-21.json`
- `data_sources/manifests/walksafe_kr_v3_ai_relabel_holdout_eval_summary_2026-05-21.json`

다음 실험은 full training을 무작정 늘리기보다 아래를 분리해 비교하는 것이 안전하다.

1. v2 weight freeze/unfreeze 전략.
2. hard-negative 비율 조정.
3. LR/epoch schedule 조정.
4. AI relabel 20장만 추가했을 때와 기존 60장 전체 학습의 차이.
5. 최종 blind test 별도 확보.

<!-- walksafe-v3-experiment-matrix-2026-05-21-start -->
## v3 30 epoch experiment matrix

PR #2 AI relabel 통합 이후 30 epoch 계열 4개 실험과 holdout-candidate 평가를 완료했다.

| model | precision | recall | mAP50 | mAP50-95 |
| --- | ---: | ---: | ---: | ---: |
| v2 baseline | 0.743 | 0.605 | 0.676 | 0.499 |
| v3 10epoch interim | 0.549 | 0.438 | 0.446 | 0.285 |
| ft30_default | 0.520 | 0.423 | 0.416 | 0.249 |
| ft30_lr001_attempt | 0.520 | 0.423 | 0.416 | 0.249 |
| ft30_lr001_freeze10_attempt | 0.582 | 0.408 | 0.427 | 0.269 |
| posheavy_ft30_lr001_attempt | 0.486 | 0.415 | 0.398 | 0.231 |

결론:

- 4개 30 epoch 실험 모두 v2 baseline보다 낮다.
- best 30 epoch는 `ft30_lr001_freeze10_attempt`의 mAP50-95 0.269지만, 이전 v3 10epoch interim 0.285보다도 낮다.
- `optimizer=auto`가 `lr0`를 무시했으므로 `lr001` 실험명은 실제 low LR 실험으로 해석하면 안 된다.
- 현재는 v2 baseline 유지가 안전하다.

산출물:

- `docs/execution/2026-05-21_model_v3_experiment_matrix.md`
- `data_sources/manifests/walksafe_kr_v3_experiment_matrix_summary_2026-05-21.json`

<!-- walksafe-v3-experiment-matrix-2026-05-21-end -->
