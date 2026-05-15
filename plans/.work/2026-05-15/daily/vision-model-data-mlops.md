# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps lane note (2026-05-16)

## 최근 진행 근거
- 사용자 제공 `AGENTS.md` 지침(2026-05-15 요청 기준): 저장소 내부 `AGENTS.md`는 확인되지 않아 사용자 메시지 지침을 우선 적용.
- `README.md`: 모델 현재 상태는 AI Hub 513 `TL8/TL9/TS8/TS9` 기반 v1/v2 학습 완료, v2 test split/외부 validation/ONNX 연결 검토 단계로 정리됨.
- `docs/model_training_status.md` (2026-05-13): `walksafe_kr_v2`는 23,475 images / 40,161 boxes, validation `precision 0.73656`, `recall 0.58380`, `mAP50 0.66394`, `mAP50-95 0.49194`.
- `docs/execution/2026-05-14_model.md`: v2 freeze hash 검증, dataset 구조 검증, test split 검증 완료. test 결과는 `precision 0.728`, `recall 0.581`, `mAP50 0.657`, `mAP50-95 0.481`, 판정은 `baseline_frozen`.
- `docs/execution/2026-05-14_vlvs_dry_run.md`: `VL1+VS1`은 tactile positive 0개라 hard-negative 용도, `VL2+VS2`는 tactile 2,082장, positive 1,578장, boxes 5,326개.
- `docs/execution/2026-05-14_external_validation_subset.md`: `build_aihub513_validation_subset.py` 추가 및 VL2+VS2 tactile subset 외부 검증 완료. 결과는 `precision 0.751`, `recall 0.605`, `mAP50 0.680`, `mAP50-95 0.501`; class `0 damaged_tactile_block` 전용 근거.
- `docs/execution/2026-05-14_detect_adapter_implementation.md`, `docs/execution/2026-05-14_next_step_parallel.md`: 백엔드 `/detect` Ultralytics `.pt` adapter 1차 구현 및 TestClient smoke 완료. 샘플 응답은 `source: "server"` detection 4개.
- `docs/execution/2026-05-14_pwa_server_detector_wiring.md`: PWA `NEXT_PUBLIC_DETECTOR_MODE=server` wiring은 lint/typecheck 통과, 실브라우저/실폰 `/detect` 호출과 `source: "server"` 신고 저장은 수동 검증 대기.
- `docs/model_training_3day_execution_plan.md` (2026-05-13): 2026-05-16 모델 목표는 ONNX export, PT/ONNX 동등성, latency, PWA/백엔드 연결 후보 결정, v3/v4 계획 확정.
- `docs/execution/2026-05-14_cleanup_candidates.md`: `datasets/walksafe_kr_v2` 199G는 현재 삭제 금지에 가깝고, `datasets/walksafe_kr_v1` 49G 및 AI Hub 원천 zip은 승인 후 정리 후보.
- `daylog/2026-05-15.md`, `plans/catchup/2026-05-15.md`: 2026-05-15 실행 문서 부재, 모델 handoff/ONNX/failure sampling/실브라우저 server detect는 미완료 후보로 남음.
- 직접 확인(2026-05-15 KST): `/` 가용 공간 약 `6.0G`, `best.pt/last.pt/results.csv/args.yaml/data.yaml/SHA256SUMS.txt` 존재, `best.onnx` 없음, `runs/failure_sampling` 산출물 없음, `docs/execution/2026-05-15*.md` 없음, `plans/daily/2026-05-16.md` 없음.

## 내일 목표 후보
- 1순위: 디스크/보존 게이트를 먼저 확정하고, ONNX/export/failure sampling을 시작해도 되는 공간을 확보한다.
- 2순위: v2 validation/test/VL2+VS2 external metric을 하나의 handoff 표로 고정하고, “class 0 점자블록 baseline, 4-class 서비스 모델 아님”을 명시한다.
- 3순위: 백엔드/PWA 공유용 `MODEL_ARTIFACT_PATH`, `MODEL_VERSION`, class order, threshold 후보, class coverage 제한을 확정한다.
- 4순위: 공간 확보 후 v2 `best.pt`를 ONNX로 export하고 hash, export 설정, 실패 시 보류 사유를 기록한다.
- 5순위: PT vs ONNX test split metric 동등성과 로컬 latency를 측정해 연결 후보를 `server`, `onnx`, `hold` 중 하나로 판정한다.
- 6순위: VL1+VS1 정상 점자블록 hard-negative false positive 점검을 작은 산출물 또는 streaming 방식으로 수행한다.
- 7순위: 기존 test/external validation 결과에서 실패 후보 최소 30장을 샘플링하고 v3 보강 bucket을 만든다.
- 8순위: v3 class 0 실패 보정과 v4 4-class 한국 데이터 보강 backlog를 분리한다.

