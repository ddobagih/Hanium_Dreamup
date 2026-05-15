# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors lane note (2026-05-16)

## 최근 진행 근거
- 2026-05-15 저장소 확인: 저장소 내부 `AGENTS.md`와 `plans/daily/2026-05-16.md`는 없음. 사용자 제공 AGENTS 지침과 기존 문서 기준으로 정리.
- 2026-05-15 `daylog/2026-05-15.md`: PWA server detector wiring은 코드/문서 근거가 있으나, 실폰 카메라/GPS/방향/TTS/진동, 브라우저 마이크 E2E, `source: "server"` 신고 저장, PWA 설치/offline은 확인 필요로 남음.
- 2026-05-15 `plans/daily/2026-05-15.md`, `plans/catchup/2026-05-15.md`: 실폰 통합 검증, PWA server detector E2E, STT 마이크 E2E, 접근성 점검이 catch-up 핵심.
- 2026-05-14 `docs/execution/2026-05-14_frontend.md`: `apps/web` lint/typecheck와 `/`, `/admin`, `/manifest.webmanifest`, `/sw.js` HTTP 확인 통과. 실제 브라우저 권한 기반 E2E는 대기.
- 2026-05-14 `docs/execution/2026-05-14_pwa_stt_implementation.md`: `MediaRecorder` 녹음 UI와 `/speech/stt` 업로드 client 구현. 실제 마이크/STT E2E는 대기.
- 2026-05-14 `docs/execution/2026-05-14_pwa_server_detector_wiring.md`: `NEXT_PUBLIC_DETECTOR_MODE=server`에서 `/detect/health`, `/detect` 호출과 `source: "server"` 매핑 구현. 실폰/브라우저 호출 검증은 대기.
- 2026-05-14 `docs/execution/2026-05-14_next_step_parallel.md`: 실제 v2 `best.pt` 기반 backend `/detect` TestClient smoke에서 `model_status: ready`, detection 4개, 첫 detection `source: server` 기록.
- 2026-05-13 `docs/frontend_3day_execution_plan.md`: 2026-05-16 목표는 model waiting/server inference/STT UI/fallback 상태와 데모 기준 확정.
- 2026-05-12 `docs/neck_worn_phone_test_checklist.md`, `docs/figma_make_accessibility_review.md`: 목걸이 착용, 화면 미주시 TTS/진동, `aria-live`, 큰 터치 영역, fake detector 오해 방지 기준 확인.

## 내일 목표 후보
- 실폰 또는 ADB reverse/HTTPS 환경에서 PWA 카메라, GPS, 방향, TTS, 진동, 신고, `/admin`, PWA shell을 우선 검증.
- `NEXT_PUBLIC_DETECTOR_MODE=server`로 실제 브라우저/실폰에서 `/detect` 호출, bbox 표시, `source: "server"` 신고 저장까지 확인.
- PWA 음성 명령 녹음 → voice server `/speech/stt` → intent handler 동작을 실제 마이크로 확인.
- TalkBack/키보드/DevTools 기준으로 위험 상태, 음성 토글, 음성 명령, 신고 버튼, 카메라/bbox 낭독 순서를 점검.
- fake/server/STT 결과를 분리해 데모 설명 기준과 후속 PR 후보를 확정.

