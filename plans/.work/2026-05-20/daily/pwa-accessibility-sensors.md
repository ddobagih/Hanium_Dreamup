# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors lane note (2026-05-21)

## 최근 진행 근거
- 저장소 내부 `AGENTS.md`: 없음. 사용자 제공 AGENTS 지침 기준 적용.
- `product/vision.md`, `product/roadmap.md`, `product/backlog.md`, `product/done-criteria.md`, `product/decisions.md`(2026-05-18~19): 제품 방향은 화면 주시보다 TTS/진동/스크린리더 우선 PWA. M1은 Android 목걸이 착용 smoke, M2는 IMU 3~5초 ROI PoC.
- `README.md`(2026-05-18): PWA는 fake detector 기본, `NEXT_PUBLIC_DETECTOR_MODE=server`로 backend `/detect` 연결 가능. Android 실폰/목걸이, 브라우저/실폰 마이크, TTS HTTP, PostGIS runtime은 미완료.
- `docs/current_status.md`(2026-05-18 보정): `apps/web`에는 카메라, fake/server overlay, GPS/방향, TTS/진동, 신고, STT 업로드 UI, `/admin` 구현. 실폰, offline, TalkBack은 미완료.
- `docs/pwa_backend_status.md`(2026-05-17 보정): 2026-05-15 headless fixture에서 `source=server` 신고 저장 근거가 있으나 Android field 근거는 아님.
- `docs/execution/2026-05-19_pwa_accessibility_sensors.md`: 카메라/센서 재연결, GPS watch 재시작, 방향 센서 상태, 신고 영역 접근성 보강. `node --check`, `npm run lint`, `npm run typecheck`, `npm run build`, server-mode build PASS.
- `docs/execution/2026-05-19_integration_field_report.md`: PWA dev server와 server-mode headless E2E는 loopback 제한으로 실패. Android, TalkBack, `/admin` 운영 흐름은 BLOCKED/PENDING.
- `daylog/2026-05-20.md`: 5/20은 model/data 중심. PWA/Android/Voice/PostGIS runtime 신규 완료 근거 없음.
- `plans/features`, `plans/verify`, `PM/status`, `PM/reports/*`: 확인 가능한 scheduler/product-audit/automation-metrics 산출물 없음.
- `plans/daily/2026-05-21.md`: 이미 존재. PWA 항목은 IMU ROI fixture PoC, PWA 정적 회귀, 접근성 점검, Android gate, fake smoke, offline/TalkBack을 내일 후보로 둠.

## 내일 목표 후보
- 1순위: IMU 3~5초 ROI fixture PoC를 시작한다. 실기기 로그가 없어도 heading/속도/horizon/bbox 중심점으로 “이동 경로 안/밖 위험”을 나누는 신규 feature slice를 만든다.
- 2순위: ROI 변경 뒤 PWA 정적 회귀와 접근성 상태를 재확인한다.
- 3순위: Android/ADB reverse가 가능하면 fake mode 목걸이 smoke로 카메라, GPS/heading, TTS/진동, 6초 쿨다운을 기록한다.
- 4순위: backend runtime이 가능하면 fake 신고 1건을 `/admin` 목록/상세/상태 변경까지 report ID 기준으로 추적한다.
- 5순위: voice runtime이 가능하면 PWA 음성 명령 `create_report`가 버튼 신고와 같은 조건을 쓰는지 확인한다.

## 상세 체크리스트 초안
- [ ] 시작 gate 확인 → 검증: `git status --short --branch --untracked-files=all`, `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_DETECTOR_MODE`, `NEXT_PUBLIC_VOICE_API_BASE` 기록.
- [ ] IMU ROI fixture 범위 확정 → 검증: `product/backlog.md` P1-001과 연결하고 Static/fixture PoC이며 Device E2E가 아님을 명시.
- [ ] ROI 순수 계산 helper 최소 구현 → 검증: heading, 추정 속도, 3~5초 horizon, bbox 중심점 fixture로 ROI 내/외 판정이 재현됨.
- [ ] ROI 결과를 PWA 위험 판단에 과대 연결하지 않음 → 검증: 사용자 안전 성능으로 쓰지 않고, 실행 문서에 “실험적 fixture”로 기록.
- [ ] PWA 정적 회귀 실행 → 검증: `cd apps/web && npm run lint && npm run typecheck && npm run build`, `node --check apps/web/public/sw.js`.
- [ ] 접근성 정적 점검 → 검증: `aria-live`, `aria-pressed`, 신고 disabled reason, bbox `aria-hidden`, 주요 버튼 48px 이상 확인.
- [ ] Android 접속 gate 기록 → 검증: ADB reverse/LAN/HTTPS, 기기명, Android/Chrome, 카메라/위치/마이크 권한을 PASS/BLOCKED로 기록.
- [ ] fake mode 목걸이 smoke 가능 시 실행 → 검증: 후면 카메라, bbox, GPS accuracy, heading, 4개 위험 TTS/진동, 6초 쿨다운, `source=fake` 기록.
- [ ] fake 신고와 `/admin` 운영 흐름 가능 시 확인 → 검증: 신고 ID, 이미지, 위치 품질, `fake_source`, duplicate 후보, `new -> reviewed -> resolved` 기록.
- [ ] PWA 설치/offline/TalkBack 가능 시 확인 → 검증: standalone 실행, 네트워크 차단 후 `/` fallback, TalkBack 읽기 순서 기록. 오프라인 신고 큐로 표현하지 않음.
- [ ] PWA voice smoke 가능 시 확인 → 검증: `신고해`, `음성 켜`, `음성 꺼`, `다시 말해줘`, `지금 어디야`의 transcript/intent/confidence/UI action 기록.
- [ ] 실행 문서 입력 제공 → 검증: `docs/execution/2026-05-21_pwa_accessibility_sensors.md`에 PASS/FAIL/BLOCKED/PENDING 분리.

## 리스크/확인 필요
- Android/ADB, loopback TCP, Docker/PostGIS, browser mic 환경이 없으면 field/runtime 항목은 BLOCKED로 둔다.
- ROI fixture는 safe alternative일 뿐 DeviceMotion/Orientation 실기기 검증이나 실제 충돌 위험 성능 근거가 아니다.
- `source=fake`는 UI/API 검증용이며 정확도, 지연시간, 실제 보행 안전 근거가 아니다.
- `source=server` headless 근거는 모델-백엔드-PWA 연결 smoke이며 목걸이 field 성능으로 쓰지 않는다.
- 목적지/경로/Kakao Map API는 MVP 제외/P2 후순위다. PWA 음성 목적지 UI가 실제 길 안내처럼 보이면 안 된다.
- 외부 배포, 운영 DB, secret, AWS/S3, 지자체 API, 실제 Kakao key 사용은 자동 계획에 넣지 않는다.
- `plans/daily/2026-05-21.md`가 이미 있으므로 merge 단계에서 기존 내용을 덮어쓰기보다 lane note를 통합해야 한다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이번 작업은 문서/코드 근거 확인과 단일 lane note 작성이어서 하위 에이전트 분리보다 병렬 shell 조회로 충분했다.
- 통합 결론: 5/21 PWA lane은 blocked field 항목 반복보다 product M2의 IMU ROI fixture slice를 우선 전진시키고, Android/runtime 검증은 gate가 열릴 때만 별도 근거로 남긴다.