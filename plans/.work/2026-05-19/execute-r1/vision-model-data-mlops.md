# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps catch-up execution note (2026-05-19 r1)

## 수행한 작업

- `plans/catchup/2026-05-19.md`, `plans/daily/2026-05-18.md`, 최근 daylog, README/model/data 문서를 확인했다. repo 내부 `AGENTS.md`는 없어서 사용자 제공 지침을 적용했다.
- `model/sample_yolo_failures.py`를 추가해 full test split failure sampling을 이미지 저장 없이 CSV/checkpoint 방식으로 실행할 수 있게 했다.
- v2 test split 80장 balanced smoke를 실행했다.
  - positive/negative: `40/40`
  - GT boxes: `129`
  - predictions: `119`
  - candidate rows: `85`
  - 생성 이미지 파일: `0`
- v3 후보 manifest 2개를 통합해 61행 candidate index와 정책 문서를 만들었다.
- stale 모델 문서에서 VL2+VS2 외부 검증 완료 상태와 현재 남은 model next step을 보정했다.
- daylog는 지침대로 수정하지 않았다.

## 변경 파일

- `model/sample_yolo_failures.py`
- `model/README.md`
- `data_sources/manifests/walksafe_kr_v3_candidate_index_2026-05-19.csv`
- `data_sources/manifests/walksafe_kr_v3_candidate_index_summary_2026-05-19.json`
- `data_sources/manifests/walksafe_kr_v3_policy_2026-05-19.md`
- `data_sources/manifests/korean_dataset_candidates.md`
- `docs/model_training_status.md`
- `docs/model_v2_status.md`
- `docs/current_status.md`
- `docs/execution/2026-05-19_model_data_mlops.md`

로컬 ignored 산출물:

- `runs/failure_sampling/walksafe_kr_v2_test_stream_smoke_20260519/failure_candidates.csv`
- `runs/failure_sampling/walksafe_kr_v2_test_stream_smoke_20260519/checkpoint.json`
- `runs/failure_sampling/walksafe_kr_v2_test_stream_smoke_20260519/summary.json`

## 검증

- `.venv/bin/python -m py_compile model/sample_yolo_failures.py`: PASS
- `.venv/bin/python model/sample_yolo_failures.py ... --max-images 80`: PASS
- `python3 model/validate_yolo_dataset.py --data datasets/walksafe_kr_v2/data.yaml`: PASS
- v3 candidate index CSV load: `61` rows
- sampler CSV load: `85` rows
- sampler output image count: `0`
- `sha256sum best.pt best.onnx`: 기존 hash 유지 확인
- `git ls-files` 대형 산출물 scan: `.pt`, `.onnx`, `runs/**`, v2 dataset images/labels tracked 없음
- `git diff --check`: PASS

## 미완료/확인 필요

- 전체 test split 2,347장 sampling은 실행하지 않았다. 현재 `/` 사용률이 `99%`, 가용 `16G`라 80장 smoke까지만 수행했다.
- VL1+VS1 전체 1,038장 hard-negative inference는 실행하지 않았다.
- browser/ONNX Runtime Web latency는 실행하지 않았다. 브라우저/PWA 런타임 측정 환경이 없어 backend CPU latency와 분리해 보류했다.
- 새 학습은 하지 않았다.
- class `1..3` 한국 GT가 없어 4-class metric은 산출하지 않았다.
- repo와 `/home/ddobagi/Downloads` 검색 기준 AI Hub 159 `Average_stature/out` zip과 직접 촬영 후보 파일은 확인되지 않았다.
- 작업트리에는 내 변경 외 `apps/web/app/page.tsx`, `daylog/2026-05-18.md`, `plans/**`, `product/**` 변경/미추적 파일이 있다. merge 시 변경 주체 구분이 필요하다.

## 병렬 에이전트 활용 메모

- 하위/병렬 에이전트는 사용하지 않았다.
- 같은 파일을 복수 에이전트가 동시에 수정하지 않았고, daylog도 수정하지 않았다.