## 상세 체크리스트 초안
- [ ] 디스크 최신 상태 확인 → 검증: `df -h /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads` 결과와 최소 필요 공간을 기록.
- [ ] 보존 대상 재확인 → 검증: `best.pt`, `last.pt`, `results.csv`, `args.yaml`, `data.yaml`, `SHA256SUMS.txt`, VL2 subset `BUILD_SUMMARY.md`와 validation log 존재 확인.
- [ ] 정리 승인 후보 분리 → 검증: 삭제 금지, 즉시 정리 가능, 사용자 승인 필요 항목을 `docs/execution/2026-05-14_cleanup_candidates.md` 기준으로 재분류.
- [ ] v2 metric 비교표 작성 → 검증: validation/test/VL2+VS2 external의 precision, recall, mAP50, mAP50-95를 한 표로 정리.
- [ ] 모델 handoff 값 확정 → 검증: `MODEL_ARTIFACT_PATH`, `MODEL_VERSION=walksafe-kr-tactile-v2-full-20260514-best-02a6be87`, class order, 초기 confidence 후보, class 0 제한 명시.
- [ ] ONNX export 실행 여부 결정 → 검증: `best.onnx` 부재, `onnx/onnxruntime` 설치, `onnxslim` 부재, 여유 공간을 확인하고 실행/보류를 기록.
- [ ] ONNX export 수행 → 검증: `best.onnx` 생성, hash 기록, `.onnx`가 Git 추적 대상이 아닌지 확인.
- [ ] PT vs ONNX 동등성 검증 → 검증: 같은 `datasets/walksafe_kr_v2` test split에서 mAP50-95 차이 절대 `0.01` 이내, precision/recall 차이 절대 `0.02` 이내인지 기록.
- [ ] PT/ONNX latency 측정 → 검증: test 이미지 100~200장 기준 mean/p50/p95 ms를 기록하고 백엔드 서버 후보 p95 `250ms` 또는 상대 기준을 판단.
- [ ] VL1+VS1 hard-negative 평가 → 검증: positive 0개 전제를 유지한 상태에서 false positive count, confidence 분포, 산출물 용량을 기록.
- [ ] 실패 후보 30장 이상 샘플링 → 검증: 각 항목에 `source`, `expected_class`, `failure_type`, `action`, 비식별 필요 여부를 기록.
- [ ] v3/v4 backlog 확정 → 검증: v3는 class 0 실패 보정, v4는 킥보드/자전거·공사물·포트홀 한국 데이터 보강으로 분리.

## 리스크/확인 필요
- 현재 디스크가 사실상 가득 찬 상태다. 대형 subset 생성, ONNX export, predictions 저장, failure sampling 전에 공간 확보 또는 산출물 경로 조정이 필요하다.
- v2는 실질적으로 `damaged_tactile_block` 중심이다. 4개 클래스 API 계약과 모델 성능 근거를 혼동하면 안 된다.
- VL2+VS2 결과는 tactile subset 외부 validation이지 AI Hub 513 전체 official validation 또는 4-class 성능 근거가 아니다.
- VL1+VS1은 positive가 0개라 recall 검증이 아니라 정상 점자블록 오탐 점검용이다.
- VL2+VS2 validation 중 JPEG 다수 복구 저장, label row 5,326개 대비 validation instances 5,325개 불일치가 기록됐다.
- `best.onnx`는 아직 없고 `onnxslim`도 설치되지 않았다. `simplify=true` export 실패 시 fallback 기준을 기록해야 한다.
- 새 `datasets/walksafe_kr_v3/data.yaml` 같은 파일은 현재 `.gitignore`에 자동 제외되지 않을 수 있다. v3/v4 생성 전 Git 추적 방지 확인이 필요하다.
- PWA server detector wiring은 코드 검증만 됐고, 실제 브라우저/실폰에서 `/detect` 호출, bbox 표시, `source: "server"` 신고 저장은 아직 근거가 없다.
- `python` 명령은 없던 기록이 있으므로 모델 작업은 `.venv/bin/python`, `.venv/bin/yolo` 또는 `python3` 기준으로 계획해야 한다.

## 병렬 에이전트 활용 메모
- 하위/병렬 에이전트는 사용하지 않음.
- 이번 작업은 문서, 로그, artifact 존재 여부, 디스크 상태를 단일 lane note로 통합하는 범위라 별도 에이전트 분리가 필요하지 않았다.
- 독립적인 파일 읽기와 존재 확인은 병렬 shell 호출로 처리했고, 결론은 위 체크리스트에 통합했다.