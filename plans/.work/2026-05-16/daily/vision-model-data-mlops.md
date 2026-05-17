# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps lane note (2026-05-17)

## 최근 진행 근거
- 사용자 제공 `AGENTS.md` 지침 / 직접 확인(2026-05-16): 저장소 내부 `AGENTS.md`는 없음. 사용자 메시지의 AGENTS 지침 기준 적용.
- `README.md` / `docs/current_status.md` / `docs/model_training_status.md` (2026-05-13): AI Hub 513 `TL8/TL9/TS8/TS9` 기반 `walksafe_kr_v1/v2` 학습 완료. v2 validation은 `precision 0.73656`, `recall 0.58380`, `mAP50 0.66394`, `mAP50-95 0.49194`.
- `docs/execution/2026-05-14_model.md`: v2 artifact hash, dataset 구조 검증, test split 검증 완료. test split은 `precision 0.728`, `recall 0.581`, `mAP50 0.657`, `mAP50-95 0.481`, 판정 `baseline_frozen`.
- `docs/execution/2026-05-14_external_validation_subset.md`: VL2+VS2 tactile subset 외부 검증 완료. `2,082` images / `5,325` instances, `precision 0.751`, `recall 0.605`, `mAP50 0.680`, `mAP50-95 0.501`. 단 class `0 damaged_tactile_block` 전용 근거.
- `docs/execution/2026-05-15_model_onnx_followup.md`: `best.onnx` export 완료, sha256 기록, raw tensor PT/ONNX smoke 통과. 30장 CPU latency는 PT p95 `13.70ms`, ONNX p95 `68.67ms`; full metric equivalence는 미실행.
- `docs/execution/2026-05-16_model_data_mlops.md`: 모델 handoff 표 정리. backend ready artifact는 `.pt`, ONNX는 export 산출물이나 backend ready artifact 아님. `MODEL_VERSION=walksafe-kr-tactile-v2-full-20260514-best-02a6be87`.
- `docs/execution/2026-05-16_model_data_mlops.md` / `runs/failure_sampling/.../summary.json`: full test failure sampling은 exit code `137`로 중단. seed 고정 subset `360`장 샘플링 성공, 후보 `264`행 중 review CSV `40`행 생성.
- `runs/failure_sampling/.../failure_candidates.csv` (2026-05-16 산출): selected bucket은 `false_positive_normal_tactile 16`, `missed_defect 12`, `small_or_far 12`. 자동 후보라 수동 시각 검수 전.
- `docs/execution/2026-05-16_model_data_mlops.md`: VL1+VS1 dry-run 결과 positive `0`, negative `1,038`, boxes `0`. damaged tactile recall 검증이 아니라 hard-negative false positive 평가용.
- 직접 확인(2026-05-16): `plans/daily/2026-05-17.md`는 아직 없음.

## 내일 목표 후보
- 1순위: failure 후보 40행을 수동 시각 검수해 v3 보강 후보, 라벨 품질 의심, 제외 대상을 분리한다.
- 2순위: VL1+VS1 정상 점자블록 hard-negative 평가를 작은 subset부터 실행해 false positive count와 confidence 분포를 기록한다.
- 3순위: ONNX full metric equivalence를 test split 기준으로 실행할지 결정하고, 가능하면 PT/ONNX precision/recall/mAP 차이를 산출한다.
- 4순위: PT/ONNX latency를 100~200장 기준으로 보강 측정하고, `server`, `onnx`, `hold` 연결 후보를 갱신한다.
- 5순위: v3는 class 0 실패 보정, v4는 class 1~3 한국 GT 확보로 분리해 데이터 보강 manifest를 확정한다.
- 6순위: 모델 handoff 문구를 backend/PWA/integration lane과 맞춘다. 핵심 문구는 “v2는 class 0 점자블록 baseline이며 4-class 서비스 모델 근거가 아님”.

