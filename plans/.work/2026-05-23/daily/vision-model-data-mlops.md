# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps lane note (2026-05-24)

## 최근 진행 근거
- `product/vision.md`, `product/decisions.md`, `product/done-criteria.md` (2026-05-18): 모델 성능은 한국 보행 환경 validation/test와 실폰/field 근거만 제품 성능으로 사용한다. fake/headless/model metric/field 근거를 섞지 않는다.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports` (확인일 2026-05-23): 파일 없음. `PM/` 디렉터리도 없어 feature scheduler, verify scheduler, product audit, automation metrics 산출물은 반영할 것이 없다.
- `plans/daily/2026-05-24.md` (확인일 2026-05-23): 기존 파일 없음.
- `README.md`, `docs/current_status.md`, `docs/walksafe-v2/*.md` (2026-05-22): `/detect/v2`, `/reports/v2`, `fake-v2`, `server-v2`, two-model helper는 구현됐지만 실제 YOLO26s custom / YOLO26n COCO inference adapter는 아직 미구현이다.
- `model/README.md`, `configs/walksafe_two_model_runtime_20260522.yaml` (2026-05-22): custom tactile 후보는 YOLO26s 3-class, COCO helper는 YOLO26n inference-only다. class id를 전역으로 합치지 않고 `model_key`, `source_model`, `class_name`을 유지한다.
- `docs/execution/2026-05-22_yolo26s_stage1_stage2_review.md`, `runs/reports/walksafe_tactile3_yolo26s_20260522/*.md` (2026-05-22): 기존 tactile3 YOLO26s Stage1이 현재 app/MVP 후보. Stage1 test all mAP50-95 `0.730`, Stage2 `0.726`; `tactile_damage_area`는 Stage1 test P `0.644`, R `0.467`, mAP50-95 `0.293`로 병목이다.
- `datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/REVIEW_APPLY.md` (2026-05-22): 120건 review decision 적용 dataset이 built 상태다. bbox fix 14, missing area add 25, accept 60, exclude 12, false damage remove 9, materialized rows 23,475.
- `daylog/2026-05-23.md`, `runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/results.csv` (2026-05-23): reviewed tactile3 YOLO26s Stage1 학습 진행 중. 확인 시점 83 epoch 완료, best epoch 62 mAP50 `0.83289`, mAP50-95 `0.73982`. 200 epoch/Stage2/final validation 전이라 최종 성능이 아니다.
- `daylog/2026-05-21.md`, `docs/execution/2026-05-21_model_v3_experiment_matrix.md`: v3 relabel 계열은 holdout candidate에서 v2 baseline보다 낮아 대체 불가. 현 deploy/reference weight는 기존 v2 또는 YOLO26s 후보 검증 결과를 별도로 봐야 한다.
- `docs/model_training_status.md`, `docs/model_v2_status.md`: v2 `best.pt` test split mAP50-95 `0.481`, ONNX metric equivalence는 완료됐지만 browser/ONNX Runtime Web latency는 미완료다. YOLO26s/tactile3 후보의 ONNX export 근거는 아직 확인되지 않았다.

## 내일 목표 후보
- 1순위: reviewed tactile3 YOLO26s 파이프라인 완료 여부를 gate로 확인하고, 완료 시 Stage1/Stage2 summary와 기존 2026-05-21 Stage1 후보를 비교해 “MVP 후보”를 갱신할지 판단한다.
- 2순위: `/detect/v2` real custom YOLO26s adapter thin slice를 계획/구현 후보로 잡는다. weight는 로컬 설정으로만 참조하고, raw detection을 two-model helper 입력으로 정규화하는 범위에 제한한다.
- 3순위: COCO YOLO26n inference-only adapter smoke 기준을 고정한다. allowlist, threshold, `model_key=coco_general`, source 분리, latency 기록 형식을 우선한다.
- 4순위: `tactile_damage_area` 병목 보강을 위해 reviewed dataset 기준 FP/FN 샘플 리뷰와 threshold calibration 계획을 만든다. threshold만 올려 해결한다고 가정하지 않는다.
- 5순위: 선택된 YOLO26s 후보의 ONNX/export smoke를 준비한다. 실제 export는 GPU 학습/평가와 충돌하지 않을 때만 실행하고, browser latency는 별도 기기 조건이 있을 때만 둔다.
- 6순위: class `1..3` 한국 GT와 독립 blind validation manifest thin slice를 갱신한다. 새 대형 다운로드나 full training보다 source/split/privacy/label status를 먼저 고정한다.
- 7순위: model card/evidence registry를 갱신해 fake contract, v2 baseline, YOLO26s metric, reviewed 중간 결과, field 미검증을 분리한다.

## 상세 체크리스트 초안
- [ ] 시작 gate 확인 → 검증: `git status --short --branch --untracked-files=all`, `df -h`, `git ls-files '*.pt' '*.onnx' 'runs/**' 'datasets/**/images/**' 'datasets/**/labels/**'`로 작업트리/디스크/대형 산출물 추적 여부 기록.
- [ ] scheduler/PM 산출물 재확인 → 검증: `plans/features`, `plans/verify/ready`, `plans/verify/reports`, `PM/status`, `PM/reports`가 비어 있거나 없는 경우 “반영 산출물 없음”으로 기록.
- [ ] reviewed YOLO26s 상태 확인 → 검증: `scripts/check_walksafe_tactile3_reviewed_training_status_20260522.sh` 또는 CPU-only `results.csv` parser로 완료 epoch, best epoch, finetune 존재 여부, log의 DONE/중단 상태 기록.
- [ ] 학습 진행 중이면 GPU 작업 금지 → 검증: 새 train/val/predict/export를 실행하지 않고 CPU-only summary와 다음 작업 대기 조건만 문서화.
- [ ] reviewed 파이프라인 완료 시 Stage1/Stage2 summary 생성 → 검증: `results.csv`, checkpoint, validation output 존재 여부를 CPU-only로 요약하고 기존 `walksafe_tactile3_yolo26s_img960_musgd_e200_20260521` 후보와 mAP/recall/class별 병목 비교.
- [ ] MVP 후보 판정 초안 작성 → 검증: `normal_tactile_block`, `damaged_tactile_block`, `tactile_damage_area` class별 test 지표와 샘플 리뷰 필요 여부를 분리하고, 중간 epoch 수치를 최종으로 쓰지 않음.
- [ ] `/detect/v2` real adapter thin slice 설계 → 검증: local-only `custom_tactile_weight_path`, model load 실패 정책, raw bbox/conf/class 정규화, helper 입력/출력 fixture를 정의.
- [ ] adapter unit/smoke safe alternative 작성 → 검증: 실제 weight/GPU 없이 synthetic raw detections로 `model_key`, `source_model`, class threshold, COCO allowlist, cross-model NMS 미적용을 테스트할 수 있게 계획.
- [ ] COCO YOLO26n smoke 기준 작성 → 검증: allowlist 8개 class, threshold `0.25` 초안, 신고 저장 금지, risk evaluator 입력 전용, latency 기록 필드를 문서화.
- [ ] `tactile_damage_area` FP/FN calibration plan 작성 → 검증: reviewed 120건 decision 결과와 기존 Stage1/Stage2 test 병목을 연결하고, low-confidence 단독 damage area는 검토/쿨다운/중복 gate가 필요하다고 명시.
- [ ] ONNX/export 후보 판단 → 검증: selected YOLO26s `best.pt`가 고정된 뒤 export 실행 여부를 결정하고, 실행 시 PT/ONNX raw tensor 또는 metric smoke와 p95 latency를 v2 ONNX 결과와 별도 기록.
- [ ] browser latency는 별도 보류 처리 → 검증: 모바일/브라우저/ONNX Runtime Web 환경이 없으면 CPU ONNX latency를 browser latency로 대체하지 않음.
- [ ] class `1..3` / blind validation manifest 갱신 → 검증: `class_id`, `source_type`, `source_file`, `label_status`, `privacy_status`, `location_status`, `split_key`, `exclude_reason` 필드와 직접촬영/AI Hub 159 후보를 기록하되 실제 대형 다운로드는 하지 않음.
- [ ] evidence/model card 갱신 후보 정리 → 검증: fake-v2 contract, server-v2 fake contract, YOLO26s metric, v2 baseline, reviewed 중간 결과, field 미검증을 서로 다른 등급으로 표기.
- [ ] 실행 문서/daylog 인계 메모 작성 → 검증: `docs/execution` 또는 `daylog`에 실제 실행한 검증만 PASS로 기록하고, 막힌 항목은 BLOCKED/PENDING과 safe alternative를 함께 남김.

## 리스크/확인 필요
- reviewed YOLO26s Stage1은 2026-05-23 확인 시점에 진행 중이다. 완료/Stage2/test validation 전까지 최종 모델 성능이나 MVP 후보 교체 근거로 쓰면 안 된다.
- `ai_tasks/walksafe_tactile_damage_area_review_20260522` 쪽 note에는 pending 문구가 남아 있고, 실제 built 근거는 `datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/REVIEW_APPLY.md`에 있다. 문서 충돌을 정리해야 한다.
- `/detect/v2`는 현재 fake contract다. real YOLO26s/COCO adapter 연결 전까지 server-v2를 실제 모델 inference 완료로 표현하면 안 된다.
- `tactile_damage_area`는 작은 bbox와 라벨 경계 이슈 때문에 recall/precision tradeoff가 크다. 자동 신고 확정에는 class별 threshold, 중복/쿨다운, damage context, 검토 큐가 필요하다.
- ONNX는 v2 baseline 근거만 확실하다. YOLO26s 후보 ONNX export와 browser latency는 별도 검증 전까지 미확인이다.
- class `1..3` 한국 GT가 아직 없어 4-class metric, 정확도 90% 달성, 서비스 전체 성능을 주장할 수 없다.
- holdout candidate는 이미 v2/v3 분석에 쓰인 참고 평가셋이라 최종 blind test로 약하다. 새 blind validation 계획이 필요하다.
- `runs/`, `.pt`, `.onnx`, dataset images/labels, 로그는 로컬 산출물이며 GitHub 업로드 금지 대상이다.
- 대형 다운로드, full inference, 새 full training, 운영 배포, 외부 업로드, secret/API key 사용은 자동 계획에서 직접 실행하지 않는다. 가능한 safe alternative는 CPU-only parser, fixture, dry-run config, synthetic raw detection, bounded manifest다.

## 병렬 에이전트 활용 메모
- 사용함.
- Explorer 1: product/plan/scheduler/verify/PM 문서 범위를 맡겼고, `plans/features`, `plans/verify/*`, `PM/*` 산출물이 없으며 5/24 계획은 product/backlog와 최근 model execution 근거에서 수동 도출해야 한다는 결론을 통합했다.
- Explorer 2: model/data/runs/ai_tasks/daylog 범위를 맡겼고, YOLO26s Stage1 후보, reviewed dataset built 상태, reviewed 학습 진행 중, v3 relabel 대체 불가, ONNX/export 미완료를 확인했다.
- 통합 결론: 2026-05-24는 새 catch-up 반복보다 reviewed YOLO26s 완료 판정, real `/detect/v2` adapter thin slice, COCO smoke 기준, `tactile_damage_area` calibration, YOLO26s ONNX/export 준비를 우선한다.