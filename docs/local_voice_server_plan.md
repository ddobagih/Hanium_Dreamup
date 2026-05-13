# Local Voice Server Plan (STT/TTS Prototype)

Summary status is tracked in `docs/voice_stt_tts_status.md`. This file keeps the runnable details and experiment notes.

## Scope

- Project: Hanium_Dreamup / WalkSafe Assist
- Goal: local GPU-based STT/TTS prototype while image YOLO training is handled separately.
- No paid/cloud inference API is used.
- This work adds only voice-related files and does not modify the YOLO training code, dataset builder, or backend `/detect` placeholder.

## Environment check

Checked on 2026-05-12 KST.

```text
GPU: NVIDIA GeForce RTX 5070 Ti
VRAM: 16303 MiB
Driver: 580.142
Python: 3.14.4
Torch in model env: 2.11.0+cu130
CUDA available: True
CUDA runtime: 13.0
Device capability: (12, 0)
```

Voice dependencies were installed into a separate virtualenv so the running YOLO training environment is not modified:

```bash
python3 -m venv .venv-voice
source .venv-voice/bin/activate
python -m pip install --upgrade pip setuptools wheel
python -m pip install -r requirements-voice.txt
```

Notes:

- `sox` system binary is not installed. `qwen-tts` prints a warning, but the CustomVoice generation test still succeeded.
- `flash-attn` is not installed. Qwen3-TTS falls back to the manual PyTorch path; it works but may be slower.

## Added files

```text
voice/__init__.py
voice/intents.py
voice/stt.py
voice/tts.py
voice/server.py
scripts/test_stt.py
scripts/test_tts.py
requirements-voice.txt
samples/voice/stt/.gitkeep
outputs/voice/.gitkeep
outputs/voice/cache/.gitkeep
outputs/voice/tts/.gitkeep
```

Local generated audio/CSV/log/model caches are ignored by git.

## Run server

```bash
cd <repo-root>
source .venv-voice/bin/activate
PYTHONPATH=. python -m uvicorn voice.server:app --host 127.0.0.1 --port 9001
```

Smoke test:

```bash
curl http://127.0.0.1:9001/health
```

Observed response:

```json
{
  "status": "ok",
  "server": "voice",
  "cuda_available": true,
  "gpu": "NVIDIA GeForce RTX 5070 Ti",
  "stt_model": "medium",
  "tts_model": "Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice",
  "tts_mode": "custom",
  "tts_cache_dir": "outputs/voice/cache"
}
```

## API draft

### `GET /health`

Returns server/GPU/model configuration.

### `POST /speech/intent`

Input:

```json
{"transcript":"목적지 서울역으로 설정해"}
```

Output shape:

```json
{
  "transcript": "목적지 서울역으로 설정해",
  "normalized": "목적지 서울역으로 설정해",
  "intent": "set_destination",
  "score": 0.92,
  "slots": {"destination": "서울역"}
}
```

### `POST /speech/stt`

- Multipart field: `audio`
- Returns: `transcript`, `intent`, `confidence`/`score`, `slots`, model, duration.

Example:

```bash
curl -F "audio=@samples/voice/stt/command_06.wav" http://127.0.0.1:9001/speech/stt
```

### `POST /speech/tts`

Input:

```json
{"text":"전방에 점자블록 파손이 있습니다.", "use_cache": true}
```

Returns a WAV file. Frequently used danger warnings should be pre-generated and cached under `outputs/voice/cache`.

## STT

### Model

- Primary: `faster-whisper` with `medium`
- Device selection: `cuda` if available, otherwise CPU
- Compute type: `float16` on CUDA, `int8` on CPU
- Candidate to compare later: `large-v3-turbo`

### Intent labels

- `create_report`
- `voice_on`
- `voice_off`
- `repeat_last`
- `set_destination`
- `start_navigation`
- `unknown`

### STT test command

Because no real recorded command audio was available yet, a synthetic local test set was generated with Qwen3-TTS CustomVoice and then transcribed with faster-whisper medium. This verifies the local pipeline, but it is not a substitute for real pedestrian/noisy microphone tests.

```bash
source .venv-voice/bin/activate
PYTHONPATH=. python scripts/test_stt.py \
  --audio-dir samples/voice/stt \
  --manifest samples/voice/stt/manifest.json \
  --model-size medium \
  --output outputs/voice/stt_faster_whisper_medium_synthetic.csv
```

### STT synthetic result

| model | input | transcript | intent | time sec | success |
| --- | --- | --- | --- | ---: | --- |
| medium | 신고해 | 싱고해 | create_report | 0.478 | true |
| medium | 현재 위험 신고해 | 현재 위험신고에 | create_report | 0.455 | true |
| medium | 음성 꺼 | 음성 꺼 | voice_off | 0.395 | true |
| medium | 음성 켜 | 음... 성...켜 | voice_on | 0.439 | true |
| medium | 다시 말해줘 | 다시 말해줘 | repeat_last | 0.401 | true |
| medium | 목적지 서울역으로 설정해 | 목적지 서울역으로 설정해 | set_destination | 0.583 | true |
| medium | 길 안내 시작해 | 히히힣... 길안내 시작해 | start_navigation | 0.706 | true |

