# 2026-05-20 v3 manual review pack

## 목적

2번 경로(70개 manual review / additional positive 정리)를 진행하기 위해 사용자 검수용 visual review pack을 만들었다.

이 단계에서는 라벨을 자동 확정하지 않았다. review pack은 사람이 `review_decision`을 채우기 위한 보조 산출물이다.

## 대상

| 항목 | 값 |
| --- | ---: |
| rows | 70 |
| unique images | 47 |
| false_positive_extra_box | 47 |
| missed_defect | 20 |
| small_or_far | 3 |

## 산출물

- Review manifest: `data_sources/manifests/walksafe_kr_v3_manual_review_70_manifest_2026-05-20.csv`
- Decision template: `data_sources/manifests/walksafe_kr_v3_manual_review_70_decision_template_2026-05-20.csv`
- Summary JSON: `data_sources/manifests/walksafe_kr_v3_manual_review_70_summary_2026-05-20.json`
- Local review root: `datasets/walksafe_kr_v3_manual_review_20260520`
- Contact sheets:
- `datasets/walksafe_kr_v3_manual_review_20260520/images/contact_sheets/manual_review_contact_sheet_01.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/contact_sheets/manual_review_contact_sheet_02.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/contact_sheets/manual_review_contact_sheet_03.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/contact_sheets/manual_review_contact_sheet_04.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/contact_sheets/manual_review_contact_sheet_05.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/contact_sheets/manual_review_contact_sheet_06.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/contact_sheets/manual_review_contact_sheet_07.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/contact_sheets/manual_review_contact_sheet_08.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/contact_sheets/manual_review_contact_sheet_09.jpg`
- Crop contact sheets:
- `datasets/walksafe_kr_v3_manual_review_20260520/images/crop_contact_sheets/manual_review_crop_contact_sheet_01.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/crop_contact_sheets/manual_review_crop_contact_sheet_02.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/crop_contact_sheets/manual_review_crop_contact_sheet_03.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/crop_contact_sheets/manual_review_crop_contact_sheet_04.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/crop_contact_sheets/manual_review_crop_contact_sheet_05.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/crop_contact_sheets/manual_review_crop_contact_sheet_06.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/crop_contact_sheets/manual_review_crop_contact_sheet_07.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/crop_contact_sheets/manual_review_crop_contact_sheet_08.jpg`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/crop_contact_sheets/manual_review_crop_contact_sheet_09.jpg`

## 검수 기준

`data_sources/manifests/walksafe_kr_v3_manual_review_70_decision_template_2026-05-20.csv`의 `review_decision`에 아래 중 하나를 채운다.

- `accept_existing_gt_positive`: 기존 source label이 학습에 충분함.
- `needs_bbox_relabel`: 파손은 있으나 bbox를 수동 수정해야 함.
- `hard_negative_empty_label`: 파손 점자블럭이 없음. 단, 이미지 전체가 target damage 없음일 때만 사용.
- `exclude_unclear_or_policy`: 불명확하거나 v3.0 정책상 제외.

`bbox_source`는 필요한 경우 `source_label`, `manual_relabel`, `empty`, `exclude` 중 하나를 적는다.

## 주의

- Red box는 triage용 prediction이며, 그대로 label로 복사하지 않는다.
- Green box는 기존 source label이다.
- Yellow box는 review queue의 candidate GT box다.
- 최종 학습 반영은 사용자가 decision template을 채운 뒤 별도 단계에서 수행한다.
