# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS lane note (2026-05-22)

## 최근 진행 근거
- 2026-05-21 `daylog/2026-05-21.md`: 5/21 실행 문서가 없고, PWA/Backend/Voice/Integration 신규 완료 근거도 없다고 기록됨. 5/21 계획 항목은 실행 근거가 아니라 다음 실행 체크리스트로 취급해야 함.
- 2026-05-21 `plans/daily/2026-05-21.md`: voice lane 후보는 hands-free 신고, 실제 HTTP contract, CORS, TTS 7문구 HTTP cache/fallback, 자연어 목적지 표현 검토.
- 2026-05-20 `daylog/2026-05-20.md`: 5/20은 모델/Data/MLOps 중심. Voice HTTP/mic/TTS 청취의 새 완료 근거 없음.
- 2026-05-19 `docs/execution/2026-05-19_voice_stt_tts.md`: `py_compile` PASS, direct handler/ASGI 기준 health/intent/CORS/TTS cache 계약 PASS. 실제 loopback HTTP는 sandbox socket 제한으로 BLOCKED, 브라우저/실폰 mic E2E와 청취 평가는 PENDING.
- 2026-05-18 `docs/voice_stt_tts_status.md`, `docs/current_status.md`: 실제 사람 음성 8개 intent 성공, 평균 `1.807 sec`, p95 `2.048 sec`. 단 local sample/direct-call은 브라우저/실폰 E2E가 아님.
- 2026-05-15 `docs/execution/2026-05-15_runtime_followup_after_reset.md`: 단일 문구 `안전하게 이동하세요.`는 실제 HTTP `/speech/tts` 2회 요청 cache hit 확인. 7개 위험/운영 문구 전체, fallback, 휴대폰 스피커 청취는 별도 미완료.
- `product/backlog.md`: P0-005는 브라우저/실폰 마이크 STT E2E, P1-002는 TTS HTTP cache/fallback/청취 평가. 목적지/경로 안내는 P2 후순위.
- `product/decisions.md`: STT/TTS는 local/free 우선, Cloud STT/TTS·Kakao Map 실제 API·외부 배포/secret은 C 작업.
- `plans/features`, `plans/verify/ready`, `plans/verify/reports`: 파일 없음. `PM` 디렉터리도 없어 feature/verify/product-audit/automation-metrics 산출물은 반영할 근거 없음.
- 기존 `plans/daily/2026-05-22.md`: 없음.

## 내일 목표 후보
- 1순위: hands-free 신고 evidence trace를 닫는다. 마이크 `신고해` → `create_report` → 기존 `handleReport()`와 같은 disabled 조건, duplicate check, `POST /reports` 경로인지 확인한다.
- 2순위: 실제 HTTP voice contract와 PWA CORS를 direct-call 근거와 분리해 확인한다.
- 3순위: TTS 7문구 HTTP cache/fallback 계약을 확인한다. 위험 경고 primary path는 여전히 Web Speech API/진동 fallback으로 둔다.
- 4순위: 데스크톱 또는 Android mic smoke가 가능하면 핵심 명령 4~5개를 transcript/intent/confidence/UI action 기준으로 기록한다.
- 5순위: `서울역으로 안내해줘` 자연어 확장 여부를 결정안으로 정리한다. 채택해도 route/Kakao API 없이 destination state 저장까지만 한다.

## 상세 체크리스트 초안
- [ ] 시작 gate 확인 → 검증: `git status`, `.venv-voice`, `NEXT_PUBLIC_VOICE_API_BASE`, `VOICE_CORS_ORIGINS`, voice `9001`, web `3000`, backend `8000`, Android 접속 방식 기록
- [ ] voice 서버 실제 HTTP 기동 → 검증: `GET /health`에서 `status=ok`, `server=voice`, STT/TTS model/env 값 기록
- [ ] HTTP contract smoke 실행 → 검증: `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5` PASS 또는 socket/loopback 차단 사유 기록
- [ ] PWA CORS 확인 → 검증: 브라우저 Network 또는 OPTIONS 요청에서 `/speech/stt` CORS 오류 없음 기록
- [ ] hands-free 신고 경로 확인 → 검증: `신고해` STT 결과가 `create_report`이고, PWA가 버튼 신고와 같은 `handleReport()` 흐름을 쓰는지 로그/화면 상태로 확인
- [ ] 신고 runtime이 막히면 safe alternative 수행 → 검증: STT 결과와 PWA intent handler까지는 PASS/PARTIAL로, duplicate check/`POST /reports`는 backend/PostGIS 미가동 사유로 BLOCKED 기록
- [ ] 데스크톱 mic 핵심 명령 E2E → 검증: `신고해`, `음성 켜`, `음성 꺼`, `다시 말해줘`, `지금 어디야`의 transcript/intent/confidence/UI action 기록
- [ ] TTS 7문구 HTTP cache 확인 → 검증: 각 문구 2회 `POST /speech/tts`, 두 번째 `X-Voice-Cached: true`, WAV 크기, 응답 시간 기록
- [ ] TTS fallback 회귀 확인 → 검증: server down, timeout, `speechEnabled=false`, `repeat_last`, 브라우저 TTS fallback, 진동 fallback에서 앱이 멈추지 않음
- [ ] 자연어 목적지 표현 결정안 작성 → 검증: `서울역으로 안내해줘`를 `set_destination`으로 확장할지 matrix 작성. route/Kakao API 호출 없음
- [ ] Android 가능 시 실폰 mic/스피커 smoke → 검증: 기기명, Android/Chrome, ADB reverse/LAN/HTTPS, 마이크 권한, 핵심 명령 4개 이상 UI 변화, 스피커 청취 평가 기록
- [ ] 실행 문서 작성 → 검증: `docs/execution/2026-05-22_voice_stt_tts.md`에 HTTP/PWA/실폰/direct-call/local sample 근거를 분리

## 리스크/확인 필요
- loopback HTTP, 브라우저 권한, 실폰 접근이 막히면 direct ASGI/TestClient, local audio fixture, mock STT 응답은 PARTIAL 근거로만 기록한다.
- Android LAN HTTP는 secure context 문제로 mic/location/service worker가 막힐 수 있다. safe alternative는 desktop localhost 또는 ADB reverse다.
- TTS cache 파일은 일부 존재하지만, 7문구 전체 HTTP header/fallback/청취 완료 근거와는 다르다.
- Qwen3-TTS 동적 생성은 2초 이상 걸릴 수 있어 위험 경고 primary path로 두지 않는다.
- `서울역으로 안내해줘` 확장은 제품 판단 필요. safe alternative는 규칙 변경 없이 `unknown` fallback을 유지하고 dry-run matrix만 남기는 것이다.
- 원본 음성 샘플, 생성 WAV, STT CSV, `outputs/voice/*`, Hugging Face cache는 local-only 산출물이다.
- Cloud STT/TTS, Kakao Map 실제 API, 외부 업로드/배포/secret 작업은 자동 실행 계획에 넣지 않는다.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 범위가 voice lane 문서·코드 근거 확인과 단일 lane note 작성으로 좁아 하위 에이전트로 나누지 않았다. 병렬 shell 조회만 사용해 문서, 코드, scheduler 산출물 유무를 확인했다.