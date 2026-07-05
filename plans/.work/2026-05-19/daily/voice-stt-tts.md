# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS lane note (2026-05-20)

## 최근 진행 근거
- 2026-05-19 `docs/execution/2026-05-19_voice_stt_tts.md`: `voice/*`, `scripts/check_voice_contract.py`, STT/TTS 테스트 스크립트 py_compile PASS. 실제 loopback HTTP contract는 sandbox socket 제한으로 BLOCKED, direct handler/ASGI 기준 health, intent, CORS, TTS cache 계약은 PASS.
- 2026-05-19 `daylog/2026-05-19.md`: Voice 코드 변경 없음. 브라우저/실폰 마이크 E2E, TTS HTTP cache header, fallback, 휴대폰 스피커 청취 평가는 완료 근거 없음.
- 2026-05-19 `docs/execution/2026-05-19_integration_field_report.md`: Voice direct/ASGI 계약은 PARTIAL로 분류. 실제 HTTP/browser/phone 근거와 분리 필요.
- 2026-05-19 `docs/execution/2026-05-19_pwa_accessibility_sensors.md`: PWA lint/typecheck/build PASS. PWA voice/CORS/마이크 E2E는 runtime 접근 환경 부재로 BLOCKED.
- 2026-05-18 `docs/voice_stt_tts_status.md`: 실제 사람 음성 8개 모두 지원 intent 판정, 평균 STT 지연 `1.807 sec`, p95 `2.048 sec`. HTTP `/speech/tts` cache header와 실폰 청취 평가는 미완료.
- 2026-05-14 `docs/execution/2026-05-14_pwa_stt_implementation.md`: PWA `MediaRecorder` 녹음과 `/speech/stt` multipart 업로드 UI 구현. 수동 브라우저 마이크 E2E는 별도 필요.
- 2026-05-19 확인: 저장소 내부 `AGENTS.md`와 `plans/daily/2026-05-20.md`는 없음. 사용자 제공 지침과 기존 문서를 기준으로 작성.

## 내일 목표 후보
- 1순위: 일반 개발 세션에서 voice 서버 실제 HTTP `/health`, contract smoke, CORS를 direct-call 근거와 분리해 확인.
- 2순위: 데스크톱 브라우저 마이크로 핵심 intent의 transcript, confidence, UI action 기록.
- 3순위: TTS 7문구 HTTP cache header와 WAV 응답 크기 확인.
- 4순위: voice server down, STT timeout, 마이크 권한 거부, `unknown`, `speechEnabled=false`, `repeat_last` fallback 확인.
- 5순위: Android 실폰 접속 방식 확정 후 핵심 명령 4개 이상과 스피커 청취 smoke.
- 6순위: `서울역으로 안내해줘`를 `set_destination`으로 확장할지 제품 판단 후 rule/test 반영 여부 결정.

## 상세 체크리스트 초안
- [ ] voice/web env 확인 → 검증: `NEXT_PUBLIC_VOICE_API_BASE`, `VOICE_CORS_ORIGINS`, web `3000`, voice `9001` 접속 주소 기록
- [ ] voice 서버 기동 → 검증: `PYTHONPATH=. python -m uvicorn voice.server:app --host 127.0.0.1 --port 9001`, `GET /health` 응답 기록
- [ ] HTTP contract smoke → 검증: `python scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5` PASS
- [ ] PWA CORS 확인 → 검증: 브라우저 Network 또는 curl header로 `/speech/stt` OPTIONS/POST CORS 오류 없음 기록
- [ ] TTS 7문구 HTTP cache 확인 → 검증: 각 문구 2회 `POST /speech/tts`, 두 번째 `X-Voice-Cached: true`, WAV 크기 기록
- [ ] 데스크톱 마이크 E2E → 검증: `신고해`, `음성 켜/꺼`, `다시 말해줘`, `지금 어디야`, `목적지 서울역으로 설정해`, `길 안내 시작해` transcript/intent/confidence/UI action 기록
- [ ] 음성 신고 경로 확인 → 검증: `create_report`가 현재 위험 신고 버튼과 같은 조건을 쓰는지 확인하고, backend 저장 성공 여부는 integration/backend 근거와 분리
- [ ] fallback 회귀 확인 → 검증: server down, timeout, 권한 거부, `unknown`, confidence `< 0.7`에서 앱 전체가 막히지 않음
- [ ] Android 접속 방식 확정 → 검증: ADB reverse/LAN/HTTPS 중 실제 방식, 기기명, Android/Chrome 버전, 마이크 권한 기록
- [ ] 실폰 마이크/스피커 smoke → 검증: 핵심 명령 4개 이상 UI 변화, TTS 시작 지연, 명료도, 음량 기록
- [ ] 자연어 목적지 표현 결정 → 검증: 채택 시 intent rule/test 후보 명시, 미채택 시 `unknown` fallback 문서화

## 리스크/확인 필요
- 2026-05-19에는 sandbox TCP 제한으로 실제 HTTP 검증이 막혔으므로, 2026-05-20은 loopback/브라우저 접근 가능한 세션이 필요함.
- Android에서 `127.0.0.1`은 접속 방식에 따라 의미가 달라 ADB reverse/LAN/HTTPS를 먼저 확정해야 함.
- LAN HTTP는 마이크, 위치, service worker 권한이 secure context 문제로 막힐 수 있음.
- TTS direct-call cache와 HTTP `X-Voice-Cached` 근거를 섞으면 안 됨.
- STT 8/8 성공은 known-intent 로컬 샘플 기준이며, 보행 중 잡음/바람/복수 화자/실폰 마이크 성능으로 확대 해석 금지.
- Qwen3-TTS 동적 생성은 기존 기록상 2초 이상 걸릴 수 있어 위험 경고 primary path로 두면 안 됨.
- 원본 음성 샘플, 생성 WAV, STT CSV, `outputs/voice/*`는 로컬 산출물이며 GitHub 업로드 대상이 아님.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이번 작업은 voice lane 문서/코드 근거 확인과 단일 lane note 작성 범위라 하위 에이전트로 나눌 만큼 독립 실행 작업이 크지 않았음.