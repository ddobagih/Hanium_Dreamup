# 2026-05-20 v2 baseline report

## 범위

이 문서는 WalkSafe Assist 점자블록 class `0: damaged_tactile_block` 중심 v2 baseline을 고정하기 위한 요약이다. 새 검증이나 새 학습은 실행하지 않았고, 기존 문서/로그에 기록된 수치와 로컬 artifact hash를 정리했다.

## artifact

| 항목 | 값 |
| --- | --- |
| run | `runs/detect/walksafe_kr_tactile_v2_full` |
| backend-ready weight | `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt` |
| last weight | `runs/detect/walksafe_kr_tactile_v2_full/weights/last.pt` |
| ONNX | `runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx` |
| data yaml | `datasets/walksafe_kr_v2/data.yaml` |
| log | `logs/walksafe_kr_tactile_v2_full.log` |

Hash:

| artifact | sha256 |
| --- | --- |
| `best.pt` | `02a6be87626e9ba00bb72715d45d8c27d06103d851882a08f404260e453d8e94` |
| `best.onnx` | `c21f47013ad340761a2743bc20ba36da8aa4110c40a16bb0ddf7b6913858efe7` |

## dataset 규모

`datasets/walksafe_kr_v2`는 AI Hub 513 `TL8/TL9/TS8/TS9` 전체를 사용한 class `0` 중심 데이터셋이다.

| split | images | labels | positive images | negative images | boxes |
| --- | ---: | ---: | ---: | ---: | ---: |
| train | 16,433 | 16,433 | 8,341 | 8,092 | 28,096 |
| val | 4,695 | 4,695 | 2,383 | 2,312 | 8,086 |
| test | 2,347 | 2,347 | 1,191 | 1,156 | 3,979 |

## v2 metric

Validation `best.pt`:

| metric | value |
| --- | ---: |
| precision | 0.73656 |
| recall | 0.58380 |
| mAP50 | 0.66394 |
| mAP50-95 | 0.49194 |

Test split:

| metric | value |
| --- | ---: |
| images | 2,347 |
| instances | 3,979 |
| precision | 0.728 |
| recall | 0.581 |
| mAP50 | 0.657 |
| mAP50-95 | 0.481 |

판정:

- v2는 baseline으로 동결 가능하다.
- recall `0.581`, mAP50-95 `0.481`이라 최종 서비스 모델로 보기는 부족하다.
- class `1..3` 한국 GT가 없어 4-class metric으로 해석하지 않는다.
- v3 평가에서 v2 test failure 후보를 train에 사용하면 기존 v2 test metric은 v3 개선 근거로 재사용하지 않는다.

## ONNX / latency

| 항목 | 값 |
| --- | ---: |
| PT test mAP50-95 | 0.481 |
| ONNX test mAP50-95 | 0.482 |
| PT p95 latency | 12.6493ms |
| ONNX Runtime CPU p95 latency | 58.1289ms |

현재 backend-ready artifact는 `.pt`로 유지한다. browser/ONNX Runtime Web과 모바일 latency는 아직 미검증이다.

## hard-negative / external validation

- AI Hub 513 `VL2+VS2` tactile positive subset class `0` 외부 검증 완료.
- AI Hub 513 `VL1+VS1` hard-negative 200장 평가 완료.
- conf `0.35` 기준 FP image `21/200`, FP detections `30`.
- 위 hard-negative 평가는 정상 점자블록 false positive 평가이며 recall/mAP 평가가 아니다.

## 과대해석 금지

- v2 metric은 class `0 damaged_tactile_block` baseline으로만 본다.
- 4개 위험 클래스 전체 성능으로 말하지 않는다.
- 목표 “정확도 90%”를 달성한 상태가 아니다.
- fake detector, PWA smoke, server adapter smoke와 모델 성능 metric을 섞지 않는다.
