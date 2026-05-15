# 2026-05-15 Voice STT/TTS Validation Lane

## Result

PASS for local voice API contract smoke on `127.0.0.1:9001`.

Notes:

- Existing worktree change before validation: `M apps/web/next-env.d.ts`.
- Used `.venv-voice` (`Python 3.14.4`).
- Started a temporary local uvicorn server and stopped it after validation.
- Did not modify voice/app code.
- Did not run browser/phone microphone E2E.
- Did not call `/speech/tts`; existing TTS cache directory had no WAV files, so calling TTS could trigger model load/download.

## Commands and Results

```bash
git status --short --untracked-files=all
```

Result:

```text
 M apps/web/next-env.d.ts
```

```bash
if [ -d .venv-voice ]; then echo '.venv-voice exists'; if [ -x .venv-voice/bin/python ]; then .venv-voice/bin/python --version; fi; else echo '.venv-voice missing'; fi
pwd
ls -la scripts/check_voice_contract.py voice/server.py 2>/dev/null || true
```

Result:

```text
.venv-voice exists
Python 3.14.4
/home/ddobagi/Code/hanium-dreamup
-rw-rw-r-- 1 ddobagi ddobagi 8473 May 14 01:13 scripts/check_voice_contract.py
-rw-rw-r-- 1 ddobagi ddobagi 8069 May 14 00:57 voice/server.py
```

```bash
.venv-voice/bin/python -m uvicorn voice.server:app --host 127.0.0.1 --port 9001
```

Result: server started successfully on `http://127.0.0.1:9001` with process id `1348894`; stopped with `Ctrl+C` after checks.

```bash
.venv-voice/bin/python scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 5
```

Result:

```text
Voice API contract check: http://127.0.0.1:9001
[PASS] /health status=ok server=voice stt_model=medium
[PASS] /speech/intent transcript='신고해' intent=create_report
[PASS] /speech/intent transcript='음성 켜' intent=voice_on
[PASS] /speech/intent transcript='음성 꺼' intent=voice_off
[PASS] /speech/intent transcript='다시 말해줘' intent=repeat_last
[PASS] /speech/intent transcript='목적지 서울역으로 설정해' intent=set_destination
[PASS] /speech/intent transcript='길 안내 시작해' intent=start_navigation
[PASS] /speech/intent transcript='지금 어디야' intent=get_current_location
[PASS] /speech/stt empty upload returns detail.code=empty_audio
[PASS] /speech/stt unsupported upload returns detail.code=unsupported_audio_type
Voice API contract smoke check passed.
```

```bash
curl -sS -i http://127.0.0.1:9001/health
```

Result:

```text
HTTP/1.1 200 OK
{"status":"ok","server":"voice","cuda_available":true,"gpu":"NVIDIA GeForce RTX 5070 Ti","stt_model":"medium","tts_model":"Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice","tts_mode":"custom","tts_cache_dir":"outputs/voice/cache"}
```

```bash
curl -sS -i -X POST http://127.0.0.1:9001/speech/intent \
  -H 'Content-Type: application/json' \
  --data '{"transcript":"목적지 서울역으로 설정해"}'
```

Result:

```text
HTTP/1.1 200 OK
{"transcript":"목적지 서울역으로 설정해","normalized":"목적지 서울역으로 설정해","intent":"set_destination","score":0.92,"slots":{"destination":"서울역"}}
```

Additional exploratory intent check:

```bash
curl -sS -i -X POST http://127.0.0.1:9001/speech/intent \
  -H 'Content-Type: application/json' \
  --data '{"transcript":"서울역으로 안내해줘"}'
```

Result:

```text
HTTP/1.1 200 OK
{"transcript":"서울역으로 안내해줘","normalized":"서울역으로 안내해줘","intent":"unknown","score":0.2,"slots":{}}
```

```bash
if [ -d outputs/voice/cache ]; then
  echo 'outputs/voice/cache exists'
  find outputs/voice/cache -maxdepth 1 -type f -name '*.wav' -printf '%p\t%s bytes\t%TY-%Tm-%Td %TH:%TM:%TS\n' | sort
  count=$(find outputs/voice/cache -maxdepth 1 -type f -name '*.wav' | wc -l)
  echo "wav_count=$count"
else
  echo 'outputs/voice/cache missing'
fi
```

Result:

```text
outputs/voice/cache exists
wav_count=0
```

```bash
if command -v ss >/dev/null 2>&1; then ss -ltnp '( sport = :9001 )' || true; else lsof -nP -iTCP:9001 -sTCP:LISTEN || true; fi
```

Result after stopping the temporary server:

```text
State Recv-Q Send-Q Local Address:Port Peer Address:PortProcess
```

## Blockers / Not Executed

- `/speech/tts` not executed because no cached WAV existed under `outputs/voice/cache`; avoiding possible Qwen TTS model load/download.
- Real STT transcription with microphone/audio was not executed; contract smoke only tested validation errors for `/speech/stt`.
- Browser/phone microphone E2E was not executed and should not be considered passed.

## Follow-up Candidates

- Decide whether natural phrasing like `서울역으로 안내해줘` should map to `set_destination`; it currently returns `unknown`.
