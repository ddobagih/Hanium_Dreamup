# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps catch-up lane note (2026-05-19)

## 확인한 근거

- `plans/daily/2026-05-18.md`: Vision Model/Data/MLOps 계획 항목은 체크박스상 모두 미체크.
- `docs/execution/2026-05-18_model_data_mlops.md`: 5/18 실제 수행 결과와 미실행 항목 확인.
- `plans/.work/2026-05-18/execute-r1/vision-model-data-mlops.md`, `execute-r2/vision-model-data-mlops.md`: r1/r2 실행 note 확인.
- `daylog/2026-05-18.md`: 5/18 통합 daylog에서 검증/제한/미완료 항목 확인.
- `docs/execution/2026-05-14_external_validation_subset.md`: VL2+VS2 tactile subset 외부 validation 완료 근거 확인.
- `plans/daily/2026-05-19.md`, `plans/.work/2026-05-18/daily/vision-model-data-mlops.md`: 5/19 후보 계획 확인. 현재 둘 다 미추적 파일 상태.
- `daylog/2026-05-19.md`, `docs/execution/2026-05-19*`: 현재 없음 확인.

## 완료로 판단한 항목

- 실행 전 gate 확인 완료: `df -h`, `best.pt`/`best.onnx` sha256, 대형 산출물 Git 미추적 확인.
- v3 curation manifest 초안 완료: 40행 manifest와 summary JSON 생성, `source`, `bucket`, `action`, `privacy_status`, `split_policy` 포함.
- VL1+VS1 hard-negative FP 후보 검수 완료: conf `0.35` 상위 FP 30 detections를 21 unique images로 묶어 contact sheet 3장 검수.
- full test split failure sampling 재시도 설계 완료: streaming, CSV append, checkpoint, 디스크/RSS/CSV 중단 조건 문서화.
- class `1..3` v4 데이터 계획 갱신 완료: 킥보드/자전거, 공사 장애물, 포트홀별 positive/negative 목표와 제외 기준 기록.
- ONNX backend 판단 정리 완료: full metric equivalence는 통과했지만 backend ready artifact는 `.pt` 유지.
- stale 모델 문서 일부 보정 완료: ONNX 미완료/모델 미연결 표현은 r1/r2에서 수정됨.

## 미완료 작업 후보

- [ ] browser/ONNX Runtime Web latency 측정 → 이유/근거: 5/18 실행 문서와 daylog 모두 미실행으로 기록.
- [ ] streaming failure sampler smoke 실행 → 이유/근거: 재시도 설계는 완료됐지만 50~100장 smoke나 full sampling 재실행은 없음.
- [ ] full test split sampling 실행 여부 판단 → 이유/근거: 기존 실행은 exit code `137`; `/` 사용률 99%라 대형 실행 보류 판단 필요.
- [ ] VL1+VS1 전체 1,038장 hard-negative inference → 이유/근거: 200장만 수행, 전체 확장은 디스크 사유로 미실행.
- [ ] v3 후보 인덱스 통합 및 정책 결정 → 이유/근거: 40행 manifest와 21행 FP review는 별도 존재하나 min-box 9건, 강한 마모/그림자 4건의 포함/제외/threshold 정책은 미정.
- [ ] AI Hub 159/직접 촬영 후보 확인 보강 → 이유/근거: AI Hub 159 1차 다운로드 계획과 용량은 있으나, 보유 파일 여부와 직접 촬영 후보 목록은 확실한 완료 근거 부족.
- [ ] class `1..3` 한국 GT 기반 4-class metric → 이유/근거: 한국 GT가 없어 산출 불가.
- [ ] 개인정보/위치정보 정식 audit → 이유/근거: contact sheet triage만 수행, 정식 비식별 검수는 아니라고 기록됨.

## 오늘 catch-up 후보 스케줄

- [ ] 실행 전 디스크/산출물 gate 재확인 → 검증: `df -h`, `sha256sum best.pt best.onnx`, `git ls-files` 대형 산출물 미추적 기록.
- [ ] 모델 문서 상충 여부 확인 → 검증: `VL2|VS2|외부 validation|4-class` 표현을 실행 로그 기준으로 완료/미완료 분류.
- [ ] streaming sampler smoke 실행 → 검증: test image 50~100장 기준 CSV, checkpoint, bucket count 생성, 대량 이미지 미생성 확인.
- [ ] v3 후보 인덱스 통합 → 검증: 40행 manifest와 21행 FP review row count 및 `privacy_status`, `split_policy` 보존 확인.
- [ ] min-box/hard-negative 정책 결정 → 검증: 9개 min-box 후보와 강한 마모/그림자 FP 4건을 포함/제외/라벨 보정/threshold 후보로 분류.
- [ ] AI Hub 159/직접 촬영 후보 보강 → 검증: 보유 파일, 최소 subset, 예상 용량, privacy/location 기준 기록.
- [ ] ONNX browser latency 실행/보류 판단 → 검증: 실행 시 기기/브라우저/p50/p95 기록, 미실행 시 환경 사유 기록.
- [ ] 5/19 Model 실행 문서 작성 → 검증: `docs/execution/2026-05-19_model_data_mlops.md`에 PASS/FAIL/BLOCKED/PENDING 분리.

## 확인 필요

- `docs/execution/2026-05-14_external_validation_subset.md`는 VL2+VS2 tactile subset 외부 validation 완료를 기록하지만, `docs/model_training_status.md`, `docs/model_v2_status.md`에는 VL2+VS2가 남은 검증처럼 보이는 문구가 있다.
- 현재 작업트리에 `daylog/2026-05-18.md` 수정과 5/19 계획/product 문서 미추적 파일이 있다. merge 에이전트가 변경 주체를 확인해야 한다.
- v2는 class `0 damaged_tactile_block` baseline이다. 4개 위험 클래스 전체 성능으로 쓰면 안 된다.
- VL1+VS1은 positive box가 없어 recall/mAP가 아니라 hard-negative FP 평가로만 사용해야 한다.
- 새 학습, full inference, 대형 subset 생성은 디스크 gate 통과 전까지 보류가 맞다.

## 병렬 에이전트 활용 메모

- 하위/병렬 에이전트는 사용하지 않았다.
- 이번 작업은 읽기 전용 근거 대조와 lane note 출력만 수행했다. catch-up 파일과 daylog는 작성하지 않았다.