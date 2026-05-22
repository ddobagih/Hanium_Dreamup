# WalkSafe v2 two-model app flow

이 폴더는 WalkSafe v2의 two-model 탐지, 자동 신고, 사용자 경고 정책 문서를 모은다.

## 문서 구조

| File | 내용 |
| --- | --- |
| `two_model_runtime_plan.md` | custom tactile 모델과 COCO helper 모델을 분리 운용하는 런타임 계획 |
| `coco_inference_policy.md` | COCO pretrained 모델의 inference-only 사용 정책 |
| `backend_api_contract.md` | `/detect/v2`, `/reports/v2` 백엔드 API 계약 |
| `backend_safety_audit.md` | backend v2 구현/분리 시 주의점 |
| `frontend_display_policy.md` | tactile/general 채널 분리, 화면/음성 표시 정책 |
| `auto_report_policy.md` | 자동 신고, 음성 신고, 사용자 경고 우선순위 정책 |

## 코드 위치

- Runtime helper: `model/two_model_runtime.py`
- Backend API/services: `backend/app/api/`, `backend/app/services/`
- Frontend UI/hooks: `apps/web/app/_walksafe/`
- Frontend v2 clients/types: `apps/web/lib/*-v2.ts`, `apps/web/types/inference-v2.ts`
