"""Local STT, intent, and TTS prototype API.

This server classifies commands and returns audio; it does not execute reports
or navigation itself. Web/PWA callbacks consume its STT result, while the
Android product path uses platform speech services independently.
"""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from contextlib import suppress
from hmac import compare_digest
import ipaddress
import json
import math
import os
import re
import tempfile
from functools import lru_cache
from pathlib import Path
from typing import Any

import torch
from fastapi import FastAPI, File, HTTPException, Request, Response, UploadFile, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.openapi.utils import get_openapi
from fastapi.responses import FileResponse
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from voice.intents import classify_intent, intent_response_payload, intent_schema_payload
from voice.inference_process import IsolatedInferencePool, VoiceInferenceTimeout
from voice.model_integrity import ModelKind, validate_model_integrity_configuration
from voice.phrases import phrase_catalog_payload, phrase_text_by_id
from voice.request_limits import (
    AudioProbeError,
    ProcessLocalRateLimiter,
    UploadConcurrencyGate,
    acquire_single_process_lock,
    probe_audio_duration,
    release_single_process_lock,
)
from voice.telemetry import intent_telemetry_schema
from voice.stt import LocalSTTEngine
from voice.tts import DEFAULT_TTS_MODEL_ID, LocalTTSEngine


DEFAULT_CORS_ORIGINS = "http://localhost:3000,http://127.0.0.1:3000"
DEFAULT_MAX_UPLOAD_BYTES = 10 * 1024 * 1024
DEFAULT_MAX_REQUEST_BYTES = 11 * 1024 * 1024
DEFAULT_STT_MAX_AUDIO_DURATION_SECONDS = 30.0
DEFAULT_STT_MAX_UPLOAD_CONCURRENCY = 1
DEFAULT_STT_UPLOAD_QUEUE_TIMEOUT_SECONDS = 2.0
DEFAULT_STT_RATE_WINDOW_SECONDS = 60.0
DEFAULT_STT_GLOBAL_RATE_LIMIT = 120
DEFAULT_STT_ACTOR_RATE_LIMIT = 12
DEFAULT_STT_IP_RATE_LIMIT = 30
DEFAULT_STT_MAX_CONCURRENCY = 1
DEFAULT_STT_QUEUE_TIMEOUT_SECONDS = 2.0
DEFAULT_TTS_MAX_CONCURRENCY = 1
DEFAULT_TTS_QUEUE_TIMEOUT_SECONDS = 2.0
DEFAULT_STT_INFERENCE_TIMEOUT_SECONDS = 30.0
DEFAULT_TTS_INFERENCE_TIMEOUT_SECONDS = 60.0
DEFAULT_READY_TIMEOUT_SECONDS = 180.0
DEPLOYMENT_FLOAT_ENV_LIMITS = (
    ("VOICE_STT_UPLOAD_QUEUE_TIMEOUT_SECONDS", DEFAULT_STT_UPLOAD_QUEUE_TIMEOUT_SECONDS, 0.05, 30.0),
    ("VOICE_STT_RATE_WINDOW_SECONDS", DEFAULT_STT_RATE_WINDOW_SECONDS, 1.0, 3600.0),
    ("VOICE_STT_MAX_AUDIO_DURATION_SECONDS", DEFAULT_STT_MAX_AUDIO_DURATION_SECONDS, 1.0, 120.0),
    ("VOICE_STT_QUEUE_TIMEOUT_SECONDS", DEFAULT_STT_QUEUE_TIMEOUT_SECONDS, 0.1, 30.0),
    ("VOICE_TTS_QUEUE_TIMEOUT_SECONDS", DEFAULT_TTS_QUEUE_TIMEOUT_SECONDS, 0.1, 30.0),
    ("VOICE_STT_INFERENCE_TIMEOUT_SECONDS", DEFAULT_STT_INFERENCE_TIMEOUT_SECONDS, 1.0, 300.0),
    ("VOICE_TTS_INFERENCE_TIMEOUT_SECONDS", DEFAULT_TTS_INFERENCE_TIMEOUT_SECONDS, 1.0, 300.0),
    ("VOICE_READY_TIMEOUT_SECONDS", DEFAULT_READY_TIMEOUT_SECONDS, 1.0, 300.0),
)
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
ACTOR_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")
VOICE_SERVICE_TOKEN_HEADER = "x-walksafe-voice-service-token"
VOICE_CLIENT_IP_HEADER = "x-walksafe-voice-client-ip"
DEPLOYMENT_ENVIRONMENTS = {"field", "staging", "production"}
SUPPORTED_ENVIRONMENTS = {"development", "test", *DEPLOYMENT_ENVIRONMENTS}


