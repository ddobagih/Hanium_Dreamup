# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors lane note (2026-05-18)

## 최근 진행 근거
- `docs/current_status.md` / 2026-05-17 보정: PWA는 카메라, fake/server 탐지 overlay, GPS/방향, TTS/진동, 신고, STT 업로드 UI, `/admin`이 구현됐지만 Android 실폰, 목걸이 착용, PWA 설치/offline, TalkBack, 브라우저/실폰 마이크 E2E는 미완료로 정리됨.
- `docs/pwa_backend_status.md` / 2026-05-17 보정: 기본 경로는 fake detector이고, `NEXT_PUBLIC_DETECTOR_MODE=server` + `MODEL_ARTIFACT_PATH` 환경에서는 headless fixture 기준 `source: "server"` 신고 저장 smoke 근거가 있음. 실폰 field 성능 근거는 아님.
- `docs/execution/2026-05-15_pwa_server_detection_e2e.md`, `docs/execution/2026-05-15_pwa_production_server_e2e.md`: dev/prod headless Chromium fixture에서 `/detect` 호출, bbox 탐지, `/reports` 저장, `source=server`, `metadata.source=server` 확인. 실폰은 미실행.
- `plans/.work/2026-05-16/execute-r1/pwa-accessibility-sensors.md`: `speechEnabled=false`에서 `repeat_last`가 음성을 재생하지 않도록 수정, `/admin` row/status 버튼 `aria-pressed` 추가, service worker navigation/static/API fallback 분리. `lint/typecheck/build` 통과 기록.
- `plans/.work/2026-05-17/execute-r1/pwa-accessibility-sensors.md`: `apps/web` 코드는 추가 수정 없음. `node --check`, `npm run lint`, `npm run typecheck`, `npm run build`, server-mode build PASS. `adb` 없음, 실폰/목걸이/offline/TalkBack/STT E2E는 미완료.
- `docs/execution/2026-05-17_integration_field_report.md`: sandbox loopback 제한으로 server-mode E2E 재실행은 `/health` 접근 단계에서 실패. Docker/PostGIS, Android/ADB, fake 신고 `/admin`, 마이크 E2E는 대기.
- `plans/.work/2026-05-17/execute-r1/voice-stt-tts.md`: TTS 7문구 cache hit와 STT 샘플 8개 재평가는 통과했지만, PWA 브라우저/실폰 마이크 E2E, CORS, 실폰 스피커 청취 평가는 미실행.
- `docs/neck_worn_phone_test_checklist.md` / 2026-05-17 보정: 실폰/목걸이 테스트는 안전한 실내/통제 smoke로만 표현해야 하며 fake/server 결과를 성능 근거로 섞지 않도록 명시.
- `plans/daily/2026-05-18.md`: 현재 없음. 저장소 내부 `AGENTS.md`도 확인되지 않아 사용자 제공 AGENTS 지침 기준으로 판단.

## 내일 목표 후보
- Android 접속 방식부터 고정하고 실폰 fake mode에서 카메라, GPS, 방향, TTS, 진동, 신고 가능 여부를 기록한다.
- fake 신고 1건을 생성해 `/admin`에서 이미지, source, 위치 품질, duplicate 후보, 상태 변경을 확인한다.
- TalkBack 또는 DevTools 접근성 점검으로 위험 상태, 음성/녹음/신고 버튼, 카메라 장식 요소 낭독 여부를 확인한다.
- PWA 설치/offline shell을 확인하되 오프라인 신고 큐처럼 과대표현하지 않는다.
- backend/model/voice 서버가 접근 가능할 때만 server mode와 마이크 STT E2E를 확인하고, headless smoke와 실폰 field 근거를 분리한다.

