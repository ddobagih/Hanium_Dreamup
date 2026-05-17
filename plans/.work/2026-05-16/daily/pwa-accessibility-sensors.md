# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors lane note (2026-05-17)

## 최근 진행 근거
- 사용자 제공 AGENTS 지침: 저장소 내부 `AGENTS.md`는 확인되지 않아 사용자 제공 지침을 기준으로 적용.
- `README.md` / 2026-05-16 확인: PWA는 `apps/web`, 신고 관리는 `/admin`, 기본 검증은 `npm run lint && npm run typecheck && npm run build`.
- `plans/daily/2026-05-17.md` / 2026-05-16 확인: 파일 없음.
- `docs/execution/2026-05-14_frontend.md`: `apps/web` lint/typecheck, `/`, `/admin`, manifest, SW HTTP 200 확인. 브라우저/실폰 카메라·센서·TTS·진동은 수동 검증 대기.
- `docs/execution/2026-05-14_pwa_server_detector_wiring.md`: `NEXT_PUBLIC_DETECTOR_MODE=server`, `/detect/health`, `/detect`, `source: "server"` 매핑 구현 및 lint/typecheck 통과.
- `docs/execution/2026-05-14_pwa_stt_implementation.md`: `MediaRecorder` 기반 녹음, `/speech/stt` 업로드, intent handler 구현 및 lint/typecheck 통과.
- `docs/execution/2026-05-15_pwa_validation.md`: PWA lint/typecheck/build PASS. Android 기기는 연결되지 않아 실폰 검증 미실행.
- `docs/execution/2026-05-15_pwa_server_detection_e2e.md`, `docs/execution/2026-05-15_pwa_production_server_e2e.md`: headless Chromium fixture 기준 server mode에서 `source=server`, `metadata.source=server` 신고 저장 PASS.
- `plans/.work/2026-05-16/execute-r1/pwa-accessibility-sensors.md`, `daylog/2026-05-16.md`: `repeat_last`가 음성 꺼짐 상태에서 재생되지 않도록 수정, 관리자 row/status 버튼 `aria-pressed` 추가, SW navigation fallback/static cache/API 실패 처리 분리. 문서상 PWA lint/typecheck/build PASS.
- `docs/execution/2026-05-16_integration_field_report.md`: 실폰/목걸이 field test, 브라우저·실폰 마이크 STT E2E, PWA install/offline/TalkBack은 완료 근거로 쓰지 않도록 재분류.
- `apps/web/app/page.tsx`, `apps/web/lib/detect-api.ts`, `apps/web/lib/voice-api.ts`, `apps/web/public/sw.js` / 2026-05-16 확인: fake/server 탐지, GPS, 방향, TTS, 진동, STT 업로드, shell fallback 구현은 있으나 실제 모바일 권한·센서 동작은 별도 검증 필요.

## 내일 목표 후보
1. Android 실폰 접속 경로를 확정하고 카메라/GPS/방향/TTS/진동 권한과 동작을 실제 기기에서 기록한다.
2. 목걸이 착용 상태의 fake mode 보행 화면과 신고→`/admin` 운영 흐름을 수동 E2E로 확인한다.
3. PWA install/offline shell과 TalkBack 또는 DevTools 접근성 점검을 완료 근거/미완료 근거로 분리 기록한다.
4. 브라우저 또는 실폰 마이크로 STT intent가 실제 UI action으로 이어지는지 확인한다.
5. server mode는 이미 headless E2E 근거가 있으므로, 실폰에서 `/detect` 호출과 `source=server` 신고 저장 재현 여부만 별도 확인한다.
6. 오래된 상태 문서의 fake-only/placeholder 서술을 최신 server mode 구현 근거와 충돌하지 않게 갱신 후보로 정리한다.

