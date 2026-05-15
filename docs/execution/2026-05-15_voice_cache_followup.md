# 2026-05-15 Voice cache follow-up

기준 시각: 2026-05-15 11:54 KST

## 범위

- Voice STT/TTS validation/cache follow-up만 확인했다.
- 브라우저/실폰 마이크 검증은 수행하지 않았다.
- `voice/*` source는 수정하지 않았다.

## 1. 시작 전 git status

명령:

```bash
git status --short
```

결과:

```text
 M daylog/2026-05-15.md
?? docs/execution/2026-05-15_backend_validation.md
?? docs/execution/2026-05-15_disk_cleanup_candidates.md
?? docs/execution/2026-05-15_installed_program_usage_candidates.md
?? docs/execution/2026-05-15_low_risk_cleanup_result.md
?? docs/execution/2026-05-15_model_validation.md
?? docs/execution/2026-05-15_obs_removal_attempt.md
?? docs/execution/2026-05-15_parallel_validation_summary.md
?? docs/execution/2026-05-15_pwa_validation.md
?? docs/execution/2026-05-15_voice_validation.md
?? docs/execution/2026-05-15_wine_obs_snap_cleanup_attempt.md
?? docs/execution/2026-05-15_wine_obs_snap_cleanup_result.md
```

## 2. 임시 voice server

명령:

```bash
source .venv-voice/bin/activate
export PYTHONPATH=.
export HF_HUB_OFFLINE=1
export TRANSFORMERS_OFFLINE=1
python -m uvicorn voice.server:app --host 127.0.0.1 --port 9001
```

결과:

```text
Started server process [1371343]
Uvicorn running on http://127.0.0.1:9001
```

`HF_HUB_OFFLINE=1`/`TRANSFORMERS_OFFLINE=1`은 TTS 확인 중 대용량 다운로드를 피하려고 사용했다.

## 3. Voice contract smoke

명령:

```bash
source .venv-voice/bin/activate
PYTHONPATH=. python scripts/check_voice_contract.py --base-url http://127.0.0.1:9001 --timeout 3
```

결과: PASS

```text
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

## 4. Cache/GPU gate

명령:

```bash
find outputs/voice/cache -maxdepth 1 -mindepth 1 -printf '%f\t%y\t%s bytes\t%TY-%Tm-%Td %TH:%TM:%TS\n' | sort
find outputs/voice/cache -maxdepth 1 -type f -name '*.wav' | wc -l
du -sh outputs/voice/cache
du -sh ~/.cache/huggingface ~/.cache/huggingface/hub/models--Qwen--Qwen3-TTS-12Hz-0.6B-CustomVoice
nvidia-smi --query-gpu=name,memory.total,memory.used,memory.free --format=csv,noheader,nounits
```

결과:

```text
.gitkeep	f	0 bytes	2026-05-12 16:23:58.8783430370
wav_count=0
4.0K	outputs/voice/cache
6.1G	/home/ddobagi/.cache/huggingface
2.4G	/home/ddobagi/.cache/huggingface/hub/models--Qwen--Qwen3-TTS-12Hz-0.6B-CustomVoice
NVIDIA GeForce RTX 5070 Ti, 16303, 631, 15207
```

## 5. `/speech/tts` cache 확인 시도

조건상 Hugging Face cache와 GPU 여유 메모리가 있어 짧은 안전 문구 1개로 TTS를 시도했다.

문구: `안전하게 이동하세요.`

명령:

```bash
curl -sS --fail-with-body --max-time 300 \
  -D /tmp/voice_tts_1.headers \
  -o /tmp/voice_tts_1.wav \
  -w 'curl_http_code=%{http_code}\ncurl_time_total=%{time_total}\ncurl_size_download=%{size_download}\n' \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data '{"text":"안전하게 이동하세요.","use_cache":true}' \
  http://127.0.0.1:9001/speech/tts
```

결과: FAIL, 첫 요청에서 중단했다.

```text
curl: (22) The requested URL returned error: 500
curl_http_code=500
curl_time_total=5.199980
curl_size_download=21
curl_exit=22
HTTP/1.1 500 Internal Server Error
content-length: 21
content-type: text/plain; charset=utf-8
error_body_preview: Internal Server Error
```

서버 로그 핵심:

```text
Warning: flash-attn is not installed. Will only run the manual PyTorch version.
/bin/sh: 1: sox: not found
huggingface_hub.errors.OfflineModeIsEnabled: Cannot reach https://huggingface.co/api/models/Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice: offline mode is enabled.
```

대용량 다운로드 방지용 offline mode에서 `transformers`/`huggingface_hub`가 모델 metadata 조회를 시도해 500이 발생했다. 오류 발생 조건에 따라 두 번째 `/speech/tts` 호출은 수행하지 않았다.

TTS 시도 후 cache 상태:

```text
.gitkeep	f	0 bytes	2026-05-12 16:23:58.8783430370
wav_count=0
4.0K	outputs/voice/cache
```

Cache hit는 확인하지 못했다.

## 6. 서버 종료

종료: `Ctrl-C`

결과:

```text
Shutting down
Application shutdown complete
Finished server process [1371343]
```

포트 확인:

```bash
ss -ltnp | grep ':9001' || true
```

결과: 출력 없음. `127.0.0.1:9001` listener 없음.

## 요약

- `scripts/check_voice_contract.py`: PASS.
- `outputs/voice/cache`: `.gitkeep`만 존재, WAV 0개.
- Hugging Face cache: 존재, Qwen CustomVoice cache도 존재.
- GPU: RTX 5070 Ti, free 약 15.2GiB 관측.
- `/speech/tts`: 첫 요청 HTTP 500으로 중단. cache WAV 생성 없음, cache hit 미확인.
