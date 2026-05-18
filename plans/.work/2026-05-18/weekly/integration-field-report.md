# Hanium Dreamup / WalkSafe Assist - Integration/Field Test/Report weekly lane note (2026-W21)

## 이번 주 목표 후보

- `plans/weekly/2026-W21.md`는 없음 확인. 이번 note는 `daylog/2026-05-15~18.md`, `plans/daily/2026-05-18.md`, `plans/daily/2026-05-19.md`, `plans/catchup/2026-05-18.md`, `docs/execution/*integration_field_report.md` 근거로 작성한다.
- 1순위: 일반 개발 세션에서 PostGIS `5432`, backend `8000`, web `3000`, voice `9001`, Android 접속 경로를 고정한다.
- 2순위: fake mode 실폰/목걸이 smoke와 `/admin` 운영 흐름을 신고 ID 기준으로 확인한다.
- 3순위: server mode는 headless fixture smoke와 실폰/통제 입력 결과를 분리해 `source=server`, `metadata.source=server` 근거만 남긴다.
- 4순위: voice HTTP/PWA/STT/TTS 검증을 direct-call 근거와 분리해 기록한다.
- 5순위: 데모/보고 기준을 정리해 `fake`, `server`, v2 class `0` 모델, 실폰 field 관찰 근거를 섞지 않는다.

## 날짜별/단계별 체크리스트

- 2026-05-18 월: 기준 고정
  - [ ] `git status`, `NEXT_PUBLIC_*`, `MODEL_*`, `VOICE_*`, `DATABASE_URL` 기록
  - [ ] `plans/weekly/2026-W21.md` 부재와 기존 daily/catch-up 근거 정리
  - [ ] Docker/PostGIS, local TCP, ADB 가능 여부 확인

- 2026-05-19 화: runtime smoke
  - [ ] PostGIS/Alembic/reports API를 skip 없이 재검증
  - [ ] `/health`, `/detect/health`, `/reports?limit=1`, `/admin`, voice `/health` 확인
  - [ ] `scripts/check_pwa_server_e2e.py --web-mode dev|start` 재실행 또는 BLOCKED 사유 기록

- 2026-05-20 수: 실폰/목걸이 fake mode
  - [ ] Android 접속 방식 확정: ADB reverse, LAN IP, HTTPS 중 실제 동작 경로
  - [ ] 기기명, Android/Chrome 버전, 카메라/위치/마이크 권한 기록
  - [ ] 카메라 각도, 흔들림, 렌즈 가림, GPS accuracy, heading, TTS/진동, 화면 미주시 인지성 확인

- 2026-05-21 목: 신고/관리자 운영 흐름
  - [ ] fake 신고 생성 후 `/admin` 목록/상세/이미지/위치 품질/검토 플래그 확인
  - [ ] duplicate 후보와 `new -> reviewed -> resolved` 상태 변경 확인
  - [ ] 테스트 row/upload cleanup 또는 보존 사유 기록

- 2026-05-22 금: server mode와 voice 통합
  - [ ] `MODEL_ARTIFACT_PATH` 설정 후 `/detect/health ready` 확인
  - [ ] 실폰 또는 통제 입력에서 bbox와 신고 payload `source=server` 확인
  - [ ] `신고해`, `음성 켜/꺼`, `다시 말해줘`, `지금 어디야`의 transcript/intent/confidence/UI action 기록
  - [ ] TTS 7문구 HTTP 2회 요청, 두 번째 `X-Voice-Cached: true`, fallback, 휴대폰 스피커 청취 평가 기록

- 2026-05-23 토: 데모 리허설/보고 기준 정리
  - [ ] fake demo, server smoke, voice demo, `/admin` 운영 demo 순서를 분리
  - [ ] v2 모델은 class `0 damaged_tactile_block` baseline으로만 표기
  - [ ] 실폰 테스트는 안전한 실내/통제 smoke로만 표현

- 2026-05-24 일: 주간 마감
  - [ ] `docs/execution/2026-05-24_integration_field_report.md` 또는 주간 통합 문서에 PASS/FAIL/BLOCKED/PENDING 정리
  - [ ] 다음 주 이관 항목: PostGIS 배포/인증, 4-class 데이터, 실제 field 확대, 배포 HTTPS

## 검증 계획

- 정적/빌드: `cd apps/web && npm run lint && npm run typecheck && npm run build`, `node --check apps/web/public/sw.js`
- Backend: `python -m pytest backend/tests -q -rs`, reports no-skip, Alembic head, HTTP smoke, row/upload cleanup
- PWA server mode: `scripts/check_pwa_server_e2e.py --web-mode dev|start`, `source=server`, `metadata.source=server`
- 실폰: Android Chrome 권한, 목걸이 착용 카메라 각도, GPS/heading, TTS/진동, PWA install/offline, TalkBack
- Voice: `scripts/check_voice_contract.py`, PWA CORS, 브라우저/실폰 마이크, TTS HTTP cache header, fallback, 청취 평가
- 보고 검증: 실행하지 않은 항목은 PASS로 쓰지 않고, fixture smoke와 field 관찰을 분리한다.

## 리스크/확인 필요

- 같은 sandbox 조건이면 Docker, local TCP, 포트 조회, ADB가 다시 막힐 수 있다.
- Android에서 `127.0.0.1`은 휴대폰 자신이므로 ADB reverse가 아니면 LAN IP 또는 HTTPS가 필요하다.
- LAN HTTP는 카메라, 마이크, 위치, service worker 권한이 secure context 문제로 막힐 수 있다.
- `source=fake`는 UI/API/운영 흐름 근거일 뿐 정확도, 지연시간, 실제 보행 안전 근거가 아니다.
- `source=server` headless PASS는 known-positive fixture smoke이며 실폰 목걸이 field 성능 근거가 아니다.
- v2 모델은 class `0` baseline이다. 4-class 서비스 성능으로 표현하면 안 된다.
- STT/TTS direct-call 성공은 HTTP/PWA/실폰 성공 근거가 아니다.
- `서울역으로 안내해줘`를 `set_destination`으로 확장할지는 제품 판단 필요.
- 원본 이미지, 위치 데이터, 음성 샘플, 생성 WAV, `.pt`, `.onnx`, `runs/`, `outputs/`는 GitHub 업로드 대상이 아니다.

## 병렬 에이전트 활용 메모

- 병렬 explorer 2개를 사용했다.
- Agent 1: 최근 daylog, daily/catch-up 계획, integration execution 문서, `plans/weekly/2026-W21.md` 부재를 확인했다.
- Agent 2: docs/scripts/code 근거를 확인해 PWA, backend, voice, model, report policy, neck-worn checklist의 통합 검증 포인트를 정리했다.
- 통합 결론: W21은 새 기능 확대보다 runtime gate, 실폰/목걸이 smoke, `/admin` 운영 흐름, server mode smoke 재확인, voice HTTP/PWA 검증, 보고 근거 분리가 핵심이다.