## 상세 체크리스트 초안
- [ ] 실폰 접속 방식 고정 → 검증: 기기명, Android/Chrome 버전, ADB reverse 또는 LAN/HTTPS 방식, `3000/8000/9001` 접근 가능 여부 기록.
- [ ] fake mode 후면 카메라 smoke → 검증: 프리뷰, bbox, 4개 위험 클래스 순환, `데모 탐지 모드`, `source=fake` 표시를 실폰에서 확인.
- [ ] 목걸이 착용 각도 점검 → 검증: `docs/neck_worn_phone_test_checklist.md` 기준 전방 1~3m, 바닥 일부, 흔들림, 렌즈 가림 여부 기록.
- [ ] GPS/방향 센서 확인 → 검증: 위치 허용/거부, 정확도 m, `DeviceOrientation` 방향값 또는 대기 상태, 신고 metadata 반영 여부 기록.
- [ ] TTS/진동 체감 확인 → 검증: 4개 위험 문구, 6초 쿨다운, 음성 켜짐/꺼짐별 진동, 신고 성공/실패/유사 신고 진동 구분 기록.
- [ ] fake 신고와 관리자 화면 확인 → 검증: 실폰 신고 1건 생성 후 `/admin` 이미지, 위치 품질, `fake_source`, duplicate 후보, `new → reviewed → resolved` 확인.
- [ ] PWA 설치/offline shell 확인 → 검증: standalone 실행, 네트워크 차단 뒤 `/` fallback, manifest/icon/static cache 동작 기록.
- [ ] 접근성 수동 점검 → 검증: 위험 배너 `aria-live`, 음성 토글/녹음/관리자 상태 버튼 `aria-pressed`, 신고 버튼 disabled reason, bbox 장식 요소 낭독 여부, 터치 타깃 확인.
- [ ] STT 마이크 E2E 확인 → 검증: `신고해`, `음성 켜`, `음성 꺼`, `다시 말해줘`, `지금 어디야`의 transcript, intent, confidence, UI action 기록.
- [ ] server mode 실폰 재현 → 검증: `NEXT_PUBLIC_DETECTOR_MODE=server`에서 `/detect/health`, `/detect`, bbox 표시, 신고 payload `source=server` 저장 여부 기록.
- [ ] 문서 충돌 목록 정리 → 검증: `docs/current_status.md`, `docs/pwa_backend_status.md`, `docs/frontend_handoff_without_model.md`의 placeholder/fake-only 문구를 최신 근거와 대조해 수정 후보만 분리.

## 리스크/확인 필요
- 실폰/ADB/HTTPS 환경이 없으면 카메라, GPS, 방향, TTS, 진동, PWA 설치, TalkBack 항목은 완료가 아니라 수동 검증 대기다.
- `source=fake`는 UI/API 데모 근거이며 실제 안전 판단, 모델 정확도, 경보 지연 근거로 쓰면 안 된다.
- `source=server` E2E는 현재 headless fixture/known-positive 이미지 근거다. 실폰 카메라 field 성능 근거와 분리해야 한다.
- v2 모델은 `damaged_tactile_block` 중심 baseline이다. 4개 위험 클래스 전체 성능으로 표현하면 안 된다.
- iOS Safari는 `DeviceOrientationEvent.requestPermission` 처리가 없어 heading이 계속 대기 상태일 수 있다. Android Chrome 기준 결과와 분리 필요.
- SW는 shell/static fallback 중심이다. 오프라인 신고 큐나 재전송 보장은 구현되어 있지 않다.
- 이번 lane note 작성 중 새 `npm`, 브라우저, 실폰 검증은 실행하지 않았고, 문서/코드 근거를 통합했다.

## 병렬 에이전트 활용 메모
- 사용함.
- 하위 작업 1: 최근 PWA 관련 문서/daylog/계획 근거 수집. 결론은 정적 검증, server detector wiring, headless server E2E, 접근성 일부 보강은 근거가 있으나 실폰/목걸이/마이크/offline/TalkBack은 미완료.
- 하위 작업 2: `apps/web` 구현 상태 확인. 결론은 fake/server 탐지, GPS, 방향, TTS/진동, STT 업로드, 관리자 UI, 기본 접근성 affordance는 구현되어 있으나 iOS 방향 권한, 센서 재연결, 오프라인 신고 큐, 자동 접근성 검증은 약하거나 없음.
- 통합 결론: 2026-05-17 PWA lane은 새 기능 확대보다 실폰 센서·접근성·운영 흐름 검증과 최신 문서 충돌 정리를 우선한다.