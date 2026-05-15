# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps catch-up lane note (2026-05-15)

## 확인한 근거

- `plans/daily/2026-05-14.md`는 없음. 대신 `docs/model_training_3day_execution_plan.md`, `plans/.work/2026-05-14/daily/vision-model-data-mlops.md`, `plans/daily/2026-05-15.md`를 lane 계획 근거로 확인함.
- `daylog/`는 비어 있음. `docs/execution/*2026-05-15*` 문서도 없음. 따라서 2026-05-15 새벽 추가 완료 근거는 확인되지 않음.
- 주요 실행 근거: `docs/execution/2026-05-14_model.md`, `2026-05-14_dataset_discovery.md`, `2026-05-14_vlvs_dry_run.md`, `2026-05-14_external_validation_subset.md`, `2026-05-14_detect_adapter_implementation.md`, `2026-05-14_next_step_parallel.md`, `2026-05-14_cleanup_candidates.md`.
- 실제 파일 확인 기준 `LOCAL_DATASETS/raw_downloads`에는 `TL8/TL9/TS8/TS9/VL1/VL2/VS1/VS2.zip` symlink가 존재함.
- `runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx`는 없음.
- 읽기 전용 확인 작업이라 daylog는 새로 작성하지 않음.

## 완료로 판단한 항목

- v2 artifact 존재 확인, hash manifest 검증, `datasets/walksafe_kr_v2/data.yaml` 구조 검증은 완료.
- v2 `best.pt` test split 결과 확인 완료: precision `0.728`, recall `0.581`, mAP50 `0.657`, mAP50-95 `0.481`.
- v2는 test split 기준 `baseline_frozen` 가능으로 판단됐으나, 서비스 최종 모델 확정은 아님.
- AI Hub 513 raw zip 8개 발견 및 로컬 symlink 정리 완료.
- VL1+VS1, VL2+VS2 dry-run 검사 완료. VL1+VS1은 positive 0개, VL2+VS2는 positive 1,578장/box 5,326개.
- VL2+VS2 tactile subset 외부 validation 완료: precision `0.751`, recall `0.605`, mAP50 `0.680`, mAP50-95 `0.501`.
- 백엔드 `/detect` Ultralytics `.pt` adapter 1차 구현 및 로컬 smoke 완료. 샘플 `/detect` 결과는 `source: "server"`, detection 4개로 기록됨.
- PWA `NEXT_PUBLIC_DETECTOR_MODE=server` wiring은 lint/typecheck 기준 완료. 단, 브라우저/실폰 수동 검증은 대기.

## 미완료 작업 후보

- [ ] 최신 디스크 상태 확인 및 정리 승인 분리 → 이유/근거: 5/14 문서에 `/` 여유 공간 `25G`와 subset 이후 `6.6G` 기록이 혼재하며, 실제 5/15 재측정 근거가 없음.
- [ ] 삭제/이동 후보 승인 처리 → 이유/근거: `datasets/walksafe_kr_v1`, `TS8/TS9.zip`, `VS1/VS2.zip`, `runs/validation` 정리 후보만 문서화됐고 승인/실행 근거 없음.
- [ ] v2 val/test/external metric 비교표 작성 → 이유/근거: 수치는 여러 문서에 흩어져 있으나 lane 결과표로 통합된 근거 없음.
- [ ] v2 판정 및 class coverage handoff 확정 → 이유/근거: class 0 baseline 한계는 기록됐지만 `MODEL_VERSION`, threshold, class order, coverage 제한을 공유 형식으로 고정한 문서는 없음.
- [ ] 실패 프레임 bucket 확정 및 30장 이상 샘플링 → 이유/근거: bucket 기준은 계획에 있으나 실제 실패 후보 목록, source, action 기록 없음.
- [ ] VL1+VS1 hard-negative false positive 평가 → 이유/근거: dry-run으로 positive 0개는 확인됐지만 v2 추론 기반 오탐 수는 아직 없음.
- [ ] ONNX export 준비 및 동등성 계획 확인 → 이유/근거: `best.onnx` 부재. export, hash, PT/ONNX metric/latency 검증 근거 없음.
- [ ] v3/v4 데이터 보강 후보 정리 → 이유/근거: class 1~3 한국 데이터 부족은 상태 문서에 있으나 다음 수집/라벨링 후보가 실행 결과로 정리되지 않음.
- [ ] PWA server detector 실브라우저 smoke → 이유/근거: wiring은 구현됐지만 카메라 프레임 `/detect` 호출, bbox 표시, `source: "server"` 신고 저장은 수동 검증 대기.

## 오늘 catch-up 후보 스케줄

- [ ] 디스크/보존 게이트 확인 → 검증: `df -h`, 주요 경로 용량, `best.pt/last.pt/results.csv/args.yaml/data.yaml/SHA256SUMS.txt` 존재 여부 기록.
- [ ] v2 지표 비교표 작성 → 검증: validation, test, VL2+VS2 external subset의 precision/recall/mAP50/mAP50-95를 한 표로 정리.
- [ ] 모델 handoff 값 확정 → 검증: `MODEL_ARTIFACT_PATH`, `MODEL_VERSION=walksafe-kr-tactile-v2-full-20260514-best-02a6be87`, class order, threshold 후보, class 0 중심 제한을 기록.
- [ ] 실패 프레임 샘플링 기준 고정 → 검증: `missed_defect`, `false_positive_normal_tactile`, `low_light_or_blur`, `small_or_far`, `new_class_gap` 정의를 결과 로그에 남김.
- [ ] 실패 후보 30장 이상 수집 → 검증: 각 후보에 source, expected class, failure type, action, 비식별 필요 여부 기록.
- [ ] VL1+VS1 hard-negative 평가 가능성 확인 또는 실행 → 검증: 공간 확보 방식과 false positive count, 산출물 크기 기록.
- [ ] ONNX export 준비 상태 확인 → 검증: `best.onnx` 부재, 필요 패키지, 출력 경로, 예상 용량, PT vs ONNX 비교 절차 기록.
- [ ] v3/v4 보강 backlog 정리 → 검증: class 0 실패 보정과 class 1~3 한국 데이터 부족 보강 후보를 분리해 다음 작업으로 남김.

## 확인 필요

- `plans/daily/2026-05-14.md`가 없어 merge 시 기준 계획 파일 누락을 명시해야 함.
- 2026-05-15 daylog/execution 문서가 없어 새벽 작업 완료로 볼 근거는 없음.
- `MODEL_VERSION`은 adapter smoke에서 `best`로 반환된 기록이 있으므로, 해시 포함 version 값으로 고정할지 확인 필요.
- VL2+VS2 결과는 class `0 damaged_tactile_block` subset 검증이다. 4-class 서비스 성능 근거로 쓰면 안 됨.
- 추가 validation/export/failure sampling 전 디스크 여유 공간 재확인이 필요함.

## 병렬 에이전트 활용 메모

- 하위/병렬 에이전트는 사용하지 않음.
- 확인 범위가 문서와 가벼운 파일 존재 확인 중심이라 단일 에이전트에서 통합 판단함.