# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps catch-up lane note (2026-05-16)

## 확인한 근거

- `plans/daily/2026-05-15.md`: Vision Model/Data/MLOps 체크리스트 11개 항목 확인.
- `daylog/2026-05-15.md`: 디스크 정리, 모델 검증, ONNX export, `/detect` ready smoke, PWA server E2E 기록 확인.
- `docs/execution/2026-05-15_model_validation.md`: v2 artifact/hash, metric 표, external subset, ONNX/failure sampling 당시 pending 기록 확인.
- `docs/execution/2026-05-15_model_onnx_followup.md`: `best.onnx` export, raw PT/ONNX smoke, 30장 CPU latency 기록 확인.
- `docs/execution/2026-05-15_disk_cleanup_candidates.md`, `2026-05-15_low_risk_cleanup_result.md`, `2026-05-15_wine_obs_snap_cleanup_result.md`: 디스크/정리 후보와 최종 `/` 가용 `18G` 확인.
- `docs/execution/2026-05-15_runtime_followup_after_reset.md`, `2026-05-15_pwa_server_detection_e2e.md`, `2026-05-15_pwa_production_server_e2e.md`: v2 `best.pt` backend ready, `source=server` detection/report 확인.
- `daylog/2026-05-16.md`, `docs/execution/2026-05-16*.md`: 확인되지 않음.

## 완료로 판단한 항목

- 디스크 최신 상태 확인: `/` 가용 공간이 `5.9G → 14G → 18G`로 정리 후 기록됨.
- 보존 대상 재확인: `best.pt`, `last.pt`, `results.csv`, `args.yaml`, `data.yaml`, `SHA256SUMS.txt` hash 검증 통과.
- 정리 승인 필요 항목 분리: AI Hub zip, `datasets/**/images`, `runs/validation/**` 등 고위험 후보가 분리됨.
- v2 지표 비교표 작성: validation/test/VL2+VS2 external subset metric 표가 기록됨.
- v2 판정 문구 고정: class `0 damaged_tactile_block` baseline이며 4-class 서비스 모델 근거가 아님이 명시됨.
- ONNX export 준비/수행: `best.onnx` 생성, sha256 기록, Git ignored 확인.
- PT/ONNX 빠른 smoke: raw tensor allclose 통과, 30장 CPU latency 기록됨.
- backend `/detect` v2 ready smoke: `MODEL_ARTIFACT_PATH`, `MODEL_VERSION` 설정 후 known-positive image에서 `source=server` detection 확인.
- PWA server mode E2E: dev/start production 기준 모두 `source=server`, `metadata.source=server` report 저장 확인.

## 미완료 작업 후보

- [ ] 실패 프레임 bucket 정의 → 이유/근거: `missed_defect`, `false_positive_normal_tactile`, `low_light_or_blur`, `small_or_far`, `new_class_gap` 기준 문서화 근거 없음.
- [ ] 실패 후보 30장 이상 샘플링 → 이유/근거: `runs/failure_sampling` 산출물 없음, `model_validation`에 pending으로 기록.
- [ ] VL1+VS1 hard-negative 평가 실행/방식 확정 → 이유/근거: VL1+VS1 dry-run은 `1038 tactile images / 0 boxes`로 확인됐지만 false positive count/confidence 분포는 없음.
- [ ] 모델 handoff 표 최종화 → 이유/근거: path/version/class order/threshold 값은 흩어져 있으나 백엔드/PWA 공유용 단일 표 근거는 불명확.
- [ ] ONNX full metric equivalence → 이유/근거: raw tensor smoke만 통과. test split mAP/precision/recall 동등성 검증은 미실행.
- [ ] PT/ONNX 확대 latency 측정 → 이유/근거: 30장 CPU smoke만 있음. 100~200장 기준이나 browser/ONNX Runtime Web 검증은 없음.
- [ ] v3/v4 데이터 보강 후보 확정 → 이유/근거: class 0 실패 보정과 class 1~3 한국 데이터 보강을 분리한 backlog 문서 근거 없음.

## 오늘 catch-up 후보 스케줄

- [ ] 모델 handoff 표 작성 → 검증: `MODEL_ARTIFACT_PATH`, `MODEL_VERSION`, class order, confidence/iou/imgsz, class 0 제한, `best.pt`/`best.onnx` sha256를 한 표로 기록.
- [ ] failure bucket 기준 확정 → 검증: 5개 bucket 정의와 샘플링 입력 source, 산출 경로를 문서화.
- [ ] 실패 후보 30장 샘플링 계획 또는 실행 여부 결정 → 검증: 실행 시 30장 이상 CSV/표 기록, 미실행 시 디스크/입력 source 보류 사유 기록.
- [ ] VL1+VS1 hard-negative 평가 계획 확정 → 검증: streaming/subset 방식, 예상 산출물 크기, false positive 기록 형식 명시.
- [ ] ONNX full metric/latency 보강 여부 결정 → 검증: test split metric delta 또는 “raw smoke까지만 완료” 제한 문구 기록.
- [ ] v3/v4 backlog 확정 → 검증: v3는 class 0 실패 보정, v4는 킥보드/자전거·공사물·포트홀 한국 데이터 보강으로 분리.

## 확인 필요

- `2026-05-16` daylog/execution 문서가 없어 새벽 추가 작업 반영 근거는 없음.
- `plans/daily/2026-05-16.md`는 존재하지만, 일부 내용이 “2026-05-15 실행 문서 없음”처럼 이후 생성된 5/15 문서와 충돌하므로 merge 시 최신 5/15 execution/daylog를 우선해야 함.
- ONNX는 생성됐지만 full metric equivalence는 완료로 표시하면 안 됨.
- 외부 validation은 VL2+VS2 tactile subset class 0 근거다. 4-class 전체 성능으로 확장 금지.
- 읽기 전용 조사만 수행했으므로 이번 응답에서는 daylog를 작성하지 않음.

## 병렬 에이전트 활용 메모

- 이번 검토에서는 별도 하위 에이전트를 새로 사용하지 않음.
- 기존 2026-05-15 문서에 기록된 병렬 lane 결과를 통합해 판단함: Model validation lane, Model ONNX follow-up, PWA server E2E, Backend/PostGIS follow-up.