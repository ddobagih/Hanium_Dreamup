# Local Voice Prototype

로컬 STT, rule-based intent 분류, Qwen3-TTS 생성 API를 제공하는 프로토타입이다. Android 제품 음성 경로와는 별개다.

## 책임 경계

- `server.py`는 음성을 text/intent로 변환하고 TTS WAV를 반환한다. 신고나 길안내 동작을 직접 실행하지 않는다.
- Web/PWA는 `/speech/stt` 결과를 callback으로 실행하지만 안내 음성은 브라우저 `speechSynthesis`를 사용한다.
- Android는 플랫폼 `SpeechRecognizer`와 `TextToSpeech`를 사용하며 신고·목적지 설정/변경·후보 번호 선택·취소·“다음 경로 뭐야?”·길안내 중지를 연결한다. local voice server를 거치지 않는다.
- intent의 `score`는 음향 모델 confidence가 아니라 규칙별 고정 heuristic 점수다.
- raw audio와 transcript를 서버에 영구 저장하는 endpoint는 없다. telemetry 모듈은 비식별 payload schema만 정의한다.

## 파일 역할

| 파일 | 역할 |
|---|---|
| `server.py` | FastAPI endpoint, 입력/업로드 제한, 임시 파일 정리, bounded STT/TTS 실행과 TTS fallback |
| `stt.py` | lazy faster-whisper inference |
| `intents.py` | 한국어 명령 정규화, intent/slot, 실행 안전 정책 |
| `tts.py` | lazy Qwen3-TTS inference와 WAV cache |
| `phrases.py` | 고정 안내 문구 catalog |
| `telemetry.py` | raw text를 제외한 intent telemetry schema |

## 실행 안전 계약

- transcript는 최대 200자, `phrase_id`는 최대 80자, 직접 TTS text는 최대 180자다.
- “서울역 말고 시청”, “서울역이 아니라 시청”, “서울역 또는 시청”처럼 취소·정정·복수 목적지가 섞인 문장은 임의로 하나를 고르지 않고 `safe_noop` 재질문으로 닫는다.
- STT 본문 파싱 전 업로드 동시성·전체 request byte·전역/actor/IP rate gate를 적용한다. 전역 기본값은 60초당 120건이며 `VOICE_STT_GLOBAL_RATE_LIMIT`으로 조정한다. 파일 복사 후에는 `soundfile`/`ffprobe`로 실제 audio stream 재생시간(기본 최대 30초)을 검증한 뒤 추론한다.
- STT/TTS 추론은 모델 load 상태를 명확히 검증하고 GPU 메모리 중복 적재를 막기 위해 프로세스당 각각 동시 실행 1개로 제한한다. queue 대기는 기본 2초로 제한하며 초과하면 `503`과 busy code를 반환한다.
- blocking STT/TTS 추론은 별도 spawn process pool에서 실행한다. hard deadline이나 요청 취소가 발생하면 해당 pool을 종료하고 다음 요청에서 새 worker를 시작하므로 hang이 영구적으로 처리 용량을 점유하지 않는다.
- `/health`는 process liveness만, `/ready`는 STT와 TTS worker가 실제 model load를 완료했는지 확인한다.
- loopback을 포함한 모든 API 호출은 24자 이상의 전용 `VOICE_SERVICE_TOKEN`을 `x-walksafe-voice-service-token`으로 보내야 한다. field/admin token은 Voice 인증에 사용할 수 없다. Web BFF만 이 token과 인증된 actor를 조립하며, `WALKSAFE_GATEWAY_TRUSTED_IP_HEADER`로 명시한 `cf-connecting-ip` 또는 `x-real-ip`만 검증 후 Voice rate key로 전달한다.
- Voice OpenAPI의 모든 operation도 같은 `VoiceServiceToken` header API-key security scheme을 필수로 선언한다.
- rate/upload 상태는 process-local이다. field/staging/production은 `VOICE_SERVICE_WORKERS=1`, `VOICE_SERVICE_REPLICAS=1`을 startup에서 강제하고 `VOICE_SERVICE_PROCESS_LOCK_PATH`의 OS lock으로 같은 host의 중복 process를 거부한다. 다중 host replica 전환에는 Redis/DB 같은 원자 shared limiter가 필요하며 현재 외부 인프라 blocker다.
- STT/TTS는 Hugging Face `main`, tag, 최신 cache snapshot을 사용하지 않는다. `VOICE_*_MODEL_REVISION`의 40자리 commit snapshot을 사전 적재하고, `VOICE_*_MODEL_MANIFEST_PATH`가 열거한 모든 파일 SHA-256과 manifest 자체의 `VOICE_*_MODEL_MANIFEST_SHA256`이 일치해야만 load한다. runtime download는 수행하지 않는다.
- TTS WAV는 같은 filesystem에서 임시 파일을 fsync한 뒤 atomic rename하며, `VOICE_TTS_CACHE_MAX_FILES`와 `VOICE_TTS_CACHE_MAX_BYTES` 범위 안에서 오래된 항목을 정리한다.
- Android는 recognizer의 최상위 가설만 사용하고 confidence가 제공되면 0.55 미만을 실행하지 않는다. 안내 우선순위는 `risk > interaction > navigation`이며 위험 안내 전에 진행 중 STT를 취소한다.

## 실행과 검증

```bash
source .venv-voice/bin/activate
python -m pip install -r voice/requirements.txt
export VOICE_SERVICE_TOKEN='replace-with-a-dedicated-24-character-secret'
PYTHONPATH=. python -m uvicorn voice.server:app --host 127.0.0.1 --port 9001
```

실제 배포 준비 확인은 인증 header를 포함한 `GET /ready`를 사용한다. 최초 요청은 model load 시간만큼 걸릴 수 있으며 `VOICE_READY_TIMEOUT_SECONDS`는 최대 300초다. 추론 deadline은 `VOICE_STT_INFERENCE_TIMEOUT_SECONDS`, `VOICE_TTS_INFERENCE_TIMEOUT_SECONDS`로 설정한다. non-WAV 형식의 재생시간 검증을 위해 운영 image에 `ffprobe`가 필요하며 검증할 수 없으면 `invalid_audio`로 실패한다.

모델 manifest 형식은 아래와 같다. `files`에는 snapshot의 일부가 아니라 모든 논리 파일을 기록해야 하며 STT는 `config.json`, `model.bin`, `tokenizer.json`, `vocabulary.*`, TTS는 `config.json`과 실제 weight 파일을 반드시 포함한다.

```json
{
  "schema_version": "walksafe.voice_model_files.v1",
  "kind": "stt",
  "model_id": "owner/repository",
  "revision": "40자리 소문자 commit SHA",
  "files": {
    "config.json": "64자리 소문자 SHA-256",
    "model.bin": "64자리 소문자 SHA-256",
    "tokenizer.json": "64자리 소문자 SHA-256",
    "vocabulary.json": "64자리 소문자 SHA-256"
  }
}
```

```bash
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. .venv/bin/python -m pytest -p no:cacheprovider \
  tests/test_voice_intents.py tests/test_voice_tts.py -q
```

단위 테스트는 실제 STT/TTS model load와 실기기 microphone/speaker 품질을 검증하지 않는다. 샘플 추론, HTTP cache header, 실제 청취 평가는 별도 evidence로 남긴다.
