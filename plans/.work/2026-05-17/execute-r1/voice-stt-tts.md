# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS catch-up execution note (2026-05-17 r1)

## 수행한 작업

- `plans/catchup/2026-05-17.md`, `plans/daily/2026-05-16.md`, 최근 daylog, README, voice lane note를 확인했다.
- 저장소 내부 `AGENTS.md`는 없어서 사용자 제공 지침을 적용했다.
- 기본 Qwen3 TTS model id가 네트워크 없는 환경에서 Hugging Face metadata 조회로 실패하던 문제를 수정했다.
  - 로컬 Hugging Face cache snapshot이 있으면 모델 로드 경로만 snapshot으로 대체한다.
  - cache key와 응답의 model id는 기존 기본값 기준을 유지한다.
- PWA 기준 TTS 7문구 cache를 생성/확인했다.
- STT intent dry-run과 실제 사람 음성 샘플 8개를 2026-05-17 산출물로 재실행했다.

## 변경 파일

- `voice/tts.py`
  - 로컬 Hugging Face hub cache snapshot 탐색 로직 추가.
  - `Qwen3TTSModel.from_pretrained()`가 repo id 대신 로컬 snapshot을 우선 로드하도록 변경.
- Git 추적 제외 산출물:
  - `outputs/voice/stt_intent_dry_run_2026-05-17.csv`
  - `outputs/voice/stt_myvoice_results_2026-05-17.csv`
  - `outputs/voice/cache/qwen3_tts_*.wav` 7개 대상 문구 cache

## 검증

- 통과: `PYTHONPATH=. .venv-voice/bin/python -m py_compile voice/tts.py voice/server.py voice/intents.py scripts/test_stt.py scripts/test_tts.py scripts/check_voice_contract.py`
- 통과: `git diff --check -- voice/tts.py`
- 통과: STT dry-run intent 8개 전부 success.
- 통과: 실제 사람 음성 8개 재평가.
  - samples `8`, success `8`
  - avg `1.807s`, max `2.048s`
  - `지금어디야.m4a -> get_current_location`
- 통과: TTS 7문구 cache.
  - 두 번째 요청 기준 전부 cached `true`
  - 직접 `speech_tts(TTSRequest(...))` 호출 기준 `status=200`, `X-Voice-Cached=true`
- 제한: `uvicorn` HTTP `/health`, `scripts/check_voice_contract.py --base-url`, `curl` localhost smoke는 sandbox network namespace에서 `Operation not permitted`/접속 실패로 통과 처리하지 않았다.
- 제한: 브라우저/실폰 마이크 E2E, PWA CORS, 청취 평가는 실행하지 못했다.

## 미완료/확인 필요

- 데스크톱 브라우저와 Android 실폰 마이크 E2E는 여전히 수동 검증 필요.
- `NEXT_PUBLIC_VOICE_API_BASE` 기반 PWA `/speech/stt` CORS 확인은 미실행.
- TTS 청취 평가, 휴대폰 스피커 명료도/시작 지연 평가는 미실행.
- voice server down fallback, `speechEnabled=false`/`repeat_last` 런타임 회귀는 브라우저에서 재확인 필요.
- `서울역으로 안내해줘`는 여전히 `unknown`이다. `set_destination`으로 확장할지는 제품 판단 필요.
- daylog는 지시에 따라 직접 수정하지 않았다.
- git commit/push는 하지 않았다.

## 병렬 에이전트 활용 메모

- 하위/병렬 에이전트는 사용하지 않았다.
- 작업 범위가 `voice/tts.py` 수정과 로컬 STT/TTS 검증으로 충분했다.
- 다른 lane의 기존 작업트리 변경은 건드리지 않았다.