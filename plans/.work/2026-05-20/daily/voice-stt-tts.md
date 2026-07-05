# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS lane note (2026-05-21)

## 최근 진행 근거
- 2026-05-20 `plans/daily/2026-05-21.md`: voice lane은 HTTP contract, CORS, hands-free 신고, TTS HTTP cache/fallback, 자연어 목적지 표현 검토를 5/21 후보로 이미 반영했다.
- 2026-05-20 `daylog/2026-05-20.md`: 5/20은 모델/Data/MLOps 중심 실행이었다. Voice HTTP contract, 브라우저/실폰 마이크 E2E, TTS HTTP cache/fallback/청취 평가는 새 완료 근거가 없다.
- 2026-05-20 확인: `plans/features`, `plans/verify/ready`, `plans/verify/reports`는 파일 없음. `PM` 디렉터리도 없어 product audit, verify report, automation metrics 산출물은 반영할 근거가 없다.
- 2026-05-19 `docs/execution/2026-05-19_voice_stt_tts.md`: voice 코드 `py_compile` PASS, direct handler/ASGI 기준 health/intent/CORS/TTS cache 계약 PASS. 실제 loopback HTTP는 sandbox socket 제한으로 BLOCKED.
- 2026-05-18 `docs/voice_stt_tts_status.md`: 실제 사람 음성 8개는 지원 intent 8/8 성공, 평균 `1.807 sec`, p95 `2.048 sec`. 단, 브라우저/실폰 마이크 E2E와 HTTP TTS cache header는 미완료다.
- 2026-05-18 `product/backlog.md`, `product/done-criteria.md`: P0-005는 브라우저/실폰 마이크 STT E2E, P1-002는 TTS HTTP cache/fallback/청취 평가다. local sample 성공을 E2E 완료로 쓰면 안 된다.
- 현재 코드 확인: `voice/intents.py`는 `create_report`, `voice_on/off`, `repeat_last`, `set_destination`, `start_navigation`, `get_current_location`을 지원한다. `apps/web/lib/voice-api.ts`와 `apps/web/app/page.tsx`는 MediaRecorder 업로드와 PWA intent action 연결이 구현되어 있다.
- 현재 코드 확인: `서울역으로 안내해줘` 형태는 아직 명시적 `set_destination` 규칙이 아니다. `목적지 서울역으로 설정해/안내해` 계열은 지원된다.

## 내일 목표 후보
- 1순위: 실제 HTTP 기반 voice contract를 확인하고 direct-call 근거와 분리한다.
- 2순위: hands-free 신고 slice를 닫는다. 마이크 `신고해` → `create_report` → 기존 신고 버튼과 같은 disabled 조건/duplicate check/`POST /reports` 경로인지 확인한다.
- 3순위: TTS 7문구 HTTP cache/fallback을 확인한다. 두 번째 요청 `X-Voice-Cached: true`, WAV 크기, server-down/timeout/voice-off/repeat fallback을 분리 기록한다.
- 4순위: 자연어 목적지 표현 `서울역으로 안내해줘` 확장 여부를 제품 판단 항목으로 정리한다. 채택해도 route/Kakao API 없이 목적지 state 저장까지만 한다.
- 5순위: Android/ADB reverse가 가능하면 실폰 마이크 핵심 명령 4개와 휴대폰 스피커 청취 smoke를 수행한다.

## 상세 체크리스트 초안
- [ ] voice/web env 확인 → 검증: `NEXT_PUBLIC_VOICE_API_BASE`, `VOICE_CORS_ORIGINS`, web `3000`, voice `9001`, Android 접속 방식 기록
- [ ] voice 서버 실제 HTTP 기동 → 검증: `GET /health` 응답과 `status=ok`, `server=voice`, 모델/env 값 기록
- [ ] HTTP contract smoke 실행 → 검증: `python scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5` PASS 또는 socket 차단 사유 기록
- [ ] PWA CORS 확인 → 검증: 브라우저 Network 또는 OPTIONS/curl로 `/speech/stt` CORS 오류 없음 기록
- [ ] 데스크톱 마이크 핵심 명령 E2E → 검증: `신고해`, `음성 켜`, `음성 꺼`, `다시 말해줘`, `지금 어디야`의 transcript/intent/confidence/UI action 기록
- [ ] 음성 신고 경로 비교 → 검증: `create_report`가 현재 위험 신고 버튼과 같은 disabled 조건, duplicate check, `POST /reports` 흐름을 쓰는지 기록
- [ ] TTS 7문구 HTTP cache 확인 → 검증: 각 문구 2회 `POST /speech/tts`, 두 번째 `X-Voice-Cached: true`, WAV 크기와 시작 지연 기록
- [ ] fallback 회귀 확인 → 검증: server down, timeout, 마이크 권한 거부, `unknown`, confidence `< 0.7`, `speechEnabled=false`, `repeat_last`에서 앱이 멈추지 않음
- [ ] 자연어 목적지 표현 결정안 작성 → 검증: `서울역으로 안내해줘`를 `set_destination`으로 확장할지 기록하고, 미채택 시 `unknown` fallback 유지
- [ ] 실폰 마이크/스피커 smoke 가능 시 수행 → 검증: ADB reverse/LAN/HTTPS, 기기명, Android/Chrome 버전, 마이크 권한, 핵심 명령 4개 이상 UI 변화, 청취 평가 기록
- [ ] 실행 문서 작성 → 검증: `docs/execution/2026-05-21_voice_stt_tts.md`에 HTTP/PWA/실폰/direct-call 근거를 분리해 기록

## 리스크/확인 필요
- loopback HTTP, 브라우저 권한, 실폰 접근이 막히면 direct ASGI, local audio fixture, mock STT 응답은 PARTIAL 근거로만 기록한다.
- LAN HTTP는 마이크/위치/service worker가 secure context 문제로 막힐 수 있다. safe alternative는 desktop localhost 또는 ADB reverse다.
- `서울역으로 안내해줘` 확장은 제품 판단 필요. safe alternative는 규칙 변경 없이 dry-run matrix와 `unknown` fallback 문서화다.
- TTS direct-call cache와 HTTP `X-Voice-Cached` header는 다른 근거다. 섞어 완료 처리하지 않는다.
- Qwen3-TTS 동적 생성은 기존 기록상 2초 이상 걸릴 수 있으므로 위험 경고 primary path로 두지 않는다.
- 원본 음성 샘플, 생성 WAV, STT CSV, `outputs/voice/*`는 로컬 산출물이며 GitHub 업로드 대상이 아니다.
- Cloud STT/TTS, Kakao Map 실제 API, 외부 업로드/배포/secret 작업은 자동 계획에서 직접 실행하지 않는다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 범위가 voice lane 문서/코드 근거 확인과 merge용 lane note 작성으로 좁고, 최종 산출물이 단일 markdown이라 하위 에이전트로 나누지 않았다. 병렬 shell 조회만 사용해 문서와 코드 근거를 확인했다.