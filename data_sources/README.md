# 데이터 소스

모델 파이프라인 smoke test와 pretrain 후보를 위해 인증 없이 접근 가능한 공개 데이터셋을 수집한다.

실제 모델 v1의 기본 학습/검증 대상은 한국 보행 환경 데이터셋인 `datasets/walksafe_kr_v1`이다. 이 폴더의 공개 데이터는 최종 validation/test 기준으로 사용하지 않는다.

## 수집 완료

| 원천 데이터셋 | 용도 | 대상 클래스 | 라이선스/비고 |
| --- | --- | --- | --- |
| `jaygala24/pothole-detection` release asset | 포트홀 탐지 | `pothole` | GitHub repository 기준 MIT |
| `LibreYOLO/road-traffic` | 자전거 탐지 보조 | `parked_kickboard_bicycle` | RF100, CC BY 4.0 |
| `Libre-YOLO/street-work` | 공사 콘 탐지 보조 | `construction_obstacle` | RF100, CC BY 4.0 |

## 한국 데이터 후보

한국 기준 모델 v1에 필요한 데이터 후보는 `data_sources/manifests/korean_dataset_candidates.md`에 정리한다.

가장 먼저 필요한 데이터는 `damaged_tactile_block` 한국 직접 촬영 데이터와 정상 점자블록 negative 이미지다.

## 수집 보류

| 데이터셋 | 사유 |
| --- | --- |
| Roboflow Universe `tactile_paving` | 공개 페이지는 있으나 직접 다운로드 URL이 403으로 차단됨 |
| ScooterDet | 다운로드 가능하지만 단일 zip이 약 1.1GB라 v1 baseline 이후 보강 대상으로 둠 |

## 변환

raw 데이터셋을 `datasets/walksafe_v1` YOLO 구조로 변환한다.

```bash
python data_sources/scripts/build_walksafe_v1.py
python model/validate_yolo_dataset.py --data datasets/walksafe_v1/data.yaml
```

생성 데이터는 크고 개인정보/위치정보가 포함될 수 있으므로 Git 추적에서 제외한다.

AI Hub 513 점자블럭 데이터는 `datasets/walksafe_kr_v1`로 변환한다.

```bash
python data_sources/scripts/build_walksafe_kr_tactile.py --dry-run
python data_sources/scripts/build_walksafe_kr_tactile.py
python model/validate_yolo_dataset.py
```
