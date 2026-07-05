# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors lane note (2026-05-25)

## 최근 진행 근거
- `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`(2026-05-18 결정 기준): 제품은 시각장애인·저시력 보행자용 착용형 스마트폰 PWA이며, 화면보다 TTS/진동/스크린리더를 우선한다. M1은 Android 목걸이 착용 smoke, M2는 IMU 3~5초 ROI PoC와 길안내 확장이다.
- `README.md`(2026-05-22): `apps/web` Next.js PWA, `fake-v2/server-v2`, `/detect/v2`, `/reports/v2` 흐름이 현재 기준으로 정리되어 있다.
- `docs/current_status.md`, `docs/pwa_backend_status.md`(2026-05-23): PWA는 `apps/web/app/_walksafe/`로 카메라, 센서, 탐지, 음성, 신고, 위험 피드백 hook/component가 분리되어 있고, 관리자 화면은 v2 필터/export/model health를 표시한다. Android 실폰/목걸이/TalkBack/offline/실폰 마이크 E2E는 미완료다.
- `daylog/2026-05-22.md`: v2 PWA 흐름이 크게 전진했다. `fake-v2/server-v2`, 자동 tactile damage 신고, 음성 `create_report` 우선 신고, admin v2 metadata 표시, risk evaluator 분리, `npm run lint/typecheck/build` PASS 근거가 있다.
- `daylog/2026-05-23.md`: real `/detect/v2` ASGI smoke가 Stage1 후보 모델로 `damaged_tactile_block`, `tactile_damage_area` 2개 detection 반환까지 확인됐다. 단 threshold/latency/field calibration은 보행 안전 성능 근거가 아니다.
- `docs/walksafe-v2/frontend_display_policy.md`, `docs/walksafe-v2/auto_report_policy.md`(2026-05-23): `damaged_tactile_block`은 조용한 자동 신고, `tactile_damage_area`는 보조 표시, general 객체는 bbox history 등 risk context가 있을 때만 TTS/진동 경고다.
- `docs/walksafe-v2/navigation_integration_policy.md`, `daylog/2026-05-24.md`: 기본 보행 길안내 provider가 TMAP으로 정리됐고, `useNavigationGuidance`, `navigation-api.ts`, `AssistPanel`에 길안내 상태/음성 흐름이 연결됐다. 실제 TMAP API smoke는 `TMAP_APP_KEY` 부재로 미실행이다.
- `apps/web/app/_walksafe/components/AssistPanel.tsx`, `CameraSurface.tsx`, `useSensors.ts`: 현재 코드에는 `aria-live`, `aria-pressed`, 신고 disabled reason, bbox `aria-hidden`, GPS watch, DeviceOrientation heading, 길안내 상태 카드가 있다.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports`: 디렉터리만 있고 파일 없음. `PM/status`, `PM/reports`, product-audit, automation-metrics 산출물도 없음. `plans/daily/2026-05-25.md` 없음.
- 저장소 루트 `AGENTS.md`는 없음. 사용자 제공 AGENTS 지침을 적용했다.

## 내일 목표 후보
- 1순위: TMAP 길안내와 WalkSafe 위험 안내의 TTS/진동 우선순위 회귀를 고정한다. 위험 경고가 길안내보다 우선하고, 정상 점자블록 follow 안내는 위험이 없을 때만 말해야 한다.
- 2순위: IMU/heading 3~5초 ROI fixture PoC를 PWA lane의 신규 feature slice로 전진시킨다. 실제 사용자 경고에는 연결하지 않고 순수 계산/fixture 테스트로 제한한다.
- 3순위: v2 자동 신고/음성 신고/accessibility 상태를 정적·fixture 회귀로 고정한다.
- 4순위: Android/ADB가 가능하면 목걸이 착용 smoke와 PWA 설치/offline/TalkBack을 실행한다. 불가하면 BLOCKED와 safe alternative를 명확히 남긴다.
- 5순위: TMAP key가 있으면 `/navigation/walking` 실제 smoke를 실행하고, 없으면 mock/unit/typecheck까지만 PASS로 기록한다.

## 상세 체크리스트 초안
- [ ] 시작 gate 확인 → 검증: `git status --short --branch --untracked-files=all`, `plans/features`, `plans/verify`, `PM` 산출물 존재 여부, `NEXT_PUBLIC_DETECTOR_MODE`, `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_VOICE_API_BASE`, `NEXT_PUBLIC_WALKSAFE_DESTINATION_*` 기록.
- [ ] v2 위험/신고 정책 회귀 확인 → 검증: `bash scripts/check_frontend_risk_evaluator_policy_20260523.sh`, `apps/web/tests/auto-report-v2-policy.test.ts` 경로 확인, `fake-v2` 저장 없음과 `server-v2` tactile damage 신고 정책 확인.
- [ ] 길안내 TTS 중재 확인 → 검증: `useRiskFeedback`에서 `riskActive` 시 `navigationSpeechPrompt`가 말해지지 않고, `normal_tactile_block` follow 안내는 위험이 없을 때만 동작하는지 fixture/코드 기준 확인.
- [ ] IMU/heading ROI fixture 범위 확정 → 검증: heading, 추정 속도, 3초/5초 horizon, bbox 중심점 fixture 4개 이상으로 ROI 내/외 판정. TTS/진동에는 연결하지 않음.
- [ ] 접근성 정적 점검 확장 → 검증: `aria-live`, `aria-pressed`, `aria-describedby`, 신고 disabled reason, bbox `aria-hidden`, 주요 버튼 48px/64px 이상 CSS 확인.
- [ ] PWA 정적 회귀 실행 → 검증: `cd apps/web && npm run lint && npm run typecheck && npm run build`, `node --check apps/web/public/sw.js`.
- [ ] 음성 요청 신고 경로 확인 → 검증: `create_report`가 `server-v2`에서 `trigger=voice`, `auto_reported=false`, cooldown 우회로 연결되고 `fake-v2`에서는 저장하지 않는지 확인.
- [ ] Android 목걸이 smoke 가능 시 실행 → 검증: 기기명, Chrome, 접속 방식, 카메라 각도, GPS accuracy, heading, TTS/진동, 6초 cooldown, `fake-v2/server-v2` source 분리 기록.
- [ ] PWA 설치/offline/TalkBack 가능 시 확인 → 검증: standalone 실행, 네트워크 차단 후 shell fallback, TalkBack 읽기 순서와 live region 기록.
- [ ] TMAP 길안내 smoke 분기 → 검증: `TMAP_APP_KEY` 있으면 `/navigation/walking` 실제 HTTP smoke, 없으면 `backend/tests/test_navigation_routes.py`와 `apps/web` typecheck만 PASS로 기록.
- [ ] evidence 등급 분리 → 검증: Static/Unit/ASGI real model smoke/Device E2E/Field를 표로 분리하고, fake/server-v2 결과를 모델 정확도나 보행 안전 근거로 쓰지 않음.

## 리스크/확인 필요
- Android 실폰, ADB reverse, TalkBack, offline, 목걸이 착용 환경은 장비 의존이다. safe alternative는 DevTools/코드 기반 접근성 점검과 fixture 테스트이며 Device E2E PASS로 쓰지 않는다.
- `TMAP_APP_KEY` 없이는 실제 provider smoke를 실행할 수 없다. safe alternative는 MockTransport/unit test와 프론트 typecheck다.
- 목적지 이름→좌표 변환은 아직 미구현이다. safe alternative는 `NEXT_PUBLIC_WALKSAFE_DESTINATION_LAT/LNG/NAME` 테스트 좌표만 사용한다.
- IMU ROI fixture는 실제 충돌 위험 성능 근거가 아니다. 실기기 DeviceMotion/Orientation/GPS 로그 전까지 사용자-facing 경고에 연결하지 않는다.
- 현재 작업트리는 미추적 산출물이 많고 upstream보다 뒤처진 기록이 있다. 구현 계획 시 `git add .`, 대형 산출물 추가, 임의 reset은 피한다.
- 운영 DB, secret, 외부 배포, AWS/S3, Cloud STT/TTS, Kakao/TMAP 실제 key 커밋, 지자체 API, destructive QA는 자동 계획에 직접 실행으로 잡지 않는다.

## 병렬 에이전트 활용 메모
- 사용함.
- 하위 에이전트 1: product/PM/scheduler 근거를 조사했다. 결론은 product 문서가 기준이고, `plans/features`, `plans/verify`, `PM/status`, product-audit, automation-metrics, `plans/daily/2026-05-25.md`는 반영할 파일이 없다는 것.
- 하위 에이전트 2: PWA 코드/문서/daylog 근거를 조사했다. 결론은 v2 PWA와 TMAP 길안내 연결은 전진했지만 Android/TalkBack/offline/실폰 마이크/실제 TMAP smoke/IMU ROI fixture는 미완료라는 것.
- 통합 결론: 2026-05-25 PWA lane은 막힌 실폰 검증만 반복하지 말고, 길안내와 위험 안내의 음성 우선순위, v2 신고 접근성 상태, IMU ROI fixture를 안전한 feature slice로 전진시키는 계획이 적절하다.