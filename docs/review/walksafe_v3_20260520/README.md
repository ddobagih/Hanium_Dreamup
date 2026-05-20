# WalkSafe v3 review package — 2026-05-20

이 폴더는 사용자가 외부에서 GitHub로 확인할 수 있게 만든 **축소 리뷰 패키지**입니다.
원본 이미지나 전체 sanitized dataset은 올리지 않았습니다.

## 폴더

1. `01_privacy_hold/`
   - 대상: 39 rows
   - 볼 것: 얼굴/번호판/민감 위치가 blur 후에도 남아 있는지
   - 결정: `pass`, `drop`, `hold`

2. `02_hard_negative_candidates/`
   - 대상: 37 rows
   - 볼 것: 진짜 파손 점자블럭이 없는 정상 이미지인지
   - 결정: `hard_negative`, `positive`, `drop`, `hold`

3. `03_bbox_relabel_required/`
   - 대상: 12 rows
   - 볼 것: 빨간 박스가 파손/단절 점자블럭만 잘 감싸는지
   - 결정: `relabel_done`, `drop`, `hold`

## 답변 예시

```text
privacy_hold:
v3-review-001 pass
v3-review-002 drop

hard_negative:
v3-review-010 hard_negative
v3-review-011 positive

bbox:
v3-review-020 hold
v3-review-021 relabel_done
```

주의: bbox는 GitHub 이미지만 보고 정확한 좌표 수정까지 하기 어렵습니다. `relabel_done`은 별도 라벨링 툴에서 수정 완료했을 때만 쓰는 것이 안전합니다.
