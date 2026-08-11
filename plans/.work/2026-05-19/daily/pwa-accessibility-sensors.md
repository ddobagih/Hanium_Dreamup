# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors lane note (2026-05-20)

## 최근 진행 근거
- `docs/execution/2026-05-19_pwa_accessibility_sensors.md`(2026-05-19): `apps/web/app/page.tsx`에 카메라/센서 재연결, GPS watch 재시작, 방향 센서 권한/상태 표시, 신고 영역 `aria-live`, 신고 버튼 `aria-describedby` 보강이 반영됨. `node --check`, `npm run lint`, `npm run typecheck`, `npm run build`, server-mode build는 PASS.
- `daylog/2026-05-19.md`(2026-05-19): PWA 정적 회귀와 server-mode build는 PASS로 기록됨. Android 실폰/목걸이, TalkBack, PWA offline, 브라우저/실폰 마이크, TTS HTTP cache/청취 평가는 미실행 또는 환경 차단.
- `docs/execution/2026-05-19_integration_field_report.md`(2026-05-19): server-mode headless E2E 재시도는 loopback socket 제한으로 FAIL. `/admin` 운영 흐름은 runtime 신고 생성이 없어 PENDING.
- `docs/current_status.md`(2026-05-18 보정): PWA는 카메라, fake/server 탐지 overlay, GPS/방향, TTS/진동, 신고, 중복 후보, STT 업로드 UI, `/admin`이 구현됨. 실폰 PWA 검증, 목걸이 field smoke, 브라우저/실폰 마이크 E2E, offline/TalkBack은 남음.
- `docs/pwa_backend_status.md`(2026-05-17 보정): 2026-05-15 headless fixture E2E에서 `source=server`, `metadata.source=server` 신고 저장 근거가 있음. 단, 실폰 field 성능 근거는 아님.
- `docs/neck_worn_phone_test_checklist.md`(2026-05-17 보정): Android 실폰/목걸이 착용, PWA 설치/offline, TalkBack 수동 검증은 아직 완료 근거가 없으며 안전한 실내/통제 smoke로만 표현해야 함.
- `apps/web/app/page.tsx`(현재 코드): 후면 카메라, Geolocation, DeviceOrientation, Web Speech API, Vibration API, 신고 API, MediaRecorder 기반 STT 업로드, 음성 intent 처리 UI가 존재함.
- `plans/daily/2026-05-20.md`는 확인 시점에 없음.

## 내일 목표 후보
- 1순위: 일반 개발 세션에서 PWA 실행 환경과 Android 접속 경로를 먼저 고정한다.
- 2순위: PWA 정적 회귀를 재확인하고, `apps/web` 기준선이 깨지지 않았는지 기록한다.
- 3순위: Android Chrome + ADB reverse 우선 경로로 fake mode 목걸이 착용 smoke를 수행한다.
- 4순위: 카메라/GPS/방향/TTS/진동을 화면 미주시 사용 기준으로 수동 기록한다.
- 5순위: fake 신고 1건을 생성해 `/admin` 목록/상세/이미지/위치 품질/상태 변경까지 확인한다.
- 6순위: PWA 설치/offline shell과 TalkBack 또는 DevTools 접근성 점검을 완료 또는 명확히 BLOCKED 처리한다.
- 7순위: voice 서버가 준비되면 PWA 녹음 `/speech/stt` E2E와 핵심 intent UI 동작을 확인한다.

