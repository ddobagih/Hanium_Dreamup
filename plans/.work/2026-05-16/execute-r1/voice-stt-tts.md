# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS catch-up execution note (2026-05-16 r1)

## 수행한 작업

- `plans/catchup/2026-05-16.md`, `plans/daily/2026-05-15.md`, `daylog/2026-05-15.md`, README, voice lane note를 확인했다.
- 저장소 내부 `AGENTS.md`는 없어서 사용자 제공 AGENTS 지침을 기준으로 적용했다.
- 음성 꺼짐 상태에서 `repeat_last`가 최근 상태를 음성으로 재생할 수 있던 조건을 수정했다.
- STT dry-run 기본 명령 목록에 `지금 어디야`를 추가해 현재 intent schema와 맞췄다.
- 실제 사람 음성 샘플 8개를 재평가했고 `지금어디야.m4a`가 `get_current_location`으로 분류됨을 확인했다.
- TTS cache 대상 문구 7개를 확인했지만, 생성 완료는 하지 못했다.

## 변경 파일

- `apps/web/app/page.tsx`
  - `speechEnabled=false`일 때 `repeat_last`가 음성을 재생하지 않도록 조건 수정.
- `scripts/test_stt.py`
  - dry-run intent 테스트에 `지금 어디야` 추가.
- 생성된 로컬 산출물, Git 추적 제외:
  - `outputs/voice/stt_intent_dry_run_2026-05-16.csv`
  - `outputs/voice/stt_myvoice_results_2026-05-16.csv`

## 검증

- 통과: `cd apps/web && npm run lint && npm run typecheck`
- 통과: `PYTHONPATH=. .venv-voice/bin/python -m py_compile ...`
- 통과: `scripts/test_stt.py --dry-run-intents`
  - 8개 intent 전부 성공.
- 통과: 실제 사람 음성 STT 재평가
  - samples `8`, success `8`
  - avg `2.219s`, p95 `2.481s`
  - `지금어디야.m4a` -> `get_current_location`
- 통과: 직접 함수 호출 기준 `/health`, 7개 `/speech/intent`, STT error shape 확인.
- 통과: `git diff --check`
- 실패/제한:
  - `curl` localhost smoke는 sandbox에서 `Operation not permitted`로 실행 불가.
  - `/speech/tts` batch cache 생성은 시도했지만 반환되지 않아 완료 근거에서 제외했다.
  - 현재 확인된 cache WAV는 기존 단일 문구 `안전하게 이동하세요.` 1개뿐이고, PWA 위험/운영 문구 7개 cache는 아직 없음.

## 미완료/확인 필요

- 브라우저/실폰 마이크 E2E는 실행하지 못했다.
- PWA dev server와 `NEXT_PUBLIC_VOICE_API_BASE` CORS 연결은 실제 브라우저에서 확인하지 못했다.
- TTS 대상 7개 문구 cache 생성과 `X-Voice-Cached: true` 확인은 미완료다.
- `서울역으로 안내해줘`는 여전히 `unknown`이다. `set_destination`으로 확장할지 제품 판단이 필요하다.
- daylog는 지시에 따라 직접 수정하지 않았다.

## 병렬 에이전트 활용 메모

- 하위/병렬 에이전트는 사용하지 않았다.
- 작업 범위가 voice lane의 작은 코드 수정과 로컬 검증으로 충분했다.
- 다른 lane 파일 변경이 작업트리에 있었지만 건드리지 않았다.