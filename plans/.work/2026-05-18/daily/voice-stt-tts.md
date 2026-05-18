# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS lane note (2026-05-19)

## 최근 진행 근거
- 2026-05-18 `plans/.work/2026-05-18/execute-r1/voice-stt-tts.md`: `voice/*`, `scripts/test_stt.py`, `scripts/test_tts.py`, `scripts/check_voice_contract.py` py_compile 통과. STT dry-run 8개 success, 실제 사람 음성 8/8 success, avg `1.900s`, p95 `2.019s`. TTS 7문구는 direct-call 기준 cache 존재와 cached `true` 확인.
- 2026-05-18 `daylog/2026-05-18.md`: Voice 로컬 STT/TTS 검증은 완료 근거가 있으나 HTTP `/health`, contract, CORS preflight, `/speech/tts` HTTP cache hit은 sandbox loopback 제한으로 PASS 처리하지 않음.
- 2026-05-18 `docs/voice_stt_tts_status.md`: 2026-05-17 CSV 기준 실제 사람 음성 8개 모두 지원 intent 판정, avg `1.807s`, p95 `2.048s`; Qwen3-TTS는 로컬 Hugging Face cache snapshot 우선 사용으로 보정됨.
- 2026-05-18 `docs/execution/2026-05-18_integration_field_report.md`: STT/TTS는 로컬 프로토타입 근거로만 보고, 브라우저/실폰 마이크 E2E, HTTP cache header, fallback, 청취 평가는 별도 근거 필요.
- 2026-05-14 `docs/execution/2026-05-14_pwa_stt_implementation.md`: PWA `MediaRecorder` 녹음과 `/speech/stt` multipart 업로드 UI가 구현됐지만, 수동 브라우저 마이크 E2E는 남음.
- 2026-05-18 코드 확인: `voice/server.py`는 `/health`, `/speech/intent`, `/speech/stt`, `/speech/tts`, CORS, 업로드 검증을 제공하고 `voice/intents.py`는 `get_current_location`을 포함함. `apps/web/lib/voice-api.ts`, `apps/web/app/page.tsx`는 `NEXT_PUBLIC_VOICE_API_BASE`, 6초 STT timeout, intent별 UI action을 연결함.
- 2026-05-18 확인: `서울역으로 안내해줘`는 현재 `unknown`으로 남아 있으며, 제품 판단 없이 rule을 확장하지 않았음.
- 2026-05-18 확인: 저장소 내부 `AGENTS.md`와 `plans/daily/2026-05-19.md`는 확인되지 않아 사용자 제공 지침과 5/18 로그/문서를 기준으로 작성함.

## 내일 목표 후보
- 1순위: 일반 개발 세션에서 voice 서버 HTTP health/contract, CORS, `/speech/tts` HTTP cache header를 direct-call 결과와 분리해 재검증.
- 2순위: 데스크톱 브라우저 마이크로 핵심 intent의 transcript, confidence, UI action을 기록.
- 3순위: 가능하면 Android 실폰 접속 경로를 고정하고 핵심 명령 4개 이상을 실폰 마이크로 확인.
- 4순위: TTS 7문구의 HTTP cache, voice-off/repeat/server-down fallback, 휴대폰 스피커 청취 평가를 기록.
- 5순위: `서울역으로 안내해줘`를 `set_destination`으로 확장할지 결정하고, 채택 시 rule/test/PWA 결과를 함께 확인.
- 6순위: 실행 결과를 `docs/execution/2026-05-19_voice_stt_tts.md`에 통과/실패/대기/확인 필요로 분리 기록.

