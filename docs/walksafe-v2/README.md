# WalkSafe v2 two-model app flow

이 폴더는 WalkSafe v2의 two-model 탐지, 자동 신고, 사용자 경고 정책의 현재 기준 문서를 모은다.

## 현재 구현 요약

- Frontend는 `fake-v2`, `server-v2` 모드를 지원한다.
- Backend는 `/detect/v2`, `/reports/v2`를 제공한다.
- `/detect/v2`는 아직 실제 YOLO 추론이 아니라 fake contract이다.
- `/reports/v2`는 `custom_tactile`의 damage class만 저장한다.
- 일반 객체는 신고하지 않고, 보행 위험일 때만 경고한다.

## 문서 구조

| File | 내용 |
|---|---|
| `backend_api_contract.md` | `/detect/v2`, `/reports/v2` 현재 백엔드 계약 |
| `frontend_display_policy.md` | tactile/general 표시, 음성/진동, risk feedback 정책 |
| `auto_report_policy.md` | 자동 신고, 음성 요청 신고, 경고/신고 분리 정책 |
| `two_model_runtime_plan.md` | custom tactile + COCO helper runtime 현황과 gap |
| `coco_inference_policy.md` | COCO pretrained inference-only allowlist 정책 |

## 코드 위치

- Runtime helper: `model/two_model_runtime.py`
- Runtime config: `configs/walksafe_two_model_runtime_20260522.yaml`
- Backend routers/services: `backend/app/api/`, `backend/app/services/`
- Frontend UI/hooks: `apps/web/app/_walksafe/`
- Frontend v2 clients/types: `apps/web/lib/*-v2.ts`, `apps/web/types/inference-v2.ts`

## 주의

과거 별도 안전 감사 문서 내용은 현재 계약 문서로 흡수했다. 최신 구현 확인은 이 README의 문서들을 기준으로 한다.
