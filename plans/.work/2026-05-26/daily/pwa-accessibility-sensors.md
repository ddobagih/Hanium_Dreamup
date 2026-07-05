# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors lane note (2026-05-27)

## 최근 진행 근거
- `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md` / 최신 제품 기준: 화면보다 TTS·진동·스크린리더·큰 터치 영역을 우선하는 착용형 PWA. PWA 설치/offline/TalkBack, Android 목걸이 smoke, IMU/ROI field는 아직 완료 근거 없음.
- `docs/current_status.md` / 2026-05-25: `apps/web/app/_walksafe/`에 카메라, 센서, 탐지, 음성, 신고, 위험 피드백, 길안내 hook이 분리됨. Admin은 v2 필터/export/model health까지 구현됨.
- `docs/pwa_backend_status.md` / 2026-05-23: 카메라, GPS, DeviceOrientation heading, Web Speech TTS, 진동, v2 자동/음성 신고, PWA manifest/service worker, `/admin` 운영 화면 구현 상태 확인. Android 실폰/목걸이, PWA install/offline/TalkBack, 실폰 mic E2E는 미완료.
- `daylog/2026-05-25.md`: Android `SM_S931N` USB smoke에서 PWA load, 후면 카메라 preview, fake-v2 overlay, GPS 표시, 음성 UI 권한 prompt 확인. 단 TalkBack/install/offline/목걸이 실보행/실폰 mic는 완료 아님.
- `daylog/2026-05-26.md`, `/home/ddobagi/PM/status/2026-05-26-features.md`: PM feature commit `1320a07`이 `/detect/v2` adapter seam, `/reports` v2 metadata filter, PWA Wake Lock, opt-in local TTS fallback을 구현.
- `/home/ddobagi/PM/status/2026-05-26-verify.md`, `/home/ddobagi/PM/reports/verify/2026-05-26/hanium-dreamup/2026-05-26-hanium-dreamup-feature-batch-report.md`: verify `fail`, `push_ready=False`. 원인: `apps/web/app/_walksafe/hooks/useWakeLock.ts:71` lint 실패, PostGIS no-skip은 Docker socket 권한 문제로 차단.
- `plans/features/2026-05-26_feature_followup_implementation_list.md`: PWA/offline, 접근성 live region, DeviceMotion permission, ROI debug gate, settings privacy, evidence matrix가 다음 구현 후보로 정리됨.
- `plans/daily/2026-05-27.md`: 현재 없음. 프로젝트 내부 `plans/verify`도 파일 없음. PM verify 산출물은 `/home/ddobagi/PM/...`에서 확인.
- 현재 checkout: 원격보다 10커밋 behind이고 dirty/untracked가 많음. PM isolated worktree 결과와 현재 checkout 결과를 섞지 말아야 함.

## 내일 목표 후보
- 1순위: 5/26 PWA Wake Lock verify unblock. lint 실패를 먼저 해소하고 `npm run lint/typecheck/build` 기준으로 PM feature batch push 가능성을 회복.
- 2순위: PWA install/offline thin slice. 설치 가능/설치됨 상태, standalone/display-mode, SW version/update 상태, offline shell 문구를 mock/static으로 검증.
- 3순위: 음성 녹음 fallback 상태 세분화. 마이크 권한 거부, 서버 미연결, timeout, 빈 녹음, low confidence를 서로 다른 UI/TTS/진동 상태로 고정.
- 4순위: 센서/ROI safe gate. DeviceMotion permission/unsupported 상태, 자동 보폭 confidence, heading/speed unknown 시 ROI unknown 처리, debug gate를 추가 또는 검증.
- 5순위: 접근성 정적 회귀 강화. 상태/위험 요약 DOM 우선, `aria-live`, `aria-pressed`, `aria-describedby`, bbox `aria-hidden`, 터치 타깃, settings privacy script를 evidence로 묶기.
- 6순위: Android Device E2E는 환경이 열릴 때만 실행. 불가하면 BLOCKED와 static/fixture 대안만 기록.

