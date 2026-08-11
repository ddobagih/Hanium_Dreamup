# 2026-05-20 v3 gate execution summary

## 범위

사용자가 첨부한 `walksafe_v3_vision_ai_plan.md` 기준으로 병렬 에이전트를 실행해 v3 학습 전 gate 산출물을 만들었다. 이후 사용자 수동 검수 결과를 반영해 privacy/hard-negative/metadata 상태를 갱신했다. 새 학습, 라벨 txt 수정, `datasets/walksafe_kr_v3` 생성은 하지 않았다.

## 병렬 실행 결과와 최신 사용자 검수 반영

| Agent | 역할 | 결과 |
| --- | --- | --- |
| Privacy | sanitized image/manifest 갱신 | 196행/126 unique 모두 민감정보 없음, 블러 0, privacy hold 0 |
| Label | label policy/post-policy 갱신 | hard-negative 55행 파손 없음, 빈 라벨 후보 |
| Report | 보고서 갱신 | 90%+ 판단은 정성/spot-check로 분리 |
| Metadata | readiness/master 갱신 | 55행 staging-ready pending holdout, 공식 full-train/eval ready 0 |

## Privacy gate

| 항목 | 값 |
| --- | ---: |
| review queue rows | 196 |
| sanitized unique images | 126 |
| 최종 privacy decision `privacy_pass_user_review_no_sensitive_no_blur` | 196 |
| privacy hold remaining | 0 |
| 사용자 검수상 민감정보 발견 | 0 |
| 최종 blur count | 0 |
| sanitized EXIF present | 0 |

Privacy blocker는 사용자 수동 검수로 해제됐다. 자동 detector 후보 count는 감사 추적용으로만 남긴다.

## Label gate

| final_label_status / 정책 묶음 | rows |
| --- | ---: |
| hard-negative 빈 라벨 후보 | 55 |
| `manual_bbox_relabel_required` | 12 |
| `exclude_v3_0_min_box_policy` | 9 |
| `manual_visual_review_required` | 70 |
| `exclude_v3_0_small_far_policy` | 50 |

| 확인 항목 | 결과 |
| --- | --- |
| output rows | 196 |
| `use_pred_box_as_label=no` | 196 |
| 실제 label txt 수정 | 하지 않음 |

사용자 검수상 hard-negative 성격 묶음에는 파손 점자블럭이 없다. 다만 bbox 12행 보정, duplicate/positive/manual review, split 확정 전에는 공식 full-train/eval ready가 아니다.

## Split / holdout gate

| 항목 | 값 |
| --- | ---: |
| split_groups rows | 196 |
| split_assignment rows | 196 |
| final test 배정 | 0 |
| holdout 배정 | 0 |
| holdout_manifest 평가 이미지 | 0 |
| test_manifest 평가 이미지 | 0 |

독립 holdout/test 데이터가 없고, split_group은 자동 초안이다.

## 현재 최종 판정

- v2 baseline은 동결 가능하다.
- v3 review/gate 산출물은 크게 진전됐고, privacy blocker는 해제됐다.
- hard-negative 55행은 빈 라벨 staging 후보가 됐다.
- 하지만 `datasets/walksafe_kr_v3` 생성과 smoke 학습은 아직 하면 안 된다.
- 공식 full-train/eval ready 행은 0개다.
- 사용자가 본 파손 탐지 품질은 전반적으로 90% 이상으로 보이나, 이는 정성/spot-check 판단이며 독립 holdout/test 공식 metric이 아니다.

## 다음 unblock 작업

1. bbox 12행 수동 보정, duplicate/extra/positive/manual review 70행 결정.
2. hard-negative 빈 라벨 후보 55행을 split/manifest에 확정 반영.
3. dHash/filename 자동 group을 인간 검수로 확정하고 독립 holdout/test를 확보.
4. small/far 50행과 min-box 9행은 v3.0 제외 상태를 유지한다.
5. 그 후 `datasets/walksafe_kr_v3` 생성, validator, dry-run, smoke 학습 순서로 진행한다.

## 사용자 추가 승인 반영 - bbox 12행 / holdout 후보

- 3번 bbox/파손 검토는 사용자 정성 기준 90%+로 수용했다.
- `manual_bbox_relabel_required`였던 12행은 `bbox_user_review_pass_existing_gt_label`로 전환했다.
- `pred_box_xywhn`는 여전히 라벨로 복사하지 않고, 기존 source GT label을 사용한다.
- v3 train staging 후보는 46행이다: hard-negative 34행 + positive existing GT 12행.
- `VL1+VS1 hard-negative 200`을 holdout 후보로 보존하기 위해, 해당 subset에서 온 hard-negative review 21행은 train staging에서 제외하고 `holdout_reserved_not_train`으로 둔다.
- holdout 후보는 `VL2+VS2` positive 2,082장/5,326 boxes와 `VL1+VS1` negative 200장/0 boxes다.
- 이 holdout 후보는 이미 v2 외부검증/오류분석에 사용되어 최종 blind test로는 약하므로, v3 학습/threshold tuning에 섞지 않는 조건으로만 독립 holdout 후보로 사용한다.
