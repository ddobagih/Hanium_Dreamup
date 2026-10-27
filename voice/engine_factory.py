"""Process-local speech engine construction, independent of the HTTP server."""

from __future__ import annotations

from functools import lru_cache
import os

from voice.stt import LocalSTTEngine
from voice.tts import DEFAULT_TTS_MODEL_ID, DEFAULT_TTS_SPEAKER, LocalTTSEngine


def bounded_env_int(name: str, default: int, *, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except ValueError:
        return default
    return min(max(value, minimum), maximum)


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
        hotwords=os.getenv("VOICE_STT_HOTWORDS") or None,
        initial_prompt=os.getenv("VOICE_STT_INITIAL_PROMPT") or None,
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
        speaker=os.getenv("VOICE_TTS_SPEAKER", DEFAULT_TTS_SPEAKER),
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