class TranscriptRequest(BaseModel):
    transcript: str = Field(min_length=1, max_length=200)


class TTSRequest(BaseModel):
    text: str | None = Field(default=None, min_length=1, max_length=180)
    phrase_id: str | None = Field(default=None, min_length=1, max_length=80)
    use_cache: bool = True
    allow_fallback: bool = True


def env_csv(name: str, default: str) -> list[str]:
    return [value.strip() for value in os.getenv(name, default).split(",") if value.strip()]


def max_upload_bytes() -> int:
    try:
        value = int(os.getenv("VOICE_MAX_UPLOAD_BYTES", str(DEFAULT_MAX_UPLOAD_BYTES)))
    except ValueError:
        return DEFAULT_MAX_UPLOAD_BYTES
    return min(value, 32 * 1024 * 1024) if value > 0 else DEFAULT_MAX_UPLOAD_BYTES


def max_request_bytes() -> int:
    return bounded_env_int(
        "VOICE_STT_MAX_REQUEST_BYTES",
        DEFAULT_MAX_REQUEST_BYTES,
        minimum=64 * 1024,
        maximum=33 * 1024 * 1024,
    )


def voice_process_lock_path() -> Path:
    configured = os.getenv("VOICE_SERVICE_PROCESS_LOCK_PATH", f"/tmp/walksafe-voice-{os.geteuid()}.lock")
    path = Path(configured).expanduser()
    if not path.is_absolute():
        raise RuntimeError("VOICE_SERVICE_PROCESS_LOCK_PATH must be absolute")
    return path


