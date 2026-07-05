# 2026-05-20 WalkSafe v3 staging / holdout plan

## 사용자 결정 반영

- 1번 privacy: 민감정보 없음, 블러 불필요.
- 2번 hard-negative: 파손 점자블럭 없음.
- 3번 bbox/파손 검토: 정성 기준 90%+로 수용. 기존 source GT label을 사용하고 `pred_box_xywhn`는 라벨로 복사하지 않는다.
- 4번 holdout: 기존 로컬 외부 검증 후보를 holdout 후보로 사용하되, v3 학습/threshold tuning과 분리한다.

## v3 train staging 후보

| 후보 | rows | label 정책 |
| --- | ---: | --- |
| hard-negative empty label | 34 | 사용자 검수로 파손 없음, 빈 라벨 사용 |
| positive existing GT | 12 | 사용자 90%+ 검수로 기존 source GT label 사용 |
| 합계 | 46 | reference manifest만 생성, 실제 dataset materialization 없음 |

`VL1+VS1 hard-negative 200`을 holdout 후보로 보존하기 위해, 같은 subset에서 온 hard-negative review 21행은 train staging에서 제외했다.

## holdout 후보

| 후보 | images | boxes | 상태 |
| --- | ---: | ---: | --- |
| `runs/validation/aihub513_vl2_vs2_tactile_subset` | 2082 | 5326 | positive holdout candidate |
| `runs/validation/aihub513_vl1_vs1_tactile_hard_negative_200_20260517` | 200 | 0 | negative holdout candidate |

주의: 이미 v2 외부검증/오류분석에 사용된 데이터이므로 최종 blind test로는 약하다. 다만 v3 학습/threshold tuning에 섞지 않고 분리하면 독립 holdout 후보로 사용할 수 있다.

## 산출물

- `data_sources/manifests/walksafe_kr_v3_train_staging_manifest_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_train_staging_summary_2026-05-20.json`
- `data_sources/manifests/walksafe_kr_v3_holdout_candidate_manifest_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_holdout_candidate_summary_2026-05-20.json`
- `data_sources/manifests/walksafe_kr_v3_holdout_manifest_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_test_manifest_2026-05-20.csv`

## 다음 단계

1. train staging 46행으로 실제 `datasets/walksafe_kr_v3` materialization dry-run을 수행한다.
2. 이미지/라벨 복사 또는 symlink 정책을 정한 뒤 validator를 통과시킨다.
3. smoke 학습을 짧게 실행하되, holdout 후보는 학습/threshold tuning에 사용하지 않는다.


## 중복 제거 기준

review row 기준 train staging 후보는 46행이지만, 실제 YOLO dataset materialization에는 `sanitized_image_path` 기준 unique manifest를 사용한다.

| 기준 | rows/images | 설명 |
| --- | ---: | --- |
| row-level train staging | 46 | review row 기준 |
| unique image train staging | 35 | 실제 materialization 권장 기준 |
| unique hard-negative | 26 | 빈 라벨 |
| unique positive existing GT | 9 | 기존 source GT label 사용 |

중복 row 11개는 같은 이미지/라벨을 중복 학습시키지 않기 위해 materialization 단계에서 제거한다.
