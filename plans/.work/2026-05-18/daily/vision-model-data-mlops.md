# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps lane note (2026-05-19)

## 최근 진행 근거
- 2026-05-18 `docs/execution/2026-05-18_model_data_mlops.md`: v3 후보 manifest 40행, VL1+VS1 hard-negative FP review 21행 정리 완료. full failure sampling은 설계만 했고 재실행하지 않음.
- 2026-05-18 현재 확인: `/` 가용 17G, 사용률 99%. `best.pt` sha256 `02a6be...e8e94`, `best.onnx` sha256 `c21f47...8efe7` 재확인.
- 2026-05-18 현재 확인: `.pt`, `.onnx`, `runs/**`, `datasets/walksafe_kr_v2/**` 대형 산출물은 Git 추적 없음. `.gitkeep`만 추적됨.
- 2026-05-17 `docs/execution/2026-05-17_model_data_mlops.md`: PT/ONNX full test metric equivalence 통과. ONNX Runtime CPU p95는 PT보다 느려 backend ready artifact는 `.pt` 유지.
- 2026-05-17 `docs/execution/2026-05-17_model_data_mlops.md`: VL1+VS1 200장 hard-negative에서 conf 0.35 기준 FP image 21/200, FP detections 30 확인.
- 2026-05-14 `docs/execution/2026-05-14_external_validation_subset.md`: VL2+VS2 tactile subset 2,082장 외부 검증 완료, mAP50-95 `0.501`, recall `0.605`. class 0 전용 근거임.
- 2026-05-18 `docs/model_training_status.md`, `docs/model_v2_status.md`: v2는 `damaged_tactile_block` class 0 baseline이며 4-class 서비스 성능으로 해석 금지.
- 2026-05-18 확인: `plans/daily/2026-05-19.md`는 없음. 저장소 내부 `AGENTS.md`도 없음으로 기록되어 사용자 제공 지침 기준 적용.

## 내일 목표 후보
- 1순위: 디스크/산출물 gate를 먼저 고정하고 대형 복사, 새 학습, full inference 재실행 가능 여부를 판단한다.
- 2순위: full test split failure sampling을 기존 방식으로 반복하지 말고 streaming/checkpoint 방식의 작은 smoke부터 실행한다.
- 3순위: v3 후보 40행 + hard-negative FP 21행을 학습 후보 인덱스로 통합하되, 원본 이미지는 local-only로 유지한다.
- 4순위: `review_min_box_policy_before_training` 9행과 강한 마모/그림자 FP 4건의 라벨/threshold/augmentation 정책을 결정한다.
- 5순위: class `1..3` 한국 GT 확보 계획을 실행 가능한 파일/장소/라벨링 단위로 쪼갠다.
- 6순위: browser/ONNX Runtime Web latency는 PWA 런타임과 기기 조건이 확보될 때만 측정한다.

## 상세 체크리스트 초안
- [ ] 실행 전 gate 확인 → 검증: `df -h`, `sha256sum best.pt best.onnx`, `git ls-files` 대형 산출물 추적 여부 기록.
- [ ] 모델 문서 상충 정리 → 검증: VL2+VS2 subset 완료/미완료 표현을 `rg "VL2|VS2|외부 validation"`로 찾아 실행 로그 기준으로 분류.
- [ ] streaming failure sampler 작업 범위 확정 → 검증: 이미지 저장 기본 off, CSV append, 100장 checkpoint, RSS/CSV/디스크 중단 조건 문서화.
- [ ] streaming sampler smoke 실행 → 검증: test image 50~100장 기준 CSV, checkpoint, bucket count 생성 및 대량 이미지 미생성 확인.
- [ ] full test split sampling 실행 여부 판단 → 검증: 가용 10GB 미만 또는 RSS 2GB 초과 위험이면 보류 사유만 기록.
- [ ] v3 후보 인덱스 통합 → 검증: 40행 manifest와 21행 FP review row count, source, action, privacy_status, split_policy 보존.
- [ ] min box 정책 검토 → 검증: 9개 후보를 학습 포함/제외/라벨 보정으로 나누고 결정 근거 기록.
- [ ] hard-negative threshold 후보 비교 → 검증: 기존 VL1+VS1 conf 0.25/0.35 결과와 필요 시 conf 상향 후보의 FP count 비교.
- [ ] class `1..3` 데이터 확보 작업표 구체화 → 검증: 킥보드/자전거, 공사 장애물, 포트홀별 1차 positive/negative source와 제외 기준 기록.
- [ ] ONNX browser latency 보류/실행 판단 → 검증: 실행 시 기기, 브라우저, p50/p95 기록. 미실행 시 환경 사유 기록.

## 리스크/확인 필요
- `/` 사용률 99%라 대형 subset 생성, 전체 이미지 저장, 새 학습은 먼저 막아야 한다.
- v2는 class 0 점자블록 baseline이다. 4개 위험 클래스 전체 성능으로 보고하면 안 된다.
- 기존 full failure sampling은 exit code 137 기록이 있어 같은 방식 반복은 리스크가 크다.
- contact sheet 검수는 빠른 triage이며 정식 개인정보/위치정보 비식별 검수가 아니다.
- `docs/model_training_status.md`, `docs/model_v2_status.md` 일부 문구는 VL2+VS2 완료 상태와 충돌 가능성이 있어 확인 필요.
- class `1..3` 한국 GT가 아직 없어 4-class metric 산출은 불가하다.
- 현재 조회 시 `daylog/2026-05-18.md`가 modified로 보여, merge 에이전트가 최종 저장 전 변경 주체를 확인해야 한다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이번 작업은 파일 수정 없이 lane note를 만들기 위한 읽기/근거 통합이며, 핵심 근거가 최근 실행 로그와 manifest에 집중되어 단일 에이전트의 병렬 파일 조회로 충분했다.