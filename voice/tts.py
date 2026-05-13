from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

import torch


DEFAULT_TTS_MODEL_ID = "Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice"
OPTIONAL_TTS_MODEL_ID = "Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice"
DEFAULT_VOICE_INSTRUCT = "차분하고 명확한 한국어 보행 안전 안내 음성. 너무 빠르지 않게 말하세요."
TTSMode = Literal["custom", "design", "clone"]


@dataclass(frozen=True)
class TTSResult:
    output_path: Path
    sample_rate: int
    duration_sec: float
    model: str
    cached: bool
    mode: TTSMode


class LocalTTSEngine:
    """Lazy Qwen3-TTS wrapper for local inference.

    Default mode is CustomVoice, which uses preset local model speakers and does not require a user reference voice. Voice cloning is also supported with local reference audio/text. No paid or cloud inference API is used.
    """

    def __init__(
        self,
        model_id: str = DEFAULT_TTS_MODEL_ID,
        device_map: str | None = None,
        dtype: str = "bfloat16",
        attn_implementation: str | None = None,
        language: str = "Korean",
        mode: TTSMode = "custom",
        voice_instruct: str = DEFAULT_VOICE_INSTRUCT,
        speaker: str | None = None,
        ref_audio: str | None = None,
        ref_text: str | None = None,
        cache_dir: str | Path = "outputs/voice/cache",
    ) -> None:
        self.model_id = model_id
        self.device_map = device_map or ("cuda:0" if torch.cuda.is_available() else "cpu")
        self.dtype = getattr(torch, dtype)
        self.attn_implementation = attn_implementation
        self.language = language
        self.mode: TTSMode = mode
        self.voice_instruct = voice_instruct
        self.speaker = speaker
        self.ref_audio = ref_audio
        self.ref_text = ref_text
        self.cache_dir = Path(cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self._model = None

    def load(self) -> None:
        if self._model is not None:
            return
        try:
            from qwen_tts import Qwen3TTSModel
        except ImportError as exc:
            raise RuntimeError(
                "qwen-tts is not installed. Install with `pip install -r requirements-voice.txt`."
            ) from exc

        kwargs = {
            "device_map": self.device_map,
            "dtype": self.dtype,
        }
        if self.attn_implementation:
            kwargs["attn_implementation"] = self.attn_implementation
        self._model = Qwen3TTSModel.from_pretrained(self.model_id, **kwargs)

    def _cache_path(self, text: str, mode: TTSMode, suffix: str = ".wav") -> Path:
        key = hashlib.sha256(
            f"{self.model_id}|{self.language}|{mode}|{self.voice_instruct}|{text}".encode("utf-8")
        ).hexdigest()[:20]
        return self.cache_dir / f"qwen3_tts_{key}{suffix}"

    def synthesize(
        self,
        text: str,
        output_path: str | Path | None = None,
        use_cache: bool = True,
        mode: TTSMode | None = None,
        voice_instruct: str | None = None,
        ref_audio: str | None = None,
        ref_text: str | None = None,
    ) -> TTSResult:
        synth_mode: TTSMode = mode or self.mode
        output = Path(output_path) if output_path else self._cache_path(text, synth_mode)
        output.parent.mkdir(parents=True, exist_ok=True)
        if use_cache and output.exists() and output.stat().st_size > 0:
            return TTSResult(output, sample_rate=0, duration_sec=0.0, model=self.model_id, cached=True, mode=synth_mode)

        self.load()
        assert self._model is not None
        try:
            import soundfile as sf
        except ImportError as exc:
            raise RuntimeError("soundfile is not installed. Install requirements-voice.txt.") from exc

        start = time.perf_counter()
        if synth_mode == "custom":
            speaker = self.speaker
            if not speaker and hasattr(self._model, "get_supported_speakers"):
                speakers = self._model.get_supported_speakers()
                if speakers:
                    speaker = sorted(speakers)[0]
            if not speaker:
                raise RuntimeError("Custom voice mode requires a supported speaker; set --speaker or use a CustomVoice model.")
            wavs, sample_rate = self._model.generate_custom_voice(
                text=text,
                speaker=speaker,
                language=self.language,
                instruct=voice_instruct or self.voice_instruct,
                non_streaming_mode=True,
            )
        elif synth_mode == "clone":
            local_ref_audio = ref_audio or self.ref_audio
            local_ref_text = ref_text or self.ref_text
            if not local_ref_audio or not local_ref_text:
                raise RuntimeError("Voice clone mode requires --ref-audio and --ref-text.")
            wavs, sample_rate = self._model.generate_voice_clone(
                text=text,
                language=self.language,
                ref_audio=local_ref_audio,
                ref_text=local_ref_text,
            )
        else:
            wavs, sample_rate = self._model.generate_voice_design(
                text=text,
                instruct=voice_instruct or self.voice_instruct,
                language=self.language,
                non_streaming_mode=True,
            )
        elapsed = time.perf_counter() - start
        sf.write(str(output), wavs[0], sample_rate)
        return TTSResult(output, sample_rate=sample_rate, duration_sec=elapsed, model=self.model_id, cached=False, mode=synth_mode)
