# WalkSafe v2 unified-primary app flow

이 폴더는 WalkSafe v2의 unified-primary 탐지, legacy fallback, 자동 신고, 사용자 경고 정책의 현재 기준 문서를 모은다.

## 2026-07-02 현재 위치

- 주 사용자 앱 경로는 Android native ARCore/TFLite APK다.
- PWA `fake-v2`/`server-v2`는 demo/API contract 검증 경로다.
- Backend `/detect/v2`, `/reports/v2`, `/reports/export`는 계속 운영/API 기준이다.
- 2026-07-02 사용자 정책 결정은 `policy_decisions_20260702.md`를 우선 기준으로 둔다.
- Android report upload와 TTS/haptic 연결 코드는 Device Gate 뒤에 붙어 있다. bbox/depth 좌표 정합, 실기기 TTS/진동, PostGIS no-skip 검증 전에는 제품 완료 evidence로 쓰지 않는다.

## 현재 구현 요약

- Android native는 `model-config/two_model_runtime.json`에서 `unified_walksafe`를 primary로 사용하고, unified asset이 없을 때만 legacy two-model fallback을 사용한다.
- 다음 모델 후보는 YOLO26n unified 13-class 단일 모델이다. class order/source plan은 `../model_unified_13class_aihub_sources_20260602.md`를 기준으로 한다.
- Frontend/PWA는 `fake-v2`, `server-v2` 모드를 지원한다.
- Backend는 `/detect/v2`, `/reports/v2`, `/reports/export`를 제공한다.
- `/detect/v2` 기본값은 fake contract이고, `yolo`/`real` mode는 model path가 있으면 lazy YOLO provider를 사용한다.
- `/reports/v2`는 `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block`만 저장한다.
- Android native upload는 report metadata에 `source=android`, APK/config/depth/gate metadata를 싣고 backend/admin/export에서 Android source로 분리된다.
- `/reports/export`는 신고 목록을 CSV/JSON/GeoJSON으로 내보낸다.
- 일반 객체는 신고하지 않고, 보행 위험일 때만 경고한다.
- TMAP 보행 경로는 `/navigation/walking`으로 받아 WalkSafe TTS/점자블록 보조 안내와 분리 결합한다. Android에서는 후순위 재사용 후보다.

## 문서 구조

| File | 내용 |
|---|---|
| `backend_api_contract.md` | `/detect/v2`, `/reports/v2`, `/reports/export` 현재 백엔드 계약 |
| `frontend_display_policy.md` | Web/PWA tactile/general 표시, 음성/진동, risk feedback 정책 |
| `auto_report_policy.md` | 자동 신고, 음성 요청 신고, 경고/신고 분리 정책 |
| `two_model_runtime_plan.md` | unified-primary/legacy-fallback runtime config 분리 기준 |
| `coco_inference_policy.md` | COCO pretrained inference-only allowlist 정책 |
| `backend_model_integration_notes.md` | 학습 완료 후 YOLO adapter를 `/detect/v2`에 연결할 때의 backend 주의사항 |
| `reviewed_model_rollout_checklist.md` | reviewed YOLO26s 학습 완료 후 모델 선택/threshold/앱 검증 체크리스트 |
| `voice_command_strategy.md` | 모든 문장 녹음 없이 STT→intent→slot으로 음성 명령을 처리하는 전략 |
| `navigation_integration_policy.md` | TMAP 보행 길안내와 WalkSafe 점자블록/위험 안내 통합 정책 |
| `policy_decisions_20260702.md` | P-01~P-17 사용자 확정/미확정 정책 source of truth |
| `threshold_tuning_playbook_20260702.md` | P-04 threshold와 P-07/P-08/P-09 조정 판단 기준 |
| `public_agency_submission_policy.md` | 기관 제출 기능 없음, 운영자 내부 export만 제공하는 정책 |
| `android_report_source_metadata_decision_20260601.md` | Android native report upload 전 source/metadata/allowlist 결정 초안 |
| `concept_decision_questions_20260523.md` | 구현이 엇갈리지 않도록 확정해야 할 컨셉 질문 목록 |

## 코드 위치

- Android native app: `apps/android/`
- Android runtime config: `apps/android/app/src/main/assets/model-config/two_model_runtime.json`
- Android TFLite detector: `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/`
- Android ARCore depth: `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/depth/`
- Backend runtime helper: `model/two_model_runtime.py`
- Backend runtime config: `configs/walksafe_two_model_runtime_stage1_mvp_20260523.json`
- Backend routers/services: `backend/app/api/`, `backend/app/services/`
- Frontend UI/hooks: `apps/web/app/_walksafe/`
- Frontend v2 clients/types: `apps/web/lib/*-v2.ts`, `apps/web/types/inference-v2.ts`

## 주의

- Android TFLite threshold와 backend `/detect/v2` threshold를 같은 평가 조건으로 섞지 않는다.
- PWA/headless 결과는 Android ARCore depth/`N보` Device PASS가 아니다.
- 과거 별도 안전 감사 문서 내용은 현재 계약 문서로 흡수했다. 최신 구현 확인은 이 README와 `docs/current_status.md`를 기준으로 한다.
