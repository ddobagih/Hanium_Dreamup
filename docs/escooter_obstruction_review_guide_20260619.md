# 전동킥보드 장애물 후보 검수 가이드

갱신일: 2026-06-19

## 검수 파일

- 이미지 contact sheet: `runs/review/escooter_obstruction_candidates_20260619/contact_sheet_*.jpg`
- overlay: `runs/review/escooter_obstruction_candidates_20260619/overlays/`
- crop: `runs/review/escooter_obstruction_candidates_20260619/crops/`
- CSV: `runs/review/escooter_obstruction_candidates_20260619/escooter_obstruction_review_template.csv`

## approve 기준

`review_decision`에 `approve`를 넣는 경우:

- 전동킥보드 또는 PM이 보도/보행 경로에 있다.
- 정지/방치 상태로 보인다.
- 보행자가 지나갈 경로를 막거나 피해야 할 장애물이다.
- bbox가 객체를 충분히 감싼다.

## reject 기준

`review_decision`을 비우거나 `reject`로 둔다.

- 탑승자가 타고 주행 중이다.
- 차도나 자전거도로에 있어 보행 장애물이 아니다.
- 너무 멀거나 너무 작아 경고 대상으로 부적합하다.
- 킥보드가 아니라 다른 객체다.
- 화면/라벨이 애매해서 positive로 넣기 어렵다.

## bbox 수정

기본 bbox가 맞지 않으면 아래 컬럼을 고친다.

- `approved_xmin`
- `approved_ymin`
- `approved_xmax`
- `approved_ymax`

수정한 행만 `approve`한다.

## 중요

검수 CSV에서 승인된 행만 최종 13클래스 데이터셋의 `e_scooter_obstruction`으로 들어간다.
승인 행이 없으면 빌더는 데이터셋 생성을 중단하고 학습으로 넘어가지 않는다.