## 상세 체크리스트 초안
- [ ] source-of-truth gate 작성 → 검증: 현재 checkout, PM worktree `1320a07`, `plans/daily/2026-05-27.md` 부재, dirty/behind 상태를 표로 분리
- [ ] Wake Lock lint 실패 수정 → 검증: PM worktree 기준 `cd apps/web && npm run lint && npm run typecheck && npm run build`
- [ ] Wake Lock 상태 UI smoke 추가/확인 → 검증: unsupported, idle, requesting, active, paused, denied/error 상태가 `aria-live`와 버튼 상태로 구분되는지 DOM/static test
- [ ] PWA install/offline 상태 slice → 검증: `beforeinstallprompt`, `display-mode: standalone`, `navigator.onLine`, SW no-store를 mock/static으로 확인
- [ ] 음성 녹음 fallback matrix → 검증: NotAllowedError, STT server down, timeout, empty audio, low confidence fixture가 서로 다른 message/vibration/TTS 정책을 통과
- [ ] DeviceMotion/보폭 상태 표시 → 검증: 미지원/권한 필요/수집 중/추정됨/fallback 상태 unit 또는 fixture test
- [ ] ROI debug gate와 unknown 처리 → 검증: heading/speed 없음에서 `on_path` 과대판정 금지, debug off이면 TTS primary 판단에 영향 없음
- [ ] 접근성/PWA 정적 회귀 → 검증: `python3 scripts/check_frontend_accessibility_static.py`, `node --check apps/web/public/sw.js`
- [ ] 기존 정책 smoke 재확인 → 검증: `scripts/check_frontend_route_progress_policy_20260525.sh`, `scripts/check_frontend_step_length_policy_20260525.sh`, `scripts/check_frontend_risk_evaluator_policy_20260523.sh`
- [ ] settings privacy 회귀 → 검증: `bash scripts/check_frontend_settings_privacy_20260526.sh`, 보호자 연락처가 report/STT payload로 전송되지 않음
- [ ] Android 가능 시 Device E2E → 검증: 기기명, Chrome, ADB reverse/LAN, 권한, 카메라 각도, GPS accuracy, heading, TTS/진동, `fake-v2/server-v2` source 분리 기록

## 리스크/확인 필요
- PM feature batch는 verify 실패 상태라 push-ready가 아님. safe alternative: Wake Lock lint만 먼저 수정하고 나머지는 verify marker 재실행 대상으로 둔다.
- 현재 checkout은 dirty + behind 10. safe alternative: PM isolated worktree와 현재 checkout을 분리 기록하고, 임의 reset/add/push 금지.
- Android/TalkBack/install/offline/목걸이 field는 env 필요. safe alternative: 정적 접근성, mock install/offline, fixture 기반 센서 검증만 PASS로 기록.
- Wake Lock은 화면 꺼짐 완화이지 background camera 보장이 아님. safe alternative: UI 문구를 “앱이 보이는 동안 화면 꺼짐을 줄임” 수준으로 제한.
- DeviceMotion/ROI는 field 전까지 안전 판단 근거가 아님. safe alternative: debug gate와 unknown 처리만 제품 코드에 남김.
- local TTS fallback은 voice server가 있어야 HTTP 검증 가능. safe alternative: opt-in 설정, timeout/non-audio/browser fallback fixture를 우선.
- `distance_m`는 실제 depth 추정이 아님. safe alternative: 명시적 거리/source/confidence가 없으면 “약 N보 앞” 문구 금지.
- PostGIS/Docker 차단은 PWA lane 단독으로 해소 불가. safe alternative: PWA/API client mock과 serializer fixture만 사용하고 DB PASS로 쓰지 않음.

## 병렬 에이전트 활용 메모
- 사용함.
- Explorer 1: product, PM feature/verify/product-audit/automation metrics, `plans/features`를 조사. 결론: 2026-05-26 PM feature는 Wake Lock/local TTS 등 PWA 관련 신규 slice가 있으나 verify 실패, 내일은 PWA/offline·접근성·센서 safe gate가 우선.
- Explorer 2: `apps/web`, README, docs, 최근 daylog, 기존 daily plan을 조사. 결론: PWA 핵심 hook과 일부 Android smoke는 존재하지만 TalkBack/install/offline/실폰 mic/목걸이 field는 미완료, 현재 검증은 `scripts/check_frontend_*.sh` 중심으로 잡아야 함.
- 통합 결론: 내일은 “검증 실패 해소 + product 목표를 미는 PWA install/offline·센서·음성 fallback slice”로 계획한다.