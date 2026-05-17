# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps catch-up execution note (2026-05-17 r1)

## 수행한 작업

- 계획/기준 문서와 최근 daylog, README/model/data 문서를 확인했다.
- `best.pt` / `best.onnx` hash와 Git 대형 산출물 미추적 상태를 재확인했다.
- ONNX full metric equivalence를 test split 전체 `2,347`장으로 실행했다.
  - PT: `P 0.729 / R 0.581 / mAP50 0.657 / mAP50-95 0.481`
  - ONNX: `P 0.731 / R 0.580 / mAP50 0.655 / mAP50-95 0.482`
  - 판정: 계획 기준 내 통과.
- 120장 latency를 측정했다.
  - PT p95 `12.6493ms`
  - ONNX CPU p95 `58.1289ms`
  - 판정: ONNX는 서버 후보로 가능하지만 PT보다 빠르지는 않음. backend ready artifact는 `.pt` 유지.
- VL1+VS1 hard-negative 200장 subset을 생성하고 실제 inference를 실행했다.
  - `conf=0.25`: FP image `32/200`, FP detections `52`
  - `conf=0.35`: FP image `21/200`, FP detections `30`
- 기존 failure 후보 40행을 contact sheet로 시각 검수해 v3 curation action을 붙였다.
  - hard-negative 유지 `16`
  - localization 보정 positive `12`
  - 최소 박스 정책 재검토 `9`
  - small-object augmentation 후보 `3`
- lane 결과 문서 작성: [2026-05-17_model_data_mlops.md](/home/ddobagi/Code/hanium-dreamup/docs/execution/2026-05-17_model_data_mlops.md)

## 변경 파일

- 추가: `docs/execution/2026-05-17_model_data_mlops.md`
- ignored/local 산출물:
  - `runs/benchmark/walksafe_kr_v2_test_latency_20260517/summary.json`
  - `runs/validation/aihub513_vl1_vs1_tactile_hard_negative_200_20260517/**`
  - `runs/validation/aihub513_vl1_vs1_tactile_hard_negative_200_20260517_inference/**`
  - `runs/failure_sampling/walksafe_kr_v2_test_subset_20260516/manual_review_20260517.csv`
  - `runs/failure_sampling/walksafe_kr_v2_test_subset_20260516/manual_review_20260517_summary.json`
  - `runs/failure_sampling/walksafe_kr_v2_test_subset_20260516/review_sheets_20260517/*.jpg`

## 검증

- `df -h /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads`
- `sha256sum best.pt best.onnx`
- `git ls-files '*.pt' '*.onnx' 'runs/**' 'datasets/**/images/**'`
- `.venv/bin/yolo detect val` PT/ONNX test split 전체 실행
- Python latency benchmark 120장 실행
- VL1+VS1 hard-negative 200장 subset build 및 inference 실행
- `model/validate_yolo_dataset.py --data runs/validation/aihub513_vl1_vs1_tactile_hard_negative_200_20260517/data.yaml`
- `rg -n '[[:blank:]]$' docs/execution/2026-05-17_model_data_mlops.md`

## 미완료/확인 필요

- browser/ONNX Runtime Web latency는 미실행.
- VL1+VS1 전체 `1,038`장 hard-negative inference는 보류. 200장에서 이미 명확한 FP 후보가 나왔고, 전체 확장은 약 5GB 이상 추가 복사가 필요함.
- full test split failure sampling 재시도는 미실행. 기존 `exit code 137` 회피용 streaming/저장량 제한 방식이 필요함.
- class `1..3` 한국 GT가 없어 4-class metric은 산출하지 않음.
- contact sheet 검수는 빠른 triage이며 정식 개인정보/위치정보 비식별 검수는 아님.
- daylog는 사용자 지시대로 직접 작성하지 않았고, merge 에이전트가 통합해야 함.

## 병렬 에이전트 활용 메모

- 이번 r1 Vision Model/Data/MLOps 작업에는 하위/병렬 에이전트를 사용하지 않았다.
- 작업 중 다른 lane으로 보이는 worktree 변경이 존재했으나 건드리지 않았다.
- git commit/push는 실행하지 않았다.