# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps lane note (2026-05-18)

## 최근 진행 근거
- `README.md` / `model/README.md` / `data_sources/README.md` 확인: 모델 산출물·데이터셋·`runs/`, `.pt`, `.onnx`는 local-only 원칙이며 기본 모델 작업은 `datasets/walksafe_kr_v2`와 `runs/detect/walksafe_kr_tactile_v2_full` 기준.
- `docs/execution/2026-05-17_model_data_mlops.md` / `daylog/2026-05-17.md`: ONNX full metric equivalence 완료. test split 2,347장 기준 PT `mAP50-95 0.481`, ONNX `0.482`로 기준 내 통과.
- `docs/execution/2026-05-17_model_data_mlops.md`: 120장 latency 측정 완료. PT p95 `12.6493ms`, ONNX CPU p95 `58.1289ms`; backend ready artifact는 계속 `.pt` 유지.
- `docs/execution/2026-05-17_model_data_mlops.md`: VL1+VS1 hard-negative 200장 inference 완료. `conf=0.35`에서도 FP image `21/200`, FP detections `30`으로 정상 점자블록 고신뢰 오탐이 남음.
- `runs/failure_sampling/walksafe_kr_v2_test_subset_20260516/manual_review_20260517_summary.json`: failure 후보 40행 수동 triage 완료. hard-negative `16`, localization 보정 positive `12`, 최소 박스 정책 재검토 `9`, small-object 후보 `3`.
- `docs/execution/2026-05-16_model_data_mlops.md`: full test split failure sampling은 기존 `exit code 137`로 중단되어 streaming/저장량 제한 방식이 필요함.
- `docs/model_training_status.md` / `docs/model_v2_status.md`: v2는 class `0 damaged_tactile_block` 중심 baseline이며 4-class 서비스 성능 근거가 아님.
- `data_sources/manifests/korean_dataset_candidates.md` / `docs/korean_data_strategy.md`: class `1..3` 한국 GT 확보가 별도 과제이며 해외 공개 데이터는 smoke/pretrain 후보로만 사용.
- `plans/daily/2026-05-18.md`: 파일 없음 확인. 저장소 내부 `AGENTS.md`도 없음 확인, 사용자 제공 지침 적용.

## 내일 목표 후보
- v3 class `0` 큐레이션 manifest 초안 작성: 수동 검수 40행과 VL1+VS1 hard-negative FP 후보를 학습 후보/보류/제외로 분리.
- full test split failure sampling 재시도 방식 설계: `exit code 137` 회피를 위해 batch/streaming/저장량 제한 기준부터 확정.
- AI Hub 159 또는 직접 촬영 기반 1인칭 보행 실패 프레임 수집 계획 구체화.
- class `1..3` 한국 데이터 확보 계획 갱신: 킥보드/자전거, 공사 구조물/적치물, 포트홀별 최소 GT 목표와 수집 경로 분리.
- 최신 실행 결과와 충돌하는 모델 문서 보정 범위 정리: ONNX full metric 완료, `.pt` ready artifact 유지, browser ONNX Runtime Web 미실행을 명확히 반영.
- browser/ONNX Runtime Web latency는 PWA/실폰 환경 준비 여부를 확인한 뒤 실행 또는 보류 사유 기록.

## 상세 체크리스트 초안
- [ ] 실행 전 gate 확인 → 검증: `git status --short --branch --untracked-files=all`, `df -h /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads`, `sha256sum best.pt best.onnx`, `git ls-files '*.pt' '*.onnx' 'runs/**' 'datasets/**/images/**' 'datasets/**/labels/**'` 결과 기록.
- [ ] v3 curation manifest 초안 작성 → 검증: `manual_review_20260517_summary.json`의 `16+12+9+3=40`행과 manifest row count가 일치하고, 각 row에 `source`, `bucket`, `action`, `privacy_status`, `split_policy`가 있음.
- [ ] VL1+VS1 hard-negative 상위 FP 후보 검수 → 검증: `top_false_positive_candidates_conf_035.csv` 상위 후보를 정상 점자블록 오탐/라벨 의심/보류로 분류하고 v3 hard-negative 포함 여부 기록.
- [ ] full test split failure sampling 재시도 설계 → 검증: batch size, 출력 CSV 최대 크기, 이미지 저장 여부, 중간 checkpoint 정책을 문서화하고 같은 방식이 `exit code 137`을 피할 근거를 남김.
- [ ] AI Hub 159/직접 촬영 후보 경로 확인 → 검증: 파일 보유 여부, 필요한 최소 subset, 예상 용량, 개인정보/위치정보 검토 기준을 기록하고 전체 다운로드/대량 복사는 하지 않음.
- [ ] class `1..3` v4 데이터 계획 작성 → 검증: `parked_kickboard_bicycle`, `construction_obstacle`, `pothole`별 한국 positive/negative 최소 목표, 수집 후보, 라벨링 제외 기준이 분리됨.
- [ ] 모델 문서 stale 문구 목록화 → 검증: `docs/current_status.md`, `docs/model_integration_plan.md`, `PROJECT_PLAN.md`, `docs/model_training_status.md`에서 최신 결과와 충돌하는 문장을 표로 정리.
- [ ] browser/ONNX Runtime Web latency 실행 여부 결정 → 검증: 실행 시 기기/브라우저/프레임 수/p50/p95를 기록하고, 미실행 시 PWA/실폰 환경 미준비 등 사유를 명시.
- [ ] 새 학습 여부 보류 판단 → 검증: v3 manifest와 privacy/split 기준이 확정되기 전에는 `walksafe_kr_tactile_v3_full` 학습을 시작하지 않았음을 기록.

## 리스크/확인 필요
- `/` 가용 공간이 `17G`, 사용률 `99%`라 대형 subset/full inference는 실패하거나 다른 lane 작업을 방해할 수 있음.
- 현재 worktree에 backend/voice/docs/daylog/plans 변경과 미추적 파일이 많음. 모델 lane 작업 시 다른 lane 변경을 덮어쓰지 않아야 함.
- VL1+VS1은 positive box가 `0`개라 recall/mAP 평가가 아니라 false positive hard-negative 평가로만 사용 가능.
- VL2+VS2 external metric은 class `0` tactile subset 전용이며 AI Hub 513 전체 validation 또는 4-class 성능으로 해석하면 안 됨.
- contact sheet 검수는 빠른 triage이며 정식 개인정보/위치정보 비식별 검수는 아님.
- v2 recall은 약 `0.58` 수준이라 보행자 안전용 최종 모델로 과대표기하면 안 됨.
- class `1..3` 한국 GT가 없어 4-class metric은 아직 산출할 수 없음.
- `best.onnx`는 metric equivalence는 통과했지만 browser/ONNX Runtime Web latency는 아직 미실행. backend ready artifact는 `.pt`.

## 병렬 에이전트 활용 메모
- 하위/병렬 에이전트는 사용하지 않음.
- 이유: 이번 작업은 최종 파일 수정 없이 lane note 근거를 모으는 범위였고, 관련 문서/로그가 한 lane 안에 모여 있어 현재 세션의 병렬 파일 조회만으로 충분했음.