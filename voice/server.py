from __future__ import annotations

import os
import tempfile
from functools import lru_cache
from pathlib import Path
from typing import Any

import torch
from fastapi import FastAPI, File, HTTPException, UploadFile, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from voice.intents import classify_intent
from voice.stt import LocalSTTEngine
from voice.tts import DEFAULT_TTS_MODEL_ID, LocalTTSEngine


DEFAULT_CORS_ORIGINS = "http://localhost:3000,http://127.0.0.1:3000"
DEFAULT_MAX_UPLOAD_BYTES = 10 * 1024 * 1024
UPLOAD_CHUNK_BYTES = 1024 * 1024
SUPPORTED_AUDIO_EXTENSIONS = {".wav", ".mp3", ".m4a", ".mp4", ".flac", ".ogg", ".oga", ".webm", ".aac"}
SUPPORTED_AUDIO_CONTENT_TYPES = {
    "audio/aac",
    "audio/flac",
    "audio/mp3",
    "audio/mp4",
    "audio/mpeg",
    "audio/ogg",
    "audio/wav",
    "audio/wave",
    "audio/webm",
    "audio/x-flac",
    "audio/x-m4a",
    "audio/x-wav",
    "application/ogg",
    "video/webm",
}
GENERIC_UPLOAD_CONTENT_TYPES = {"", "application/octet-stream"}
CONTENT_TYPE_SUFFIXES = {
    "audio/aac": ".aac",
    "audio/flac": ".flac",
    "audio/mp3": ".mp3",
    "audio/mp4": ".m4a",
    "audio/mpeg": ".mp3",
    "audio/ogg": ".ogg",
    "audio/wav": ".wav",
    "audio/wave": ".wav",
    "audio/webm": ".webm",
    "audio/x-flac": ".flac",
    "audio/x-m4a": ".m4a",
    "audio/x-wav": ".wav",
    "application/ogg": ".ogg",
    "video/webm": ".webm",
}


class TranscriptRequest(BaseModel):
    transcript: str = Field(min_length=1)


class TTSRequest(BaseModel):
    text: str = Field(min_length=1)
    use_cache: bool = True


def env_csv(name: str, default: str) -> list[str]:
    return [value.strip() for value in os.getenv(name, default).split(",") if value.strip()]


def max_upload_bytes() -> int:
    try:
        value = int(os.getenv("VOICE_MAX_UPLOAD_BYTES", str(DEFAULT_MAX_UPLOAD_BYTES)))
    except ValueError:
        return DEFAULT_MAX_UPLOAD_BYTES
    return value if value > 0 else DEFAULT_MAX_UPLOAD_BYTES


def upload_content_type(audio: UploadFile) -> str:
    return (audio.content_type or "").split(";", maxsplit=1)[0].strip().lower()


def audio_upload_suffix(audio: UploadFile) -> str:
    suffix = Path(audio.filename or "").suffix.lower()
    content_type = upload_content_type(audio)
    if content_type in SUPPORTED_AUDIO_CONTENT_TYPES:
        return suffix if suffix in SUPPORTED_AUDIO_EXTENSIONS else CONTENT_TYPE_SUFFIXES.get(content_type, ".wav")
    if suffix in SUPPORTED_AUDIO_EXTENSIONS and content_type in GENERIC_UPLOAD_CONTENT_TYPES:
        return suffix
    raise stt_error(
        status.HTTP_400_BAD_REQUEST,
        "unsupported_audio_type",
        "Unsupported audio upload. Use wav, mp3, m4a, flac, ogg, webm, mp4, or aac.",
    )


def stt_error(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(status_code=status_code, detail={"code": code, "message": message})


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
app.add_middleware(
    CORSMiddleware,
    allow_origins=env_csv("VOICE_CORS_ORIGINS", DEFAULT_CORS_ORIGINS),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


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
    tmp_path: Path | None = None
    try:
        suffix = audio_upload_suffix(audio)
        upload_limit = max_upload_bytes()
        if getattr(audio, "size", None) is not None and audio.size > upload_limit:
            raise stt_error(
                status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                "audio_too_large",
                f"Uploaded audio exceeds the {upload_limit} byte limit.",
            )
        bytes_written = 0
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp_path = Path(tmp.name)
            while chunk := await audio.read(UPLOAD_CHUNK_BYTES):
                bytes_written += len(chunk)
                if bytes_written > upload_limit:
                    raise stt_error(
                        status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                        "audio_too_large",
                        f"Uploaded audio exceeds the {upload_limit} byte limit.",
                    )
                tmp.write(chunk)
        if bytes_written == 0:
            raise stt_error(status.HTTP_400_BAD_REQUEST, "empty_audio", "Uploaded audio file is empty.")
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
        raise stt_error(status.HTTP_503_SERVICE_UNAVAILABLE, "stt_unavailable", str(exc)) from exc
    finally:
        try:
            await audio.close()
        except Exception:
            pass
        try:
            if tmp_path is not None:
                tmp_path.unlink(missing_ok=True)
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
