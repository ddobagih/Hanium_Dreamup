# 2026-05-20 Model v3 label gate

## 입력과 산출물

입력:

- `data_sources/manifests/walksafe_kr_v3_review_queue_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_label_decision_manifest_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_minbox_bbox_review_2026-05-20.md`
- `docs/execution/2026-05-20_model_sampling_200_triage.md`

산출물:

- `data_sources/manifests/walksafe_kr_v3_label_policy_2026-05-20.md`
- `data_sources/manifests/walksafe_kr_v3_label_decision_final_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_label_decision_final_post_policy_2026-05-20.csv`

## 사용자 수동 검수 반영

- privacy_hold 39행은 민감정보 없음으로 전부 해제됐고, 최종 블러는 불필요하다.
- hard-negative 성격 묶음은 사용자 검수 결과 파손 점자블럭 없음으로 정리됐다. 따라서 빈 라벨 후보로 볼 수 있다.
- 사용자가 본 파손 탐지 품질은 전반적으로 잘 맞고 90% 이상으로 보이나, 이는 정성/spot-check 판단이다. 독립 holdout/test metric이나 공식 성능 수치로 쓰면 안 된다.

## label gate가 아직 닫히지 않은 이유

- 현재 실제 수동 bbox 보정이 없으므로 positive/relabel 후보를 학습 라벨로 확정할 수 없다.
- `pred_box_xywhn`는 모델 예측/triage 결과라 라벨로 복사할 수 없다.
- `small_or_far` 50행과 min-box 9행은 사용자 정책상 v3.0에서 제외한다.
- hard-negative 성격 묶음은 파손 점자블럭 없음으로 확인됐지만, 최종 학습 반영은 split/manifest 확정 후 진행해야 한다.
- 독립 holdout/test가 없으므로 v3 성능을 공식 metric으로 검증할 수 없다.

## final CSV 상태 요약

| final_label_status | 의미 |
| --- | --- |
| `hard_negative_confirmed_empty_label` / hard-negative 후보 | 사용자 검수상 파손 점자블럭 없음. 빈 라벨 후보로 split/manifest 확정 필요 |
| `relabel_required` | 원본 이미지 기준 수동 bbox 보정 필요 |
| `hold` | min-box 또는 small/far 정책으로 v3.0 제외/보류 |
| `blocked` | 수동 시각 검토, duplicate/GT 판단 등이 끝나지 않아 차단 |

공통 정책:

- `use_pred_box_as_label`는 전 행 `no`다.
- 실제 label txt 수정은 하지 않았다.
- v3 학습 입력으로 즉시 승인된 행은 없다.

## 실제 다음 작업

1. `manual_bbox_relabel_required` 12행은 원본 이미지를 보고 class 0 손상 영역 bbox를 수동 보정한다.
2. `manual_duplicate_or_box_review_required` 47행은 중복 예측, extra box, 누락 GT를 구분한다.
3. 사용자 검수로 파손 없음이 확인된 hard-negative 빈 라벨 후보를 split/manifest에 확정 반영한다.
4. `small_or_far` 50행과 min-box 9행은 v3.0 제외 상태를 유지하고, 별도 실험군 여부만 추후 결정한다.
5. 독립 holdout/test 후보를 확보하거나, 부재 상태를 명시하고 학습/평가 범위를 제한한다.
6. 후속 승인 CSV를 만든 뒤에만 라벨 txt 복사/수정과 학습 manifest 생성을 진행한다.

## 검증 기준

- final CSV rows = 196
- `use_pred_box_as_label` 빈틈없이 `no`
- `final_label_status` 빈 값 0
- 공식 metric과 사용자 정성 검수를 문서에서 구분
- `git diff --check` PASS

## 사용자 추가 승인 반영 - bbox 12행 / holdout 후보

- 3번 bbox/파손 검토는 사용자 정성 기준 90%+로 수용했다.
- `manual_bbox_relabel_required`였던 12행은 `bbox_user_review_pass_existing_gt_label`로 전환했다.
- `pred_box_xywhn`는 여전히 라벨로 복사하지 않고, 기존 source GT label을 사용한다.
- v3 train staging 후보는 46행이다: hard-negative 34행 + positive existing GT 12행.
- `VL1+VS1 hard-negative 200`을 holdout 후보로 보존하기 위해, 해당 subset에서 온 hard-negative review 21행은 train staging에서 제외하고 `holdout_reserved_not_train`으로 둔다.
- holdout 후보는 `VL2+VS2` positive 2,082장/5,326 boxes와 `VL1+VS1` negative 200장/0 boxes다.
- 이 holdout 후보는 이미 v2 외부검증/오류분석에 사용되어 최종 blind test로는 약하므로, v3 학습/threshold tuning에 섞지 않는 조건으로만 독립 holdout 후보로 사용한다.