def bounded_env_int(name: str, default: int, *, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except ValueError:
        return default
    return min(max(value, minimum), maximum)


def bounded_env_float(name: str, default: float, *, minimum: float, maximum: float) -> float:
    try:
        value = float(os.getenv(name, str(default)))
    except ValueError:
        if os.getenv("WALKSAFE_ENVIRONMENT", "development").strip().lower() in DEPLOYMENT_ENVIRONMENTS:
            raise RuntimeError(f"{name} must be a finite number") from None
        return default
    if not math.isfinite(value):
        if os.getenv("WALKSAFE_ENVIRONMENT", "development").strip().lower() in DEPLOYMENT_ENVIRONMENTS:
            raise RuntimeError(f"{name} must be a finite number")
        return default
    return min(max(value, minimum), maximum)


STT_MAX_CONCURRENCY = bounded_env_int(
    "VOICE_STT_MAX_CONCURRENCY", DEFAULT_STT_MAX_CONCURRENCY, minimum=1, maximum=1
)
STT_INFERENCE_SEMAPHORE = asyncio.Semaphore(STT_MAX_CONCURRENCY)
TTS_MAX_CONCURRENCY = bounded_env_int(
    "VOICE_TTS_MAX_CONCURRENCY", DEFAULT_TTS_MAX_CONCURRENCY, minimum=1, maximum=1
)
TTS_INFERENCE_SEMAPHORE = asyncio.Semaphore(TTS_MAX_CONCURRENCY)
STT_INFERENCE_POOL = IsolatedInferencePool("stt", STT_MAX_CONCURRENCY)
TTS_INFERENCE_POOL = IsolatedInferencePool("tts", TTS_MAX_CONCURRENCY)
STT_UPLOAD_GATE = UploadConcurrencyGate(
    bounded_env_int(
        "VOICE_STT_MAX_UPLOAD_CONCURRENCY",
        DEFAULT_STT_MAX_UPLOAD_CONCURRENCY,
        minimum=1,
        maximum=8,
    )
)
STT_REQUEST_RATE_LIMITER = ProcessLocalRateLimiter()


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


class VoiceClientDisconnected(RuntimeError):
    pass


async def run_inference_until_disconnect(request: Request, awaitable: Any) -> Any:
    task = asyncio.create_task(awaitable)
    receive = getattr(request, "receive", None)

    async def wait_for_disconnect() -> None:
        if callable(receive):
            while True:
                message = await receive()
                if message.get("type") == "http.disconnect":
                    return
        else:
            while not await request.is_disconnected():
                await asyncio.sleep(0.05)

    disconnect_task = asyncio.create_task(wait_for_disconnect())
    try:
        done, _pending = await asyncio.wait(
            {task, disconnect_task},
            return_when=asyncio.FIRST_COMPLETED,
        )
        if task in done:
            return task.result()
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task
        raise VoiceClientDisconnected("voice client disconnected")
    finally:
        if not task.done():
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task
        if not disconnect_task.done():
            disconnect_task.cancel()
            with suppress(asyncio.CancelledError):
                await disconnect_task


@lru_cache(maxsize=1)
def get_stt_engine() -> LocalSTTEngine:
    return LocalSTTEngine(
        model_size=os.getenv("VOICE_STT_MODEL", "medium"),
        model_revision=os.getenv("VOICE_STT_MODEL_REVISION") or None,
        model_manifest_path=os.getenv("VOICE_STT_MODEL_MANIFEST_PATH") or None,
        model_manifest_sha256=os.getenv("VOICE_STT_MODEL_MANIFEST_SHA256") or None,
        device=os.getenv("VOICE_STT_DEVICE") or None,
        compute_type=os.getenv("VOICE_STT_COMPUTE_TYPE") or None,
        language=os.getenv("VOICE_STT_LANGUAGE", "ko"),
    )


@lru_cache(maxsize=1)
def get_tts_engine() -> LocalTTSEngine:
    return LocalTTSEngine(
        model_id=os.getenv("VOICE_TTS_MODEL", DEFAULT_TTS_MODEL_ID),
        model_revision=os.getenv("VOICE_TTS_MODEL_REVISION") or None,
        model_manifest_path=os.getenv("VOICE_TTS_MODEL_MANIFEST_PATH") or None,
        model_manifest_sha256=os.getenv("VOICE_TTS_MODEL_MANIFEST_SHA256") or None,
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
        cache_max_files=bounded_env_int("VOICE_TTS_CACHE_MAX_FILES", 256, minimum=1, maximum=10_000),
        cache_max_bytes=bounded_env_int(
            "VOICE_TTS_CACHE_MAX_BYTES",
            512 * 1024 * 1024,
            minimum=1024 * 1024,
            maximum=10 * 1024 * 1024 * 1024,
        ),
    )


@asynccontextmanager
async def lifespan(_app: FastAPI):
    validate_voice_runtime_configuration()
    process_lock: tuple[int, int] | None = None
    try:
        if os.getenv("WALKSAFE_ENVIRONMENT", "development").strip().lower() in DEPLOYMENT_ENVIRONMENTS:
            process_lock = acquire_single_process_lock(voice_process_lock_path())
        yield
    finally:
        STT_INFERENCE_POOL.close()
        TTS_INFERENCE_POOL.close()
        if process_lock is not None:
            release_single_process_lock(process_lock)


app = FastAPI(title="WalkSafe Assist Local Voice API", version="0.1.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=env_csv("VOICE_CORS_ORIGINS", DEFAULT_CORS_ORIGINS),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def validate_voice_runtime_configuration() -> None:
    environment = os.getenv("WALKSAFE_ENVIRONMENT", "development").strip().lower()
    if environment not in SUPPORTED_ENVIRONMENTS:
        raise RuntimeError("WALKSAFE_ENVIRONMENT has an unsupported value")
    if environment not in DEPLOYMENT_ENVIRONMENTS:
        return
    expected = os.getenv("VOICE_SERVICE_TOKEN", "").strip()
    if len(expected) < 24:
        raise RuntimeError("VOICE_SERVICE_TOKEN must contain at least 24 characters in deployed environments")
    if expected in _gateway_credentials():
        raise RuntimeError("VOICE_SERVICE_TOKEN must be dedicated and differ from gateway/backend credentials")
    if os.getenv("VOICE_SERVICE_WORKERS", "").strip() != "1":
        raise RuntimeError("process-local Voice limits require VOICE_SERVICE_WORKERS=1")
    if os.getenv("VOICE_SERVICE_REPLICAS", "").strip() != "1":
        raise RuntimeError("process-local Voice limits require VOICE_SERVICE_REPLICAS=1")
    for name, default, minimum, maximum in DEPLOYMENT_FLOAT_ENV_LIMITS:
        bounded_env_float(name, default, minimum=minimum, maximum=maximum)
    voice_process_lock_path()
    model_settings: tuple[tuple[ModelKind, str], ...] = (
        ("stt", "VOICE_STT_MODEL"),
        ("tts", "VOICE_TTS_MODEL"),
    )
    for kind, prefix in model_settings:
        model_id = os.getenv(prefix, "").strip()
        revision = os.getenv(f"{prefix}_REVISION", "").strip()
        manifest_path = os.getenv(f"{prefix}_MANIFEST_PATH", "").strip()
        manifest_sha256 = os.getenv(f"{prefix}_MANIFEST_SHA256", "").strip()
        if not Path(manifest_path).is_absolute():
            raise RuntimeError(f"{prefix}_MANIFEST_PATH must be absolute in deployed environments")
        try:
            validate_model_integrity_configuration(
                kind,
                model_id,
                revision,
                manifest_path,
                manifest_sha256,
            )
        except (ValueError, RuntimeError) as exc:
            raise RuntimeError(f"{prefix} integrity configuration is invalid: {exc}") from exc


def _voice_request_authorized(_client_host: str | None, headers: Any) -> tuple[bool, str]:
    expected = os.getenv("VOICE_SERVICE_TOKEN", "").strip()
    if len(expected) < 24 or expected in _gateway_credentials():
        return False, "service_token_not_configured"
    candidate = headers.get(VOICE_SERVICE_TOKEN_HEADER, "")
    if candidate and compare_digest(candidate, expected):
        return True, "service_token"
    return False, "service_token_invalid"


def _gateway_credentials() -> set[str]:
    credentials = {
        os.getenv(name, "").strip()
        for name in ("WALKSAFE_FIELD_TEST_TOKEN", "WALKSAFE_ADMIN_TOKEN", "WALKSAFE_GATEWAY_SESSION_SECRET")
        if os.getenv(name, "").strip()
    }
    for name in ("WALKSAFE_FIELD_ACCOUNTS_JSON", "WALKSAFE_ADMIN_ACCOUNTS_JSON"):
        try:
            accounts = json.loads(os.getenv(name, "[]"))
        except json.JSONDecodeError:
            continue
        if not isinstance(accounts, list):
            continue
        credentials.update(
            token.strip()
            for account in accounts
            if isinstance(account, dict) and isinstance((token := account.get("token")), str) and token.strip()
        )
    return credentials


def _voice_error_response(status_code: int, code: str, message: str, **headers: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"detail": {"code": code, "message": message}},
        headers={"cache-control": "no-store", **headers},
    )


def _voice_rate_identity(request: Request) -> tuple[str | None, str] | JSONResponse:
    actor_id = request.headers.get("x-walksafe-actor-id", "").strip()
    if actor_id and ACTOR_ID_PATTERN.fullmatch(actor_id) is None:
        return _voice_error_response(400, "invalid_voice_actor", "Voice actor id is invalid.")
    forwarded_ip = request.headers.get(VOICE_CLIENT_IP_HEADER, "").strip()
    if forwarded_ip:
        try:
            client_ip = str(ipaddress.ip_address(forwarded_ip))
        except ValueError:
            return _voice_error_response(400, "invalid_voice_client_ip", "Voice client IP is invalid.")
    else:
        direct_ip = request.client.host if request.client else "unknown"
        try:
            client_ip = str(ipaddress.ip_address(direct_ip))
        except ValueError:
            client_ip = "unknown"
    return (actor_id or None, client_ip)


@app.middleware("http")
async def require_internal_service_auth(request: Request, call_next: Any) -> Response:
    if request.method == "OPTIONS":
        return await call_next(request)
    authorized, reason = _voice_request_authorized(
        request.client.host if request.client else None,
        request.headers,
    )
    if not authorized:
        status_code = 503 if reason == "service_token_not_configured" else 401
        return _voice_error_response(
            status_code,
            reason,
            "Voice service access is restricted to an authenticated internal caller.",
        )
    if request.method != "POST" or request.url.path != "/speech/stt":
        return await call_next(request)

    request_limit = max_request_bytes()
    declared_length = request.headers.get("content-length")
    if declared_length is not None:
        try:
            parsed_length = int(declared_length)
        except ValueError:
            return _voice_error_response(400, "invalid_content_length", "content-length must be an integer.")
        if parsed_length < 0:
            return _voice_error_response(400, "invalid_content_length", "content-length must not be negative.")
        if parsed_length > request_limit:
            return _voice_error_response(
                status.HTTP_413_CONTENT_TOO_LARGE,
                "voice_request_too_large",
                f"Voice request exceeds the {request_limit} byte limit.",
            )

    identity = _voice_rate_identity(request)
    if isinstance(identity, JSONResponse):
        return identity
    actor_id, client_ip = identity
    window_seconds = bounded_env_float(
        "VOICE_STT_RATE_WINDOW_SECONDS",
        DEFAULT_STT_RATE_WINDOW_SECONDS,
        minimum=1.0,
        maximum=3600.0,
    )
    rate_limits = [
        ("service:stt", bounded_env_int(
            "VOICE_STT_GLOBAL_RATE_LIMIT",
            DEFAULT_STT_GLOBAL_RATE_LIMIT,
            minimum=1,
            maximum=100_000,
        )),
        (f"ip:{client_ip}", bounded_env_int(
            "VOICE_STT_IP_RATE_LIMIT",
            DEFAULT_STT_IP_RATE_LIMIT,
            minimum=1,
            maximum=10_000,
        )),
    ]
    if actor_id:
        rate_limits.append((f"actor:{actor_id}", bounded_env_int(
            "VOICE_STT_ACTOR_RATE_LIMIT",
            DEFAULT_STT_ACTOR_RATE_LIMIT,
            minimum=1,
            maximum=10_000,
        )))
    rate_decision = STT_REQUEST_RATE_LIMITER.check(rate_limits, window_seconds)
    if not rate_decision.allowed:
        return _voice_error_response(
            status.HTTP_429_TOO_MANY_REQUESTS,
            "voice_rate_limited",
            "Voice request rate limit exceeded.",
            **{"retry-after": str(rate_decision.retry_after_seconds)},
        )

    acquired = await STT_UPLOAD_GATE.acquire(
        bounded_env_float(
            "VOICE_STT_UPLOAD_QUEUE_TIMEOUT_SECONDS",
            DEFAULT_STT_UPLOAD_QUEUE_TIMEOUT_SECONDS,
            minimum=0.05,
            maximum=30.0,
        )
    )
    if not acquired:
        return _voice_error_response(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "stt_upload_busy",
            "Speech upload capacity is busy. Retry shortly.",
            **{"retry-after": "1"},
        )

    original_receive = request._receive
    received_bytes = 0
    request_too_large = False

    async def limited_receive() -> dict[str, Any]:
        nonlocal received_bytes, request_too_large
        message = await original_receive()
        if message.get("type") == "http.request":
            received_bytes += len(message.get("body", b""))
            if received_bytes > request_limit:
                request_too_large = True
                raise RuntimeError("voice_request_too_large")
        return message

    request._receive = limited_receive
    try:
        try:
            response = await call_next(request)
        except Exception:
            if request_too_large:
                return _voice_error_response(
                    status.HTTP_413_CONTENT_TOO_LARGE,
                    "voice_request_too_large",
                    f"Voice request exceeds the {request_limit} byte limit.",
                )
            raise
        if request_too_large:
            return _voice_error_response(
                status.HTTP_413_CONTENT_TOO_LARGE,
                "voice_request_too_large",
                f"Voice request exceeds the {request_limit} byte limit.",
            )
        return response
    finally:
        STT_UPLOAD_GATE.release()


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
        "stt_model_revision": os.getenv("VOICE_STT_MODEL_REVISION") or None,
        "stt_max_concurrency": STT_MAX_CONCURRENCY,
        "stt_max_upload_concurrency": STT_UPLOAD_GATE.maximum,
        "stt_max_request_bytes": max_request_bytes(),
        "stt_max_audio_duration_seconds": bounded_env_float(
            "VOICE_STT_MAX_AUDIO_DURATION_SECONDS",
            DEFAULT_STT_MAX_AUDIO_DURATION_SECONDS,
            minimum=1.0,
            maximum=120.0,
        ),
        "tts_model": os.getenv("VOICE_TTS_MODEL", DEFAULT_TTS_MODEL_ID),
        "tts_model_revision": os.getenv("VOICE_TTS_MODEL_REVISION") or None,
        "tts_max_concurrency": TTS_MAX_CONCURRENCY,
        "tts_mode": os.getenv("VOICE_TTS_MODE", "custom"),
        "tts_cache_dir": os.getenv("VOICE_TTS_CACHE_DIR", "outputs/voice/cache"),
        "process_isolation": True,
        "rate_limit_store": "process_local",
        "required_workers": 1,
        "required_replicas": 1,
    }


