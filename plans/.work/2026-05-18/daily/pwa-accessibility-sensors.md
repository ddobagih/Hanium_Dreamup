# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors lane note (2026-05-19)

## 최근 진행 근거
- `README.md`, `docs/current_status.md` / 2026-05-18 보정: PWA는 카메라, fake/server 탐지 overlay, GPS/방향, TTS/진동, 신고 전송, 중복 후보 advisory, STT 업로드 UI, `/admin` 구현 상태로 정리됨. 실폰 PWA 검증, 목걸이 착용 field test, 브라우저/실폰 마이크 E2E, offline/TalkBack 수동 점검은 미완료.
- `docs/pwa_backend_status.md` / 2026-05-17 보정: 기본 데모 경로는 fake detector이고, `NEXT_PUBLIC_DETECTOR_MODE=server` + `MODEL_ARTIFACT_PATH` 환경에서는 headless fixture 기준 `source=server` 신고 저장 smoke 근거가 있음. Android 실폰/목걸이 field 성능 근거는 아직 없음.
- `docs/execution/2026-05-15_pwa_server_detection_e2e.md`, `docs/execution/2026-05-15_pwa_production_server_e2e.md` / 2026-05-15: dev/prod headless Chromium에서 `/detect` 호출, bbox, `/reports` 저장, `source=server`, `metadata.source=server` 확인. 실폰 검증은 미실행.
- `plans/.work/2026-05-18/execute-r1/pwa-accessibility-sensors.md` / 2026-05-18: `apps/web` 영구 변경 없음. `node --check apps/web/public/sw.js`, `npm run lint`, `npm run typecheck`, `npm run build`, server-mode build PASS. `adb` 없음, 포트 조회 제한으로 실폰/목걸이/offline/TalkBack/STT E2E 미수행.
- `plans/.work/2026-05-18/execute-r2/pwa-accessibility-sensors.md` / 2026-05-18: PWA 코드는 수정하지 않고 문서 표현 보정만 확인. Android 실폰/목걸이, GPS/heading, TTS/진동, PWA 설치/offline, TalkBack, fake 신고 `/admin` 운영 흐름은 자동 수행 범위 밖으로 남음.
- `docs/neck_worn_phone_test_checklist.md` / 2026-05-17 보정: 실폰/목걸이 테스트는 안전한 실내/통제 smoke로만 표현해야 하며, fake/server 결과를 정확도나 실제 보행 안전 근거로 섞지 않도록 명시.
- `apps/web/app/page.tsx`, `apps/web/lib/detect-api.ts`, `apps/web/lib/voice-api.ts`, `apps/web/public/sw.js` / 코드 확인: 카메라, GPS, `DeviceOrientation.alpha`, Web Speech API, Vibration API, `MediaRecorder` STT 업로드, fake/server detector 분기, shell cache는 구현되어 있음. 다만 구현 확인은 실폰/TalkBack/offline 통과 근거가 아님.
- `plans/daily/2026-05-19.md` / 확인 시점: 파일 없음. `plans/daily`에는 2026-05-15~2026-05-18만 존재.
- 저장소 내부 `AGENTS.md` / 확인 시점: 없음. 사용자 제공 AGENTS 지침 기준 적용.
- `git status --short --branch --untracked-files=all` / 확인 시점: `daylog/2026-05-18.md` 수정 상태가 남아 있음. `apps/web` 변경은 확인되지 않음.

## 내일 목표 후보
- Android 접속 방식 확정: ADB reverse, LAN IP, HTTPS 중 실제 가능한 경로를 먼저 고정한다.
- fake mode 실폰/목걸이 smoke: 카메라 각도, GPS, 방향, TTS, 진동, 화면 미주시 인지성을 기록한다.
- fake 신고와 `/admin` 운영 흐름 확인: 신고 ID, 이미지, 위치 품질, `fake_source`, duplicate 후보, 상태 변경을 확인한다.
- TalkBack 또는 DevTools 접근성 점검: `aria-live`, `aria-pressed`, 신고 버튼 disabled reason, 카메라/bbox 낭독 제외, 터치 타깃을 확인한다.
- PWA 설치/offline shell 확인: standalone 실행과 `/` fallback만 검증하고, 오프라인 신고 큐처럼 표현하지 않는다.
- backend/model/voice 서버 접근이 확보될 때만 server mode와 PWA 마이크 STT E2E를 추가 확인한다.
- 실행 결과는 `docs/execution/2026-05-19_pwa_accessibility_sensors.md` 후보 문서에 통과/실패/대기/확인 필요로 분리한다.

