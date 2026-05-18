# 2026-05-19 Voice STT/TTS catch-up

범위: Voice STT/TTS lane의 2026-05-19 r1 실행 note를 정식 execution 문서로 승격했다. 새 런타임 재시도, intent rule 확장, daylog 작성, Git commit/push는 하지 않았다.

## 기준 문서

- `plans/catchup/2026-05-19.md`
- `plans/daily/2026-05-18.md`
- `daylog/2026-05-18.md`
- `plans/.work/2026-05-19/execute-r1/voice-stt-tts.md`
- `README.md`

저장소 내부 `AGENTS.md`는 없었고 사용자 제공 지침을 적용했다.

## 수행 결과

| 항목 | 상태 | 근거 |
| --- | --- | --- |
| voice 코드 문법 확인 | PASS | `py_compile` 통과 |
| 실제 loopback HTTP contract | BLOCKED | sandbox client socket 제한으로 `Operation not permitted` |
| direct handler/ASGI contract | PASS | `/health`, intent, STT 오류, CORS, TTS cache 계약 직접 호출 확인 |
| PWA voice wiring 정적 확인 | PASS | `create_report`, `repeat_last`, 위치 안내, 음성 on/off fallback 연결 확인 |
| PWA lint/typecheck | PASS | `apps/web` lint/typecheck 통과 |
| desktop/Android mic E2E | PENDING | 브라우저/실폰 권한 환경 필요 |
| TTS 실제 HTTP cache/청취 | PENDING | direct-call cache만 확인, HTTP/실폰 청취는 미확인 |
| fallback runtime | PENDING | voice server down, timeout, 권한 거부, `speechEnabled=false`는 브라우저 런타임에서 재확인 필요 |
| 목적지 자연어 확장 | PENDING | `서울역으로 안내해줘`는 현재 `unknown`; 제품 결정 없이 rule 확장하지 않음 |

## 검증 상세

PASS:

```bash
.venv-voice/bin/python -m py_compile \
  voice/server.py \
  voice/stt.py \
  voice/tts.py \
  voice/intents.py \
  scripts/check_voice_contract.py \
  scripts/test_stt.py \
  scripts/test_tts.py
```

BLOCKED:

```bash
scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5
```

실패 사유:

```text
<urlopen error [Errno 1] Operation not permitted>
```

PASS, direct handler/ASGI:

- `/health` 응답 shape 확인
- `/speech/intent` 7개 기본 intent 확인
- `서울역으로 안내해줘 -> unknown` 확인
- `/speech/stt` empty/unsupported 오류 계약 확인
- CORS preflight `Origin: http://localhost:3000`에서 `access-control-allow-origin` 확인
- TTS 7문구 두 번째 호출 기준 `X-Voice-Cached: true`, WAV 존재/크기 확인

PASS, PWA 정적 검증:

```bash
cd apps/web && npm run lint
cd apps/web && npm run typecheck
```

## 변경 파일

- r1 실행 note 기준 voice lane 코드 변경 없음.
- 이 문서 승격으로 추가한 파일:
  - `docs/execution/2026-05-19_voice_stt_tts.md`

## 미완료 / 제한

- 실제 loopback HTTP `/health`, contract script, 브라우저 Network CORS 확인은 sandbox TCP 제한으로 완료 처리하지 않는다.
- 데스크톱/Android 실폰 마이크 E2E와 휴대폰 스피커 청취 평가는 장비/브라우저 권한 환경에서 확인해야 한다.
- 음성 `create_report`가 실제 브라우저 녹음 후 duplicate check와 `POST /reports`까지 타는지는 backend/PWA runtime과 함께 재확인해야 한다.
- direct `/health` 검증은 sandbox CUDA hang을 피하려고 CUDA unavailable 상태로 확인했으므로 GPU 상태 근거로 쓰지 않는다.