@app.get("/ready")
async def readiness(response: Response) -> dict[str, Any]:
    timeout = bounded_env_float(
        "VOICE_READY_TIMEOUT_SECONDS",
        DEFAULT_READY_TIMEOUT_SECONDS,
        minimum=1.0,
        maximum=300.0,
    )

    async def check(pool: IsolatedInferencePool) -> dict[str, Any]:
        try:
            result = await pool.run("ready", timeout=timeout)
            return {"ready": True, "evidence": "live_worker_probe", "model": result["model"]}
        except Exception as exc:
            return {"ready": False, "reason": "model_warmup_failed", "error_type": type(exc).__name__}

    # Load sequentially to avoid a transient double-model GPU memory spike.
    stt_check = await check(STT_INFERENCE_POOL)
    tts_check = await check(TTS_INFERENCE_POOL)
    ready = stt_check["ready"] is True and tts_check["ready"] is True
    if not ready:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return {
        "status": "ready" if ready else "not_ready",
        "checks": {"stt": stt_check, "tts": tts_check},
    }


@app.post("/speech/intent")
def speech_intent(request: TranscriptRequest) -> dict[str, Any]:
    result = classify_intent(request.transcript)
    return {
        "transcript": request.transcript,
        **intent_response_payload(result),
    }


@app.get("/speech/intents")
def speech_intents() -> dict[str, Any]:
    return intent_schema_payload()


