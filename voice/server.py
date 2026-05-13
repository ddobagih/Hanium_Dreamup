from __future__ import annotations

import os
import tempfile
from functools import lru_cache
from pathlib import Path
from typing import Any

import torch
from fastapi import FastAPI, File, HTTPException, UploadFile, status
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from voice.intents import classify_intent
from voice.stt import LocalSTTEngine
from voice.tts import DEFAULT_TTS_MODEL_ID, LocalTTSEngine


class TranscriptRequest(BaseModel):
    transcript: str = Field(min_length=1)


class TTSRequest(BaseModel):
    text: str = Field(min_length=1)
    use_cache: bool = True


@lru_cache(maxsize=1)
def get_stt_engine() -> LocalSTTEngine:
    return LocalSTTEngine(
        model_size=os.getenv("VOICE_STT_MODEL", "medium"),
        device=os.getenv("VOICE_STT_DEVICE") or None,
        compute_type=os.getenv("VOICE_STT_COMPUTE_TYPE") or None,
        language=os.getenv("VOICE_STT_LANGUAGE", "ko"),
    )


@lru_cache(maxsize=1)
def get_tts_engine() -> LocalTTSEngine:
    return LocalTTSEngine(
        model_id=os.getenv("VOICE_TTS_MODEL", DEFAULT_TTS_MODEL_ID),
        device_map=os.getenv("VOICE_TTS_DEVICE_MAP") or None,
        dtype=os.getenv("VOICE_TTS_DTYPE", "bfloat16"),
        attn_implementation=os.getenv("VOICE_TTS_ATTENTION") or None,
        language=os.getenv("VOICE_TTS_LANGUAGE", "Korean"),
        mode=os.getenv("VOICE_TTS_MODE", "custom"),
        voice_instruct=os.getenv("VOICE_TTS_INSTRUCT", "차분하고 명확한 한국어 보행 안전 안내 음성. 너무 빠르지 않게 말하세요."),
        speaker=os.getenv("VOICE_TTS_SPEAKER") or None,
        ref_audio=os.getenv("VOICE_TTS_REF_AUDIO") or None,
        ref_text=os.getenv("VOICE_TTS_REF_TEXT") or None,
        cache_dir=os.getenv("VOICE_TTS_CACHE_DIR", "outputs/voice/cache"),
    )


app = FastAPI(title="WalkSafe Assist Local Voice API", version="0.1.0")


@app.get("/health")
def health() -> dict[str, Any]:
    cuda = torch.cuda.is_available()
    gpu = torch.cuda.get_device_name(0) if cuda else None
    return {
        "status": "ok",
        "server": "voice",
        "cuda_available": cuda,
        "gpu": gpu,
        "stt_model": os.getenv("VOICE_STT_MODEL", "medium"),
        "tts_model": os.getenv("VOICE_TTS_MODEL", DEFAULT_TTS_MODEL_ID),
        "tts_mode": os.getenv("VOICE_TTS_MODE", "custom"),
        "tts_cache_dir": os.getenv("VOICE_TTS_CACHE_DIR", "outputs/voice/cache"),
    }


@app.post("/speech/intent")
def speech_intent(request: TranscriptRequest) -> dict[str, Any]:
    result = classify_intent(request.transcript)
    return {
        "transcript": request.transcript,
        "normalized": result.normalized,
        "intent": result.intent,
        "score": result.score,
        "slots": result.slots,
    }


@app.post("/speech/stt")
async def speech_stt(audio: UploadFile = File(...)) -> dict[str, Any]:
    suffix = Path(audio.filename or "input.wav").suffix or ".wav"
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp.write(await audio.read())
            tmp_path = Path(tmp.name)
        result = get_stt_engine().transcribe_file(tmp_path)
        return {
            "transcript": result.transcript,
            "intent": result.intent,
            "confidence": result.score,
            "score": result.score,
            "slots": result.slots,
            "language": result.language,
            "duration_sec": result.duration_sec,
            "model": result.model,
            "segments": result.segments,
        }
    except RuntimeError as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from exc
    finally:
        try:
            tmp_path.unlink(missing_ok=True)  # type: ignore[name-defined]
        except Exception:
            pass


@app.post("/speech/tts")
def speech_tts(request: TTSRequest) -> FileResponse:
    try:
        result = get_tts_engine().synthesize(request.text, use_cache=request.use_cache)
    except RuntimeError as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from exc
    headers = {
        "X-Voice-Model": result.model,
        "X-Voice-Cached": str(result.cached).lower(),
        "X-Voice-Mode": result.mode,
        "X-Voice-Generation-Seconds": f"{result.duration_sec:.3f}",
    }
    return FileResponse(result.output_path, media_type="audio/wav", filename=result.output_path.name, headers=headers)
