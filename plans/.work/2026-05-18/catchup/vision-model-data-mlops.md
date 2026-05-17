# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps catch-up lane note (2026-05-18)

## 확인한 근거

- `plans/daily/2026-05-17.md`
- `daylog/2026-05-17.md`
- `docs/execution/2026-05-17_model_data_mlops.md`
- `docs/execution/2026-05-17_integration_field_report.md`
- `plans/.work/2026-05-17/execute-r1/vision-model-data-mlops.md`
- `plans/.work/2026-05-17/daily/vision-model-data-mlops.md`
- `plans/daily/2026-05-18.md`
- `daylog/2026-05-18.md`, `docs/execution/2026-05-18*.md`는 없음 확인

## 완료로 판단한 항목

- 디스크/산출물 gate, `best.pt`/`best.onnx` hash, 대형 산출물 Git 미추적 확인 완료.
- ONNX full metric equivalence 완료. test split 2,347장 기준 PT `mAP50-95 0.481`, ONNX `0.482`로 계획 기준 통과.
- PT/ONNX latency 측정 완료. 120장 기준 PT p95 `12.6493ms`, ONNX CPU p95 `58.1289ms`; backend ready artifact는 `.pt` 유지 판단.
- VL1+VS1 hard-negative 200장 subset inference 완료. `conf=0.35` 기준 FP image `21/200`, FP detections `30`.
- failure 후보 40행 contact sheet 기반 수동 triage 완료. hard-negative `16`, localization 보정 `12`, 최소 박스 정책 재검토 `9`, small-object 후보 `3`.
- VL1+VS1 전체 1,038장 확장 여부는 보류로 판단 완료. 200장에서 이미 FP 후보가 나왔고 약 5GB 이상 추가 복사가 필요하다는 근거 있음.
- v2 모델 한계와 handoff 판단은 실행 문서에 기록됨. v2는 class `0` baseline이며 4-class 서비스 성능 근거가 아니고 backend ready artifact는 `.pt`.

## 미완료 작업 후보

- [ ] v3 curation manifest 초안 작성 → 이유/근거: 수동 triage는 완료됐지만 `source`, `bucket`, `action`, `privacy_status`, `split_policy`를 갖춘 manifest는 완료 근거 없음.
- [ ] VL1+VS1 hard-negative 상위 FP 후보 검수 → 이유/근거: 200장 inference 지표는 있으나 `top_false_positive_candidates_conf_035.csv` 상위 후보의 시각 검수/분류 근거는 없음.
- [ ] full test split failure sampling 재시도 설계 → 이유/근거: 기존 full sampling은 `exit code 137` 기록이 있고, streaming/저장량 제한 설계가 아직 필요함.
- [ ] browser/ONNX Runtime Web latency → 이유/근거: ONNX metric과 CPU latency는 완료됐지만 브라우저/PWA 환경 latency는 미실행.
- [ ] class `1..3` v4 한국 데이터 확보 계획 갱신 → 이유/근거: 한국 GT 부재와 별도 수집 필요성은 기록됐지만 클래스별 최소 목표/수집 경로/제외 기준 문서화는 부족함.
- [ ] 모델 문서 stale 문구 정리 → 이유/근거: `plans/daily/2026-05-18.md`에 `docs/model_integration_plan.md`의 ONNX 미완료 문구 등 최신 실행 결과와 충돌하는 문서가 남았다고 기록됨.
- [ ] 개인정보/위치정보 정식 비식별 검수 → 이유/근거: contact sheet triage는 빠른 검수이며 정식 privacy/location 검수는 아니라고 명시됨.

## 오늘 catch-up 후보 스케줄

- [ ] 실행 전 gate 재확인 → 검증: `git status`, `df -h`, `sha256sum best.pt best.onnx`, `git ls-files` 대형 산출물 추적 여부 기록.
- [ ] v3 curation manifest 작성 → 검증: 수동 검수 40행과 manifest row count 일치, `privacy_status`와 `split_policy` 포함.
- [ ] VL1+VS1 상위 FP 후보 검수 → 검증: conf `0.35` 상위 후보를 정상 점자블록 오탐/라벨 의심/보류로 분류.
- [ ] full failure sampling 재시도 방식 설계 → 검증: batch size, CSV 최대 크기, 이미지 저장 여부, checkpoint 정책 문서화.
- [ ] v4 데이터 확보 계획 갱신 → 검증: 킥보드/자전거, 공사 장애물, 포트홀별 한국 positive/negative 목표와 제외 기준 분리.
- [ ] browser ONNX latency 실행 여부 결정 → 검증: 실행 시 기기/브라우저/p50/p95 기록, 미실행 시 환경 사유 기록.
- [ ] stale 모델 문서 목록화 및 보정 범위 제안 → 검증: 최신 결과와 충돌하는 문장, 수정 대상, 보류 사유를 표로 정리.

## 확인 필요

- 2026-05-18 새벽 실행 daylog 또는 `docs/execution/2026-05-18*.md`는 현재 저장소에서 확인되지 않음.
- `/` 사용률이 99%로 기록되어 대형 full inference나 복사는 다른 lane 작업을 방해할 수 있음.
- class `1..3` 한국 GT가 없어 4-class metric은 아직 산출 불가.
- VL1+VS1은 positive box가 0개라 recall/mAP가 아니라 false positive hard-negative 평가로만 사용해야 함.
- `best.onnx`는 metric equivalence는 통과했지만 backend ready artifact는 여전히 `.pt`.

## 병렬 에이전트 활용 메모

- 하위/병렬 에이전트는 사용하지 않음.
- 이번 작업은 파일 수정 없이 계획표, daylog, 실행 문서 근거를 대조하는 범위라 현재 세션의 병렬 파일 조회만으로 충분했음.