@app.get("/speech/intent-telemetry/schema")
def speech_intent_telemetry_schema() -> dict[str, Any]:
    return intent_telemetry_schema()


@app.get("/speech/phrases")
def speech_phrases() -> dict[str, object]:
    return phrase_catalog_payload()


@app.post("/speech/stt")
async def speech_stt(request: Request, audio: UploadFile = File(...)) -> dict[str, Any]:
    tmp_path: Path | None = None
    try:
        suffix = audio_upload_suffix(audio)
        upload_limit = max_upload_bytes()
        if getattr(audio, "size", None) is not None and audio.size > upload_limit:
            raise stt_error(
                status.HTTP_413_CONTENT_TOO_LARGE,
                "audio_too_large",
                f"Uploaded audio exceeds the {upload_limit} byte limit.",
            )
        bytes_written = 0
        # faster-whisper consumes a file path. The finally block removes this
        # request-scoped copy whether decoding succeeds or fails.
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp_path = Path(tmp.name)
            while chunk := await audio.read(UPLOAD_CHUNK_BYTES):
                bytes_written += len(chunk)
                if bytes_written > upload_limit:
                    raise stt_error(
                        status.HTTP_413_CONTENT_TOO_LARGE,
                        "audio_too_large",
                        f"Uploaded audio exceeds the {upload_limit} byte limit.",
                    )
                tmp.write(chunk)
        if bytes_written == 0:
            raise stt_error(status.HTTP_400_BAD_REQUEST, "empty_audio", "Uploaded audio file is empty.")
        try:
            media_duration_seconds = await run_inference_until_disconnect(
                request,
                probe_audio_duration(tmp_path),
            )
        except AudioProbeError as exc:
            raise stt_error(
                status.HTTP_400_BAD_REQUEST,
                "invalid_audio",
                "Uploaded media does not contain a verifiable audio stream.",
            ) from exc
        duration_limit = bounded_env_float(
            "VOICE_STT_MAX_AUDIO_DURATION_SECONDS",
            DEFAULT_STT_MAX_AUDIO_DURATION_SECONDS,
            minimum=1.0,
            maximum=120.0,
        )
        if media_duration_seconds > duration_limit:
            raise stt_error(
                status.HTTP_422_UNPROCESSABLE_CONTENT,
                "audio_duration_exceeded",
                f"Uploaded audio exceeds the {duration_limit:g} second duration limit.",
            )
        try:
            await asyncio.wait_for(
                STT_INFERENCE_SEMAPHORE.acquire(),
                timeout=bounded_env_float(
                    "VOICE_STT_QUEUE_TIMEOUT_SECONDS",
                    DEFAULT_STT_QUEUE_TIMEOUT_SECONDS,
                    minimum=0.1,
                    maximum=30.0,
                ),
            )
        except TimeoutError as exc:
            raise stt_error(
                status.HTTP_503_SERVICE_UNAVAILABLE,
                "stt_busy",
                "Speech recognition is busy. Retry shortly.",
            ) from exc
        # faster-whisper is synchronous and expensive. Keep it off the ASGI
        # event loop and hold capacity until the worker actually returns.
        try:
            result = await run_inference_until_disconnect(
                request,
                STT_INFERENCE_POOL.run(
                    "infer",
                    str(tmp_path),
                    timeout=bounded_env_float(
                        "VOICE_STT_INFERENCE_TIMEOUT_SECONDS",
                        DEFAULT_STT_INFERENCE_TIMEOUT_SECONDS,
                        minimum=1.0,
                        maximum=300.0,
                    ),
                ),
            )
        finally:
            STT_INFERENCE_SEMAPHORE.release()
        intent_result = classify_intent(result.transcript)
        return {
            "transcript": result.transcript,
            **intent_response_payload(
                intent_result,
                acoustic_confidence=result.acoustic.confidence,
                avg_logprob=result.acoustic.avg_logprob,
                no_speech_probability=result.acoustic.no_speech_probability,
                acoustic_allowed=result.acoustic.execution_allowed,
            ),
            "language": result.language,
            "duration_sec": result.duration_sec,
            "audio_duration_sec": media_duration_seconds,
            "model": result.model,
            "segments": result.segments,
        }
    except VoiceInferenceTimeout as exc:
        raise stt_error(
            status.HTTP_504_GATEWAY_TIMEOUT,
            "stt_inference_timeout",
            "Speech recognition exceeded its hard deadline.",
        ) from exc
    except VoiceClientDisconnected as exc:
        raise stt_error(499, "voice_client_disconnected", "Voice client disconnected.") from exc
    except RuntimeError as exc:
        raise stt_error(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "stt_unavailable",
            "Speech recognition is unavailable.",
        ) from exc
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


