# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS catch-up execution note (2026-05-18 r1)

## 수행한 작업

- `plans/catchup/2026-05-18.md`, `plans/daily/2026-05-17.md`, `daylog/2026-05-17.md`, README, voice lane catch-up note를 확인했다.
- 저장소 루트 `AGENTS.md` 파일은 없어서 사용자 제공 지침을 적용했다.
- Voice lane 미완료 중 로컬에서 가능한 항목만 재검증했다.
- `voice` 서버를 `uvicorn`으로 기동해 실제 HTTP `/health`와 contract script를 시도했지만, sandbox loopback TCP가 `Operation not permitted`로 차단됐다.
- PWA voice 연동은 정적으로 확인했다.
  - `create_report` intent는 버튼 신고와 같은 `handleReport()` 경로를 호출한다.
  - `repeat_last`와 위험 안내는 `speechEnabled` 조건을 거친다.
  - `NEXT_PUBLIC_VOICE_API_BASE` 기본값과 voice 서버 기본 CORS origin은 코드상 `127.0.0.1:9001`, `localhost/127.0.0.1:3000` 기준이다.
- `서울역으로 안내해줘`는 현재도 `unknown`으로 확인했다. 제품 결정 없이 intent rule을 확장하지 않았다.

## 변경 파일

- 추적 파일 변경 없음.
- 생성/갱신된 Git 추적 제외 로컬 산출물:
  - `outputs/voice/stt_intent_dry_run_2026-05-18.csv`
  - `outputs/voice/stt_myvoice_results_2026-05-18.csv`
- `next build`가 `apps/web/next-env.d.ts`를 부수 변경했지만, 요청 범위 변경이 아니어서 원복했다.
- 기존 작업트리의 다른 수정/미추적 파일은 건드리지 않았다.

## 검증

- 통과: `py_compile` for `voice/tts.py`, `voice/server.py`, `voice/intents.py`, `scripts/test_stt.py`, `scripts/test_tts.py`, `scripts/check_voice_contract.py`
- 통과: `scripts/test_stt.py --dry-run-intents`
  - 8개 intent rule 전부 success
- 통과: 실제 사람 음성 샘플 8개 재평가
  - success `8/8`
  - avg `1.900s`, p95 `2.019s`, max `2.019s`
- 통과: TTS 7문구 direct cache 재확인
  - 7개 모두 cache file 존재
  - `LocalTTSEngine.synthesize(..., use_cache=True)` 기준 cached `true`
- 통과: `apps/web` 정적 검증
  - `npm run lint`
  - `npm run typecheck`
  - `npm run build`
- 제한: 실제 HTTP `/health`, `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001`, CORS preflight, `/speech/tts` HTTP cache hit은 sandbox TCP 생성 제한으로 PASS 처리하지 않았다.

## 미완료/확인 필요

- 브라우저 Network 기준 `/speech/stt` 요청과 CORS 오류 없음 확인 필요.
- 데스크톱/Android 실폰 마이크 E2E는 수동 검증 필요.
- TTS HTTP header `X-Voice-Cached: true`는 실제 HTTP 환경에서 재확인 필요.
- 휴대폰 스피커 청취 평가, voice server down fallback, `speechEnabled=false` 런타임 회귀는 브라우저/실폰 환경에서 확인 필요.
- `서울역으로 안내해줘`를 `set_destination`으로 확장할지는 제품 판단 필요.
- daylog는 지시에 따라 수정하지 않았다.
- git commit/push는 하지 않았다.

## 병렬 에이전트 활용 메모

- 하위/병렬 에이전트는 사용하지 않았다.
- 이번 범위는 voice lane 단독 검증과 HTTP 제한 확인으로 충분했고, 같은 파일 동시 수정 위험이 없도록 파일 변경을 만들지 않았다.