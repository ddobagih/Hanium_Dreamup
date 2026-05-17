# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS catch-up lane note (2026-05-18)

## 확인한 근거

- `plans/daily/2026-05-17.md`: Voice STT/TTS 계획 항목은 모두 `[ ]` 상태.
- `daylog/2026-05-17.md`: `voice/tts.py` 수정, STT dry-run 8개 success, 실제 음성 8개 success, 평균 `1.807s`, max `2.048s`, TTS 7문구 두 번째 요청 cache hit 기록.
- `plans/.work/2026-05-17/execute-r1/voice-stt-tts.md`: TTS 7문구 cache는 direct-call 기준 확인. HTTP `/health`, contract script, curl smoke는 sandbox network 제한으로 통과 처리하지 않음.
- `docs/execution/2026-05-17_integration_field_report.md`: 브라우저/실폰 마이크 E2E, PWA CORS, TTS 청취 평가는 미실행. `adb` 없음, loopback 제한 기록.
- `docs/execution/2026-05-17_model_data_mlops.md`: Voice 완료 근거 없음.
- `daylog/2026-05-18.md`, `docs/execution/*2026-05-18*`, `docs/execution/2026-05-17_voice_stt_tts.md`: 확인되지 않음.

## 완료로 판단한 항목

- STT intent dry-run 8개 success.
- 실제 사람 음성 샘플 8개 재평가 success. 단, 계획 검증값의 p95는 실행 note에 명시되지 않음.
- Qwen3 TTS 로컬 Hugging Face cache snapshot 우선 로드 수정: `voice/tts.py`, py_compile 및 `git diff --check` 통과.
- PWA 기준 TTS 7문구 cache 생성/확인: direct-call 기준 두 번째 요청 cached `true`, `X-Voice-Cached=true`.

## 미완료 작업 후보

- [ ] voice server HTTP health/contract 재확인 → 이유/근거: 5/17에는 sandbox network 제한으로 `/health`, `scripts/check_voice_contract.py --base-url`을 PASS 처리하지 않음.
- [ ] PWA voice base/CORS 확인 → 이유/근거: 브라우저 Network에서 `/speech/stt` 요청과 CORS 오류 없음 확인 기록 없음.
- [ ] 데스크톱 브라우저 마이크 E2E → 이유/근거: 실제 마이크 발화의 transcript/intent/confidence/UI action 기록 없음.
- [ ] 실폰 voice 접속 및 핵심 명령 확인 → 이유/근거: Android Chrome, web `3000`, voice `9001`, 마이크 권한, UI 변화 기록 없음.
- [ ] TTS 7문구 HTTP cache hit 재확인 → 이유/근거: 5/17 완료 근거는 direct-call 기준이며 실제 HTTP/curl 경로는 미확인.
- [ ] TTS 청취 평가 → 이유/근거: 휴대폰 스피커 기준 명료도, 속도, 시작 지연, 주변 소음 구분 가능 여부 기록 없음.
- [ ] `speechEnabled=false`/`repeat_last` 런타임 회귀 → 이유/근거: 코드 수정 근거는 있으나 브라우저/실폰 동작 검증 없음.
- [ ] voice server down fallback → 이유/근거: 서버 미실행 시 PWA 명령 UI와 브라우저 TTS/진동 유지 확인 없음.
- [ ] 목적지 자연어 표현 결정 → 이유/근거: `서울역으로 안내해줘`는 여전히 `unknown`, 제품 판단 필요.
- [ ] Voice 실행 문서 작성 → 이유/근거: `docs/execution/2026-05-17_voice_stt_tts.md` 없음.

## 오늘 catch-up 후보 스케줄

- [ ] voice server HTTP health/contract 재확인 → 검증: `/health`, `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001` 결과 기록.
- [ ] PWA voice base/CORS 확인 → 검증: `NEXT_PUBLIC_VOICE_API_BASE`, `VOICE_CORS_ORIGINS`, 브라우저 Network `/speech/stt`, CORS 오류 여부 기록.
- [ ] 데스크톱 마이크 E2E → 검증: `신고해`, `음성 켜/꺼`, `다시 말해줘`, `지금 어디야`, `목적지 서울역으로 설정해`, `길 안내 시작해`의 transcript, intent, confidence, UI action 기록.
- [ ] 실폰 마이크 E2E → 검증: 기기명, Android/Chrome 버전, 접속 방식, 마이크 권한, 핵심 명령 4개 이상 UI 변화 기록.
- [ ] 음성 신고와 버튼 신고 비교 → 검증: `create_report` intent가 현재 위험 신고 버튼과 같은 조건, duplicate check, `POST /reports` 흐름을 타는지 확인.
- [ ] TTS 7문구 HTTP cache hit 및 청취 평가 → 검증: 2회 요청 기준 두 번째 `X-Voice-Cached: true`, WAV 존재, 휴대폰 스피커 청취 결과 기록.
- [ ] fallback 회귀 확인 → 검증: voice server down, `speechEnabled=false`, `repeat_last`에서 앱 전체가 막히지 않고 진동/상태 문구가 유지되는지 기록.
- [ ] 목적지 자연어 확장 여부 결정 → 검증: 채택 시 intent rule/test 추가, 미채택 시 unknown fallback 문서화.
- [ ] Voice 실행 문서 작성 → 검증: `docs/execution/2026-05-18_voice_stt_tts.md`에 실행/실패/대기/확인 필요를 분리.

## 확인 필요

- STT 샘플 재평가는 8/8 success지만 p95 지연값이 실행 note에 명시되지 않았다.
- TTS 7문구 cache는 최신 voice r1/daylog 기준 완료이나, 일부 통합 문서에는 단일 cache hit 또는 미완료 표현이 남아 있어 문서 정리가 필요하다.
- Android에서는 `127.0.0.1`이 휴대폰 자신을 가리키므로 ADB reverse, LAN IP, HTTPS 중 실제 접속 방식을 먼저 확정해야 한다.
- `create_report` E2E는 PWA/Backend/PostGIS 상태에 의존하므로 voice 단독 검증과 신고 저장 검증을 분리해야 한다.
- 원본 음성 샘플, STT CSV, 생성 WAV, `outputs/voice/*`는 GitHub 업로드 대상이 아니다.

## 병렬 에이전트 활용 메모

- 하위/병렬 에이전트는 사용하지 않음.
- 읽기 전용 근거 수집만 수행했으므로 daylog는 작성하지 않음.