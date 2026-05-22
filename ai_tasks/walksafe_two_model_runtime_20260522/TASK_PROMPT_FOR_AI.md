# Task Prompt for Follow-up AI

## 역할

너는 `/home/ddobagi/Code/hanium-dreamup` 저장소의 후속 AI 워커다. 목표는 WalkSafe two-model runtime/inference 계획과 YOLO26s 학습 후속 문서를 안전하게 이어받는 것이다.

## 현재 상황

- YOLO26s custom 학습이 GPU에서 진행 중일 수 있다.
- 최종 metric, stage test 결과, 배포 방식은 아직 확정되지 않았다.
- custom tactile 모델은 3-class를 다룬다.
  - `normal_tactile_block`
  - `damaged_tactile_block`
  - `tactile_damage_area`
- COCO pretrained YOLO26n은 학습 없이 inference-only 후보로 둔다.

## 절대 금지

- 진행 중인 GPU 학습을 방해하지 마라.
- GPU 추론, GPU 평가, 새 GPU 학습, benchmark를 실행하지 마라.
- 디스크 삭제, 대량 파일 이동, cleanup 실행을 하지 마라.
- 사용자 승인 전 `git commit`, `git push`, PR 생성을 하지 마라.
- 다른 워커가 수정한 파일을 되돌리거나 임의로 포맷팅하지 마라.
- 최종 metric, threshold, runtime mode, 배포 결정을 추정으로 확정하지 마라.

## 먼저 읽을 문서

- `docs/walksafe-v2/two_model_runtime_plan.md`
- `docs/walksafe-v2/coco_inference_policy.md`
- `docs/execution/2026-05-22_model_eval_report_template.md`
- `docs/execution/2026-05-22_github_change_plan.md`
- `ai_tasks/walksafe_two_model_runtime_20260522/README.md`

## 작업 절차

1. 사용자에게 이번 턴의 소유 파일/수정 범위를 확인한다.
2. `git status --short`로 작업트리 상태를 읽되, 다른 워커 변경은 건드리지 않는다.
3. GPU가 필요한 요청이면 즉시 실행하지 말고 사용자에게 승인과 실행 가능 시간을 확인한다.
4. CPU-only 문서/요약 작업이라도 모델 로드, 추론, 평가 실행 여부를 먼저 확인한다.
5. 변경 전후 파일 목록을 좁게 유지한다.
6. 검증은 파일 존재, heading 구조, CPU-only 정적 확인처럼 현재 학습을 방해하지 않는 방식으로 제한한다.
7. 최종 보고에는 변경 파일, 요약, 검증 결과, 수행하지 않은 금지 작업을 명시한다.

## 이어서 수행 가능한 작업 후보

사용자가 명시적으로 요청한 경우에만 수행한다.

- GitHub PR 설명 초안 갱신
- 리뷰어 체크리스트 보강
- 학습 완료 후 CPU-only `results.csv` 요약 문서 작성
- two-model runtime JSON 계약 구체화
- COCO allowlist/threshold 초안 문서 업데이트
- AI handoff 문서 갱신

## Acceptance Criteria

- GPU 학습을 방해하지 않았다.
- 디스크 삭제를 하지 않았다.
- 사용자 승인 없는 commit/push/PR을 하지 않았다.
- 수정 범위가 사용자에게 받은 소유 파일에 한정되었다.
- 최종 metric이 없는 상태를 명확히 표시했다.
- two-model 결과에는 `source_model`을 유지하고 cross-model NMS를 하지 않는 원칙을 보존했다.
