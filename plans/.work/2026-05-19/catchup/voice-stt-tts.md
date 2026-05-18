# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS catch-up lane note (2026-05-19)

## 확인한 근거

- `plans/daily/2026-05-18.md`: Voice STT/TTS 항목은 HTTP health/contract, CORS, 데스크톱/실폰 마이크 E2E, TTS HTTP cache, fallback, 목적지 자연어 판단, 실행 문서 작성으로 계획됨.
- `plans/.work/2026-05-18/execute-r1/voice-stt-tts.md`: 로컬 STT/TTS 재검증은 수행했지만 HTTP/CORS/TTS HTTP cache는 sandbox loopback 제한으로 PASS 처리하지 않음.
- `plans/.work/2026-05-18/execute-r2/voice-stt-tts.md`: r2에서는 voice runtime 검증을 새로 실행하지 않음.
- `daylog/2026-05-18.md`: Voice 로컬 STT dry-run 8개, 실제 음성 8/8, TTS 7문구 direct-call cache 확인. HTTP `/health`, contract, CORS preflight, `/speech/tts` HTTP cache hit은 미완료.
- `docs/voice_stt_tts_status.md`: HTTP cache header, PWA 브라우저/실폰 마이크 E2E, CORS, fallback, 휴대폰 스피커 청취 평가는 완료 근거 없음.
- `docs/execution/2026-05-18_integration_field_report.md`: STT/TTS direct-call 근거와 HTTP/PWA/실폰 검증 근거를 분리해야 한다고 기록.
- `plans/daily/2026-05-19.md`: 5/19 Voice catch-up 항목으로 HTTP contract, CORS, TTS HTTP cache, 마이크 E2E, fallback, 실행 문서 작성이 재계획됨.
- `daylog/2026-05-19.md`, `docs/execution/2026-05-19_*`, `docs/execution/2026-05-18_voice_stt_tts.md`는 현재 확인되지 않음.

## 완료로 판단한 항목

- STT intent dry-run 8개 success.
- 실제 사람 음성 샘플 8개 known-intent success.
- `voice/*`, `scripts/test_stt.py`, `scripts/test_tts.py`, `scripts/check_voice_contract.py` py_compile 통과.
- TTS 7문구 direct-call 기준 cache file 존재 및 cached `true` 확인.
- PWA voice 연동 정적 확인: `create_report`는 `handleReport()` 경로를 호출하고, `repeat_last`/위험 안내는 `speechEnabled` 조건을 거침.
- 목적지/경로/지도 API 연동은 2026-05-18 product 결정상 MVP 제외, P2 후순위로 정리됨.

## 미완료 작업 후보

- [ ] voice server HTTP health/contract 재확인 → 이유/근거: `/health`, `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001`는 loopback 제한으로 PASS 근거 없음.
- [ ] PWA voice base/CORS 확인 → 이유/근거: 브라우저 Network `/speech/stt` 요청과 CORS 오류 없음 기록이 없음.
- [ ] 데스크톱 마이크 E2E → 이유/근거: 실제 브라우저 마이크 발화의 transcript/intent/confidence/UI action 기록 없음.
- [ ] 실폰 마이크/스피커 smoke → 이유/근거: Android 기기, 접속 방식, 마이크 권한, 핵심 명령 UI 변화, TTS 청취 기록 없음.
- [ ] 음성 신고와 버튼 신고 비교 → 이유/근거: 정적 경로 확인만 있고 duplicate check, `POST /reports`, 저장 흐름 runtime 근거 없음.
- [ ] TTS 7문구 HTTP cache hit 확인 → 이유/근거: direct-call cache만 확인됐고 HTTP `X-Voice-Cached: true` header 근거 없음.
- [ ] fallback 회귀 확인 → 이유/근거: voice server down, STT timeout, 마이크 권한 거부, `speechEnabled=false`, `repeat_last`, `unknown` runtime 검증 없음.
- [ ] Voice 실행 문서 작성 → 이유/근거: `docs/execution/2026-05-18_voice_stt_tts.md`가 없음.

## 오늘 catch-up 후보 스케줄

- [ ] voice env와 서버 기동 확인 → 검증: `NEXT_PUBLIC_VOICE_API_BASE`, `VOICE_CORS_ORIGINS`, voice `9001`, `GET /health` 기록.
- [ ] voice contract smoke 재실행 → 검증: `python scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5` PASS.
- [ ] CORS preflight/POST 확인 → 검증: PWA origin에서 `/speech/stt` OPTIONS/POST가 CORS 오류 없이 통과하는지 기록.
- [ ] TTS 7문구 HTTP cache 확인 → 검증: 각 문구 2회 `POST /speech/tts`, 두 번째 `X-Voice-Cached: true`, WAV 크기/cache 파일 기록.
- [ ] 데스크톱 마이크 E2E → 검증: 핵심 명령 6개 이상 transcript/intent/confidence/UI action 기록.
- [ ] 실폰 마이크/스피커 smoke → 검증: Android 접속 방식, 마이크 권한, 핵심 명령 4개 이상, TTS 지연/명료도 기록.
- [ ] 음성 신고와 버튼 신고 비교 → 검증: `create_report` intent와 버튼 신고가 같은 조건/duplicate/`POST /reports` 흐름을 타는지 기록.
- [ ] fallback 회귀 확인 → 검증: server down, timeout, 권한 거부, low confidence, `unknown`, `speechEnabled=false`, `repeat_last`에서 앱이 막히지 않음.
- [ ] Voice 실행 문서 작성 → 검증: `docs/execution/2026-05-19_voice_stt_tts.md`에 PASS/FAIL/BLOCKED/PENDING 분리.

## 확인 필요

- 5/19 실행 환경이 loopback TCP, 브라우저, Android/ADB 또는 LAN/HTTPS 접근을 허용하는지 확인 필요.
- Android에서 `127.0.0.1`은 휴대폰 자신이므로 ADB reverse/LAN IP/HTTPS 중 하나를 먼저 고정해야 함.
- TTS direct-call cache와 HTTP `X-Voice-Cached` 근거를 섞지 말아야 함.
- 목적지/경로는 MVP 제외/P2 결정이 있으므로 `서울역으로 안내해줘`는 확장 구현보다 `unknown` fallback 유지/문서 반영 확인이 우선.
- 원본 음성 샘플, STT CSV, 생성 WAV, `outputs/voice/*`는 GitHub 업로드 대상이 아님.

## 병렬 에이전트 활용 메모

- 하위/병렬 에이전트는 사용하지 않음.
- 이번 작업은 읽기 전용 근거 확인과 lane note 출력만 요청된 범위라 daylog를 작성하지 않음.