## 상세 체크리스트 초안
- [ ] 작업트리와 env 확인 → 검증: `git status --short --branch --untracked-files=all`, `NEXT_PUBLIC_VOICE_API_BASE`, `VOICE_CORS_ORIGINS`, voice/web 접속 주소 기록.
- [ ] voice 서버 기동 → 검증: `PYTHONPATH=. python -m uvicorn voice.server:app --host 127.0.0.1 --port 9001`, `GET /health`에서 `server=voice`, STT/TTS model 확인.
- [ ] voice contract smoke 재실행 → 검증: `python scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5` PASS.
- [ ] CORS preflight 확인 → 검증: PWA origin에서 `/speech/stt` OPTIONS/POST가 CORS 오류 없이 통과하는지 브라우저 Network 또는 curl header로 기록.
- [ ] TTS 7문구 HTTP cache 확인 → 검증: 각 문구 2회 `POST /speech/tts`, 두 번째 응답 `X-Voice-Cached: true`, WAV 응답 크기와 cache 파일 존재 기록.
- [ ] 데스크톱 마이크 E2E → 검증: `신고해`, `음성 켜/꺼`, `다시 말해줘`, `지금 어디야`, `목적지 서울역으로 설정해`, `길 안내 시작해`의 transcript/intent/confidence/UI action 기록.
- [ ] 음성 신고와 버튼 신고 비교 → 검증: `create_report`가 현재 위험 신고 버튼과 같은 조건, duplicate check, `POST /reports` 흐름을 타는지 확인하되 backend 저장 검증은 integration/backend 근거와 분리.
- [ ] fallback 회귀 확인 → 검증: voice server down, STT timeout, 마이크 권한 거부, `unknown`/confidence `< 0.7`에서 앱 전체가 막히지 않고 터치 UI/TTS/진동이 유지됨.
- [ ] `speechEnabled=false`와 `repeat_last` 확인 → 검증: 음성 꺼짐 상태에서 위험 안내와 반복 명령이 의도치 않게 음성을 재생하지 않고 상태 문구/진동만 남는지 기록.
- [ ] Android 실폰 접속 경로 확정 → 검증: ADB reverse/LAN IP/HTTPS 중 하나로 web `3000`과 voice `9001` 접근, 기기명, Android/Chrome 버전, 마이크 권한 기록.
- [ ] 실폰 마이크/스피커 smoke → 검증: 핵심 명령 4개 이상 UI 변화, TTS 시작 지연, 명료도, 음량, 주변 소음 구분 가능 여부 기록.
- [ ] 자연어 목적지 표현 결정 → 검증: 채택 시 `voice/intents.py` rule과 test case 추가 후보를 명시하고, 미채택 시 `unknown` fallback을 문서화.

## 리스크/확인 필요
- Android에서 `127.0.0.1`은 휴대폰 자신을 가리킬 수 있어 ADB reverse, LAN IP, HTTPS 중 실제 접속 방식을 먼저 확정해야 함.
- 5/18 r1은 sandbox TCP 제한으로 HTTP/CORS 검증을 PASS 처리하지 않았으므로, 내일은 loopback/브라우저 접근 가능한 세션이 필요함.
- TTS 7문구 cache는 direct-call 근거와 HTTP header 근거를 분리해야 함. HTTP `X-Voice-Cached` 확인 전에는 PWA HTTP cache 완료로 쓰지 않음.
- Qwen3-TTS 동적 생성은 기존 기록상 2초 이상 걸릴 수 있어 보행 위험 경고 primary path로 두면 안 됨.
- 실제 STT 8/8은 known-intent 샘플 기준이다. 보행 중 잡음, 바람, 복수 화자, 실폰 마이크 성능 근거로 확대 해석하지 않음.
- `서울역으로 안내해줘` 확장은 제품 문구 범위 결정이 필요함. 임의로 확장하면 unknown fallback 기준이 바뀜.
- `create_report` E2E는 PWA 위험 이벤트와 backend/PostGIS 상태에 의존하므로 voice intent 처리와 신고 저장 성공을 따로 기록해야 함.
- 원본 음성 샘플, STT CSV, 생성 WAV, `outputs/voice/*`는 로컬 산출물이며 GitHub 업로드 대상이 아님.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이번 lane note는 문서/코드 근거 수집과 단일 최종 markdown 작성 범위라 별도 하위 에이전트로 나눌 필요가 없었음.