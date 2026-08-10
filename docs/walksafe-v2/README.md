# WalkSafe v2 unified-primary app flow

이 폴더는 WalkSafe v2의 unified-primary 탐지, legacy fallback, 자동 신고, 사용자 경고 정책의 현재 기준 문서를 모은다.

## 2026-07-11 현재 위치

- 주 사용자 앱 경로는 Web/PWA다.
- PWA `server-v2`는 실제 backend 연동 경로이고, `fake-v2`는 개발·API contract 검증 전용이다.
- Backend `/detect/v2`, `/reports/v2`, `/reports/export`는 계속 운영/API 기준이다.
- same-origin gateway는 설정 누락 시 fail-closed이며 field/admin named actor session을 상호 대체하지 않는다. 파일 login limiter, UI logout과 backend actor audit를 제공한다.
- 2026-07-02 사용자 정책 결정은 `policy_decisions_20260702.md`를 우선 기준으로 둔다.
- Android native는 ARCore/depth/TFLite 실험·검증 보조 경로다. report upload와 TTS/haptic 연결 코드는 Device Gate 뒤에 있지만 Web/PWA 완료 evidence를 대체하지 않는다.

## 현재 구현 요약

- Android native는 `model-config/two_model_runtime.json`에서 `unified_walksafe`를 primary로 사용하고, unified asset이 없을 때만 legacy two-model fallback을 사용한다.
- 다음 모델 후보는 YOLO26n unified 13-class 단일 모델이다. class order/source plan은 `../model-data/model_unified_13class_aihub_sources_20260602.md`를 기준으로 한다.
- Frontend/PWA는 `fake-v2`, `server-v2` 모드를 지원하며 실제 사용자 탐지는 명시적으로 설정한 `server-v2`를 사용해야 한다.
- Backend는 `/detect/v2`, `/reports/v2`, `/reports/export`를 제공한다.
- `/detect/v2` 기본값은 fake contract이고, `yolo`/`real` mode는 model path가 있으면 lazy YOLO provider를 사용한다.
- `/reports/v2`는 `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block`만 저장한다.
- Android native upload는 report metadata에 `source=android`, APK/config/depth/gate metadata를 싣고 backend/admin/export에서 Android source로 분리된다.
- `/reports/export`는 신고 목록을 CSV/JSON/GeoJSON으로 내보낸다.
- export는 내부 `internal`, 좌표·민감정보를 줄인 `minimum`, 손상 위치 신고에 필요한 정확 좌표 최소 필드 `agency` profile을 분리한다. 관리자 정확 위치 grid는 internal 전용이다.
- 일반 객체는 신고하지 않고, 보행 위험일 때만 경고한다.
- Web `server-v2`는 새 camera media frame을 기본 450ms 간격으로 sampled 탐지한다. frozen/중복 frame·stale 응답·server 실패를 “위험 없음”으로 표시하지 않으며, 전체 후보를 IoU instance별로 처리하고 모든 경고에 서로 다른 3 frames AND 700ms 안정화를 적용한다. bbox TTC는 단독 high/STOP으로 쓰지 않는다.
- TMAP-only 목적지 검색과 보행 경로는 backend 앱 전용 schema로 정규화되고 Web/PWA 길안내 hook과 Android 검증 UI에서 사용한다. Web 음성은 목적지 변경·취소와 다음 안내 질의를 포함하고 `risk > interaction > navigation`으로 중재한다. 사용자 TTS는 browser `speechSynthesis`다.
- 정상 점자블록은 TMAP 방향과 약 3~5초 camera future ROI가 정렬될 때의 근거리 보조 관측일 뿐 실제 route graph가 아니다. Web/PWA의 outdoor route, 브라우저 STT/TTS와 Release 검증은 아직 PASS가 아니다.

## 문서 구조

| File | 내용 |
|---|---|
| `backend_api_contract.md` | `/detect/v2`, `/reports/v2`, `/reports/export` 현재 백엔드 계약 |
| `frontend_display_policy.md` | Web/PWA tactile/general 표시, 음성/진동, risk feedback 정책 |
| `auto_report_policy.md` | 자동 신고, 음성 요청 신고, 경고/신고 분리 정책 |
| `two_model_runtime_plan.md` | unified-primary/legacy-fallback runtime config 분리 기준 |
| `coco_inference_policy.md` | COCO pretrained inference-only allowlist 정책 |
| `backend_model_integration_notes.md` | 현재 YOLO adapter/provider 연결과 실제 checkpoint 배포 시 주의사항 |
| `reviewed_model_rollout_checklist.md` | legacy reviewed YOLO26s fallback의 모델 선택/threshold/앱 검증 참고 |
| `voice_command_strategy.md` | 모든 문장 녹음 없이 STT→intent→slot으로 음성 명령을 처리하는 전략 |
| `navigation_integration_policy.md` | TMAP 보행 길안내와 WalkSafe 점자블록/위험 안내 통합 정책 |
| `policy_decisions_20260702.md` | P-01~P-18 사용자 확정/미확정 정책 source of truth |
| `threshold_tuning_playbook_20260702.md` | P-04 threshold와 P-07/P-08/P-09 조정 판단 기준 |
| `public_agency_submission_policy.md` | 관리자 검수·필터·CSV 다운로드 후 수동 외부 신고, 자동 API 제출 제외 정책 |
| `android_report_source_metadata_decision_20260601.md` | Android native report source/metadata/allowlist 결정 기록 |
| `concept_decision_questions_20260523.md` | 과거 질문 목록. 결정된 항목은 `policy_decisions_20260702.md`가 우선 |
| `implementation_confidence_audit_20260704.md` | 코드/unit 근거와 Device/DB/운영 미검증 범위 감사 snapshot |
| `cleanup_inventory_20260704.md` | 삭제 문서가 아닌 legacy/debug/운영 격리 후보 분류표 |

## 코드 위치

- Web/PWA primary app: `apps/web/`
- Frontend UI/hooks: `apps/web/app/_walksafe/`
- Frontend v2 clients/types: `apps/web/lib/*-v2.ts`, `apps/web/types/inference-v2.ts`
- Android experimental/validation app: `apps/android/`
- Android runtime config: `apps/android/app/src/main/assets/model-config/two_model_runtime.json`
- Android TFLite detector: `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/`
- Android ARCore depth: `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/depth/`
- Backend runtime helper: `model/two_model_runtime.py`
- Backend runtime config: `configs/walksafe_two_model_runtime_stage1_mvp_20260523.json`
- Backend routers/services: `backend/app/api/`, `backend/app/services/`

## 주의

- Android TFLite threshold와 backend `/detect/v2` threshold를 같은 평가 조건으로 섞지 않는다.
- Web/PWA policy/headless 결과는 브라우저·실폰·Release PASS가 아니며 Android ARCore depth/`N보` Device PASS도 아니다. Android evidence 역시 Web/PWA 완료를 대체하지 않는다.
- Chromium PWA lifecycle과 정책 테스트가 PASS해도 실폰 설치/mic/실외 route, 실제 기관 receipt·사람의 제출 이미지 privacy receipt, telemetry/report retention apply, 운영 account, OpenPGP backup·복구, Android 증거가 없으면 release evidence gate가 FAIL한다.
- 과거 별도 안전 감사 문서 내용은 현재 계약 문서로 흡수했다. 최신 구현 확인은 이 README와 `docs/status/current_status.md`를 기준으로 한다.