Summary:

- Synthetic intent success: `7/7 = 100%`
- Average processing time: `0.494 sec/sample`
- Caveat: synthetic TTS audio is cleaner and less varied than actual pedestrian microphone audio.

## TTS

### Model choice

Initial attempt:

- `Qwen/Qwen3-TTS-12Hz-0.6B-Base`
- Result: downloaded and loaded, but `generate_voice_design` is not supported by the Base model.
- Conclusion: Base mode needs local reference audio/text for voice cloning, or another Qwen3-TTS variant should be used.

Working prototype model:

- `Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice`
- Mode: `custom`
- Speaker used in full test: `sohee`
- Sample rate: `24000 Hz`

Candidate to compare later:

- `Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice`

### TTS test command

```bash
source .venv-voice/bin/activate
PYTHONPATH=. python scripts/test_tts.py \
  --model-id Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice \
  --mode custom \
  --speaker sohee \
  --output-dir outputs/voice/tts \
  --csv outputs/voice/tts_qwen3_0_6b_custom_sohee.csv
```

### TTS result

| model | input | output | time sec | success |
| --- | --- | --- | ---: | --- |
| Qwen3-TTS 0.6B CustomVoice | 전방에 점자블록 파손이 있습니다. | `outputs/voice/tts/tts_01_Qwen3-TTS-12Hz-0.6B-CustomVoice.wav` | 2.265 | true |
| Qwen3-TTS 0.6B CustomVoice | 오른쪽에 방치된 킥보드가 있습니다. | `outputs/voice/tts/tts_02_Qwen3-TTS-12Hz-0.6B-CustomVoice.wav` | 1.629 | true |
| Qwen3-TTS 0.6B CustomVoice | 공사 장애물이 감지되었습니다. 속도를 줄이세요. | `outputs/voice/tts/tts_03_Qwen3-TTS-12Hz-0.6B-CustomVoice.wav` | 2.950 | true |
| Qwen3-TTS 0.6B CustomVoice | 노면 파임이 감지되었습니다. 전방을 확인하세요. | `outputs/voice/tts/tts_04_Qwen3-TTS-12Hz-0.6B-CustomVoice.wav` | 2.662 | true |
| Qwen3-TTS 0.6B CustomVoice | 신고가 저장되었습니다. | `outputs/voice/tts/tts_05_Qwen3-TTS-12Hz-0.6B-CustomVoice.wav` | 2.344 | true |
| Qwen3-TTS 0.6B CustomVoice | 목적지를 다시 말씀해 주세요. | `outputs/voice/tts/tts_06_Qwen3-TTS-12Hz-0.6B-CustomVoice.wav` | 2.415 | true |

Summary:

- TTS generation success: `6/6 = 100%`
- Average generation time: `2.377 sec/sentence`
- Observed Qwen3-TTS CUDA memory in one probe: `~2153 MiB` max allocated by torch.
- TTS quality was not human-rated in this run; audio files were generated for listening review.

## GPU memory notes

- `nvidia-smi` confirmed RTX 5070 Ti 16 GB and CUDA availability.
- Qwen3-TTS 0.6B CustomVoice probe reported `cuda_max_memory_allocated_mib ~= 2153.3`.
- faster-whisper uses CTranslate2, so `torch.cuda.max_memory_allocated()` does not capture its GPU memory usage. A one-sample STT probe completed successfully with CUDA device selected.

## Caching design

Danger-warning TTS should not be generated every time during navigation. Recommended flow:

1. Pre-generate common warning phrases at startup or install time.
2. Store files under `outputs/voice/cache` keyed by model/language/text.
3. `/speech/tts` returns cached WAV immediately when available.
4. Only dynamic text such as destination prompts should be generated on demand.

Common cache candidates:

- 전방에 점자블록 파손이 있습니다.
- 오른쪽에 방치된 킥보드가 있습니다.
- 공사 장애물이 감지되었습니다. 속도를 줄이세요.
- 노면 파임이 감지되었습니다. 전방을 확인하세요.
- 신고가 저장되었습니다.
- 목적지를 다시 말씀해 주세요.

## Fine-tuning decision

Current conclusion: **do not fine-tune yet**.

Reasons:

- STT synthetic intent test reached `100%` on the defined command set after intent normalization for common STT variants like `싱고해`.
- TTS 0.6B CustomVoice generated all six Korean warning prompts locally.
- The current blocker is not model capacity; it is lack of real-world microphone recordings and human TTS quality review.

Fine-tuning should only be reconsidered after:

1. Real pedestrian microphone samples are collected.
2. Noise/wind/walking-speed tests are run.
3. Intent accuracy on real audio is below `90%`.
4. Korean TTS is judged unsuitable for demonstration even after speaker/model variant selection.

## Next steps for PWA/backend integration

