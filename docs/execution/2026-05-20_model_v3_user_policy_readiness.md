# WalkSafe v3 user policy readiness update

작성일: 2026-05-20

## 반영한 사용자 결정

```text
1. sanitized copy 생성 / EXIF 제거 / filename sanitize 유지
2. 1,2,3 전체에 대해 최종 블러 불필요
3. 기존 privacy_hold 39개는 민감정보 없음으로 전부 해제
4. hard-negative 성격 묶음은 파손 점자블럭 없음(빈 라벨 후보)
5. 파손 탐지는 정성 검수상 대체로 잘 맞고 90% 이상으로 보임
6. small/far 50개는 v3.0 제외 + 별도 실험군
7. min-box 9개는 v3.0 제외
8. 독립 holdout/test는 현재 없음
```

## 현재 readiness

```text
privacy_final_decision_counts={'privacy_pass_user_review_no_sensitive_no_blur': 196}
label_policy_decision_counts={
  'no_damaged_tactile_block_confirmed_hard_negative': 55,
  'manual_visual_review_required': 70,
  'manual_bbox_relabel_required': 12,
  'exclude_v3_0_small_far_policy': 50,
  'exclude_v3_0_min_box_policy': 9
}
v3_0_readiness_counts={
  'staging_ready_pending_holdout': 55,
  'not_train_ready': 141
}
official_full_train_eval_ready_rows=0
```

## 결론

Privacy blocker는 사용자 수동 검수로 해제됐다. 196행 모두 민감정보 없음/블러 불필요 상태로 정리했고, sanitized image는 EXIF 제거와 sanitized filename만 유지한다.

Hard-negative 55행은 사용자 검수상 파손 점자블럭 없음으로 확인되어 빈 라벨 staging 후보가 됐다. 다만 독립 holdout/test가 없으므로 공식 full-train/eval ready는 0행이다.

사용자 정성 판단으로 파손 탐지는 전반적으로 잘 맞고 90% 이상으로 보이나, 이는 spot-check 성격이다. 독립 holdout/test metric이나 공식 성능 수치처럼 사용하면 안 된다.

## 다음 gate

1. bbox relabel 12행 수정
2. duplicate/positive/manual review 70행 결정
3. hard-negative 빈 라벨 후보 55행을 split/manifest에 확정 반영
4. 독립 holdout/test 수집 또는 명시적 보류 결정

## 사용자 추가 승인 반영 - bbox 12행 / holdout 후보

- 3번 bbox/파손 검토는 사용자 정성 기준 90%+로 수용했다.
- `manual_bbox_relabel_required`였던 12행은 `bbox_user_review_pass_existing_gt_label`로 전환했다.
- `pred_box_xywhn`는 여전히 라벨로 복사하지 않고, 기존 source GT label을 사용한다.
- v3 train staging 후보는 46행이다: hard-negative 34행 + positive existing GT 12행.
- `VL1+VS1 hard-negative 200`을 holdout 후보로 보존하기 위해, 해당 subset에서 온 hard-negative review 21행은 train staging에서 제외하고 `holdout_reserved_not_train`으로 둔다.
- holdout 후보는 `VL2+VS2` positive 2,082장/5,326 boxes와 `VL1+VS1` negative 200장/0 boxes다.
- 이 holdout 후보는 이미 v2 외부검증/오류분석에 사용되어 최종 blind test로는 약하므로, v3 학습/threshold tuning에 섞지 않는 조건으로만 독립 holdout 후보로 사용한다.