## 상세 체크리스트 초안
- [ ] 작업트리와 env 확인 → 검증: `git status --short --branch --untracked-files=all`, `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_DETECTOR_MODE`, `NEXT_PUBLIC_VOICE_API_BASE` 기록.
- [ ] PWA 정적 회귀 확인 → 검증: `cd apps/web && npm run lint && npm run typecheck && npm run build`, `node --check apps/web/public/sw.js` 결과 기록.
- [ ] Android 접속 경로 확정 → 검증: ADB reverse/LAN/HTTPS 중 하나로 web `3000`, backend `8000`, voice `9001` 접근 여부와 기기명, Android/Chrome 버전 기록.
- [ ] fake mode 실폰 카메라 smoke → 검증: 후면 카메라 프리뷰, bbox, 4개 fake 위험 순환, `데모 탐지 모드`, `source=fake` 확인.
- [ ] 목걸이 착용 센서 점검 → 검증: 전방 1~3m, 바닥 일부, 흔들림, 렌즈 가림, 화면 미주시 TTS/진동 인지성 기록.
- [ ] GPS/방향 상태 확인 → 검증: 위치 허용/거부, 정확도 m, heading 값 또는 `센서 대기`, 신고 metadata 반영 여부 기록.
- [ ] TTS/진동 체감 확인 → 검증: 4개 위험 문구, 6초 쿨다운, 음성 켜짐/꺼짐별 진동, 신고 성공/실패/유사 신고 패턴 구분 기록.
- [ ] fake 신고와 `/admin` 운영 흐름 확인 → 검증: 실폰 신고 1건 생성 후 이미지, 위치 품질, `fake_source`, duplicate 후보, `new -> reviewed -> resolved` 상태 변경 확인.
- [ ] PWA 설치/offline shell 확인 → 검증: 홈 화면/standalone 실행, 네트워크 차단 뒤 `/` navigation fallback, manifest/icon/static cache 확인.
- [ ] 접근성 수동 점검 → 검증: TalkBack 또는 DevTools로 위험 상태 live region, 음성/녹음 버튼 `aria-pressed`, 신고 버튼 disabled reason, bbox `aria-hidden`, 주요 버튼 터치 타깃 확인.
- [ ] server mode 통제 입력 확인 → 검증: `/detect/health ready`일 때만 `/detect`, bbox, `source=server`, 신고 저장 확인. 모델 미준비/network/empty detection은 별도 실패 경로로 기록.
- [ ] PWA 마이크 STT E2E 확인 → 검증: `신고해`, `음성 켜`, `음성 꺼`, `다시 말해줘`, `지금 어디야`의 transcript, intent, confidence, UI action, CORS 오류 여부 기록.
- [ ] voice server down fallback 확인 → 검증: 음성 명령 오류가 앱 전체를 막지 않고 위험 TTS/진동/버튼 신고 흐름이 유지되는지 확인.
- [ ] PWA lane 실행 문서 작성 → 검증: `docs/execution/2026-05-19_pwa_accessibility_sensors.md`에 실행한 검증만 PASS로 기록.

## 리스크/확인 필요
- Android에서 `127.0.0.1`은 휴대폰 자신을 가리키므로 ADB reverse, LAN IP, HTTPS 중 접속 방식을 먼저 정해야 함.
- LAN HTTP는 카메라, 위치, 마이크, service worker 권한이 secure context 문제로 막힐 수 있음.
- Web Speech API 한국어 TTS, Vibration API, DeviceOrientation 지원은 기기/브라우저별 차이가 커서 실폰 기록이 필요함.
- `DeviceOrientation.alpha`는 실제 보행 이동 방향이 아니라 센서 heading 표시 수준으로만 기록해야 함.
- fake detector와 fake 신고는 UI/API/운영 흐름 검증용이며 정확도, 지연시간, 실제 보행 안전 근거로 쓰면 안 됨.
- server mode headless E2E는 known-positive fixture smoke이며 실폰 목걸이 field 성능 근거가 아님.
- v2 모델은 class `0 damaged_tactile_block` 중심 baseline이라 4개 위험 클래스 전체 성능으로 표현하면 안 됨.
- service worker는 shell/static fallback 중심이다. 오프라인 신고 저장/재전송 기능으로 표현하면 안 됨.
- `NEXT_PUBLIC_*` 값은 build 시점에 반영되므로 server/voice mode 검증 시 build env를 명확히 고정해야 함.
- `docs/api_reference.md`의 `/detect` 일부 표현은 2026-05-12 기준이라 최신 server adapter 상태와 충돌할 수 있음. 최신 판단은 `docs/current_status.md`, `docs/pwa_backend_status.md`, `docs/model_integration_plan.md`를 우선해야 함.

## 병렬 에이전트 활용 메모
- 하위/병렬 에이전트는 사용하지 않았다.
- 이번 범위는 PWA lane 문서와 `apps/web` 코드 판독으로 충분했고, 최종 산출물이 단일 lane note라 별도 에이전트 분할 이득이 작았다.
- 파일 조회는 병렬로 수행했지만, 코드/문서 수정은 하지 않았다. 읽기 전용 계획 note 작성이라 별도 daylog는 만들지 않았다.