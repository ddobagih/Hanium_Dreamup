# 2026-05-26 DeviceMotion / Settings / PWA Worker

## 범위

- 사용자 승인 범위: 실제 DeviceMotion field calibration은 코드/UI/log schema/dry-run까지 구현하되, 실기기 측정 완료로 주장하지 않는다.
- 제외: 실제 전화/SMS 발신, LLM/Cloud NLU 연결, 배포/secret/운영 작업.
- 근거 항목: #6 보폭 자동 측정, #17 긴급 연락처, #18 초기 설정 UI, 공통 PWA/offline 중 local/headless 가능한 항목.

## 구현

- `useAutoStepLength` / `step-length`
  - DeviceMotion permission 상태(`unsupported/prompt/granted/denied/listening`)와 권한 요청 콜백.
  - GPS+motion 기반 dry-run 보폭 추정, confidence, GPS 튐/outlier count, walking speed, min sample gate.
  - 보정 TTL 저장/만료 파싱, localStorage 예외 처리, reset/recalibration, dry-run calibration log schema.
- `useWalkSafeSettings` / `AssistPanel`
  - schema v3 복수 긴급 연락처(최대 3개) local-only 구조.
  - 전화번호 validation/masking/clear, 실제 전화/SMS 링크·발신 없음 회귀.
  - 보폭 재보정 UI, DeviceMotion 권한/샘플/신뢰도 표시.
- `usePwaStatus` / `sw.js`
  - PWA install prompt/appinstalled 상태 감지.
  - service worker `SW_VERSION`, update-ready/apply message, cached root shell navigation fallback.
  - offline 상태/설치/update 메시지 UI wiring.
- 정적/evidence
  - PWA/accessibility static check에 install/update/offline shell 조건 추가.
  - feature matrix/privacy checklist에 #6/#17/#18/PWA evidence 상태 갱신.

## 검증

| 명령 | 결과 |
|---|---|
| `bash scripts/check_frontend_step_length_policy_20260525.sh` | PASS |
| `bash scripts/check_frontend_settings_privacy_20260526.sh` | PASS |
| `bash scripts/check_frontend_pwa_policy_20260526.sh` | PASS |
| `python scripts/check_frontend_accessibility_static.py` | PASS |
| `python -m py_compile scripts/check_frontend_accessibility_static.py` | PASS |
| `node --check apps/web/public/sw.js` | PASS |
| `cd apps/web && npm run typecheck` | PASS |
| `cd apps/web && npm run lint` | PASS |
| `bash scripts/check_frontend_navigation_guidance_policy_20260524.sh` | PASS |
| `bash scripts/check_frontend_route_progress_policy_20260525.sh` | PASS |
| `bash scripts/check_frontend_admin_report_summary_policy_20260525.sh` | PASS |

## 남은 리스크

- Android 실폰 DeviceMotion/GPS 목걸이 착용 보정 로그는 아직 없다.
- TalkBack, PWA install/offline Android field, 실제 service worker update UX는 수동 실기기 evidence가 필요하다.
- 실제 전화/SMS는 요청 범위에서 제외했고 구현하지 않았다.
- PNG/maskable bitmap icon 생성은 대용량/디자인 작업 없이 보류했고 현재 SVG manifest만 검증했다.
