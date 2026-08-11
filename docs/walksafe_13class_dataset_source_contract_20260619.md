# WalkSafe 13클래스 데이터 소스 확정표

갱신일: 2026-06-19

## 원칙

- 학습은 열세 클래스를 한 번에 진행한다.
- `e_scooter_obstruction` 없는 12클래스/부분 모델은 학습하지 않는다.
- `scooter`, `PM`, `twowheeler` 자동 라벨은 곧바로 positive로 쓰지 않는다.
- 전동킥보드 장애물은 보도 위 정지/방치/보행 경로 차단으로 사람이 승인한 행만 학습에 넣는다.

## 클래스 순서

| id | class |
| ---: | --- |
| 0 | `person` |
| 1 | `bicycle` |
| 2 | `car` |
| 3 | `motorcycle` |
| 4 | `bus` |
| 5 | `truck` |
| 6 | `traffic light` |
| 7 | `normal_tactile_block` |
| 8 | `damaged_tactile_block` |
| 9 | `crosswalk` |
| 10 | `curb_step` |
| 11 | `uneven_sidewalk` |
| 12 | `e_scooter_obstruction` |

## 소스별 사용 확정

| 소스 | 현재 상태 | 사용할 클래스 | 변환 정책 | 학습 투입 조건 |
| --- | --- | --- | --- | --- |
| COCO 2017 | `datasets/coco/train2017`, `val2017`, `annotations` 보유 | 일반 객체 7개 | COCO bbox → YOLO bbox | 바로 가능 |
| AIHub 186 베리어프리 실외 | Training/Validation 원천 4개 + 실외 라벨 4개 매칭 100% | 점자블록 정상/파손, 횡단보도, 연석/단차, 노면 불량 | polygon/rectangle segmentation → bbox | 바로 가능, 샘플 시각 검수 필요 |
| AIHub 513 보행 안전 도로시설물 | `TL8/TS8`, `TL9/TS9`, `VL1/VS1`, `VL2/VS2` 매칭 가능 | 점자블록 정상/파손, 연석/단차, 노면 불량 | 기존 513 parser 재사용 | 바로 가능, crosswalk는 현재 source 없음 |
| AIHub 189 인도보행 Surface | 이미지+XML 매칭 가능 | 점자블록 정상/파손, 횡단보도, 노면 불량 | polygon → bbox, MASK 이미지는 제외 | 바로 가능, polygon bbox 과대 여부 검수 필요 |
| AIHub 189 Bbox/Polygon scooter | scooter 후보 150개 | `e_scooter_obstruction` 후보 | 후보 검수팩 생성, 승인 CSV만 사용 | 사람 승인 전 학습 투입 금지 |
| AIHub 614 개인형 이동장치 | 라벨만 있고 원천 없음 | `e_scooter_obstruction` 후보 | source 확보 후 수동 재라벨 | 현재 투입 불가 |
| AIHub 71604 배송로봇 | 라벨만 있고 원천 없음 | 후보 수준 | source 확보 후 실외만 검수 | 현재 투입 불가 |
| AIHub 557 지자체 도로정비 | 라벨만 있고 원천 없음 | 보조 후보 | source 확보 후 보조/hard negative 검토 | 현재 투입 불가 |

## 현재 preflight 결과

스모크 범위에서 `COCO + AIHub 186 + AIHub 189 Surface`만으로 12개 클래스는 train/val 모두 box가 확인됐다.

`e_scooter_obstruction`은 승인 CSV가 없어서 0개이며, 빌더가 엄격 모드로 데이터셋 생성을 중단한다.

결과 파일:

- `data_sources/manifests/walksafe_unified_13class_preflight_coco186189_smoke_20260619.json`
- `runs/review/escooter_obstruction_candidates_20260619/escooter_obstruction_review_template.csv`

## 다음 조건

- `runs/review/escooter_obstruction_candidates_20260619/contact_sheet_*.jpg`를 보고 CSV의 `review_decision`을 채운다.
- 승인값은 `approve` 또는 `approved`를 사용한다.
- bbox 수정이 필요하면 `approved_xmin`, `approved_ymin`, `approved_xmax`, `approved_ymax`를 수정한다.
- 주행 중, 탑승자 있음, 차도/자전거도로, 멀리 있음, 장애물 아님은 approve 금지.
