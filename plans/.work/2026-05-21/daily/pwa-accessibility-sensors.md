# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors lane note (2026-05-22)

## 최근 진행 근거
- `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md`(2026-05-18~19): 제품 방향은 화면 주시보다 TTS/진동/스크린리더 우선 PWA. M1은 Android 목걸이 착용 smoke, M2는 IMU 3~5초 ROI PoC.
- `README.md`, `docs/current_status.md`(2026-05-18 보정): `apps/web`에는 카메라, fake/server 탐지 overlay, GPS/방향, TTS/진동, 신고, STT 업로드 UI, `/admin`이 구현됨. Android 실폰, offline, TalkBack, 브라우저/실폰 마이크 E2E는 미완료.
- `docs/pwa_backend_status.md`(2026-05-17 보정): `NEXT_PUBLIC_DETECTOR_MODE=server` headless fixture에서 `source=server` 신고 저장 근거가 있으나 실폰/목걸이 field 근거는 아님.
- `docs/execution/2026-05-19_pwa_accessibility_sensors.md`: 카메라/센서 재연결, GPS watch 재시작, 방향 센서 상태, 신고 접근성 보강. `node --check`, `npm run lint`, `npm run typecheck`, `npm run build`, server-mode build PASS.
- `docs/execution/2026-05-19_integration_field_report.md`: PWA dev server와 server-mode headless E2E는 loopback 제한으로 실패. Android, TalkBack, `/admin` 운영 흐름은 BLOCKED/PENDING.
- `daylog/2026-05-20.md`, `daylog/2026-05-21.md`: 5/20~5/21은 model/data 중심. PWA/Backend/Voice/Integration의 2026-05-21 신규 실행 완료 근거 없음.
- `plans/daily/2026-05-21.md`(2026-05-20 작성): PWA 후보로 IMU ROI fixture PoC, 정적 회귀, 접근성 점검, Android gate, offline/TalkBack이 잡혀 있었으나 `docs/execution/2026-05-21*.md`는 없음.
- `apps/web/app/page.tsx` 현재 코드: DeviceOrientation heading, GPS, TTS/진동, MediaRecorder STT, `create_report`, `repeat_last`, `get_current_location`, 목적지 state 저장, 신고 disabled reason이 구현됨. DeviceMotion/ROI helper는 확인되지 않음.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports`: 디렉터리는 있으나 파일 없음. `PM/status`, `PM/reports/*`, product audit, automation metrics는 `PM` 디렉터리 자체 없음.
- 기존 `plans/daily/2026-05-22.md`는 없음.

## 내일 목표 후보
- 1순위: IMU/heading 기반 3~5초 ROI fixture PoC를 실제 작은 feature slice로 시작한다. DeviceMotion이 없어도 bbox 중심점, heading, 추정 속도 fixture로 ROI 내/외 판정을 재현하고 사용자 경고에는 연결하지 않는다.
- 2순위: ROI와 현재 접근성 UI가 충돌하지 않게 PWA 정적 회귀와 접근성 점검을 수행한다.
- 3순위: voice가 가능하면 `신고해 -> create_report`가 기존 신고 버튼과 같은 disabled 조건, duplicate check, `POST /reports` 경로를 쓰는지 확인한다.
- 4순위: Android/ADB reverse가 가능하면 fake mode 목걸이 smoke로 카메라 각도, GPS/heading, TTS/진동, 6초 쿨다운, `source=fake`를 기록한다.
- 5순위: backend runtime이 가능하면 fake 신고 1건을 `/admin` 목록/상세/위치품질/상태 변경까지 report ID로 추적한다.

## 상세 체크리스트 초안
- [ ] 시작 gate 확인 → 검증: `git status --short --branch --untracked-files=all`, `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_DETECTOR_MODE`, `NEXT_PUBLIC_VOICE_API_BASE`, Android/ADB 가능 여부 기록.
- [ ] ROI fixture 범위 확정 → 검증: `product/backlog.md` P1-001과 연결하고 Static/fixture PoC이며 Device E2E가 아님을 문서에 명시.
- [ ] ROI 순수 계산 helper 최소 구현 → 검증: heading, 추정 속도, 3초/5초 horizon, bbox 중심점 fixture 4개 이상으로 ROI 내/외 판정 재현.
- [ ] ROI 결과를 사용자-facing 위험 경고에 연결하지 않음 → 검증: TTS/진동 primary 판단 변경 없음, 실행 문서에 “실험적 fixture”로 기록.
- [ ] PWA 정적 회귀 실행 → 검증: `cd apps/web && npm run lint && npm run typecheck && npm run build`, `node --check apps/web/public/sw.js`.
- [ ] 접근성 정적 점검 → 검증: `aria-live`, `aria-pressed`, 신고 disabled reason, bbox `aria-hidden`, 음성 명령 버튼 label, 주요 버튼 48px 이상 확인.
- [ ] voice 신고 경로 확인 가능 시 실행 → 검증: `신고해` transcript/intent/confidence/UI action, 버튼 신고와 동일한 disabled 조건/duplicate check/`POST /reports` 경로 기록.
- [ ] Android 접속 gate 기록 → 검증: ADB reverse/LAN/HTTPS, 기기명, Android/Chrome, 카메라/위치/마이크 권한을 PASS/BLOCKED로 기록.
- [ ] fake mode 목걸이 smoke 가능 시 실행 → 검증: 후면 카메라, bbox, GPS accuracy, heading, 4개 위험 TTS/진동, 6초 쿨다운, `source=fake` 기록.
- [ ] PWA 설치/offline/TalkBack 가능 시 확인 → 검증: standalone 실행, 네트워크 차단 후 `/` fallback, TalkBack 읽기 순서 기록. 오프라인 신고 큐로 표현하지 않음.
- [ ] `/admin` 운영 흐름 가능 시 확인 → 검증: 신고 ID, 이미지, 위치 품질, `fake_source`, duplicate 후보, `new -> reviewed -> resolved` 기록.
- [ ] 실행 문서 입력 제공 → 검증: `docs/execution/2026-05-22_pwa_accessibility_sensors.md`에 PASS/FAIL/BLOCKED/PENDING 분리.

## 리스크/확인 필요
- Android/ADB, loopback TCP, Docker/PostGIS, browser mic 환경이 없으면 field/runtime 항목은 BLOCKED로 둔다. safe alternative는 fixture, DevTools 정적 점검, 코드 경로 검토다.
- ROI fixture는 safe alternative일 뿐 DeviceMotion/Orientation 실기기 검증이나 실제 충돌 위험 성능 근거가 아니다.
- `source=fake`는 UI/API 검증용이며 정확도, 지연시간, 실제 보행 안전 근거가 아니다.
- `source=server` headless 근거는 모델-백엔드-PWA 연결 smoke이며 목걸이 field 성능으로 쓰지 않는다.
- 목적지/경로/Kakao Map API는 MVP 제외/P2 후순위다. 목적지 state 저장을 실제 길 안내처럼 표현하면 안 된다.
- 외부 배포, 운영 DB, secret, AWS/S3, 지자체 API, 실제 Kakao key 사용은 자동 계획에 넣지 않는다.
- 5/20 모델 산출물과 계획 파일이 미추적 상태로 많다. 내일 실행자는 덮어쓰기 전에 작업트리를 확인해야 한다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이번 작업은 PWA lane note 단일 산출물 작성이며, product/docs/daylog/plans/code 근거 확인은 병렬 shell 조회로 충분했다.
- 통합 결론: 5/22 PWA lane은 막힌 field 검증 반복보다 product M2의 ROI fixture slice를 우선 전진시키고, Android/runtime 검증은 gate가 열릴 때만 별도 근거로 남긴다.