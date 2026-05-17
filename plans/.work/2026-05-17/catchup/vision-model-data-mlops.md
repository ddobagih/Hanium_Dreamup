# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps catch-up lane note (2026-05-17)

## 확인한 근거

- `plans/daily/2026-05-16.md`: Vision Model/Data/MLOps 체크리스트 확인.
- `daylog/2026-05-16.md`: model subset failure sampling, VL1+VS1 dry-run, handoff 문서화, 미완료 항목 확인.
- `docs/execution/2026-05-16_model_data_mlops.md`: handoff 표, metric/export 제한, failure bucket, VL1+VS1 계획, v3/v4 backlog 확인.
- `docs/execution/2026-05-16_integration_field_report.md`: fake/server/STT/field 근거 분리와 5/17 후속 후보 확인.
- `docs/execution/2026-05-15_model_validation.md`: v2 artifact/hash, metric 표, external subset 한계 확인.
- `docs/execution/2026-05-15_model_onnx_followup.md`: `best.onnx` export, raw PT/ONNX smoke, 30장 CPU latency 확인.
- `daylog/2026-05-17.md`, `docs/execution/2026-05-17*.md`: 현재 확인되지 않음.

## 완료로 판단한 항목

- 디스크/보존 artifact 확인: `/` 가용 `18G`, `best.pt`, `last.pt`, `results.csv`, `args.yaml`, `data.yaml`, `SHA256SUMS.txt` 확인.
- v2 metric 비교표 작성: validation/test/VL2+VS2 external precision, recall, mAP50, mAP50-95 정리.
- 모델 handoff 값 확정: `MODEL_ARTIFACT_PATH`, `MODEL_VERSION`, class order, threshold 후보, `best.pt`/`best.onnx` hash 정리.
- ONNX export 수행: `best.onnx` 생성 및 sha256 기록, Git ignored 확인.
- PT/ONNX raw tensor smoke: `allclose` 통과 기록 있음.
- 실패 bucket 정의 및 subset failure sampling: 360장 subset에서 review CSV 40행 생성.
- VL1+VS1 hard-negative dry-run: positive `0`, negative `1,038`, selected negative sample `10` 확인.
- v3/v4 backlog 분리: v3는 class 0 보정, v4는 class 1~3 한국 데이터 보강으로 정리.
- Model 결과 문서 작성: `docs/execution/2026-05-16_model_data_mlops.md` 작성 완료.

## 미완료 작업 후보

- [ ] ONNX full metric equivalence → 이유/근거: raw tensor smoke만 있음. test split 기준 mAP50-95, precision, recall의 PT/ONNX 차이 검증은 미실행.
- [ ] PT/ONNX 확대 latency 측정 → 이유/근거: 30장 CPU smoke만 있음. 계획표의 100~200장 기준과 browser/ONNX Runtime Web latency는 미실행.
- [ ] VL1+VS1 hard-negative 실제 inference → 이유/근거: dry-run과 실행 계획은 있으나 false positive count, confidence 분포, 상위 후보 CSV는 없음.
- [ ] full test split failure sampling 재시도 → 이유/근거: 전체 test split inference는 exit code `137`로 중단. subset 40행은 생성됐지만 full 결과는 없음.
- [ ] failure sampling CSV 수동 시각 검수 → 이유/근거: 자동 후보 40행만 생성됨. 개인정보/위치정보 비식별 검토도 필요.
- [ ] 4-class metric 산출 → 이유/근거: class `1..3` 한국 GT가 없어 4-class 서비스 성능 근거를 만들 수 없음.
- [ ] ONNX backend 사용 여부 결정 → 이유/근거: `best.onnx`는 산출됐지만 현재 backend ready artifact는 `.pt` 기준으로 문서화됨.

## 오늘 catch-up 후보 스케줄

- [ ] ONNX full metric equivalence 실행 여부 결정 → 검증: 실행 시 test split PT/ONNX precision, recall, mAP50, mAP50-95 delta 기록. 보류 시 디스크/시간/패키지 사유 기록.
- [ ] 100~200장 PT/ONNX latency 측정 → 검증: mean, p50, p95 ms와 `server`, `onnx`, `hold` 판단 기록.
- [ ] VL1+VS1 hard-negative 200장 subset inference → 검증: false positive image count, detection count, max confidence p50/p95, 상위 후보 CSV 기록.
- [ ] failure sampling CSV 수동 검수 기준 확정 → 검증: `source`, `expected_class`, `failure_type`, `action`, 비식별 필요 여부를 검수 표에 반영.
- [ ] full test split failure sampling 재시도 계획 수립 → 검증: exit code `137` 회피를 위한 batch/streaming 방식과 산출물 크기 제한 기록.
- [ ] 4-class 데이터 공백 정리 → 검증: class `1..3` 한국 GT 미보유 사실과 v4 수집/라벨링 후보를 명시.

## 확인 필요

- 2026-05-17 daylog/execution 문서가 없어 새벽 추가 완료 근거는 확인되지 않음.
- VL2+VS2 external metric은 class `0 damaged_tactile_block` tactile subset 근거임. 4-class 전체 성능으로 쓰면 안 됨.
- `best.onnx`는 export 산출물일 뿐, 현재 backend adapter ready 기준은 `.pt`.
- 대형 validation, full sampling, ONNX metric 검증은 `/` 사용률 `98%` 상태에서 실패할 수 있어 공간 확인이 먼저 필요.
- 자동 failure CSV는 수동 시각 검수 전까지 학습 데이터 확정 근거로 쓰면 안 됨.

## 병렬 에이전트 활용 메모

- 이번 검토에서는 새 하위/병렬 에이전트를 사용하지 않음.
- 기존 2026-05-16 r1 model lane note와 통합 audit 결과를 함께 대조해 판단함.