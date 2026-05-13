# 2026-05-14 Voice PWA Local Smoke

## Scope

실폰 없이 로컬에서 PWA 음성 명령 연결 상태를 확인하는 절차다. 새 `MediaRecorder` STT 업로드 연동 이후 다음 계약만 가볍게 확인한다.

- `GET /health`
- `POST /speech/intent`
- `POST /speech/stt` validation error contract

`scripts/check_voice_contract.py`는 실제 음성을 전사하지 않는다. `/speech/stt`에는 빈 `audio/webm` 업로드와 지원하지 않는 `text/plain` 업로드만 보내므로 기본 실행에서 faster-whisper 모델 추론을 돌리지 않는다.

## Voice Server

처음 실행하는 환경이면 음성 전용 의존성을 준비한다.

```bash
python3 -m venv .venv-voice
source .venv-voice/bin/activate
python -m pip install -r requirements-voice.txt
```

로컬 voice server를 `9001` 포트로 실행한다.

```bash
source .venv-voice/bin/activate
PYTHONPATH=. python -m uvicorn voice.server:app --host 127.0.0.1 --port 9001
```

기본 CORS origin은 `http://localhost:3000,http://127.0.0.1:3000`이다. 필요하면 서버 실행 전에 `VOICE_CORS_ORIGINS`를 지정한다.

## PWA Dev Server

PWA는 브라우저 공개 환경변수로 voice API 주소를 읽는다.

```env
NEXT_PUBLIC_VOICE_API_BASE=http://127.0.0.1:9001
```

`apps/web/.env.example`에는 위 기본값이 들어 있다. 로컬 실행 시에는 `apps/web/.env.local`에 같은 값을 두거나 셸에서 지정한다.

```bash
cd apps/web
NEXT_PUBLIC_VOICE_API_BASE=http://127.0.0.1:9001 npm run dev
```

브라우저에서 PWA dev server를 연 뒤 음성 명령 버튼을 쓰려면 로컬 voice server가 계속 실행 중이어야 한다.

## Contract Smoke Script

기본 주소는 `http://127.0.0.1:9001`이며, 다른 주소는 `VOICE_API_BASE` 또는 `--base-url`로 지정한다.

```bash
python3 scripts/check_voice_contract.py
```

```bash
VOICE_API_BASE=http://127.0.0.1:9001 python3 scripts/check_voice_contract.py
```

```bash
python3 scripts/check_voice_contract.py --base-url http://127.0.0.1:9001
```

확인하는 intent 예시는 다음과 같다.

| transcript | expected intent |
| --- | --- |
| `신고해` | `create_report` |
| `음성 켜` | `voice_on` |
| `음성 꺼` | `voice_off` |
| `다시 말해줘` | `repeat_last` |
| `목적지 서울역으로 설정해` | `set_destination` |
| `길 안내 시작해` | `start_navigation` |
| `지금 어디야` | `get_current_location` |

확인하는 STT 오류 계약:

| request | expected response |
| --- | --- |
| empty `audio/webm` multipart field `audio` | HTTP 400, `detail.code=empty_audio` |
| `text/plain` multipart field `audio` | HTTP 400, `detail.code=unsupported_audio_type` |

서버가 실행 중이 아니면 스크립트는 실패하면서 voice server 실행 명령과 `VOICE_API_BASE` 설정 힌트를 출력한다.

## Manual Browser Mic E2E

이 smoke는 API 계약만 본다. 실제 브라우저 microphone E2E는 여전히 수동 확인이 필요하다.

- 로컬 voice server가 실행 중이어야 한다.
- PWA dev server가 `NEXT_PUBLIC_VOICE_API_BASE`로 같은 voice server를 바라봐야 한다.
- 데스크톱 브라우저에서 마이크 권한을 허용해야 한다.
- 브라우저가 `MediaRecorder`를 지원해야 한다.
- 실제 음성 업로드는 STT 모델을 로드할 수 있으므로 smoke script와 달리 시간이 걸릴 수 있다.
