# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS weekly lane note (2026-W22)

## 이번 주 목표 후보

- 전제: `plans/weekly/2026-W22.md`는 현재 checkout에서 확인되지 않았다. 직전 `plans/weekly/2026-W21.md`, `plans/daily/2026-05-25.md`, 최근 daylog, voice 문서, `/home/ddobagi/PM` 산출물을 근거로 잡는다.
- 1순위: 5/25 PM feature slice `VOICE-INTENT-SCHEMA-001` 검증/통합 gate. `/speech/intents` schema endpoint는 PM feature worktree에 있으나 verify가 quota 오류로 `blocked`, 현재 checkout에는 미통합 상태로 보인다.
- 2순위: TTS phrase catalog + cache-status dry-run. Qwen 모델 로드/다운로드 없이 위험 안내, 음성 신고 결과, 위치 확인, 목적지 저장, 길안내 준비 문구와 cache 상태를 고정한다.
- 3순위: PWA 음성 녹음 fallback 세분화. 권한 거부, timeout, 서버 미연결, 빈 녹음, unsupported audio, low confidence, unknown을 다른 메시지/진동으로 구분한다.
- 4순위: hands-free v2 신고 trace. `신고해` intent가 `/reports/v2 trigger=voice`, `auto_reported=false`, 자동 신고 cooldown 우회 정책으로 이어지는지 fixture 또는 HTTP 가능한 범위에서 확인한다.
- 5순위: 음성 길안내 thin slice. `set_destination`/`start_navigation`을 configured destination 또는 mock route 기준으로 검증하고, 자연어 목적지는 우선 slot 저장까지만 다룬다.
- 6순위: voice evidence/telemetry 정리. raw audio 없이 transcript, normalized, intent, confidence, failure reason 수준의 local-only 근거 형식을 정한다.

## 날짜별/단계별 체크리스트

- 2026-05-25 월: 기준선 확정  
  검증: current checkout이 `behind 10`, dirty 상태임을 기록하고, PM feature commit `82ec532`와 현재 checkout의 voice schema 차이를 분리한다.

- 2026-05-26 화: intent schema gate  
  검증: `/speech/intents` endpoint 통합 여부 확인, `voice/intents.py`, `voice/server.py`, `scripts/check_voice_contract.py` py_compile, direct schema/classifier smoke.

- 2026-05-27 수: TTS catalog/cache dry-run  
  검증: catalog 문구 목록과 cache key/status가 모델 로드 없이 반환됨. 기존 `/speech/tts` 생성 동작은 바꾸지 않는다.

- 2026-05-28 목: PWA recording fallback + hands-free 신고 fixture  
  검증: mock `VoiceSttResponse(intent=create_report)`가 PWA action을 호출하고 v2 신고 정책상 `trigger=voice`, `auto_reported=false`로 이어지는지 확인한다.

- 2026-05-29 금: HTTP/CORS/TTS runtime gate  
  검증: voice server `9001` 가능 시 `/health`, `/speech/intents`, `/speech/intent`, `/speech/stt` error contract, OPTIONS/CORS, `/speech/tts` 2회 cache header를 확인한다. 막히면 direct/ASGI와 HTTP BLOCKED를 분리한다.

- 2026-05-30 토: mic/device E2E 가능 범위  
  검증: desktop 또는 Android mic에서 `신고해`, `음성 켜/꺼`, `다시 말해줘`, `지금 어디야`, `길 안내 시작해`의 transcript/intent/confidence/UI action 기록. 장비 없으면 fixture evidence만 PARTIAL.

- 2026-05-31 일: evidence 정리와 다음 주 이관  
  검증: HTTP/direct/browser/phone/TTS/cache/navigation 결과를 PASS/PARTIAL/BLOCKED로 분리하고, product 문서의 legacy voice 표현 보정 후보만 정리한다.

## 검증 계획

- Static: `py_compile`로 `voice/*`, `scripts/check_voice_contract.py` 확인.
- Unit/direct: `tests/test_voice_intents.py` 또는 direct classifier matrix로 intent drift 확인.
- HTTP contract: `.venv-voice` server 기동 후 `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5`.
- PWA static: `cd apps/web && npm run lint && npm run typecheck`.
- TTS: cache-status dry-run 우선, 가능 시 7문구 `/speech/tts` 2회 요청과 `X-Voice-Cached` 확인.
- Device E2E: Android/desktop mic, speaker, TalkBack은 실제 장비가 있을 때만 PASS로 기록한다.

## 리스크/확인 필요

- 현재 checkout은 원격보다 10커밋 뒤처지고 dirty/untracked 파일이 많다. PM feature worktree의 voice schema slice를 완료로 가정하지 않는다.
- 5/25 verify scheduler는 sandbox mount quota 오류로 blocked, `push_ready: False`다.
- 브라우저/실폰 mic, Android ADB, TalkBack, 휴대폰 스피커 청취는 환경 의존이다. 대안은 direct handler, ASGI/TestClient, fixture trace다.
- Qwen TTS 신규 생성, 대형 모델 다운로드, Cloud STT/TTS, 유료 API, secret 사용은 C 작업이다.
- 목적지 이름→좌표 변환은 아직 미구현이다. 이번 주 자연어 목적지는 configured destination/mock route까지만 다룬다.
- 원본 음성, 생성 WAV, STT/TTS CSV/log, 모델 cache는 local-only로 유지한다.

## 병렬 에이전트 활용 메모

- 이번 lane note 작성에는 별도 하위 에이전트를 쓰지 않았고, 문서/코드/PM 산출물만 병렬 shell 조회로 확인했다.
- 주간 실행에서는 `voice contract/TTS`, `PWA recording/report fixture`, `device E2E`, `evidence/docs`를 서로 다른 범위로 나누면 충돌 없이 병렬화 가능하다.