## 상세 체크리스트 초안
- [ ] 디스크/산출물 게이트 확인 → 검증: `df -h /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads`, `best.pt`, `best.onnx`, failure CSV, VL2 subset log 존재 확인.
- [ ] failure CSV 40행 수동 검수 → 검증: 각 행에 `keep_for_v3`, `label_issue`, `exclude`, `privacy_blur_needed` 중 하나를 기록.
- [ ] false positive 정상 점자블록 후보 검수 → 검증: `false_positive_normal_tactile` 16행에서 실제 negative/hard-negative 여부와 threshold 조정 필요 여부를 기록.
- [ ] missed/small-or-far 후보 검수 → 검증: `missed_defect` 12행, `small_or_far` 12행의 라벨 품질, 객체 크기, 사용자 경보 가치 여부를 기록.
- [ ] VL1+VS1 hard-negative subset 생성/평가 → 검증: 우선 200장 이하 subset에서 `conf=0.25`, `0.35`별 false positive image count, detection count, max confidence p95 기록.
- [ ] VL1+VS1 확대 여부 판단 → 검증: 공간/시간이 충분하면 1,038장 전체로 확장하고, 부족하면 subset 결과와 보류 사유 기록.
- [ ] ONNX full metric equivalence 실행 여부 결정 → 검증: 실행 시 PT 대비 ONNX `mAP50-95` 차이 절대 `0.01` 이내, precision/recall 차이 절대 `0.02` 이내 확인. 미실행 시 이유 기록.
- [ ] PT/ONNX latency 보강 → 검증: 같은 이미지 100~200장 기준 mean/p50/p95 ms와 측정 환경 CPU/GPU/device를 기록.
- [ ] v3 curation manifest 초안 작성 → 검증: v3 후보 source, bucket, action, privacy status, split 금지 기준을 표로 남김.
- [ ] v4 class 1~3 데이터 확보 계획 갱신 → 검증: `parked_kickboard_bicycle`, `construction_obstacle`, `pothole`별 한국 GT 최소 목표와 우선 수집 경로 기록.
- [ ] Git 추적 안전 확인 → 검증: `git ls-files '*.pt' '*.onnx' 'runs/**' 'datasets/**/images/**' 'datasets/**/labels/**'`에서 대형 산출물이 추적되지 않음.
- [ ] 결과 문서/merge note 작성 → 검증: 실행한 것과 미실행한 것을 분리하고, 4-class 성능으로 과대표기하지 않음.

## 리스크/확인 필요
- v2는 class `0 damaged_tactile_block` 중심 baseline이다. 킥보드/자전거, 공사물, 포트홀 성능 근거가 없다.
- VL2+VS2 external metric은 tactile subset 전용이다. AI Hub 513 전체 official validation 또는 4-class validation으로 해석하면 안 된다.
- VL1+VS1은 positive가 0개라 recall/mAP 평가가 아니라 정상 점자블록 오탐 평가만 가능하다.
- full test failure sampling은 exit code `137`로 중단됐다. 2026-05-17에도 full inference를 바로 반복하기보다 subset/streaming/저장량 제한이 필요하다.
- failure CSV는 자동 후보이며 수동 검수와 개인정보/위치정보 비식별 확인 전에는 v3 학습 데이터로 확정하면 안 된다.
- ONNX는 raw tensor smoke만 통과했다. full metric equivalence와 browser/ONNX Runtime Web latency는 아직 미실행이다.
- 새 `datasets/walksafe_kr_v3`/`v4`를 만들 경우 `data.yaml`, summary 파일의 Git 추적 여부를 별도로 확인해야 한다.
- 현재 backend ready path는 `.pt` 기준이다. ONNX는 export artifact로만 표현해야 한다.

## 병렬 에이전트 활용 메모
- 이번 lane note 작성에는 하위/병렬 에이전트를 사용하지 않았다.
- 이유: 최종 산출물이 단일 lane note이고, 작업이 문서/로그/로컬 요약 파일 대조 중심이라 분리 이득이 작았다.
- 병렬 shell 조회로 문서와 산출물은 동시에 확인했지만, 별도 하위 에이전트 결론을 통합한 것은 아니다.