def tts_fallback_path() -> Path | None:
    configured = os.getenv("VOICE_TTS_FALLBACK_WAV")
    if not configured:
        return None
    path = Path(configured)
    if path.is_file() and path.stat().st_size > 0:
        return path
    return None


@app.post("/speech/tts")
async def speech_tts(http_request: Request, request: TTSRequest) -> FileResponse:
    text = resolve_tts_request_text(request)
    try:
        await asyncio.wait_for(
            TTS_INFERENCE_SEMAPHORE.acquire(),
            timeout=bounded_env_float(
                "VOICE_TTS_QUEUE_TIMEOUT_SECONDS",
                DEFAULT_TTS_QUEUE_TIMEOUT_SECONDS,
                minimum=0.1,
                maximum=30.0,
            ),
        )
    except TimeoutError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "tts_busy", "message": "Speech synthesis is busy. Retry shortly."},
        ) from exc
    try:
        result = await run_inference_until_disconnect(
            http_request,
            TTS_INFERENCE_POOL.run(
                "infer",
                text,
                request.use_cache,
                timeout=bounded_env_float(
                    "VOICE_TTS_INFERENCE_TIMEOUT_SECONDS",
                    DEFAULT_TTS_INFERENCE_TIMEOUT_SECONDS,
                    minimum=1.0,
                    maximum=300.0,
                ),
            ),
        )
    except VoiceClientDisconnected as exc:
        raise HTTPException(
            status_code=499,
            detail={"code": "voice_client_disconnected", "message": "Voice client disconnected."},
        ) from exc
    except (RuntimeError, ValueError, VoiceInferenceTimeout) as exc:
        fallback_path = tts_fallback_path() if request.allow_fallback else None
        if fallback_path is None:
            code = "tts_inference_timeout" if isinstance(exc, VoiceInferenceTimeout) else "tts_unavailable"
            status_code = (
                status.HTTP_504_GATEWAY_TIMEOUT
                if isinstance(exc, VoiceInferenceTimeout)
                else status.HTTP_503_SERVICE_UNAVAILABLE
            )
            raise HTTPException(
                status_code=status_code,
                detail={"code": code, "message": "Speech synthesis is unavailable."},
            ) from exc
        headers = {
            "X-Voice-Model": "fallback",
            "X-Voice-Cached": "false",
            "X-Voice-Fallback": "true",
            "X-Voice-Mode": "fallback",
            "X-Voice-Generation-Seconds": "0.000",
        }
        return FileResponse(fallback_path, media_type="audio/wav", filename=fallback_path.name, headers=headers)
    finally:
        TTS_INFERENCE_SEMAPHORE.release()
    headers = {
        "X-Voice-Model": result.model,
        "X-Voice-Cached": str(result.cached).lower(),
        "X-Voice-Fallback": "false",
        "X-Voice-Mode": result.mode,
        "X-Voice-Generation-Seconds": f"{result.duration_sec:.3f}",
    }
    return FileResponse(result.output_path, media_type="audio/wav", filename=result.output_path.name, headers=headers)


