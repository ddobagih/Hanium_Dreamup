# WalkSafe 문서 지도

이 문서는 현재 문서의 우선순위를 정리한다. 오래된 실행 로그보다 아래 canonical 문서를 먼저 본다.

## 먼저 볼 문서

| 문서 | 용도 |
|---|---|
| `../README.md` | 프로젝트 빠른 시작과 현재 v2 요약 |
| `current_status.md` | 현재 구현/모델/정책 상태 요약 |
| `report_operations.md` | v2 신고 운영, 검수, export, 제출 전 정책 |
| `walksafe-v2/README.md` | v2 two-model app flow 문서 인덱스 |
| `evidence/feature_matrix.md` | 2026-05-26 240개 follow-up 항목별 evidence 상태 |
| `evidence/final_report_index.md` | 최종 보고용 evidence 색인과 완료 주장 제한 |
| `../ai_tasks/README.md` | 외부 AI 검수/후속 작업 패키지 인덱스 |

## v2 구현 기준 문서

| 문서 | 용도 |
|---|---|
| `walksafe-v2/backend_api_contract.md` | `/detect/v2/health`, `/detect/v2`, `/reports/v2`, `/reports/export` 현재 계약 |
| `walksafe-v2/backend_model_integration_notes.md` | YOLO26s/COCO lazy provider 연결 및 실제 모델 연결 시 주의사항 |
| `walksafe-v2/frontend_display_policy.md` | v2 화면/음성/진동/위험 피드백 정책 |
| `walksafe-v2/auto_report_policy.md` | 자동 신고와 음성 요청 신고 정책 |
| `walksafe-v2/voice_command_strategy.md` | STT→intent→slot 기반 음성 명령 전략 |
| `walksafe-v2/navigation_integration_policy.md` | TMAP 보행 길안내와 WalkSafe 점자블록/위험 안내 통합 정책 |
| `walksafe-v2/public_agency_submission_policy.md` | 공공기관 직접 자동 제출 보류, 내부 검수/export 후 제출 정책 |
| `walksafe-v2/two_model_runtime_plan.md` | custom tactile + COCO runtime helper/current gap |
| `walksafe-v2/coco_inference_policy.md` | COCO inference-only allowlist 정책 |
| `walksafe-v2/reviewed_model_rollout_checklist.md` | 최종 모델 학습/선정 후 checkpoint/threshold/앱 검증 체크리스트 |

## 운영/현재 상태 문서

| 문서 | 용도 |
|---|---|
| `current_status.md` | 2026-05-23 KST 기준 구현 상태와 남은 확정 사항 |
| `report_operations.md` | 신고 상태, v2 자동/음성 신고, 필터/export, 검수 흐름 |
| `model_training_status.md` | 모델 학습 상태 참고. 수치는 최신 여부 확인 필요 |
| `model_v2_status.md` | 과거/참고용 v2 모델 상태. 최신 구현 기준은 `walksafe-v2/` 우선 |
| `stt_tts_current_status.md` | 음성/STT/TTS 상태 참고 |

## 참고/과거 문서

- `docs/execution/`: 날짜별 실행 기록이다. 최신 source of truth가 아니라 작업 이력으로 본다.
- `*_3day_execution_plan.md`: 2026-05-14~16 계획 문서다. 현재 구현 기준이 아니므로 새 작업 계획에는 그대로 재사용하지 않는다.
- 기존 v1 문서(`api_reference.md`, `inference_contract.md`, `pwa_backend_status.md` 등)는 legacy v1 흐름 확인용이다. v2 작업은 `walksafe-v2/` 문서를 우선한다.
- 모델 학습 수치가 포함된 문서는 작성 시점의 스냅샷일 수 있다. 최종 모델 학습/선정은 후속 확정 필요로 보고, 수치를 인용할 때는 “마지막 확인 기준”을 명시한다.

## 로컬 전용 산출물

다음은 문서에 언급되더라도 기본적으로 GitHub에 올리지 않는다.

- 데이터셋 이미지/라벨: `datasets/**/images/**`, `datasets/**/labels/**`
- 학습 결과/weight: `runs/`, `*.pt`, `*.onnx`, `*.engine`, `*.tflite`
- AI Hub 원본 zip, 음성 샘플/출력/로그
- 중간 review pack overlay/crop 대량 산출물
