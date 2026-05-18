# Hanium Dreamup / WalkSafe Assist - PWA/Accessibility/Sensors weekly lane note (2026-W21)

## 이번 주 목표 후보

- 기준 전제: `plans/weekly/2026-W21.md`는 확인 시점에 없음. 최근 근거는 `daylog/2026-05-17.md`, `daylog/2026-05-18.md`, `docs/current_status.md`, `docs/pwa_backend_status.md`, `docs/execution/2026-05-15_pwa_*`, `plans/.work/2026-05-18/*/pwa-accessibility-sensors.md` 기준.
- PWA 정적 회귀 상태를 유지한다: `apps/web` lint/typecheck/build, service worker syntax, server-mode build.
- Android 실폰/목걸이 착용 fake mode smoke를 이번 주 핵심 목표로 둔다: 카메라 각도, GPS, 방향, TTS, 진동, 화면 미주시 인지성.
- fake 신고 생성부터 `/admin` 운영 흐름까지 닫는다: 이미지, 위치 품질, `fake_source`, duplicate 후보, 상태 변경.
- TalkBack/offline/PWA 설치 검증을 별도 완료 기준으로 분리한다.
- backend/model/voice runtime이 확보될 때만 server mode와 PWA 마이크 STT E2E를 추가 검증한다.
- fake, server, 실폰 관찰, 모델 성능 근거를 보고서에서 섞지 않는다.

## 날짜별/단계별 체크리스트

- 2026-05-18 월: 기준선 확정
  - 최근 daylog와 문서 상태 정리.
  - `plans/weekly/2026-W21.md` 부재 기록.
  - PWA env 후보 정리: `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_DETECTOR_MODE`, `NEXT_PUBLIC_VOICE_API_BASE`.

- 2026-05-19 화: 정적 회귀와 Android 접속 경로
  - `apps/web` 정적 검증 재확인.
  - ADB reverse, LAN IP, HTTPS 중 실제 가능한 Android 접속 방식 결정.
  - 기기명, Android/Chrome 버전, 권한 상태 기록.

- 2026-05-20 수: fake mode 실폰/목걸이 smoke
  - 후면 카메라 프리뷰, bbox, 4개 fake 위험 순환 확인.
  - GPS accuracy, heading 또는 센서 대기 상태 기록.
  - 4개 위험 TTS 문구, 6초 쿨다운, 음성 on/off 진동 패턴 확인.

- 2026-05-21 목: 신고와 관리자 흐름
  - fake 신고 1~2건 생성.
  - `/admin`에서 이미지, 위치 품질, `fake_source`, duplicate 후보 확인.
  - `new -> reviewed -> resolved` 상태 변경 확인.
  - 테스트 row/upload cleanup 여부 기록.

- 2026-05-22 금: 접근성/offline
  - TalkBack 또는 DevTools로 `aria-live`, `aria-pressed`, 신고 버튼 disabled reason 확인.
  - bbox/camera 장식 낭독 제외 확인.
  - PWA 설치, standalone 실행, 네트워크 차단 뒤 `/` shell fallback 확인.
  - 오프라인 신고 큐는 구현된 것처럼 표현하지 않음.

- 2026-05-23 토: 조건부 통합 확인
  - backend `/detect/health ready`가 확보되면 server mode smoke 실행.
  - `source=server`, `metadata.source=server`, bbox, 신고 저장 확인.
  - voice server가 확보되면 PWA 마이크 STT E2E와 CORS 확인.

- 2026-05-24 일: 정리와 보고 기준 고정
  - 실행 결과를 `docs/execution/2026-05-24_pwa_accessibility_sensors.md` 또는 해당 날짜 문서에 정리.
  - PASS/FAIL/BLOCKED/PENDING 구분.
  - 실폰 smoke는 통제 환경 관찰로만 표현하고 실제 안전 성능으로 확대하지 않음.

## 검증 계획

- 정적 검증:
  - `node --check apps/web/public/sw.js`
  - `cd apps/web && npm run lint && npm run typecheck && npm run build`
  - server mode build 시 `NEXT_PUBLIC_DETECTOR_MODE=server`를 build time에 명시.

- 실폰 검증:
  - Android Chrome에서 카메라/위치/마이크 권한 상태 기록.
  - 목걸이 착용 시 전방 1~3m와 바닥 일부가 함께 보이는지 확인.
  - 화면을 보지 않고 TTS/진동만으로 위험/신고 성공/실패를 구분 가능한지 기록.

- 관리자 검증:
  - 생성 신고 ID 기준으로 `/admin` 목록, 상세 이미지, 위치 품질, 검토 플래그, 상태 변경 확인.
  - fake 신고는 `source=fake`로 기록하고 성능 근거에서 제외.

- 접근성 검증:
  - 위험 상태 live region, 음성 토글 `aria-pressed`, 신고 버튼 disabled reason, 주요 터치 영역 확인.
  - 카메라/bbox가 반복 낭독되지 않는지 확인.

- 조건부 검증:
  - server mode는 `/detect/health ready`와 backend/model 접속 가능할 때만 수행.
  - PWA STT는 voice `/speech/stt` CORS와 브라우저/실폰 마이크가 확보될 때만 수행.

## 리스크/확인 필요

- Android에서 `127.0.0.1`은 휴대폰 자신을 가리키므로 접속 방식 확정이 먼저 필요하다.
- LAN HTTP는 카메라, 위치, 마이크, service worker 권한이 secure context 문제로 막힐 수 있다.
- Web Speech API 한국어 TTS, Vibration API, DeviceOrientation 지원은 기기별 차이가 크다.
- `DeviceOrientation.alpha`는 실제 이동 방향이 아니라 센서 heading 표시로만 기록해야 한다.
- `NEXT_PUBLIC_*` 값은 build time 반영이므로 server/voice mode 검증 시 env를 고정해야 한다.
- fake detector는 UI/API/운영 흐름 검증용이다. 정확도, 지연시간, 실제 보행 안전 근거로 쓰면 안 된다.
- server mode headless E2E는 known-positive fixture smoke이며 실폰 목걸이 field 성능 근거가 아니다.
- v2 모델은 class `0 damaged_tactile_block` 중심 baseline이라 4개 위험 클래스 전체 성능으로 표현하면 안 된다.
- 현재 문서 기준 PostGIS/runtime, Android 장비, voice HTTP 환경은 별도 확보가 필요하다.

## 병렬 에이전트 활용 메모

- 이번 lane note 작성에는 하위/병렬 에이전트를 사용하지 않았다.
- 실제 주간 실행에서는 PWA 실폰/접근성, backend runtime gate, voice HTTP/CORS 확인을 병렬로 나눌 수 있다.
- 최종 PWA 실행 문서와 daylog는 충돌 방지를 위해 단일 통합자가 한 번에 정리하는 것이 좋다.