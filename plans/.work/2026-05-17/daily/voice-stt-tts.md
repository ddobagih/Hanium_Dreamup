# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS lane note (2026-05-18)

## 최근 진행 근거
- 2026-05-14: [docs/execution/2026-05-14_pwa_stt_implementation.md](/home/ddobagi/Code/hanium-dreamup/docs/execution/2026-05-14_pwa_stt_implementation.md) 기준 PWA `MediaRecorder` 녹음, `NEXT_PUBLIC_VOICE_API_BASE`, `/speech/stt` 업로드 UI는 구현됨. 수동 마이크 E2E는 미완료.
- 2026-05-15: [docs/execution/2026-05-15_voice_validation.md](/home/ddobagi/Code/hanium-dreamup/docs/execution/2026-05-15_voice_validation.md) 기준 voice HTTP contract smoke 통과. `서울역으로 안내해줘`는 `unknown`.
- 2026-05-16: [plans/.work/2026-05-16/execute-r1/voice-stt-tts.md](/home/ddobagi/Code/hanium-dreamup/plans/.work/2026-05-16/execute-r1/voice-stt-tts.md) 기준 `repeat_last` 음성 꺼짐 회귀 수정, 실제 음성 8개 success, `지금어디야.m4a -> get_current_location` 확인.
- 2026-05-17: [plans/.work/2026-05-17/execute-r1/voice-stt-tts.md](/home/ddobagi/Code/hanium-dreamup/plans/.work/2026-05-17/execute-r1/voice-stt-tts.md) 및 [daylog/2026-05-17.md](/home/ddobagi/Code/hanium-dreamup/daylog/2026-05-17.md) 기준 `voice/tts.py`가 로컬 Hugging Face cache snapshot을 우선 사용하도록 수정됨. STT dry-run 8개 success, 실제 음성 8개 success, 평균 `1.807s`, max `2.048s`, TTS 7문구 두 번째 요청 cache hit 확인.
- 2026-05-17: [docs/execution/2026-05-17_integration_field_report.md](/home/ddobagi/Code/hanium-dreamup/docs/execution/2026-05-17_integration_field_report.md) 기준 브라우저/실폰 마이크 E2E, PWA CORS, TTS 청취 평가는 아직 미실행. `adb` 없음, sandbox loopback 제한으로 runtime E2E도 막힘.
- `plans/daily/2026-05-18.md`는 현재 없음. 저장소 내부 `AGENTS.md`도 검색되지 않아 사용자 제공 지침을 기준으로 봄.

## 내일 목표 후보
- 1순위: 데스크톱 브라우저에서 PWA 녹음 → `/speech/stt` → intent handler → UI action 흐름을 실제 마이크로 확인.
- 2순위: Android 실폰 또는 명확한 대체 접속 방식으로 `3000`/`9001` 접근, 마이크 권한, 핵심 음성 명령 E2E 확인.
- 3순위: TTS 7문구 cache를 실제 HTTP 경로로 재확인하고, voice-off/repeat/server-down fallback 및 휴대폰 스피커 청취 평가 수행.
- 4순위: `서울역으로 안내해줘` 같은 자연어 목적지 표현을 `set_destination`으로 확장할지 제품 판단 후 최소 rule/test 반영 후보화.
- 5순위: 최신 voice 결과를 `docs/voice_stt_tts_status.md` 또는 별도 실행 문서에 반영해 stale 문구를 정리.

## 상세 체크리스트 초안
- [ ] voice 서버 health/contract 재확인 → 검증: 일반 개발 세션에서 `/health`, `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001` 결과 기록
- [ ] PWA voice base/CORS 확인 → 검증: `NEXT_PUBLIC_VOICE_API_BASE`, `VOICE_CORS_ORIGINS`, 브라우저 Network의 `/speech/stt` 요청과 CORS 오류 없음 기록
- [ ] 데스크톱 마이크 E2E → 검증: `신고해`, `음성 켜`, `음성 꺼`, `다시 말해줘`, `지금 어디야`, `목적지 서울역으로 설정해`, `길 안내 시작해`의 transcript/intent/confidence/UI action 기록
- [ ] 실폰 마이크 E2E → 검증: 기기명, Android/Chrome 버전, 접속 방식, 마이크 권한, 핵심 명령 4개 이상 UI 변화 기록
- [ ] 음성 신고와 버튼 신고 비교 → 검증: `create_report` intent가 현재 위험 신고 버튼과 같은 조건/실패 메시지/신고 흐름을 타는지 확인
- [ ] TTS 7문구 HTTP cache hit 재확인 → 검증: PWA 위험 문구 4개와 운영 문구 3개를 2회 요청해 두 번째 `X-Voice-Cached: true`, WAV 존재 기록
- [ ] fallback 회귀 확인 → 검증: voice server 미실행, `speechEnabled=false`, `repeat_last` 상황에서 음성/진동/상태 문구가 의도대로 동작하는지 기록
- [ ] 목적지 자연어 확장 여부 결정 → 검증: 확장 시 `서울역으로 안내해줘` 같은 케이스가 `set_destination`으로 분류되고 기존 `start_navigation`과 충돌하지 않는지 dry-run 추가
- [ ] Voice 실행 문서 정리 → 검증: 실행한 항목만 통과로 쓰고, 브라우저/실폰/청취 미실행 항목은 대기 또는 막힘으로 분리

## 리스크/확인 필요
- `docs/current_status.md`와 일부 README 계열 문서에는 TTS 7문구 cache 미완료 문구가 남아 있을 수 있음. 최신 판단은 2026-05-17 r1 voice note와 daylog를 우선하되, 문서 정리가 필요함.
- 2026-05-17 TTS cache 확인은 sandbox 제약 때문에 일부 직접 호출 기준이다. 실제 HTTP/curl 경로는 일반 개발 세션에서 재확인하는 편이 안전함.
- Android에서 `127.0.0.1`은 휴대폰 자신을 가리키므로 LAN IP, ADB reverse, HTTPS 터널 중 하나를 먼저 확정해야 함.
- `create_report` E2E는 PWA/Backend/PostGIS 상태에 의존하므로 voice 단독 검증과 신고 저장 검증을 분리해야 함.
- 원본 음성 샘플, 생성 WAV, STT CSV, `outputs/voice/*`는 GitHub 업로드 대상이 아님.
- 실폰 테스트는 안전한 실내/통제 환경에서만 수행하고 실제 시각장애인 대상 현장 검증으로 표현하지 않아야 함.

## 병렬 에이전트 활용 메모
- 사용하지 않음.
- 이번 작업은 lane note 작성을 위한 읽기 전용 근거 수집이고, 2026-05-17 voice 실행 note와 daylog가 이미 코드/검증/미완료를 분리해 담고 있어 하위 에이전트 없이 통합함.