@app.post("/speech/tts/cache-status")
def speech_tts_cache_status(request: TTSRequest) -> dict[str, Any]:
    text = resolve_tts_request_text(request)
    engine = get_tts_engine()
    cache_path = engine._cache_path(text, engine.mode)  # dry-run; does not load model
    return {
        "schema_version": "walksafe.tts_cache_status.v1",
        "cached": cache_path.exists() and cache_path.stat().st_size > 0,
        "cache_key": cache_path.stem,
        "path_suffix": cache_path.suffix,
        "phrase_id": request.phrase_id,
        "model": engine.model_id,
        "mode": engine.mode,
    }


def resolve_tts_request_text(request: TTSRequest) -> str:
    if request.phrase_id:
        text = phrase_text_by_id(request.phrase_id)
        if text:
            return text
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="unknown phrase_id")
    if request.text:
        return request.text
    raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="text or phrase_id is required")


def voice_openapi() -> dict[str, Any]:
    if app.openapi_schema is not None:
        return app.openapi_schema
    schema = get_openapi(title=app.title, version=app.version, routes=app.routes)
    components = schema.setdefault("components", {})
    security_schemes = components.setdefault("securitySchemes", {})
    security_schemes["VoiceServiceToken"] = {
        "type": "apiKey",
        "in": "header",
        "name": VOICE_SERVICE_TOKEN_HEADER,
        "description": "Dedicated Web BFF-to-Voice service credential; loopback is not exempt.",
    }
    for path_item in schema.get("paths", {}).values():
        for method, operation in path_item.items():
            if method.lower() not in {"get", "post", "put", "patch", "delete"} or not isinstance(operation, dict):
                continue
            operation["security"] = [{"VoiceServiceToken": []}]
            responses = operation.setdefault("responses", {})
            responses.setdefault("401", {"description": "Missing or invalid Voice service token"})
            responses.setdefault("503", {"description": "Voice service token is not configured"})
    app.openapi_schema = schema
    return schema


app.openapi = voice_openapi
