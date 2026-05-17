# YOLO 모델 및 데이터셋 진행 상태

작성 기준일: 2026-05-13 KST

## 2026-05-18 보정 요약

- v2 `best.pt` test split 별도 검증은 완료됐다. test split 2,347장 기준 precision `0.728`, recall `0.581`, mAP50 `0.657`, mAP50-95 `0.481`이다.
- `best.onnx` full metric equivalence도 완료됐다. test split 기준 PT mAP50-95 `0.481`, ONNX mAP50-95 `0.482`로 계획 기준을 만족했다.
- PT/ONNX latency는 test split 120장으로 측정됐다. PT p95 `12.6493ms`, ONNX Runtime CPU p95 `58.1289ms`라 backend ready artifact는 계속 `.pt`로 둔다.
- VL1+VS1 hard-negative 200장 평가와 상위 FP visual review가 완료됐다. conf `0.35` 기준 FP image `21/200`, FP detections `30`이다.
- v3 후보 manifest는 `data_sources/manifests/walksafe_kr_v3_curation_manifest_2026-05-18.csv`에 정리했다.

## 범위

이 문서는 로컬에 생성된 데이터셋과 학습 결과를 요약한다. AI Hub 원본 zip, 데이터셋 이미지/라벨, `runs/`, `.pt` 파일은 GitHub에 올리지 않고 로컬에서만 보관한다.

## 클래스

| id | class_name | 현재 데이터 상태 |
| ---: | --- | --- |
| 0 | `damaged_tactile_block` | AI Hub 513 점자블록 데이터로 v1/v2 학습 완료 |
| 1 | `parked_kickboard_bicycle` | 해외 공개 보조 데이터 일부만 있음, 한국 데이터 보강 필요 |
| 2 | `construction_obstacle` | 해외 공개 안전콘 중심, 한국 공사물 보강 필요 |
| 3 | `pothole` | 해외 공개 포트홀 중심, 한국 보도/이면도로 보강 필요 |

현재 실제 한국 학습은 `damaged_tactile_block` 단일 클래스 성격이다. `data.yaml`은 4개 클래스 계약을 유지하지만, v1/v2 AI Hub 513 학습 결과의 실질 평가는 class 0 기준으로 봐야 한다.

## 로컬 데이터셋

### `datasets/walksafe_kr_v1`

AI Hub 513 `TL8/TL9/TS8/TS9`에서 균형 샘플링으로 만든 1차 데이터셋이다.

| split | images | labels | positive images | negative images | boxes |
| --- | ---: | ---: | ---: | ---: | ---: |
| train | 4,200 | 4,200 | 2,100 | 2,100 | 7,072 |
| val | 1,200 | 1,200 | 600 | 600 | 2,037 |
| test | 600 | 600 | 300 | 300 | 1,026 |

검증: `python model/validate_yolo_dataset.py` 통과.

### `datasets/walksafe_kr_v2`

AI Hub 513 `TL8/TL9/TS8/TS9` 전체를 사용한 2차 데이터셋이다.

| split | images | labels | positive images | negative images | boxes |
| --- | ---: | ---: | ---: | ---: | ---: |
| train | 16,433 | 16,433 | 8,341 | 8,092 | 28,096 |
| val | 4,695 | 4,695 | 2,383 | 2,312 | 8,086 |
| test | 2,347 | 2,347 | 1,191 | 1,156 | 3,979 |

검증: `python model/validate_yolo_dataset.py --data datasets/walksafe_kr_v2/data.yaml` 통과.

주의: 로컬 파일 스캔 기준 v2 val boxes는 `8,086`개이고, Ultralytics 학습 로그의 validation instances는 `8,085`개로 1개 차이가 있다. 별도 test 검증 전에 원인 확인이 필요하다.

## 학습 결과

### v1 balanced baseline

