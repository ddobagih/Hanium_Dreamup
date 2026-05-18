# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS weekly lane note (2026-W21)

## 이번 주 목표 후보

- 기준 근거: `plans/weekly/2026-W21.md`는 없고, `plans/weekly/`도 비어 있음. 최신 판단은 2026-05-17~2026-05-18 daylog/실행 note와 `docs/voice_stt_tts_status.md` 기준.
- 1순위: 로컬 STT/TTS 검증과 실제 PWA HTTP/브라우저/실폰 검증을 분리해, 아직 미확인인 voice HTTP contract, CORS, 브라우저/실폰 마이크 E2E를 완료한다.
- 2순위: TTS 7문구 direct-call cache 완료 근거를 HTTP `/speech/tts` header와 청취 평가 근거로 보강한다.
- 3순위: `voice_off`, `repeat_last`, server-down, STT timeout, 마이크 권한 거부, `unknown`/low-confidence fallback을 브라우저에서 확인한다.
- 4순위: `서울역으로 안내해줘`를 `set_destination`으로 확장할지 제품 판단을 받아, 채택 시 최소 rule/test/PWA 회귀만 반영한다.
- 5순위: 실행 결과를 `docs/execution/2026-05-*_voice_stt_tts.md`와 daylog 통합 근거로 남기되, 실행하지 않은 항목은 PASS로 쓰지 않는다.

## 날짜별/단계별 체크리스트

- 2026-05-18 월: 현재 상태 고정
  - [ ] `voice/server.py`, `voice/intents.py`, `apps/web/lib/voice-api.ts`, `apps/web/app/page.tsx` 계약 재확인
  - [ ] STT 8개 success, TTS 7문구 direct-call cache, 미완료 HTTP/브라우저/실폰 항목을 분리 기록

- 2026-05-19 화: voice HTTP/CORS gate
  - [ ] 일반 개발 세션에서 voice server `9001` 기동
  - [ ] `/health`, `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001` 실행
  - [ ] `NEXT_PUBLIC_VOICE_API_BASE`, `VOICE_CORS_ORIGINS`, 브라우저 Network `/speech/stt` CORS 오류 여부 확인

- 2026-05-20 수: 데스크톱 브라우저 마이크 E2E
  - [ ] `신고해`, `음성 켜`, `음성 꺼`, `다시 말해줘`, `지금 어디야`, `목적지 서울역으로 설정해`, `길 안내 시작해` 확인
  - [ ] 각 명령의 transcript, intent, confidence, UI action 기록
  - [ ] `create_report`는 버튼 신고와 같은 조건을 타는지만 확인하고, 실제 저장 성공은 backend/integration 근거와 분리

- 2026-05-21 목: Android 실폰 smoke
  - [ ] ADB reverse, LAN IP, HTTPS 중 접속 방식 확정
  - [ ] 기기명, Android/Chrome 버전, 마이크 권한 기록
  - [ ] 핵심 명령 4개 이상과 휴대폰 스피커 TTS 명료도/시작 지연 확인

- 2026-05-22 금: TTS HTTP cache/fallback 회귀
  - [ ] PWA 위험 문구 4개와 운영 문구 3개를 `/speech/tts`로 2회 요청
  - [ ] 두 번째 응답 `X-Voice-Cached: true`, WAV 크기, cache 파일 존재 확인
  - [ ] voice server down, STT timeout, 마이크 권한 거부, `speechEnabled=false`, `repeat_last`, `unknown` fallback 확인

- 2026-05-23 토: 자연어/샘플 보강 판단
  - [ ] `서울역으로 안내해줘` 확장 여부 결정
  - [ ] 채택 시 `set_destination` rule/test만 좁게 추가하고 기존 `start_navigation` 충돌 확인
  - [ ] 보행 잡음/바람/휴대폰 마이크 추가 샘플 수집 계획 정리

- 2026-05-24 일: 주간 정리
  - [ ] 실행 근거, 실패/대기 사유, 다음 주 이월 항목 정리
  - [ ] 원본 음성, STT CSV, 생성 WAV, `outputs/voice/*`는 GitHub 업로드 대상에서 제외 확인

## 검증 계획

- 정적 검증: `py_compile` for `voice/*.py`, `scripts/test_stt.py`, `scripts/test_tts.py`, `scripts/check_voice_contract.py`
- STT 회귀: `scripts/test_stt.py --dry-run-intents`, 실제 사람 음성 8개 재평가
- HTTP 계약: `/health`, `/speech/intent`, `/speech/stt` validation error contract
- PWA E2E: 브라우저 마이크 녹음 → `/speech/stt` → intent handler → UI action
- TTS: `/speech/tts` 7문구 2회 요청, `X-Voice-Cached`, `X-Voice-Generation-Seconds`, WAV 응답 확인
- fallback: server down, timeout, permission denied, low confidence, unknown, voice-off/repeat-last
- 수동 검증: Android 실폰 마이크, 스피커 청취, 주변 소음 조건, 접속 방식 기록

## 리스크/확인 필요

- 현재 STT 8/8과 TTS 7문구 cache는 로컬 프로토타입 근거다. 브라우저/실폰/HTTP header 근거로 확대 해석하면 안 된다.
- sandbox에서는 loopback/브라우저/Android 검증이 막힌 기록이 있으므로 일반 개발 세션 또는 실기기 접근이 필요하다.
- Android에서 `127.0.0.1`은 휴대폰 자신을 가리키므로 접속 방식 확정이 선행되어야 한다.
- Qwen3-TTS 동적 생성은 위험 경고 primary path로 두지 않는다. 캐시 또는 브라우저 `speechSynthesis`/진동 fallback이 기준이다.
- `서울역으로 안내해줘` 확장은 제품 문구 범위 결정 후 진행한다. 현재는 `unknown`으로 남겨야 한다.
- 음성 신고 저장 성공은 backend/PostGIS 상태에 의존하므로 voice lane 단독 PASS로 처리하지 않는다.

## 병렬 에이전트 활용 메모

- 이번 lane note 작성에는 하위/병렬 에이전트를 사용하지 않았다.
- 근거 확인 범위가 문서/코드 조회 중심이고 최종 수정 파일이 없어 단일 에이전트가 통합했다.
- 실제 주간 실행에서는 `voice HTTP/TTS`, `PWA 브라우저 마이크`, `Android 실폰/청취`를 서로 다른 책임 범위로 나누면 병렬화 가능하다.