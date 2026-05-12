# walksafe_v1 공개 baseline 데이터 소스 매니페스트

작성 기준일: 2026-05-11

`walksafe_v1`은 해외 공개 데이터 기반 smoke/pretrain 후보 데이터셋이다. 실제 모델 v1의 기본 학습/검증 대상은 한국 보행 환경 데이터셋인 `datasets/walksafe_kr_v1`이다.

## 변환 매핑

| source | source class | target class id | target class |
| --- | --- | --- | --- |
| `LibreYOLO/road-traffic` | `bicycles` | 1 | `parked_kickboard_bicycle` |
| `Libre-YOLO/street-work` | `Cone` | 2 | `construction_obstacle` |
| `jaygala24/pothole-detection` | `0` | 3 | `pothole` |

## 비어 있는 클래스

| target class id | target class | 이유 |
| --- | --- | --- |
| 0 | `damaged_tactile_block` | 인증 없이 받을 수 있는 적합한 공개 YOLO 데이터셋을 확보하지 못함 |

## 품질 주의사항

- `road-traffic`의 `bicycles`는 방치 킥보드까지 포함하지 않는다. v1에서는 자전거형 보행 장애물 보조 데이터로만 사용한다.
- `street-work`의 `Cone`은 공사 구조물 전체가 아니라 안전콘 중심 데이터다. 실제 공사 펜스, 적치물은 직접 촬영 데이터가 필요하다.
- Pothole Dataset은 도로 이미지 중심이라 보행자 1인칭 인도 환경과 도메인이 다를 수 있다.
- 최종 모델 정확도는 공개 데이터 baseline만으로 판단하지 말고 한국 직접 촬영 또는 한국 인도보행 validation set으로 다시 평가해야 한다.