## 상세 체크리스트 초안
- [ ] 작업트리와 실행 env 확인 → 검증: `git status --short --untracked-files=all`, `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_DETECTOR_MODE`, `NEXT_PUBLIC_VOICE_API_BASE`를 기록하고 다른 lane 변경을 덮어쓰지 않음.
- [ ] PWA 정적 회귀 확인 → 검증: `cd apps/web && npm run lint && npm run typecheck && npm run build`, `node --check apps/web/public/sw.js` 결과 기록.
- [ ] Android 접속 경로 확정 → 검증: ADB reverse, LAN IP, HTTPS 중 하나로 web `3000`, backend `8000`, voice `9001` 접근 여부와 기기명/Android/Chrome 버전 기록.
- [ ] fake mode 실폰 카메라 smoke → 검증: 후면 카메라 프리뷰, bbox, 4개 위험 클래스 순환, `데모 탐지 모드`, `source=fake` 표시를 Android Chrome에서 확인.
- [ ] 목걸이 착용 센서 점검 → 검증: `docs/neck_worn_phone_test_checklist.md` 기준 전방 1~3m, 바닥 일부, 흔들림, 렌즈 가림, 화면 미주시 인지성 기록.
- [ ] GPS/방향 확인 → 검증: 위치 허용/거부, 정확도 m, heading 값 또는 `센서 대기`, 신고 metadata 반영 여부 기록.
- [ ] TTS/진동 체감 확인 → 검증: 4개 위험 문구, 6초 쿨다운, 음성 켜짐/꺼짐별 진동, 신고 성공/실패/유사 신고 패턴 구분 기록.
- [ ] fake 신고와 `/admin` 운영 흐름 확인 → 검증: 실폰 신고 1건 생성 후 이미지, 위치 품질, `fake_source`, duplicate 후보, `new -> reviewed -> resolved` 상태 변경 확인.
- [ ] PWA 설치/offline shell 확인 → 검증: 홈 화면/standalone 실행, 네트워크 차단 후 `/` navigation fallback, manifest/icon/static cache 확인.
- [ ] 접근성 수동 점검 → 검증: TalkBack 또는 DevTools로 `aria-live`, `aria-pressed`, 신고 버튼 disabled reason, 카메라/bbox `aria-hidden`, 터치 타깃 확인.
- [ ] server mode 실폰 또는 통제 입력 확인 → 검증: `/detect/health ready`일 때만 `/detect` 호출, bbox, `source=server`, 신고 저장을 기록. 모델 미준비/network/empty detection UI도 별도 기록.
- [ ] PWA 마이크 STT E2E 확인 → 검증: `신고해`, `음성 켜/꺼`, `다시 말해줘`, `지금 어디야`의 transcript, intent, confidence, UI action, CORS 오류 여부 기록.
- [ ] voice/server down fallback 확인 → 검증: voice server 미실행 상태에서 음성 명령 오류가 앱 전체를 막지 않고 위험 TTS/진동/신고 흐름이 유지되는지 확인.
- [ ] PWA lane 결과 문서 작성 → 검증: `docs/execution/2026-05-18_pwa_accessibility_sensors.md`에 통과/실패/대기/막힘을 분리하고 실행하지 않은 항목은 PASS로 쓰지 않음.

## 리스크/확인 필요
- Android에서 `127.0.0.1`은 휴대폰 자신을 가리킬 수 있으므로 ADB reverse 또는 HTTPS/LAN 전략을 먼저 확정해야 함.
- LAN HTTP에서는 카메라, 위치, 마이크, service worker 권한이 secure context 문제로 막힐 수 있음.
- Web Speech API 한국어 TTS, Vibration API, DeviceOrientation 지원은 기기/브라우저별 차이가 커서 수동 기록이 필요함.
- `DeviceOrientation.alpha`는 실제 이동 방향과 다를 수 있으므로 “방향 보정 완료”가 아니라 센서값 표시 수준으로 기록해야 함.
- fake detector와 fake 신고는 UI/API/운영 흐름 확인용이며 정확도, 지연시간, 실제 보행 안전 근거로 쓰면 안 됨.
- server mode headless E2E는 known-positive fixture smoke 근거이며 실폰 목걸이 field 성능 근거가 아님.
- v2 모델은 class `0 damaged_tactile_block` 중심 baseline이므로 4개 위험 클래스 전체 서비스 성능으로 표현하면 안 됨.
- service worker는 shell/static fallback 중심이다. 오프라인 신고 저장/재전송 기능으로 표현하면 안 됨.
- `NEXT_PUBLIC_*` 값은 production build에 반영되므로 server/voice mode 검증 시 build 시점 env를 고정해야 함.
- 현재 작업트리에 backend/voice/docs/plan 변경이 남아 있어 PWA 작업자는 시작 전 diff를 확인해야 함.

## 병렬 에이전트 활용 메모
- 하위/병렬 에이전트는 사용하지 않았다.
- 이번 작업은 PWA lane 문서와 `apps/web` 소스 판독으로 충분했고, 최종 산출물이 단일 lane note라 병렬 에이전트를 쓰면 같은 판단을 중복 정리할 가능성이 컸다.