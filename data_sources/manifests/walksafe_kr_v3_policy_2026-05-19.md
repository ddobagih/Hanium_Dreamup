# WalkSafe KR v3 candidate policy - 2026-05-19

## 범위

이 문서는 v3 학습 데이터셋 생성 전 후보 index와 보류 조건만 정리한다. 새 학습, 이미지 복사, 원본 공개는 수행하지 않았다.

입력 manifest:

- `walksafe_kr_v3_curation_manifest_2026-05-18.csv`: v2 test subset failure 수동 triage 40행
- `walksafe_kr_v3_hard_negative_fp_review_2026-05-18.csv`: VL1+VS1 hard-negative FP review 21행

통합 산출물:

- `walksafe_kr_v3_candidate_index_2026-05-19.csv`
- `walksafe_kr_v3_candidate_index_summary_2026-05-19.json`

## 정책 결정

| policy_decision | rows | 처리 |
| --- | ---: | --- |
| `include_as_hard_negative` | 33 | 정상/마모/그림자 점자블록 오탐 억제용 hard negative 후보로 유지 |
| `include_after_box_review` | 12 | positive 유지 가능하지만 bbox 위치/크기 수동 보정 후 포함 |
| `hold_until_min_box_policy_review` | 9 | 너무 작거나 먼 객체 기준 확정 전 학습 포함 보류 |
| `include_with_small_object_augmentation` | 3 | small object augmentation 후보로 포함 가능 |
| `include_as_hard_negative_and_prioritize_threshold_augmentation_review` | 4 | hard negative로 유지하되 threshold/augmentation 우선 검토 |

## 학습 gate

- `hold_until_min_box_policy_review` 9행은 min-box 정책을 정하기 전 v3 train에 넣지 않는다.
- 모든 후보는 contact sheet 또는 review sheet 수준의 빠른 검수만 완료됐다. source-level 개인정보/위치정보 audit 전에는 공유하지 않고 local-only로 둔다.
- 동일 장소/연속 프레임으로 보이는 후보는 train/val/test에 섞지 않는다.
- v3 후보 index는 class `0 damaged_tactile_block` 개선용이다. class `1..3` 서비스 metric 근거로 사용하지 않는다.
