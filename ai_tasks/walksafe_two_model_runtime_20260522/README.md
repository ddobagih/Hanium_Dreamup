# WalkSafe Two-Model Runtime AI Task Handoff

- 작성일: 2026-05-22
- 목적: custom tactile YOLO26s와 COCO pretrained YOLO26n을 함께 쓰는 runtime/inference 후속 작업을 AI에게 안전하게 넘기기 위한 안내
- 현재 상태: YOLO26s custom 학습이 GPU에서 진행 중이며 최종 metric은 아직 확정되지 않았다.

## 1. 배경

이 작업 묶음은 보행 보조 서비스에서 두 종류의 객체 탐지를 분리 운용하기 위한 후속 작업이다.

- custom tactile YOLO26s
  - 점자블럭/지면 위험 중심
  - `normal_tactile_block`, `damaged_tactile_block`, `tactile_damage_area` 3-class 학습 대상
  - 현재 학습 진행 중이므로 최종 metric은 아직 없음
- COCO pretrained YOLO26n
  - 사람, 차량, 자전거, 신호등, 벤치 등 일반 객체 후보
  - 프로젝트 데이터로 학습하지 않고 inference-only 후보로 운용

## 2. 관련 문서

먼저 아래 문서를 읽고, 미확정 항목을 임의로 확정하지 않는다.

- `docs/walksafe-v2/two_model_runtime_plan.md`
- `docs/walksafe-v2/coco_inference_policy.md`
- `docs/execution/2026-05-22_model_eval_report_template.md`
- `docs/execution/2026-05-22_github_change_plan.md`

## 3. 안전 규칙

후속 AI는 아래 규칙을 반드시 지킨다.

- GPU 학습 방해 금지
  - 진행 중인 YOLO26s 학습이 있으면 추론, 평가, 새 학습, GPU benchmark를 실행하지 않는다.
  - GPU가 필요한 작업은 사용자에게 먼저 승인과 실행 시점을 확인한다.
- 디스크 삭제 금지
  - `rm`, 대량 이동, cleanup 실행, 캐시/런 삭제를 하지 않는다.
  - 정리 후보를 문서화할 수는 있지만 삭제는 사용자 승인 전 금지한다.
- GitHub 작업 승인 필요
  - `git commit`, `git push`, PR 생성은 사용자 승인 전 금지한다.
  - 필요하면 변경 계획과 diff 요약만 제공한다.
- 동시 작업 보호
  - 다른 워커가 같은 저장소에서 작업 중일 수 있으므로 지정받은 소유 파일만 수정한다.
  - 다른 워커의 변경을 되돌리거나 포맷팅하지 않는다.
- 불확실성 표시
  - 최종 metric, threshold, runtime mode, 배포 방식은 검증 전 확정하지 않는다.
  - 추정은 추정이라고 명시한다.

## 4. 이어서 시킬 수 있는 작업 예시

사용자 지시가 있을 때만 아래 작업을 선택적으로 수행한다.

### 4.1 문서 보강

- two-model runtime plan에 실제 adapter/API 경계가 생기면 문서와 JSON 예시를 맞춘다.
- COCO allowlist 변경 사유와 사용자 알림 우선순위를 업데이트한다.
- PR 설명 초안에 학습 완료 후 metric 표를 추가한다.

### 4.2 CPU-only 보고 자동화

- 학습 완료 후 `results.csv` 기반 summary만 먼저 생성한다.
- 모델 파일 로드, 추론, 평가가 포함된 명령은 실행하지 않는다.
- stage test/holdout 결과가 없으면 최종 성능으로 단정하지 않는다.

### 4.3 Runtime 구현 준비

- 구현 전 사용자에게 수정 소유 범위를 확인한다.
- custom tactile 결과와 COCO 결과에 `source_model`을 유지하는 계약을 먼저 정의한다.
- 모델 간 cross-model NMS를 넣지 않는다.
- threshold는 평가 결과와 smoke test 후 확정한다.

## 5. 기대 산출물

후속 AI 작업의 산출물은 사용자 지시에 따라 달라진다. 기본적으로는 아래 중 하나다.

- 문서 업데이트
- CPU-only 요약 결과
- runtime 계약 초안
- GitHub PR 설명/체크리스트 갱신

실제 commit/push/PR은 사용자 승인 없이는 산출물에 포함하지 않는다.

## 6. 검증 기준

- 수정 파일 목록이 사용자에게 받은 소유 범위를 넘지 않는다.
- Markdown 문서는 heading 구조를 확인한다.
- CPU-only 스크립트라도 모델 로드나 평가 실행 여부를 먼저 확인한다.
- GPU 작업, 디스크 삭제, 승인 없는 GitHub 작업을 수행하지 않았다고 최종 보고에 명시한다.
