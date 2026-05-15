# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors lane note (2026-05-15)

## 최근 진행 근거
- 2026-05-14 `docs/execution/2026-05-14_frontend.md`: `apps/web` lint/typecheck, `/`, `/admin`, `/manifest.webmanifest`, `/sw.js` HTTP 확인은 통과. 카메라 권한, GPS, 방향 센서, TTS, 진동, PWA 설치/오프라인 shell은 실폰 수동 검증 대기.
- 2026-05-14 `docs/execution/2026-05-14_pwa_stt_implementation.md`: PWA 쪽 `MediaRecorder` 녹음 UI와 `/speech/stt` 업로드 client가 추가됨. 브라우저 마이크/STT E2E는 아직 수동 검증 필요.
- 2026-05-14 `docs/execution/2026-05-14_voice_pwa_smoke.md`: voice API 계약 smoke 절차와 `NEXT_PUBLIC_VOICE_API_BASE` 기준 정리. 실제 브라우저 마이크 E2E는 미완료.
- 2026-05-13 `docs/frontend_3day_execution_plan.md`: 2026-05-15 목표는 Android 실폰/목걸이 착용 상태에서 카메라, TTS, 진동, GPS, 방향, 신고, PWA 설치/오프라인 shell 검증.
- 2026-05-12 `docs/neck_worn_phone_test_checklist.md`: 목걸이 착용 테스트 기준은 전방 1~3m와 바닥 일부, 짧은 TTS, 구분 가능한 진동, 화면 미주시 신고 성공/실패 인지.
- 2026-05-13 `docs/pwa_backend_status.md`, `docs/ui_feature_inventory.md`: PWA는 fake detector 기반 통합 확인 단계이며 `source: "fake"`는 실제 안전 판단/성능 근거로 사용 금지.
- 저장소 확인: 실제 `AGENTS.md` 파일과 `plans/daily/2026-05-15.md`는 없음. `daylog/`는 비어 있고 최근 실행 근거는 `docs/execution/`에 있음.

## 내일 목표 후보
- Android 실폰 또는 HTTPS/ADB reverse 환경에서 보행자 PWA의 카메라/GPS/방향/TTS/진동/PWA shell을 우선 검증.
- 백엔드가 준비된 상태에서 fake 탐지 신고 1건 생성 후 `/admin`에서 이미지, 위치 품질, `fake_source`, 상태 변경 확인.
- PWA 음성 명령 녹음과 `/speech/stt` 업로드를 로컬 voice server와 브라우저 마이크로 실제 확인.
- TalkBack/키보드/DevTools 기준으로 위험 상태, 음성 토글, 신고 버튼, 카메라 장식 요소 낭독 순서 점검.
- 실폰에서만 재현되는 문제를 기기/브라우저/접속 방식/재현 단계와 함께 기록.

## 상세 체크리스트 초안
- [ ] 실폰 접속 경로 준비 → 검증: `adb reverse tcp:3000 tcp:3000`, `adb reverse tcp:8000 tcp:8000`, 필요 시 `tcp:9001`까지 설정 후 Android Chrome에서 `http://localhost:3000` 접속.
- [ ] 후면 카메라/목걸이 각도 확인 → 검증: 전방 1~3m와 바닥 일부가 보이고 줄/옷깃이 렌즈를 가리지 않는지 `docs/neck_worn_phone_test_checklist.md` 형식으로 기록.
- [ ] fake detection 시각/상태 확인 → 검증: `데모 탐지 모드`, bbox, 4개 위험 클래스 순환, 신뢰도 표시가 보이되 실제 AI 탐지처럼 오해되지 않는지 확인.
- [ ] TTS/진동 체감 확인 → 검증: 4개 위험 문구를 청취하고 음성 켜짐/꺼짐 상태의 진동 패턴 차이, 신고 성공/실패 진동 구분 가능 여부 기록.
- [ ] GPS/방향 센서 확인 → 검증: 위치 권한 허용/거부, 정확도 m 표시, DeviceOrientation 방향 값 변화 또는 `대기 중` 상태를 실폰에서 확인.
- [ ] 신고 E2E 확인 → 검증: fake 탐지 후 `현재 위험 신고` 실행, 성공/실패 음성·진동·상태 문구 확인, `/admin`에서 이미지/위치/source/review flag 확인.
- [ ] 관리자 화면 운영 흐름 확인 → 검증: `/admin` 목록, 상세 이미지, 상태/유형/소스/날짜/반경 필터, `new/reviewed/resolved` 상태 변경 확인.
- [ ] PWA 설치/오프라인 shell 확인 → 검증: 홈 화면 추가 또는 설치 프롬프트, 재접속, 네트워크 차단 후 `/`, manifest, icon cache fallback 확인.
- [ ] PWA 음성 명령 E2E 확인 → 검증: voice server 실행 후 브라우저 마이크 권한 허용, `신고해`, `음성 켜`, `음성 꺼`, `다시 말해줘`, `지금 어디야` 명령의 transcript/intent/status 확인.
- [ ] 접근성 빠른 점검 → 검증: TalkBack 또는 DevTools로 위험 배너 `aria-live`, 음성 토글 `aria-pressed`, 신고 버튼 disabled 이유, 카메라/bbox `aria-hidden` 낭독 여부 확인.

## 리스크/확인 필요
- 실폰, ADB reverse, HTTPS 개발 URL 중 하나가 없으면 2026-05-15 핵심 항목은 완료가 아니라 `수동 검증 대기`로 남겨야 함.
- 모바일에서 `127.0.0.1`/`localhost`는 접속 방식에 따라 PC가 아니라 휴대폰 자신을 가리킬 수 있어 3000/8000/9001 포트 연결 전략 확인 필요.
- iOS/일부 브라우저는 Vibration API, DeviceOrientation 권한, PWA 설치 동작이 Android Chrome과 다를 수 있음.
- fake detector 결과는 UI/API 통합 확인용이며 실제 보행 안전, 모델 정확도, 경보 지연 성능 근거로 쓰면 안 됨.
- 백엔드/DB/voice server가 실행되지 않으면 신고 E2E, `/admin`, STT 브라우저 검증이 막힘.
- `docs/frontend_3day_execution_plan.md`는 2026-05-13 기준으로 STT 미연결이라고 쓰여 있으나, 2026-05-14 PWA STT 구현 로그가 더 최신이므로 merge 시 최신 로그를 우선해야 함.