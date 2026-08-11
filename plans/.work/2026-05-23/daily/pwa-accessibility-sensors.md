# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors lane note (2026-05-24)

## 최근 진행 근거
- `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/decisions.md`, `product/done-criteria.md`(2026-05-18 기준): 제품은 시각장애인/저시력 보행자용 착용형 스마트폰 PWA이며, 화면보다 TTS/진동/스크린리더를 우선한다. M1은 Android 목걸이 착용 smoke, M2는 IMU 3~5초 ROI PoC.
- `README.md`, `docs/current_status.md`, `docs/walksafe-v2/README.md`(2026-05-22): `fake-v2`/`server-v2`, `/detect/v2`, `/reports/v2` 계약과 two-model v2 앱 흐름이 구현됨. 단 `/detect/v2` 실제 YOLO26s/COCO adapter 연결은 아직 미완료.
- `docs/walksafe-v2/frontend_display_policy.md`, `docs/walksafe-v2/auto_report_policy.md`(2026-05-22): tactile damage는 자동 신고 대상, general 객체는 risk evaluator가 보행 위험으로 판단할 때만 TTS/진동 경고. 자동 신고 완료/실패는 기본 TTS 없음, 음성 요청 신고는 짧게 안내.
- `daylog/2026-05-22.md`: PWA v2 흐름이 크게 전진됨. `server-v2`, 자동 tactile damage 신고, 음성 `create_report` 우선 신고, admin v2 metadata 표시, `apps/web/app/_walksafe/` hook/component 분리 기록. 검증은 `npm run lint`, `npm run typecheck`, `npm run build` PASS.
- `daylog/2026-05-23.md`: 2026-05-23에는 backend/frontend/voice 신규 테스트 실행 없음. Android 실폰, TalkBack, 목걸이 field, voice mic E2E, v2 실제 YOLO26s/COCO adapter는 계속 확인 필요.
- `docs/execution/2026-05-19_pwa_accessibility_sensors.md`: PWA service worker 문법, lint, typecheck, build, server-mode build PASS. Android/ADB, TalkBack, offline, 실폰 카메라/GPS/heading/TTS/진동은 BLOCKED.
- `docs/execution/2026-05-15_pwa_server_detection_e2e.md`, `docs/execution/2026-05-15_pwa_production_server_e2e.md`: headless fixture 기준 `source=server`, `metadata.source=server` 신고 저장 PASS. 실폰/목걸이 field 성능 근거는 아님.
- `plans/daily/2026-05-22.md`: PWA 후보로 IMU ROI fixture, 접근성 점검, Android fake smoke, 설치/offline/TalkBack, voice 신고 UI 경로 확인이 남아 있음.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports`: 디렉터리만 있고 파일 없음. `PM` 디렉터리도 없어 feature/verify/product-audit/automation-metrics 산출물 없음.
- `plans/daily/2026-05-24.md`: 현재 없음 확인.
- 저장소 내부 `AGENTS.md`: 없음. 사용자 제공 AGENTS 지침 기준 적용.

## 내일 목표 후보
- 1순위: v2 PWA 흐름 회귀를 먼저 고정한다. `fake-v2/server-v2`, 자동 tactile 신고 상태, 음성 요청 신고, risk evaluator, admin v2 metadata 표시가 정책 문서와 맞는지 확인한다.
- 2순위: IMU/heading 3~5초 ROI fixture PoC를 실제 작은 feature slice로 전진시킨다. 사용자 경고에 바로 연결하지 않고 순수 계산/fixture/문서화로 제한한다.
- 3순위: 접근성 UI 정적 점검을 v2 상태까지 확장한다. 자동 신고 상태, 음성 요청 신고, 신고 disabled reason, `aria-live`, `aria-pressed`, bbox `aria-hidden`을 확인한다.
- 4순위: Android/ADB가 가능하면 목걸이 착용 smoke를 실행한다. 불가하면 BLOCKED와 safe alternative만 남긴다.
- 5순위: backend/PostGIS runtime이 열리면 v2 신고 1건을 `/admin` 목록/상세/status 변경까지 report ID로 추적한다.

## 상세 체크리스트 초안
- [ ] 시작 gate 확인 → 검증: `git status --short --branch --untracked-files=all`, upstream behind 여부, `plans/features`, `plans/verify`, `PM` 존재 여부, `NEXT_PUBLIC_DETECTOR_MODE`, `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_VOICE_API_BASE` 기록.
- [ ] v2 PWA 정책 회귀 확인 → 검증: `fake-v2`는 저장 없음, `server-v2`는 tactile damage만 `/reports/v2` 자동 신고, general 객체는 기본 display-only임을 코드/문서 기준으로 확인.
- [ ] v2 자동 신고 상태 점검 → 검증: `idle`, `waiting_location`, `not_reportable`, `cooldown`, `sending`, `sent`, `failed` 상태 문구와 TTS 정책이 `docs/walksafe-v2/auto_report_policy.md`와 일치.
- [ ] 음성 요청 신고 경로 확인 → 검증: `create_report`가 v2에서 tactile damage를 선택하고 `server-v2`에서는 cooldown 우회, `fake-v2`에서는 저장하지 않는지 확인.
- [ ] IMU/heading ROI fixture 범위 확정 → 검증: `product/backlog.md` P1-001과 연결하고 Static/fixture PoC이며 Device E2E가 아님을 실행 문서에 명시.
- [ ] ROI 순수 계산 helper 또는 fixture test 작성 → 검증: heading, 추정 속도, 3초/5초 horizon, bbox 중심점 fixture 4개 이상으로 ROI 내/외 판정 재현.
- [ ] ROI 결과를 사용자-facing 경고에 연결하지 않음 → 검증: TTS/진동 primary 판단 변경 없음, “실험적 fixture”와 한계를 문서화.
- [ ] PWA 정적 회귀 실행 → 검증: `cd apps/web && npm run lint && npm run typecheck && npm run build`, `node --check apps/web/public/sw.js`.
- [ ] 접근성 정적 점검 → 검증: 위험/신고/음성 상태 `aria-live`, 음성 토글 `aria-pressed`, 신고 disabled reason, bbox `aria-hidden`, 주요 버튼 48px 이상 여부 확인.
- [ ] Android 접속 gate 기록 → 검증: ADB reverse/LAN/HTTPS, 기기명, Android/Chrome, 카메라/위치/마이크 권한을 PASS/BLOCKED로 기록.
- [ ] Android 목걸이 smoke 가능 시 실행 → 검증: 후면 카메라 각도, GPS accuracy, heading, TTS/진동, 6초 쿨다운, `fake-v2`/`server-v2` source 구분 기록.
- [ ] PWA 설치/offline/TalkBack 가능 시 확인 → 검증: standalone 실행, 네트워크 차단 후 shell fallback, TalkBack 읽기 순서와 live region 기록.
- [ ] `/admin` v2 운영 흐름 가능 시 확인 → 검증: v2 report ID, 이미지, 위치 품질, v2 metadata, `new -> reviewed -> resolved` 상태 변경 기록.
- [ ] 실행 문서 입력 제공 → 검증: `docs/execution/2026-05-24_pwa_accessibility_sensors.md`에 PASS/FAIL/BLOCKED/PENDING과 실제 실행 명령을 분리 기록.

## 리스크/확인 필요
- Android/ADB, TalkBack, offline, 목걸이 착용은 장비/권한 환경이 없으면 BLOCKED다. safe alternative는 DevTools/코드 기반 접근성 점검과 fixture smoke이며, Device E2E PASS로 쓰지 않는다.
- backend/PostGIS runtime이 없으면 `/admin` v2 신고 운영 흐름은 PENDING이다. safe alternative는 report API client와 state machine 코드 경로 확인이다.
- voice HTTP/mic/TTS 청취 환경이 없으면 음성 E2E는 BLOCKED다. safe alternative는 `useVoiceCommands`와 `useAutoReportV2` 경로의 정적 확인이다.
- ROI fixture는 실제 충돌 위험 성능 근거가 아니다. 실기기 DeviceMotion/Orientation/GPS 로그와 통제 경로 검증 전까지 사용자 경고 판단에 연결하지 않는다.
- `source=fake`는 UI/API 데모 근거이고, `source=server` headless는 연결 smoke 근거다. field 성능, 모델 정확도, 보행 안전 근거로 섞지 않는다.
- `/detect/v2` 실제 YOLO26s/COCO adapter, threshold calibration, latency 측정은 미완료다. PWA 계획에서는 adapter 부재를 명확히 표시하고 fake contract를 실제 모델로 표현하지 않는다.
- 작업트리에 미추적 산출물이 많고 upstream보다 뒤처진 기록이 있다. 새 구현 전 파일 단위 변경 범위와 충돌 가능성을 확인해야 한다.
- 운영 DB, 외부 배포, secret, AWS/S3, Kakao Map 실제 key, 지자체 API, destructive QA는 자동 계획에 직접 실행으로 넣지 않는다.

## 병렬 에이전트 활용 메모
- 사용함.
- 하위 에이전트 1: product/PM/scheduler 근거를 조사했다. 결론은 product 문서가 현재 기준이고 `plans/features`, `plans/verify`, `PM` 산출물은 반영할 파일이 없다는 것.
- 하위 에이전트 2: 최근 daylog/execution/PWA 코드 근거를 조사했다. 결론은 2026-05-22 v2 PWA 기능은 크게 전진했지만 2026-05-23 PWA 신규 검증은 없고, Android/TalkBack/field/voice mic/v2 실제 adapter는 미완료라는 것.
- 통합 결론: 2026-05-24 PWA lane은 막힌 field 검증만 반복하지 말고, v2 자동 신고/음성 신고/접근성 상태를 회귀로 고정한 뒤 M2 제품 목표인 IMU ROI fixture slice를 안전한 범위에서 전진시키는 계획이 적절하다.