# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps lane note (2026-05-20)

## 최근 진행 근거
- `README.md`, `model/README.md`, `data_sources/README.md` (2026-05-19 확인): v2는 AI Hub 513 기반 `damaged_tactile_block` 중심 baseline이고, `runs/`, `.pt`, `.onnx`, dataset images/labels는 local-only 원칙.
- 프로젝트 내부 `AGENTS.md`는 없음 확인(2026-05-19); 사용자 제공 AGENTS 지침 적용.
- `plans/daily/2026-05-20.md`는 없음 확인(2026-05-19).
- `docs/model_training_status.md`, `docs/model_v2_status.md` (2026-05-19 보정): v2 test split 2,347장 기준 mAP50 `0.657`, mAP50-95 `0.481`; ONNX metric equivalence는 통과했지만 backend ready artifact는 `.pt`.
- `docs/execution/2026-05-19_model_data_mlops.md`: `model/sample_yolo_failures.py` 추가, v2 test split 80장 streaming smoke 완료, CSV 85행/checkpoint 생성, prediction image 0개.
- `data_sources/manifests/walksafe_kr_v3_candidate_index_summary_2026-05-19.json`: v3 후보 61행 통합. `include_as_hard_negative` 33행, `include_after_box_review` 12행, `hold_until_min_box_policy_review` 9행, small-object 3행, threshold/augmentation 우선 hard-negative 4행.
- `data_sources/manifests/walksafe_kr_v3_policy_2026-05-19.md`: min-box 보류 9행과 source-level privacy/location audit 전에는 v3 train 금지.
- `data_sources/manifests/korean_dataset_candidates.md` (2026-05-19): class `1..3` 한국 GT는 아직 없고, AI Hub 159 `Average_stature/out` zip 및 직접 촬영 후보 파일도 repo/Downloads 기준 미확인.
- `daylog/2026-05-19.md`: 전체 test split 2,347장 sampling, VL1+VS1 전체 1,038장 hard-negative inference, browser ONNX Runtime Web latency, 새 학습은 미실행. `/` 가용 16G, 사용률 99%.

## 내일 목표 후보
- 1순위: v3 train gate 정리. min-box 보류 9행, bbox 보정 12행, hard-negative 37행의 포함/보류/보정 기준을 학습 전 체크리스트로 고정.
- 2순위: 전체 test split sampling 실행 여부 판단. 80장 smoke 결과를 기준으로 디스크/RSS/CSV 제한을 다시 확인하고, 실행한다면 이미지 저장 없이 CSV/checkpoint만 남기는 범위로 제한.
- 3순위: source-level privacy/location audit 초안 작성. v3 후보 61행을 local-only로 유지하면서 공유/학습 편입 전 확인 항목을 명확히 분리.
- 4순위: class `1..3` 한국 GT 확보를 실제 수집 단위로 쪼개기. 킥보드/자전거, 공사 장애물, 포트홀별 최소 positive/negative 목표와 제외 기준을 실행 가능한 manifest 초안으로 정리.
- 5순위: browser/ONNX Runtime Web latency는 환경이 열릴 때만 측정. 환경이 없으면 미실행 사유만 기록하고 완료로 쓰지 않음.
- 6순위: 새 YOLO 학습은 보류. v3 gate, privacy/location audit, 디스크 확보 전에는 시작하지 않음.

## 상세 체크리스트 초안
- [ ] 시작 gate 확인 → 검증: `df -h /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads`, `sha256sum best.pt best.onnx`, `git ls-files`로 대형 산출물 미추적 확인.
- [ ] v3 후보 61행 재검산 → 검증: `walksafe_kr_v3_candidate_index_2026-05-19.csv` row count와 policy별 count가 summary JSON과 일치하는지 확인.
- [ ] min-box 보류 9행 정책 결정안 작성 → 검증: `hold_until_min_box_policy_review` 9행을 `exclude`, `manual relabel`, `small-object include` 중 하나로 분류하되 실제 학습 편입은 보류 상태로 기록.
- [ ] bbox 보정 대상 12행 점검 → 검증: `include_after_box_review` 12행의 GT/pred bbox 차이를 확인하고, 수동 라벨 보정 필요 여부와 보정 전 학습 금지 조건 기록.
- [ ] hard-negative 후보 분리 → 검증: 일반 hard-negative 33행과 threshold/augmentation 우선 4행을 별도 bucket으로 유지하고 split 혼합 금지 정책 확인.
- [ ] privacy/location audit 초안 작성 → 검증: 얼굴/차량번호/신발/민감 위치/동일 장소 연속 프레임 확인 항목을 만들고, 현재는 contact sheet 수준 검수였음을 명시.
- [ ] full test split sampling 실행/보류 판단 → 검증: `/` 가용 10GB 미만 또는 장시간/메모리 위험이면 보류, 실행 시 `--save` 없이 CSV/checkpoint만 생성하고 이미지 파일 0개 확인.
- [ ] class `1..3` 데이터 확보 manifest 초안 작성 → 검증: class별 positive/negative 목표, 우선 source, 제외 기준, split key, privacy 상태 필드 정의.
- [ ] AI Hub 159 소형 subset 보유/다운로드 가능성 재확인 → 검증: `School`, `Building_area`, `Bridge` label/image zip 존재 여부와 예상 용량을 기록. 실제 다운로드는 디스크 gate 통과 전 보류.
- [ ] ONNX browser latency 실행 가능 여부 판단 → 검증: 실행 시 기기/브라우저/p50/p95 기록, 미실행 시 브라우저/PWA 런타임 환경 부재로 `PENDING/BLOCKED` 기록.
- [ ] 2026-05-20 model 실행 문서 초안 준비 → 검증: 실행한 항목만 PASS로 쓰고, 새 학습/4-class metric/field 성능은 완료로 쓰지 않음.

## 리스크/확인 필요
- `/` 사용률 99%, 가용 16G라 전체 sampling, VL1+VS1 전체 inference, 새 학습, AI Hub 159 대형 다운로드는 디스크 gate 없이는 위험.
- v2는 class `0: damaged_tactile_block` baseline이다. class `1..3` 한국 GT가 없으므로 4-class 서비스 성능이나 90% 목표 달성 근거로 쓰면 안 됨.
- v3 후보 61행은 빠른 contact/review sheet 검수 수준이다. source-level privacy/location audit 전에는 공유나 공개 근거로 쓰면 안 됨.
- `hold_until_min_box_policy_review` 9행이 해결되기 전 v3 train을 시작하면 label noise와 작은 객체 정책 혼선 위험이 큼.
- AI Hub 159 및 직접 촬영 후보 파일은 현재 확인되지 않음. 실제 수집/다운로드 가능 여부는 사용자 환경과 디스크 확보가 필요.
- browser ONNX Runtime Web latency는 아직 실행 근거 없음. backend CPU latency나 ONNX metric equivalence로 브라우저 성능을 추정하면 안 됨.
- 현재 작업트리는 `daylog/2026-05-19.md` 수정이 남아 있음. merge 에이전트가 변경 주체를 구분해야 함.

## 병렬 에이전트 활용 메모
- 하위/병렬 에이전트는 사용하지 않았다.
- 이번 작업은 lane note 생성을 위한 읽기 전용 근거 수집이며, `docs/execution`, `daylog`, model/data manifest 범위가 좁아 단일 에이전트로 충분했다.
- 병렬 실행이 필요해지는 경우에는 `v3 후보/privacy audit`과 `class 1..3 데이터 source 조사`를 서로 다른 읽기 전용 하위 작업으로 분리하는 것이 적절하다.