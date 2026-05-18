# Hanium Dreamup / WalkSafe Assist - Vision Model/Data/MLOps weekly lane note (2026-W21)

## 이번 주 목표 후보

- v2 모델 상태를 “class `0 damaged_tactile_block` 기준선”으로 고정하고, 4-class 서비스 성능으로 표현하지 않도록 문서 충돌을 정리한다.
- v3 후보 데이터의 시작점을 확정한다: 기존 manifest 40행 + VL1/VS1 hard-negative FP review 21행.
- full failure sampling은 기존 방식 반복 대신 streaming/checkpoint 방식으로 smoke부터 검증한다.
- class `1..3` 한국 GT 확보 계획을 파일/장소/라벨링 단위까지 쪼갠다.
- ONNX는 metric equivalence 완료 상태로 두되, browser/mobile latency 근거가 생기기 전까지 backend ready artifact는 `.pt` 유지로 제안한다.
- 새 학습은 이번 주 기본 목표로 두지 않는다. 디스크, privacy, split, 라벨 정책 gate를 통과한 경우에만 v3 학습 준비로 전환한다.
- 확인 결과 `plans/weekly/2026-W21.md`는 없고, `plans/.work/2026-05-18/weekly`에는 다른 lane note만 있어 이 lane은 새 weekly note로 시작한다.

## 날짜별/단계별 체크리스트

- 2026-05-18 월: 시작선 고정
  - [ ] 최근 `daylog`, `docs/execution/2026-05-18_model_data_mlops.md`, 모델 상태 문서를 기준으로 완료/미완료를 분리한다.
  - [ ] v2 `.pt`/`.onnx` hash, Git 대형 산출물 미추적, `/` 디스크 상태를 주간 gate로 둔다.

- 2026-05-19 화: sampler smoke
  - [ ] streaming failure sampler 범위를 확정한다.
  - [ ] test image 50~100장 기준으로 CSV append, checkpoint, bucket count, 이미지 저장 off 동작을 확인한다.
  - [ ] full sampling 실행 여부는 디스크/RSS/CSV 크기 조건으로 판단한다.

- 2026-05-20 수: v3 후보 정책
  - [ ] 40행 curation manifest와 21행 hard-negative FP review를 v3 후보 인덱스로 통합한다.
  - [ ] `review_min_box_policy_before_training` 9건을 포함/제외/라벨 보정 후보로 나눈다.
  - [ ] 강한 마모/그림자 FP 후보는 hard-negative, threshold, augmentation 중 어느 정책으로 처리할지 결정한다.

- 2026-05-21 목: v4 데이터 계획
  - [ ] 킥보드/자전거, 공사 장애물, 포트홀별 한국 positive/negative source를 확정한다.
  - [ ] 직접 촬영, AI Hub 159, AI Hub 189, 지자체 공개 사진 후보를 “다운로드 가능/승인 필요/보류”로 분류한다.
  - [ ] 얼굴, 차량번호, 민감 위치 비식별 기준을 라벨링 전 gate로 둔다.

- 2026-05-22 금: 외부 검증/latency 정리
  - [ ] VL2+VS2 완료/미완료 문서 충돌을 실행 로그 기준으로 정리한다.
  - [ ] browser/ONNX Runtime Web latency는 기기/브라우저/PWA 환경이 확보될 때만 실행한다.
  - [ ] 실행하지 못하면 미실행 사유와 필요한 환경만 남긴다.

- 2026-05-23 토: v3 준비물 정리
  - [ ] `datasets/walksafe_kr_v3_candidates` 후보 구조와 split 정책을 문서화한다.
  - [ ] 같은 장소/시퀀스가 train/val/test에 섞이지 않도록 split key를 정한다.
  - [ ] 새 학습은 후보 이미지 privacy/split/라벨 정책이 통과한 뒤로 보류한다.

- 2026-05-24 일: 주간 정리
  - [ ] 이번 주 실행/보류/막힘을 `docs/execution`와 daylog 기준으로 요약한다.
  - [ ] 다음 주로 넘길 항목을 “학습 가능”, “데이터 필요”, “장비/디스크 필요”, “제품 판단 필요”로 분리한다.

## 검증 계획

- Artifact gate: `df -h`, `sha256sum best.pt best.onnx`, `git ls-files '*.pt' '*.onnx' 'runs/**' 'datasets/**/images/**' 'datasets/**/labels/**'`.
- Manifest gate: v3 curation CSV 40행, hard-negative FP review CSV 21행, summary JSON load 확인.
- Dataset gate: 새 subset을 만들 경우 `python model/validate_yolo_dataset.py --data <data.yaml>`.
- Sampler gate: 50~100장 smoke에서 CSV/checkpoint 생성, bucket count 기록, 대량 이미지 미생성 확인.
- Metric gate: v2는 기존 test/ONNX/external validation 수치를 그대로 인용하고, 새로 실행하지 않은 metric은 추가하지 않는다.
- Latency gate: browser/mobile ONNX는 기기명, 브라우저, 입력 크기, p50/p95를 함께 기록할 때만 완료로 본다.
- Reporting gate: fake/server/model metric/field 관찰을 섞지 않는다.

## 리스크/확인 필요

- `/` 사용률 99% 기록이 있어 대형 복사, full inference, 새 학습은 디스크 gate 없이 시작하면 안 된다.
- v2는 class `0` 기준선이다. class `1..3` 한국 GT가 없으므로 4-class metric은 산출 불가다.
- 기존 full failure sampling은 exit code `137` 기록이 있어 같은 방식 반복은 피한다.
- VL2+VS2 외부 validation 상태가 문서별로 충돌한다. 실행 로그는 완료 근거가 있으므로 최신 상태 문서 보정이 필요하다.
- contact sheet 검수는 빠른 triage이며 정식 개인정보/위치정보 비식별 검수가 아니다.
- 원본 이미지, 라벨, `runs/`, `.pt`, `.onnx`는 GitHub 업로드 대상이 아니다.
- browser/ONNX latency는 PWA/기기 환경이 없으면 이번 주에도 보류될 수 있다.

## 병렬 에이전트 활용 메모

- 이번 lane note 작성에는 별도 하위/병렬 에이전트를 사용하지 않았다.
- 근거가 최근 model execution 문서, manifest, daylog에 집중되어 단일 에이전트의 병렬 파일 조회로 충분했다.
- 실제 실행 단계에서는 sampler smoke, v3/v4 데이터 정책, ONNX/browser latency 조사를 서로 다른 산출물 범위로 나누면 병렬화 가능하다.