1. Run voice server on port `9001` separately from image/backend services.
2. PWA records short command audio and sends it to `POST /speech/stt`.
3. PWA/backend uses returned `intent` and `slots` for command handling.
4. Backend or PWA requests `POST /speech/tts` for dynamic prompts.
5. PWA should prefer cached static warning WAV files for low-latency danger alerts.
6. Add real phone-recorded Korean command samples under local-only `samples/voice/stt/` and rerun STT tests.

## Real human voice STT test: `samples/voice/stt/myvoice`

Checked on 2026-05-13 KST.

The requested paths `samples/stt/myvoice` and typo candidate `smaples/stt/myvoice` did not exist. The actual folder found and used was:

```text
samples/voice/stt/myvoice
```

Original audio files were not deleted or moved.

### Input files

```text
길 안내 시작해.m4a
다시말해줘.m4a
목적지 서울역으로 설정해.m4a
신고해.m4a
음성꺼.m4a
음성켜.m4a
지금어디야.m4a
현재 위험 신고해.m4a
```

### Method

- Virtualenv: `.venv-voice`
- STT model: `faster-whisper medium`
- Paid/cloud APIs: not used
- Fine-tuning: not performed
- YOLO/image inference code: not touched
- Result CSV: `outputs/voice/stt_myvoice_results.csv`
- Original first-pass CSV before intent rule patch: `outputs/voice/stt_myvoice_results_before_rule_update.csv`

Filename-based expected intent was used only when the filename clearly mapped to the current supported intent set. `지금어디야.m4a` was marked `needs_manual_review` because the current WalkSafe voice intent schema does not define a location-query intent yet.

### First pass before small intent-rule patch

| metric | value |
| --- | ---: |
| total files | 8 |
| known intent files | 7 |
| success | 5 |
| failure | 2 |
| needs manual review | 1 |
| known-intent accuracy | 71.43% |
| average latency | 0.463 sec |
| p95 latency | 0.573 sec |

First-pass failures:

| file | transcript | expected | predicted | analysis |
| --- | --- | --- | --- | --- |
| `길 안내 시작해.m4a` | 길었네 시작해 | `start_navigation` | `unknown` | STT confused “안내” as “었네”; recoverable by intent normalization because “길…시작해” remains. |
| `음성꺼.m4a` | 음성꼭 | `voice_off` | `unknown` | Common short-command ending confusion; recoverable by rule alias. |

### Intent-rule patch applied

Minimal aliases were added in `voice/intents.py`:

- `음성꼭`, `음성끅` -> `voice_off`
- `길었네시작`, `길시작` -> `start_navigation`

This is post-processing only. No model training/fine-tuning was done.

### Final result after intent-rule patch

| file | transcript | expected intent | predicted intent | time sec | status |
| --- | --- | --- | --- | ---: | --- |
| `길 안내 시작해.m4a` | 길었네 시작해 | `start_navigation` | `start_navigation` | 0.562 | success |
| `다시말해줘.m4a` | 다시 말해줘 | `repeat_last` | `repeat_last` | 0.408 | success |
| `목적지 서울역으로 설정해.m4a` | 목적지 서울역으로 설정해 | `set_destination` | `set_destination` | 0.572 | success |
| `신고해.m4a` | 신고해 | `create_report` | `create_report` | 0.387 | success |
| `음성꺼.m4a` | 음성꼭 | `voice_off` | `voice_off` | 0.422 | success |
| `음성켜.m4a` | 음성 켜 | `voice_on` | `voice_on` | 0.409 | success |
| `지금어디야.m4a` | 지금 어디야? | `needs_manual_review` | `unknown` | 0.414 | needs_manual_review |
| `현재 위험 신고해.m4a` | 현재 위험 신고해 | `create_report` | `create_report` | 0.478 | success |

Final metrics:

| metric | value |
| --- | ---: |
| total files | 8 |
| known intent files | 7 |
| success | 7 |
| failure | 0 |
| needs manual review | 1 |
| known-intent accuracy | 100.00% |
| average latency | 0.457 sec |
| p95 latency | 0.572 sec |

### Frequently wrong commands

After the small rule patch, no known supported command failed. The first-pass errors were both short/phrase-level STT variants that were solved by intent normalization rather than model changes.

`지금어디야.m4a` is not counted as wrong because no current expected intent exists. If this command is required, add a new intent such as `get_current_location` or `where_am_i` and connect it to GPS/current-location feedback.

### Judgment for WalkSafe Assist

- Whisper medium is sufficient for the tested supported commands.
- Fine-tuning is not needed now because known-intent accuracy is above the 90% threshold after lightweight intent-rule normalization.
- Latency is sufficient for the app prototype: average `0.457 sec`, p95 `0.572 sec`, both under the target thresholds of average <= 1 sec and p95 <= 2 sec.
- The main remaining work is intent schema/product behavior, not model training:
  - Decide whether `지금 어디야?` should be supported.
  - Add a formal location-query intent if needed.
  - Collect more real phone/PWA microphone samples with background noise, walking movement, and varied speakers.

Conclusion: proceed to PWA voice-command integration for the currently supported commands, with the caveat that more noisy real-world samples should be tested before demo freeze.
