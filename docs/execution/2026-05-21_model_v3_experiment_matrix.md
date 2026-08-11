# 2026-05-21 v3 experiment matrix holdout-candidate evaluation

## 목적

`datasets/walksafe_kr_v3_ai_relabel_integrated_20260521` 통합 후 30 epoch 계열 실험을 실행하고, 기존 v2 baseline과 같은 holdout candidate에서 비교했다.

이 평가는 **최종 blind test가 아니다**. holdout 후보는 v2 외부검증/오류분석에 이미 사용된 데이터라, 참고 비교로만 사용한다.

GPU는 1대라 학습/평가는 순차 실행했고, 병렬 에이전트는 데이터 variant 준비와 run/log 조사에 사용했다.

## 입력 데이터

| dataset | images | positives/empty | boxes | split |
| --- | ---: | ---: | ---: | --- |
| integrated | 60 | 34/26 | 130 | train 48 / val 12 |
| posheavy | 44 | 34/10 | 130 | train 35 / val 9 |
| holdout candidate | 2282 | - | 5326 manifest / 5325 eval | test |

## 실험 매트릭스

| experiment | data | requested setting | actual optimizer note | internal val best mAP50-95 | holdout P/R/mAP50/mAP50-95 |
| --- | --- | --- | --- | ---: | --- |
| `ft30_default` | integrated | epochs=30, lr0=0.01, freeze=None | optimizer=auto -> AdamW(lr=0.00125); requested lr0 ignored | 0.43178 | 0.520/0.423/0.416/0.249 |
| `ft30_lr001_attempt` | integrated | epochs=30, lr0=0.001, freeze=None | optimizer=auto -> AdamW(lr=0.00125); requested lr0 ignored | 0.43178 | 0.520/0.423/0.416/0.249 |
| `ft30_lr001_freeze10_attempt` | integrated | epochs=30, lr0=0.001, freeze=10 | optimizer=auto -> AdamW(lr=0.00125); requested lr0 ignored | 0.44865 | 0.582/0.408/0.427/0.269 |
| `posheavy_ft30_lr001_attempt` | posheavy | epochs=30, lr0=0.001, freeze=None | optimizer=auto -> AdamW(lr=0.00125); requested lr0 ignored | 0.36611 | 0.486/0.415/0.398/0.231 |

주의: `ft30_lr001*` 이름의 실험은 의도는 low LR 비교였지만, Ultralytics가 `optimizer=auto`에서 `lr0`를 무시했다. 따라서 실제 low LR 실험으로 해석하면 안 된다.

## Holdout candidate 결과

| model | precision | recall | mAP50 | mAP50-95 |
| --- | ---: | ---: | ---: | ---: |
| v2 baseline | 0.743 | 0.605 | 0.676 | 0.499 |
| v3 AI relabel 10epoch interim | 0.549 | 0.438 | 0.446 | 0.285 |
| ft30_default | 0.520 | 0.423 | 0.416 | 0.249 |
| ft30_lr001_attempt | 0.520 | 0.423 | 0.416 | 0.249 |
| ft30_lr001_freeze10_attempt | 0.582 | 0.408 | 0.427 | 0.269 |
| posheavy_ft30_lr001_attempt | 0.486 | 0.415 | 0.398 | 0.231 |

### v2 baseline 대비 delta

| experiment | precision | recall | mAP50 | mAP50-95 |
| --- | ---: | ---: | ---: | ---: |
| ft30_default | -0.223 | -0.182 | -0.260 | -0.250 |
| ft30_lr001_attempt | -0.223 | -0.182 | -0.260 | -0.250 |
| ft30_lr001_freeze10_attempt | -0.161 | -0.197 | -0.249 | -0.230 |
| posheavy_ft30_lr001_attempt | -0.257 | -0.190 | -0.278 | -0.268 |

### v3 10epoch interim 대비 delta

| experiment | precision | recall | mAP50 | mAP50-95 |
| --- | ---: | ---: | ---: | ---: |
| ft30_default | -0.029 | -0.015 | -0.030 | -0.036 |
| ft30_lr001_attempt | -0.029 | -0.015 | -0.030 | -0.036 |
| ft30_lr001_freeze10_attempt | +0.033 | -0.030 | -0.019 | -0.016 |
| posheavy_ft30_lr001_attempt | -0.063 | -0.023 | -0.048 | -0.054 |

## 해석

- 4개 30 epoch 실험 모두 holdout candidate에서 v2 baseline보다 낮다.
- 30 epoch 실험 중 mAP50-95가 가장 높은 것은 `ft30_lr001_freeze10_attempt`이지만, v2 baseline은 물론 이전 10 epoch v3 interim보다도 낮다.
- `posheavy`는 작은 internal val에서는 recall/mAP50이 높았지만 holdout에서는 가장 낮은 축이라 채택하면 안 된다.
- 현재 기준으로 v2 baseline을 교체하지 않는다.

## 다음 액션

1. 현재 deploy/reference weight는 v2 baseline 유지.
2. 추가 실험 시 `optimizer=AdamW` 또는 `optimizer=SGD`를 명시해 진짜 low LR/freeze 실험을 실행한다.
3. 60장 규모는 불안정하므로 추가 라벨/검수 데이터를 늘린 뒤 재학습한다.
4. holdout candidate는 계속 학습/threshold tuning에서 제외하고, 공식 성능용 final blind test는 별도로 확보한다.

## 산출물

- summary JSON: `data_sources/manifests/walksafe_kr_v3_experiment_matrix_summary_2026-05-21.json`
- train logs: `logs/walksafe_kr_v3_exp_*_20260521.log`
- holdout eval logs: `logs/walksafe_kr_v3_exp_*_holdout_20260521_val.log`
- run dirs: `runs/detect/runs/detect/walksafe_kr_v3_exp_*_20260521/`

## 검증/주의

검증 완료:

```text
python3 data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_kr_v3_ai_relabel_integrated_20260521: ok
python3 data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_kr_v3_ai_relabel_posheavy_20260521: ok
python3 data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_kr_v3_holdout_candidate: ok
experiment summary json assertions: ok
git diff --check: ok
```

- eval 후 holdout/integrated/posheavy dataset cache 파일은 제거했다.
- `v3-holdout-pos-1067.jpg`에서 duplicate label 1개가 제거되어 eval instance는 5,325개다.
- run dir가 `runs/detect/runs/detect/<name>`로 중첩된 것은 직접 `yolo` 명령에서 `project=runs/detect`를 repo 기준 상대경로로 넘긴 결과다.
