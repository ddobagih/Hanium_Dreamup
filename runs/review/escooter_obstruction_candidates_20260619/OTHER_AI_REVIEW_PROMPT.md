# 다른 AI에게 줄 전동킥보드 장애물 판정 프롬프트

아래 프롬프트를 그대로 복사해서 사용하세요.

---

너는 보행 안전 객체탐지 데이터셋의 라벨 검수자다. 목표는 `e_scooter_obstruction` 클래스에 넣을 후보만 엄격하게 고르는 것이다.

## 입력

내가 제공하는 자료는 다음과 같다.

- `escooter_obstruction_review_template.csv`
- `contact_sheet_*.jpg`
- 필요하면 개별 `overlays/*.jpg` 또는 `crops/*.jpg`

각 후보는 `candidate_id`로 식별한다. contact sheet와 CSV의 `candidate_id`를 맞춰서 판정해라. 이미지 전체에 다른 킥보드가 보여도, 반드시 해당 `candidate_id`의 빨간 bbox 후보만 판정해라.

## 판정 목표

각 후보가 최종 학습 데이터셋의 `e_scooter_obstruction` positive로 들어가도 되는지 판정한다.

`e_scooter_obstruction`의 의미는 단순히 “킥보드가 있음”이 아니다.

**보행자가 걷는 경로를 막는 전동킥보드/개인형 이동장치 장애물**만 positive다.

## approve 기준

아래 조건을 모두 만족하면 `review_decision`에 `approve`를 넣어라.

보행 경로는 인도 중앙, 횡단보도 입구, 점자블록 위/주변, 건물 출입구, 버스정류장, 경사로, 휠체어/보행자가 지나갈 통로를 포함한다.

- 전동킥보드 또는 유사 PM 객체가 맞다.
- 보도, 인도, 횡단보도 주변, 점자블록 위/주변, 출입구, 정류장, 경사로처럼 보행자가 지나갈 수 있는 경로에 있다.
- 정지 또는 방치 상태로 보인다.
- 보행자가 피해야 할 장애물로 볼 수 있다.
- 표시된 bbox가 객체를 대체로 잘 감싼다.

## reject 기준

아래 중 하나라도 해당하면 `approve`하지 마라. `review_decision`은 `reject`로 써라.

- 사람이 타고 있거나 주행 중으로 보인다.
- 차도나 자전거도로에 있어 보행 장애물이라고 보기 어렵다.
- 너무 멀거나 너무 작아 보행 경고 대상으로 부적합하다.
- 킥보드/PM이 아닌 다른 객체다.
- bbox가 완전히 틀렸고 수정 좌표를 확신할 수 없다.
- 장면만 보고 보행 경로 차단 여부가 애매하다.
- 해당 candidate bbox가 아니라 이미지 안의 다른 킥보드만 장애물로 보인다.

## hold 기준

킥보드 같기는 하지만 positive로 넣기 애매하면 `review_decision`에 `hold`를 넣어라.

예:

- 보도인지 차도인지 불확실함
- 정지/방치인지 주행 중인지 불확실함
- 일부만 보여서 객체 확인이 어려움
- bbox 수정이 필요하지만 정확한 좌표를 못 정함


## 허용 판정값

`review_decision`은 아래 셋 중 하나만 사용해라.

- `approve`
- `reject`
- `hold`

`hold`는 최종 학습에는 들어가지 않는다.

## bbox 수정

bbox가 약간 어긋났지만 객체와 장애물 판정은 확실하면 `approve`해도 된다.
그 경우 아래 컬럼을 수정해라.

- `approved_xmin`
- `approved_ymin`
- `approved_xmax`
- `approved_ymax`

좌표를 정확히 수정할 수 없으면 `hold`로 둬라.

## 출력 형식

가능하면 원본 CSV의 모든 컬럼을 유지한 채 아래 컬럼만 채워서 CSV로 돌려줘라.

- `review_decision`
- `approved_xmin`
- `approved_ymin`
- `approved_xmax`
- `approved_ymax`
- `review_note`

CSV 수정이 어렵다면 아래 표 형식으로만 답해라.

| candidate_id | review_decision | approved_xmin | approved_ymin | approved_xmax | approved_ymax | review_note |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 예시ID | approve/reject/hold | 숫자 | 숫자 | 숫자 | 숫자 | 짧은 이유 |

## 매우 중요한 규칙

- 확실하지 않으면 approve하지 말고 `hold` 또는 `reject`로 둬라.
- 단순 scooter 검출을 positive로 넣지 마라.
- 보행 장애물이라는 판단이 핵심이다.
- 학습 데이터 품질이 중요하므로 recall보다 precision을 우선한다.
- `approve`는 보수적으로 사용해라.
- 모든 행에 `review_note`를 짧게 써라. 예: `보도 위 방치`, `탑승 중`, `차도 위치`, `너무 멀음`, `불명확`.

---

## 내가 원하는 결과

최종적으로 승인된 후보만 `e_scooter_obstruction` 학습 라벨로 사용할 것이다.
그러므로 `approve`는 정말 확실한 것만 골라라.

## 이미지 해상도 사용 지침

- `contact_sheet_*.jpg`, `overlays/`, `crops/`는 빠른 확인용 축소 이미지다.
- 최종 판정은 가능하면 `overlays_fullres/`의 원본 해상도 bbox 이미지를 기준으로 한다.
- 후보가 작거나 애매하면 `originals_fullres/`와 `crops_fullres/`를 함께 확인한다.
- 원본 해상도 이미지에서도 확신이 없으면 `approve`하지 말고 `hold`로 둔다.
