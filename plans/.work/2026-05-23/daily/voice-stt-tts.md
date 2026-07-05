# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS lane note (2026-05-24)

## 최근 진행 근거
- `README.md`, `docs/current_status.md` (2026-05-22): PWA는 음성 명령 중심 사용을 전제로 하며, voice는 `create_report`, 상태 반복, 위치 확인, 목적지/길 안내 준비 intent가 구현되어 있음.
- `docs/walksafe-v2/frontend_display_policy.md`, `docs/walksafe-v2/auto_report_policy.md` (2026-05-22): 자동 tactile damage 신고는 기본 TTS 없음, 음성 요청 신고만 완료/실패를 짧게 안내.
- `daylog/2026-05-22.md`: `useVoiceCommands.ts`로 음성 녹음/intent 처리 분리, v2 `create_report`가 `server-v2`에서 `/reports/v2 trigger=voice`로 연결됨. lint/typecheck/build 및 backend 관련 테스트 PASS 기록.
- `daylog/2026-05-23.md`: backend/frontend/voice 신규 검증 없음. voice mic E2E, 실폰/TalkBack/목걸이 field test는 계속 확인 필요.
- `docs/voice_stt_tts_status.md` (2026-05-18 보정): 실제 사람 음성 8개 known intent 성공, 평균 1.807초, p95 2.048초. 단 HTTP `/speech/tts`, PWA/브라우저/실폰 mic E2E, CORS, fallback, 청취 평가는 미완료.
- `docs/execution/2026-05-19_voice_stt_tts.md`: py_compile 및 direct handler/ASGI 기준 health/intent/CORS/TTS cache 계약은 확인됐지만, 실제 loopback HTTP와 브라우저/실폰 mic는 BLOCKED/PENDING.
- `docs/execution/2026-05-15_voice_cache_followup.md`: `/speech/tts` HTTP 요청은 offline metadata 조회 문제로 500 실패, cache hit 미확인.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports`: 파일 없음. `PM/` 디렉터리도 없음. `plans/daily/2026-05-24.md`도 없음.
- 저장소 내부 `AGENTS.md` 파일은 없고, 사용자 제공 AGENTS 지침을 적용함.

## 내일 목표 후보
- 1순위: v2 hands-free 신고 evidence trace. `신고해` → STT `create_report` → PWA intent handler → `server-v2` `/reports/v2 trigger=voice` 경로를 버튼 신고와 비교한다.
- 2순위: voice HTTP/CORS 최소 gate. `/health`, `/speech/intent`, `/speech/stt` 오류 계약을 실제 HTTP로 확인하고, 막히면 direct handler/ASGI 근거와 분리한다.
- 3순위: 데스크톱 mic 짧은 E2E. `음성 켜/꺼`, `다시 말해줘`, `지금 어디야`, `신고해`의 transcript/intent/confidence/UI action을 기록한다.
- 4순위: TTS fallback/cache 안전화. Qwen 신규 생성보다 cache inventory, `speechSynthesis` fallback, voice-off/repeat/server-down 동작을 먼저 확인한다.
- 5순위: 자연어 목적지 표현 결정안. `서울역으로 안내해줘`를 `set_destination`으로 확장할지 matrix로 정리하되 Kakao/route API는 건드리지 않는다.

## 상세 체크리스트 초안
- [ ] voice/web runtime gate 확인 → 검증: `.venv-voice`, `NEXT_PUBLIC_VOICE_API_BASE`, `VOICE_CORS_ORIGINS`, web `3000`, voice `9001`, Android 접속 방식 기록.
- [ ] 실제 HTTP contract smoke 실행 → 검증: `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5` PASS 또는 loopback/socket 차단 사유 기록.
- [ ] PWA CORS 확인 → 검증: 브라우저 Network 또는 OPTIONS/POST 응답에서 `/speech/stt` CORS 오류 없음 기록.
- [ ] hands-free 신고 trace 확인 → 검증: `신고해`가 `create_report`로 들어오고 v2에서는 `/reports/v2 trigger=voice`, v1에서는 기존 신고 조건과 같은 흐름을 타는지 기록.
- [ ] mic E2E 가능 시 실행 → 검증: 5개 핵심 명령의 transcript, intent, confidence, UI 상태 변화, 실패 시 fallback 메시지 기록.
- [ ] runtime 막힘 시 safe alternative 수행 → 검증: fixture `VoiceSttResponse` 또는 direct intent handler로 UI action까지만 PARTIAL 기록하고 실제 HTTP/DB 저장은 BLOCKED로 분리.
- [ ] TTS fallback/cache 확인 → 검증: cache WAV 목록, `speechSynthesis` fallback, `speechEnabled=false`, `repeat_last`, server-down/timeout에서 앱이 멈추지 않는지 기록.
- [ ] 자연어 목적지 matrix 작성 → 검증: `목적지 서울역으로 설정해`, `서울역으로 안내해줘`, `길 안내 시작해`의 현재/희망 intent와 제품 결정 필요 여부 기록.
- [ ] Voice 실행 근거 정리 → 검증: HTTP, direct-call, browser mic, phone mic, TTS cache, fallback을 PASS/FAIL/BLOCKED/PENDING으로 분리.

## 리스크/확인 필요
- 브라우저/실폰 mic, Android ADB reverse, 휴대폰 스피커 청취는 장비/권한 환경이 필요하다. 대안은 데스크톱 mic 또는 fixture trace지만 Device E2E로 쓰면 안 된다.
- `/speech/tts`는 모델 metadata/offline/cache 문제로 500 이력이 있다. 신규 다운로드나 Cloud STT/TTS는 자동 계획에서 제외하고, local cache/fallback부터 확인한다.
- `docs/stt_tts_current_status.md`는 `지금어디야` 상태가 오래된 내용과 충돌한다. 최신 기준은 `docs/voice_stt_tts_status.md`와 `voice/intents.py`.
- `서울역으로 안내해줘` 확장은 사용자 경험상 유용하지만 목적지/경로/Kakao API까지 확장하지 않는다. 내일은 intent rule 판단안까지만 안전하다.
- 운영 DB, secret, 외부 배포, AWS/S3, Cloud STT/TTS, Kakao 실제 key, 지자체 API는 C 작업으로 보류한다.

## 병렬 에이전트 활용 메모
- 사용함.
- Product/scheduler 조사 에이전트: product 문서, `plans/features`, `plans/verify`, `PM`, 기존 daily 계획을 확인. 결론은 scheduler/PM 산출물 부재, `feature-growth` 기준 유지.
- Voice 구현/검증 조사 에이전트: `voice/`, PWA voice hook, 최근 execution/daylog를 확인. 결론은 구현은 존재하지만 HTTP/mic/TTS fallback E2E가 미완료이며 safe alternative는 contract smoke와 fixture trace.