# 2026-05-14 Voice Contract Implementation

작업 범위:

- `voice/server.py`
- `voice/intents.py`
- `docs/execution/2026-05-14_voice_contract_implementation.md`

## 구현 내용

- `voice/server.py`에 `CORSMiddleware`를 추가했다.
  - env: `VOICE_CORS_ORIGINS`
  - 기본값: `http://localhost:3000,http://127.0.0.1:3000`
- `/speech/stt` 업로드 검증을 추가했다.
  - env: `VOICE_MAX_UPLOAD_BYTES`
  - 기본값: `10485760` bytes
  - 빈 업로드: HTTP 400, `detail.code=empty_audio`
  - 용량 초과: HTTP 413, `detail.code=audio_too_large`
  - 지원하지 않는 업로드: HTTP 400, `detail.code=unsupported_audio_type`
  - `RuntimeError`: HTTP 503, `detail.code=stt_unavailable`
- `/speech/stt` 오류 응답의 `detail`을 문자열 대신 `{code, message}` 객체로 맞췄다.
- 업로드 파일을 한 번에 메모리에 읽지 않고 1MB 청크로 임시 파일에 저장하도록 바꿨다.
- `voice/intents.py`에 `get_current_location` intent를 추가했다.
  - 예: `지금 어디야`, `현재 위치`, `내 위치`, `위치 알려줘`, `위치를 알려줘`
  - 기존 `set_destination`, `create_report`, `voice_off`, `voice_on`, `repeat_last`, `start_navigation` 회귀 확인을 통과했다.

## 검증

실행:

```bash
.venv-voice/bin/python -m py_compile voice/server.py voice/intents.py
```

결과: 통과.

실행:

```bash
.venv-voice/bin/python - <<'PY'
from voice.intents import classify_intent

cases = [
    ("신고해", "create_report"),
    ("현재 위험 신고해", "create_report"),
    ("음성 꺼", "voice_off"),
    ("음성 켜", "voice_on"),
    ("다시 말해줘", "repeat_last"),
    ("목적지 서울역으로 설정해", "set_destination"),
    ("길 안내 시작해", "start_navigation"),
    ("지금 어디야", "get_current_location"),
    ("현재 위치", "get_current_location"),
    ("내 위치", "get_current_location"),
    ("위치 알려줘", "get_current_location"),
    ("위치를 알려줘", "get_current_location"),
]
for text, expected in cases:
    result = classify_intent(text)
    print(f"{text}\t{result.intent}\t{result.score}\t{result.slots}")
    assert result.intent == expected, (text, result.intent, expected)
PY
```

결과: 통과.

출력 요약:

```text
신고해	create_report	0.9	{}
현재 위험 신고해	create_report	0.9	{}
음성 꺼	voice_off	0.93	{}
음성 켜	voice_on	0.93	{}
다시 말해줘	repeat_last	0.9	{}
목적지 서울역으로 설정해	set_destination	0.92	{'destination': '서울역'}
길 안내 시작해	start_navigation	0.9	{}
지금 어디야	get_current_location	0.88	{}
현재 위치	get_current_location	0.88	{}
내 위치	get_current_location	0.88	{}
위치 알려줘	get_current_location	0.88	{}
위치를 알려줘	get_current_location	0.88	{}
```

추가 경량 확인:

```bash
VOICE_CORS_ORIGINS=http://example.test .venv-voice/bin/python - <<'PY'
import os
from fastapi.testclient import TestClient
from voice.server import app

client = TestClient(app)

preflight = client.options(
    "/speech/stt",
    headers={"Origin": "http://example.test", "Access-Control-Request-Method": "POST"},
)
print("cors", preflight.status_code, preflight.headers.get("access-control-allow-origin"))

empty = client.post("/speech/stt", files={"audio": ("empty.wav", b"", "audio/wav")})
print("empty", empty.status_code, empty.json())

unsupported = client.post("/speech/stt", files={"audio": ("note.txt", b"hello", "text/plain")})
print("unsupported", unsupported.status_code, unsupported.json())

os.environ["VOICE_MAX_UPLOAD_BYTES"] = "3"
too_large = client.post("/speech/stt", files={"audio": ("clip.wav", b"1234", "audio/wav")})
print("too_large", too_large.status_code, too_large.json())

assert preflight.status_code == 200
assert preflight.headers.get("access-control-allow-origin") == "http://example.test"
assert empty.status_code == 400 and empty.json()["detail"]["code"] == "empty_audio"
assert unsupported.status_code == 400 and unsupported.json()["detail"]["code"] == "unsupported_audio_type"
assert too_large.status_code == 413 and too_large.json()["detail"]["code"] == "audio_too_large"
PY
```

결과: 통과.

출력 요약:

```text
cors 200 http://example.test
empty 400 {'detail': {'code': 'empty_audio', 'message': 'Uploaded audio file is empty.'}}
unsupported 400 {'detail': {'code': 'unsupported_audio_type', 'message': 'Unsupported audio upload. Use wav, mp3, m4a, flac, ogg, webm, mp4, or aac.'}}
too_large 413 {'detail': {'code': 'audio_too_large', 'message': 'Uploaded audio exceeds the 3 byte limit.'}}
```

RuntimeError 계약 확인:

```bash
.venv-voice/bin/python - <<'PY'
from fastapi.testclient import TestClient
import voice.server as server

class BrokenSTT:
    def transcribe_file(self, _path):
        raise RuntimeError("model unavailable")

server.get_stt_engine = lambda: BrokenSTT()
client = TestClient(server.app)
response = client.post("/speech/stt", files={"audio": ("clip.wav", b"data", "audio/wav")})
print(response.status_code, response.json())
assert response.status_code == 503
assert response.json()["detail"]["code"] == "stt_unavailable"
PY
```

결과: 통과.

출력 요약:

```text
503 {'detail': {'code': 'stt_unavailable', 'message': 'model unavailable'}}
```

하지 않은 것:

- `scripts/test_stt.py` 전체 재실행은 대형 STT 모델 로드/추론을 피하기 위해 하지 않았다.
