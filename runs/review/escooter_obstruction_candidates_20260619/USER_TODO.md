# 사용자가 해야 할 일

## 확인할 파일

- 전체 보기: `contact_sheet_01.jpg` ~ `contact_sheet_07.jpg`
- 후보 CSV: `escooter_obstruction_review_template.csv`
- 더 자세히 볼 때:
  - `overlays/`
  - `crops/`

## 다른 AI에게 넘길 것

가장 간단한 방법:

- `OTHER_AI_REVIEW_PROMPT.md` 내용 복사
- `escooter_obstruction_review_template.csv` 업로드
- `contact_sheet_01.jpg`부터 `contact_sheet_07.jpg`까지 업로드

정확도를 더 높이는 방법:

- contact sheet로 1차 판정
- 애매한 candidate_id는 `overlays/후보번호_candidate_id.jpg`와 `crops/후보번호_candidate_id.jpg`를 추가 업로드

## 다른 AI가 돌려줘야 하는 것

둘 중 하나면 된다.

- 수정된 `escooter_obstruction_review_template.csv`
- 또는 `candidate_id`, `review_decision`, `approved_xmin`, `approved_ymin`, `approved_xmax`, `approved_ymax`, `review_note`가 있는 표

## 판정값

- `approve`: 최종 학습 positive로 넣을 전동킥보드 보행 장애물
- `reject`: 학습 positive로 쓰면 안 됨
- `hold`: 애매해서 지금은 쓰지 않음

## 주의

- `approve`만 최종 데이터셋에 들어간다.
- `reject`와 `hold`는 최종 positive에 들어가지 않는다.
- 확실하지 않으면 `approve`하지 않는 것이 맞다.