## 상세 체크리스트 초안
- [ ] 기준 상태 고정 → 검증: `git status --short --untracked-files=all`, `apps/web/.env.example`, 최신 `docs/execution`/`daylog` 근거를 기록.
- [ ] PWA 정적 회귀 확인 → 검증: `cd apps/web && npm run lint && npm run typecheck`, 가능하면 `npm run build` 결과 기록.
- [ ] 실폰 접속 경로 준비 → 검증: `adb reverse tcp:3000 tcp:3000`, `tcp:8000`, `tcp:9001` 후 Android Chrome에서 `http://localhost:3000` 접속.
- [ ] fake mode 실폰 E2E 확인 → 검증: 후면 카메라 프리뷰, bbox, 4개 위험 클래스 순환, `데모 탐지 모드`, `source: "fake"` 표시 확인.
- [ ] 목걸이 착용 센서 확인 → 검증: 전방 1~3m와 바닥 일부, GPS 정확도/거부 상태, 방향 값 또는 대기 상태를 체크리스트 형식으로 기록.
- [ ] TTS/진동 체감 확인 → 검증: 4개 위험 문구, 음성 켜짐/꺼짐 진동 차이, 신고 성공/실패/유사 신고 진동 구분 기록.
- [ ] fake 신고와 관리자 확인 → 검증: 실폰에서 신고 1건 생성 후 `/admin` 이미지, 위치 품질, `fake_source`, duplicate, 상태 변경 확인.
- [ ] server detector mode E2E 확인 → 검증: backend 모델 ready 상태에서 `NEXT_PUBLIC_DETECTOR_MODE=server` 실행, `/detect` 호출, bbox 표시, `source: "server"` 신고 payload 확인.
- [ ] PWA 음성 명령 E2E 확인 → 검증: `신고해`, `음성 켜`, `음성 꺼`, `다시 말해줘`, `지금 어디야`의 transcript/intent/confidence/UI action 기록.
- [ ] 음성 fallback 확인 → 검증: voice server 미실행, STT timeout, 마이크 권한 거부 시 앱이 멈추지 않고 터치 UI/TTS/진동이 유지되는지 확인.
- [ ] PWA 설치/offline shell 확인 → 검증: 홈 화면 추가 또는 설치, standalone 재접속, 네트워크 차단 후 `/`, manifest, icon cache fallback 확인.
- [ ] 접근성 빠른 점검 → 검증: 위험 배너 `aria-live`, 음성 토글/명령 `aria-pressed`, 신고 버튼 disabled 이유, bbox `aria-hidden`, 터치 타깃을 TalkBack 또는 DevTools로 확인.
- [ ] 데모 기준 정리 → 검증: fake 신고, server detect, STT 명령, 관리자 운영 흐름을 성능 근거와 통합 테스트 근거로 분리해 기록.

## 리스크/확인 필요
- 2026-05-15 실행 문서(`docs/execution/2026-05-15*.md`)가 없어 5/15 실폰/브라우저 완료 근거는 현재 확인되지 않음.
- `apps/web/app/page.tsx`, `apps/web/lib/detect-api.ts` 등 PWA server detector 변경이 미커밋/미추적 상태라 후속 작업 전 diff 확인 필요.
- 일부 5/13 문서는 STT 미연결 또는 `/detect` placeholder 기준이다. 5/14 실행 로그가 더 최신이다.
- 실폰, ADB reverse, HTTPS 개발 URL 중 하나가 없으면 핵심 검증은 완료가 아니라 수동 검증 대기다.
- Android Chrome 기준 결과와 iOS/다른 브라우저의 Vibration API, DeviceOrientation, PWA 설치 동작은 분리해야 한다.
- v2 모델은 실질적으로 점자블록 class 0 중심 baseline이다. server mode가 동작해도 4개 위험 클래스 성능 완료로 표현하면 안 된다.
- 코드상 `repeat_last`/음성 꺼짐 회귀는 별도 확인 필요로 보인다. 음성 꺼짐 상태에서 의도치 않은 발화가 없는지 검증해야 한다.
- fake detector 신고는 UI/API 통합 확인용이며 실제 보행 안전, 모델 정확도, 경보 지연 성능 근거로 사용 금지.

## 병렬 에이전트 활용 메모
- 하위/병렬 에이전트는 사용하지 않음.
- 이번 작업은 문서와 `apps/web` 코드 근거 확인 중심이고 최종 산출물이 단일 lane note라 메인 에이전트가 직접 통합하는 편이 충분했다.