- run: `runs/detect/walksafe_kr_tactile_v1`
- weights: `runs/detect/walksafe_kr_tactile_v1/weights/best.pt`, `last.pt`
- 최종/대표 지표:
  - precision: `0.693`
  - recall: `0.543`
  - mAP50: `0.605`
  - mAP50-95: `0.439`
- `results.csv` 기준 best mAP50-95는 epoch 49에서 약 `0.43879`

### v2 full training

- run: `runs/detect/walksafe_kr_tactile_v2_full`
- log: `logs/walksafe_kr_tactile_v2_full.log`
- weights: `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt`, `last.pt`
- 최종 epoch: `50/50`
- best epoch: `50`
- best.pt validation 지표:

| metric | value |
| --- | ---: |
| precision | 0.73656 |
| recall | 0.58380 |
| mAP50 | 0.66394 |
| mAP50-95 | 0.49194 |

v2는 v1 대비 validation 기준 mAP50-95가 약 `+0.05315`, mAP50이 약 `+0.05652` 개선됐다.

`results.csv` 마지막 5줄:

```csv
46,10565.3,0.83502,0.8827,1.00519,0.73547,0.58185,0.66289,0.49003,0.62905,5.49378,0.60385,0.00327,0.00109,0.00327,0.00109,0.00327,0.00109,0.00327,0.00109
47,10786.3,0.83778,0.86456,1.00799,0.73407,0.58108,0.66384,0.49016,0.62767,5.44989,0.60284,0.002676,0.000892,0.002676,0.000892,0.002676,0.000892,0.002676,0.000892
48,11008.6,0.83044,0.85646,1.00421,0.73437,0.58131,0.66337,0.49036,0.628,5.57374,0.60281,0.002082,0.000694,0.002082,0.000694,0.002082,0.000694,0.002082,0.000694
49,11232,0.82751,0.83727,1.00168,0.73878,0.58278,0.66427,0.49144,0.62832,5.71713,0.60313,0.001488,0.000496,0.001488,0.000496,0.001488,0.000496,0.001488,0.000496
50,11454,0.82767,0.84738,1.00444,0.73656,0.5838,0.66394,0.49194,0.62816,5.77851,0.60306,0.000894,0.000298,0.000894,0.000298,0.000894,0.000298,0.000894,0.000298
```

생성 확인:

| artifact | local path |
| --- | --- |
| `confusion_matrix.png` | `runs/detect/walksafe_kr_tactile_v2_full/confusion_matrix.png` |
| `results.png` | `runs/detect/walksafe_kr_tactile_v2_full/results.png` |
| `best.pt` | `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt` |
| `last.pt` | `runs/detect/walksafe_kr_tactile_v2_full/weights/last.pt` |

## 남은 검증

- AI Hub 513 `VL2+VS2` tactile subset 외부 검증과 class `1..3` 한국 GT validation
- 실제 보행자 시점 영상 또는 직접 촬영 이미지에서 실패 프레임 추출
- browser/ONNX Runtime Web 또는 모바일 추론 지연 측정
- full test split failure sampling 재시도. 기존 대형 실행은 exit code `137` 기록이 있어 streaming/저장량 제한 방식이 필요하다.

## 판단

v2는 v1보다 지표가 개선됐지만, 아직 목표인 mAP 0.9와는 거리가 있다. 현재 수치는 한국 점자블록 데이터 한 축의 baseline으로는 유효하지만, 실제 서비스 모델로 보기에는 부족하다. 바로 파인튜닝 범위를 키우기보다 test split과 외부 validation으로 과적합 여부를 먼저 확인해야 한다.

추가 epoch만 반복하는 것보다 다음 작업이 우선이다.

1. AI Hub validation set 외부 검증 확장
2. 실패 프레임 수집과 라벨 품질 점검
3. 한국 보행자 시점 직접 촬영 데이터 보강
4. 4개 클래스 통합 학습 데이터 확보
5. browser/mobile latency 검증
