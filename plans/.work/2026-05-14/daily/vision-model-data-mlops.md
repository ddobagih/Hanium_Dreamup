# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps lane note (2026-05-15)

## 최근 진행 근거
- `docs/model_training_3day_execution_plan.md` (작성 기준 2026-05-13): 5/15 모델 목표는 AI Hub 513 외부 validation, 실패 프레임 샘플링, v3/v4 보강 판단.
- `docs/execution/2026-05-14_model.md`: v2 `best.pt` hash/dataset 검증 통과, test split `precision 0.728`, `recall 0.581`, `mAP50-95 0.481`; v2는 baseline frozen 가능하지만 서비스 후보 확정은 보류.
- `docs/execution/2026-05-14_external_validation_subset.md`: VL2+VS2 tactile subset 2,082장으로 외부 검증 완료. 결과 `precision 0.751`, `recall 0.605`, `mAP50 0.680`, `mAP50-95 0.501`.
- `docs/execution/2026-05-14_vlvs_dry_run.md`: VL1+VS1은 tactile positive 0개라 hard-negative 검증용, VL2+VS2는 positive 1,578장/box 5,326개.
- `docs/execution/2026-05-14_detect_adapter_implementation.md`: 백엔드 `/detect`는 로컬 Ultralytics `.pt` adapter 1차 구현 완료. v2 모델은 class 0 중심임을 명시.
- 로컬 확인(2026-05-14 KST): `best.pt`는 존재, `best.onnx`는 아직 없음. `runs/validation/aihub513_vl2_vs2_tactile_subset`은 약 18G, 남은 디스크는 약 6.5G.
- `plans/daily/2026-05-15.md`는 현재 없음. `daylog/` 파일도 없고 최근 실행 기록은 `docs/execution/2026-05-14_*.md`에 있음.

## 내일 목표 후보
- 1순위: 디스크/산출물 보존 기준 확정 후 모델 작업 가능 공간 확보.
- 2순위: VL2+VS2 외부 validation 결과를 v2 val/test와 비교해 `class 0 baseline` 판정 문서화.
- 3순위: 기존 외부 validation 결과에서 실패 프레임 최소 30장 샘플링.
- 4순위: VL1+VS1 정상 점자블록 hard-negative false positive 점검 준비 또는 실행.
- 5순위: 백엔드/PWA 연결용 모델 handoff 값 확정: artifact path, model version, class coverage, threshold 후보.
- 6순위: ONNX export는 공간 확보 후 준비만 우선하고, 실제 export/동등성 검증은 5/16 게이트로 넘기는 것이 안전.

## 상세 체크리스트 초안
- [ ] 보존 대상 재확인 → 검증: `best.pt`, `last.pt`, `results.csv`, `data.yaml`, `SHA256SUMS.txt`, VL2 subset metric 로그 존재 확인.
- [ ] 공간 확보안 결정 → 검증: `df -h`로 최소 여유 공간 확인, 삭제/이동 전 보존 대상과 사용자 승인 여부 기록.
- [ ] VL2+VS2 외부 validation 비교표 작성 → 검증: v2 val/test/external의 precision, recall, mAP50, mAP50-95를 한 표로 정리.
- [ ] v2 판정 업데이트 → 검증: “점자블록 class 0 baseline은 유지, 4-class 서비스 모델은 아님”을 명시.
- [ ] 실패 프레임 샘플링 기준 확정 → 검증: `missed_defect`, `false_positive_normal_tactile`, `low_light_or_blur`, `small_or_far`, `new_class_gap` bucket 정의.
- [ ] 실패 후보 30장 이상 수집 → 검증: 각 항목에 `source`, `expected_class`, `failure_type`, `action`, 개인정보 비식별 필요 여부 기록.
- [ ] VL1 hard-negative 평가 가능성 확인 → 검증: 공간 확보 후 VL1 subset 생성/추론 또는 streaming 방식 가능 여부 확인, false positive 수 기록.
- [ ] 모델 handoff 값 확정 → 검증: `MODEL_ARTIFACT_PATH`, `MODEL_VERSION=walksafe-kr-tactile-v2-full-20260514-best-02a6be87`, class order, threshold 후보 문서화.
- [ ] ONNX export 준비 상태 확인 → 검증: `best.onnx` 부재, 필요 패키지/공간/출력 경로 확인만 수행.
- [ ] v3/v4 보강 후보 정리 → 검증: class 1~3 한국 데이터 부족과 v3 실패 보정 데이터 우선순위가 분리되어 있음.

## 리스크/확인 필요
- 디스크 여유가 약 6.5G로 매우 부족하다. 추가 subset, ONNX export, failure sampling 전 정리 또는 외부 저장 위치가 필요하다.
- 현재 v2는 실질적으로 `damaged_tactile_block` 중심 모델이다. 4개 클래스 계약은 유지하지만 킥보드/공사물/포트홀 성능 근거는 없다.
- VL2+VS2 결과는 tactile subset 외부 검증이지 AI Hub 513 전체 official validation 지표는 아니다.
- VL1+VS1은 positive가 0개라 recall 검증이 아니라 정상 점자블록 오탐 점검용이다.
- Ultralytics validation 중 JPEG 다수가 복구 저장됐고, label row 5,326개 대비 validation instances 5,325개로 중복 row 1개가 보고됐다.
- `best.onnx`가 없으므로 ONNX latency/동등성은 아직 검증되지 않았다.
- 내일 실제 작업 시 `python` 대신 `python3` 또는 `.venv/bin/python`, `.venv/bin/yolo` 사용이 안전하다.