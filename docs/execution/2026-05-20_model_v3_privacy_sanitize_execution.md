# WalkSafe v3 privacy sanitize execution

작성일: 2026-05-20

## 사용자 승인 정책 및 최신 수동 검수 결과

- 학습용/staging 복사본 생성: 진행
- EXIF 제거: 진행
- 파일명 sanitize: 진행
- 최종 블러: 불필요
- privacy_hold 39행: 사용자 수동 검수 결과 민감정보 없음, 전부 해제
- 전체 196행 / unique image 126개: `privacy_pass_user_review_no_sensitive_no_blur`
- 독립 holdout/test: 현재 없음

## 실행 범위

원본 이미지는 수정하지 않았다. v3 최종 dataset(`datasets/walksafe_kr_v3`)도 만들지 않았다.
이번 작업은 privacy gate를 닫기 위한 sanitized image staging pool 생성/갱신이다.

## 결과

| 항목 | 값 |
| --- | ---: |
| source rows | 196 |
| unique source images | 126 |
| sanitized unique images created | 126 |
| privacy hold remaining | 0 |
| sanitized EXIF present | 0 |
| face blur count | 0 |
| plate blur count | 0 |

### row 기준 privacy decision

```text
{'privacy_pass_user_review_no_sensitive_no_blur': 196}
```

### unique image 기준 privacy decision

```text
{'privacy_pass_user_review_no_sensitive_no_blur': 126}
```

### Blur

```text
unique_images_with_face_blur=0
unique_images_with_plate_blur=0
row_face_blur_counts={'no_face_blur': 196}
row_plate_blur_counts={'no_plate_blur': 196}
```

## 산출물

- `datasets/walksafe_kr_v3_privacy_sanitized_20260520/images`
- `datasets/walksafe_kr_v3_privacy_sanitized_20260520/BUILD_SUMMARY.md`
- `data_sources/manifests/walksafe_kr_v3_sanitized_unique_images_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_sanitized_image_manifest_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_privacy_audit_final_post_sanitize_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_privacy_sanitized_summary_2026-05-20.json`

## 주의

자동 detector 후보 count는 감사 추적용으로 manifest에 남아 있지만, 최종 privacy decision은 사용자 검수 결과를 따른다. 이번 사용자 수동 검수 결과, 해당 이미지들은 민감정보가 없고 블러도 불필요하다.

## 학습 가능 여부

privacy blocker는 해제됐다. 하지만 아직 v3 학습은 시작하면 안 된다. label relabel, split, 독립 holdout/test gate가 남아 있다.
