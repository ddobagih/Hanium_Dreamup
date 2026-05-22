# WalkSafe 문서 지도

이 문서는 현재 문서의 우선순위를 정리한다. 오래된 실행 로그보다 아래 canonical 문서를 먼저 본다.

## 먼저 볼 문서

| 문서 | 용도 |
|---|---|
| `../README.md` | 프로젝트 빠른 시작과 현재 v2 요약 |
| `current_status.md` | 현재 구현/모델/정책 상태 요약 |
| `walksafe-v2/README.md` | v2 two-model app flow 문서 인덱스 |
| `../ai_tasks/README.md` | 외부 AI 검수/후속 작업 패키지 인덱스 |

## v2 구현 기준 문서

| 문서 | 용도 |
|---|---|
| `walksafe-v2/backend_api_contract.md` | `/detect/v2`, `/reports/v2` 현재 계약 |
| `walksafe-v2/frontend_display_policy.md` | v2 화면/음성/진동/위험 피드백 정책 |
| `walksafe-v2/auto_report_policy.md` | 자동 신고와 음성 요청 신고 정책 |
| `walksafe-v2/two_model_runtime_plan.md` | custom tactile + COCO runtime helper/current gap |
| `walksafe-v2/coco_inference_policy.md` | COCO inference-only allowlist 정책 |

## 참고/과거 문서

- `docs/execution/`: 날짜별 실행 기록이다. 최신 source of truth가 아니라 작업 이력으로 본다.
- `*_3day_execution_plan.md`: 2026-05-14~16 계획 문서다. 현재 구현 기준이 아니므로 새 작업 계획에는 그대로 재사용하지 않는다.
- 기존 v1 문서(`api_reference.md`, `inference_contract.md`, `pwa_backend_status.md` 등)는 legacy v1 흐름 확인용이다. v2 작업은 `walksafe-v2/` 문서를 우선한다.

## 로컬 전용 산출물

다음은 문서에 언급되더라도 기본적으로 GitHub에 올리지 않는다.

- 데이터셋 이미지/라벨: `datasets/**/images/**`, `datasets/**/labels/**`
- 학습 결과/weight: `runs/`, `*.pt`, `*.onnx`, `*.engine`, `*.tflite`
- AI Hub 원본 zip, 음성 샘플/출력/로그
- 중간 review pack overlay/crop 대량 산출물
