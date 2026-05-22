# Task prompt for follow-up AI

너는 `/home/ddobagi/Code/hanium-dreamup` 저장소의 WalkSafe v2 two-model runtime 후속 작업자다.

## 현재 구현

- `model/two_model_runtime.py`: CPU-only filtering/merge helper
- `configs/walksafe_two_model_runtime_20260522.yaml`: custom tactile + COCO allowlist/threshold config
- `POST /detect/v2`: 현재 fake contract. 실제 YOLO 추론 아님
- `POST /reports/v2`: `custom_tactile` damage class만 저장
- Frontend `fake-v2`, `server-v2`: v2 display, automatic tactile damage report, voice-priority report 지원

## 제품 정책

- tactile damage는 자동 신고한다.
- 자동 신고 완료/실패는 기본 TTS로 말하지 않는다.
- 사용자가 음성으로 신고를 요청한 경우에는 완료/실패를 짧게 말한다.
- COCO/general 객체는 신고하지 않는다.
- 일반 객체는 보행 경로 차단 또는 충돌 가능 접근일 때만 사용자에게 경고한다.

## 절대 주의

- weight/dataset/runs를 commit하지 마라.
- GPU 학습/평가/추론을 시작하기 전에 사용자의 명시 승인을 받아라.
- v2 class id를 전역 id로 합치지 마라.
- cross-model NMS를 추가하지 마라.
- COCO/general 객체를 `/reports/v2`에 저장하지 마라.
- 다른 작업자의 변경을 되돌리지 마라.

## 먼저 읽을 문서

- `docs/current_status.md`
- `docs/walksafe-v2/README.md`
- `docs/walksafe-v2/backend_api_contract.md`
- `docs/walksafe-v2/two_model_runtime_plan.md`
- `docs/walksafe-v2/auto_report_policy.md`
- `ai_tasks/walksafe_two_model_runtime_20260522/README.md`

## 권장 작업 절차

1. `git status --short`로 작업트리 상태를 확인한다.
2. 사용자에게 받은 소유 파일 범위를 넘지 않는다.
3. 변경 전 관련 테스트를 찾고, 변경 후 최소 검증을 실행한다.
4. 문서 변경 시 현재 코드와 모순되는 문구를 제거한다.
5. 최종 보고에는 변경 파일, 검증 명령, 아직 미구현인 점을 명시한다.
