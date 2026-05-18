# Hanium Dreamup / WalkSafe Assist - Voice STT/TTS catch-up execution note (2026-05-19 r1)

## 수행한 작업

- `plans/catchup/2026-05-19.md`, `plans/daily/2026-05-18.md`, 최근 `daylog/2026-05-18.md`, `README.md`, voice lane note를 확인했다.
- 저장소 루트 `AGENTS.md`는 없어서 사용자 제공 지침을 적용했다.
- Voice lane 미완료 중 가능한 범위를 실행했다.
  - 실제 uvicorn voice server 기동은 성공했지만, sandbox에서 loopback client socket 생성이 `Operation not permitted`로 차단됐다.
  - 대신 voice 서버 핸들러와 ASGI CORS 미들웨어를 직접 호출해 계약을 검증했다.
- PWA voice wiring을 확인했다.
  - `create_report` intent는 기존 `handleReport()` 경로를 사용한다.
  - `repeat_last`, 위치 안내, 음성 on/off fallback 흐름은 코드상 연결되어 있다.
- `서울역으로 안내해줘`는 현재 `unknown`으로 유지됨을 확인했고, 제품 결정 없이 intent rule은 확장하지 않았다.
- daylog는 지시에 따라 수정하지 않았고, git commit/push도 하지 않았다.

## 변경 파일

- 내가 수정한 파일 없음.
- 현재 작업트리에는 다른 lane/기존 변경으로 보이는 `apps/web/app/page.tsx`, model/data/docs/product/plans 변경이 남아 있으나 되돌리거나 수정하지 않았다.

## 검증

- PASS: `.venv-voice/bin/python -m py_compile voice/server.py voice/stt.py voice/tts.py voice/intents.py scripts/check_voice_contract.py scripts/test_stt.py scripts/test_tts.py`
- BLOCKED: `scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5`
  - 실패 사유: `<urlopen error [Errno 1] Operation not permitted>`
- PASS: direct handler/ASGI 검증
  - `/health` shape 확인
  - `/speech/intent` 7개 기본 intent 확인
  - `서울역으로 안내해줘 -> unknown` 확인
  - `/speech/stt` empty/unsupported 오류 계약 확인
  - CORS preflight `Origin: http://localhost:3000`에서 `access-control-allow-origin` 확인
  - TTS 7문구 두 번째 호출 기준 `X-Voice-Cached: true`, WAV 존재/크기 확인
- PASS: `cd apps/web && npm run lint`
- PASS: `cd apps/web && npm run typecheck`
- PASS: `git diff --check`

## 미완료/확인 필요

- 실제 loopback HTTP `/health`, contract script, 브라우저 Network CORS 확인은 현재 sandbox TCP 제한으로 완료 처리하지 않았다.
- 데스크톱/Android 실폰 마이크 E2E, 휴대폰 스피커 청취 평가는 장비/브라우저 권한 환경에서 확인 필요.
- voice server down, STT timeout, 마이크 권한 거부, `speechEnabled=false` runtime fallback은 실제 브라우저에서 재확인 필요.
- 음성 `create_report`가 실제 브라우저 녹음 후 duplicate check와 `POST /reports`까지 타는지는 backend/PWA runtime과 함께 확인 필요.
- direct `/health` 검증은 sandbox CUDA hang을 피하려고 CUDA unavailable 상태로 확인했으므로 GPU 상태 근거로 쓰면 안 된다.

## 병렬 에이전트 활용 메모

- 하위/병렬 에이전트는 사용하지 않았다.
- 이번 범위는 voice lane 단독 검증이며 파일 수정이 없어 동시 수정 충돌 위험이 없었다.