## 상세 체크리스트 초안
- [ ] 작업트리와 env 확인 → 검증: `git status --short --branch --untracked-files=all`, `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_DETECTOR_MODE`, `NEXT_PUBLIC_VOICE_API_BASE` 기록.
- [ ] PWA 정적 회귀 확인 → 검증: `cd apps/web && npm run lint && npm run typecheck && npm run build`, `node --check apps/web/public/sw.js`.
- [ ] Android 접속 경로 확정 → 검증: ADB reverse 우선으로 `3000/8000/9001` 접근 여부, 기기명, Android/Chrome 버전, 권한 프롬프트 기록.
- [ ] fake mode 카메라 smoke → 검증: 후면 카메라 프리뷰, bbox, 4개 fake 위험 순환, `데모 탐지 모드`, 신고 metadata `source=fake` 확인.
- [ ] 목걸이 착용 통제 테스트 → 검증: 전방 1~3m와 바닥 일부가 보이는지, 흔들림/렌즈 가림/화면 미주시 인지성을 `docs/neck_worn_phone_test_checklist.md` 형식으로 기록.
- [ ] GPS/방향 센서 확인 → 검증: 위치 허용/거부, accuracy m, heading 값 또는 `방향 센서 미지원/권한 필요/수신 중` 상태가 화면과 신고 metadata에 반영되는지 확인.
- [ ] TTS/진동 체감 확인 → 검증: 4개 위험 문구, 6초 쿨다운, 음성 켜짐/꺼짐별 진동, 신고 성공/실패/유사 신고 패턴 구분 기록.
- [ ] fake 신고와 `/admin` 운영 흐름 확인 → 검증: 신고 ID, 이미지, 위치 품질, `fake_source`, duplicate 후보, `new -> reviewed -> resolved` 상태 변경 기록.
- [ ] PWA 설치/offline shell 확인 → 검증: standalone 실행, 네트워크 차단 후 `/` fallback, manifest/icon/static cache 확인. 오프라인 신고 큐로 표현하지 않음.
- [ ] 접근성 수동 점검 → 검증: TalkBack 또는 DevTools로 `aria-live`, `aria-pressed`, 신고 버튼 disabled reason, bbox `aria-hidden`, 주요 버튼 터치 타깃 확인.
- [ ] PWA voice/STT E2E 확인 → 검증: `신고해`, `음성 켜`, `음성 꺼`, `다시 말해줘`, `지금 어디야`의 transcript, intent, confidence, UI action, CORS 오류 여부 기록.
- [ ] server mode는 통합 lane과 근거 분리 → 검증: backend ready일 때만 `source=server` UI/신고 smoke를 기록하고, 실폰 field 성능 근거로 확대하지 않음.
- [ ] PWA 실행 결과 문서화 → 검증: `docs/execution/2026-05-20_pwa_accessibility_sensors.md` 또는 merge 에이전트 지정 위치에 PASS/FAIL/BLOCKED/PENDING 분리.

## 리스크/확인 필요
- 현재 sandbox에서는 PWA dev server와 loopback HTTP가 제한된 이력이 있어, 내일 검증은 일반 개발 세션 또는 실기기 접근 가능 환경이 필요함.
- ADB/Android 기기가 없으면 핵심 field 항목은 완료가 아니라 BLOCKED로 남겨야 함.
- LAN HTTP 접속은 카메라, 마이크, 위치, service worker가 secure context 제약으로 막힐 수 있음.
- Web Speech API, Vibration API, DeviceOrientation은 Android Chrome 기준으로도 기기별 차이가 큼.
- fake detector는 UI/API/운영 흐름 근거이며 정확도, 지연시간, 실제 보행 안전 근거가 아님.
- `source=server` headless fixture PASS는 모델-백엔드-PWA 연결 smoke 근거이며 목걸이 착용 실폰 성능 근거가 아님.
- PWA 음성 intent에 목적지/안내 상태가 있으나, product 결정상 목적지/경로/지도 API는 MVP 제외/P2 후순위로 보아 데모 문구가 실제 길 안내처럼 보이지 않게 해야 함.
- 확인 시점 `git status`에는 `daylog/2026-05-19.md` 수정이 남아 있었으므로 다음 실행자는 덮어쓰기 전에 상태를 재확인해야 함.

## 병렬 에이전트 활용 메모
- 사용하지 않음: 이번 작업은 파일 수정 없이 PWA 관련 문서, 최근 daylog, 기존 계획, `apps/web` 구현 상태를 대조하는 lane note 작성이라 단일 에이전트와 병렬 shell 조회로 충분했음.