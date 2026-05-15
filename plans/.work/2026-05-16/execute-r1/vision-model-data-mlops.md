# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps catch-up execution note (2026-05-16 r1)

## 수행한 작업

- 2026-05-16 catch-up, 2026-05-15 daily/daylog, README, 모델 관련 execution 문서를 확인하고 Vision Model/Data/MLOps lane 미완료 항목만 수행.
- v2 모델 handoff 표 작성: `MODEL_ARTIFACT_PATH`, `MODEL_VERSION`, class order, threshold, `best.pt`/`best.onnx` hash, ONNX 제한 문구 정리.
- failure bucket 5종 정의 및 `walksafe_kr_v2` test subset 기반 failure 후보 CSV 생성.
- VL1+VS1 hard-negative dry-run 재확인 및 실행 계획 정리.
- v3/v4 backlog를 class 0 보정과 class 1~3 한국 데이터 보강으로 분리.

## 변경 파일

- `docs/execution/2026-05-16_model_data_mlops.md`
- ignored 산출물:
  - `runs/failure_sampling/walksafe_kr_v2_test_subset_20260516/failure_candidates.csv`
  - `runs/failure_sampling/walksafe_kr_v2_test_subset_20260516/summary.json`

## 검증

- `df -h`: `/` 가용 `18G`, 사용률 `98%`.
- `sha256sum`: `best.pt`, `best.onnx` hash 확인.
- `.venv` 모델 패키지 확인: `ultralytics 8.4.48`, `torch 2.11.0+cu130`, `onnxruntime 1.26.0`.
- full test split failure sampling은 exit code `137`로 중단.
- seed 고정 subset sampling 성공:
  - sample `360`장, GT box `594`
  - candidate `264`행
  - review CSV `40`행
  - bucket: `false_positive_normal_tactile 16`, `missed_defect 12`, `small_or_far 12`
- VL1+VS1 dry-run 성공:
  - tactile filenames `1,038`
  - positive `0`, negative `1,038`, boxes `0`
- CSV/JSON 무결성 확인: summary `40`행과 CSV `40`행 일치.
- `git diff --check`: 통과.

## 미완료/확인 필요

- ONNX full metric equivalence는 실행하지 않음. 현재 근거는 export/raw tensor smoke/30장 CPU latency smoke까지.
- browser/ONNX Runtime Web latency는 실행하지 않음.
- class `1..3` 한국 GT가 없어 4-class metric과 `new_class_gap` 실제 샘플 CSV는 생성하지 못함.
- 생성 CSV는 자동 후보라 수동 시각 검수 및 개인정보/위치정보 비식별 검토 필요.
- daylog는 사용자 지시대로 수정하지 않았고, merge 에이전트가 통합 기록해야 함.

## 병렬 에이전트 활용 메모

- 이번 r1 수행에서는 하위/병렬 에이전트를 새로 사용하지 않음.
- 변경 범위가 단일 execution 문서와 ignored failure sampling 산출물로 좁아, 동시 파일 수정 없이 직접 수행함.