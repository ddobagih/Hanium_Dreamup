"""Lazy local Qwen3-TTS synthesis with deterministic WAV caching.

The cache key includes model and voice inputs so different speakers,
instructions, and clone references cannot reuse the same generated file.
"""

from __future__ import annotations

import hashlib
import fcntl
import os
import stat
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from threading import Lock
from typing import Literal

import torch

from voice.model_integrity import verify_model_snapshot


DEFAULT_TTS_MODEL_ID = "Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice"
OPTIONAL_TTS_MODEL_ID = "Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice"
DEFAULT_VOICE_INSTRUCT = "차분하고 명확한 한국어 보행 안전 안내 음성. 너무 빠르지 않게 말하세요."
DEFAULT_TTS_SPEAKER = "Sohee"
SUPPORTED_TTS_SPEAKERS = frozenset(
    {"Vivian", "Serena", "Uncle_Fu", "Dylan", "Eric", "Ryan", "Aiden", "Ono_Anna", "Sohee"}
)
TTSMode = Literal["custom", "design", "clone"]


@dataclass(frozen=True)
class TTSResult:
    output_path: Path
    sample_rate: int
    duration_sec: float
    model: str
    cached: bool
    mode: TTSMode
    model_revision: str | None = None
    transient: bool = False


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
        speaker: str | None = DEFAULT_TTS_SPEAKER,
        ref_audio: str | None = None,
        ref_text: str | None = None,
        cache_dir: str | Path = "outputs/voice/cache",
        cache_max_files: int = 256,
        cache_max_bytes: int = 512 * 1024 * 1024,
        model_revision: str | None = None,
        model_manifest_path: str | Path | None = None,
        model_manifest_sha256: str | None = None,
    ) -> None:
        if mode not in {"custom", "design", "clone"}:
            raise ValueError(f"Unsupported TTS mode: {mode!r}")
        if not hasattr(torch, dtype):
            raise ValueError(f"Unsupported torch dtype: {dtype!r}")
        if mode == "custom" and speaker not in SUPPORTED_TTS_SPEAKERS:
            raise ValueError("Custom voice speaker must be one of the supported Qwen3-TTS presets")
        self.model_id = model_id
        self.model_revision = model_revision
        self.model_manifest_path = model_manifest_path
        self.model_manifest_sha256 = model_manifest_sha256
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
        cache_metadata = self.cache_dir.stat(follow_symlinks=False)
        if (
            self.cache_dir.is_symlink()
            or not stat.S_ISDIR(cache_metadata.st_mode)
            or cache_metadata.st_uid != os.geteuid()
        ):
            raise ValueError("TTS cache directory must be a service-owned real directory")
        self.cache_dir.chmod(0o700)
        self.cache_max_files = max(1, cache_max_files)
        self.cache_max_bytes = max(1024 * 1024, cache_max_bytes)
        self._model = None
        self._loaded_revision: str | None = None
        self._load_lock = Lock()

    def _model_load_path(self) -> str:
        if not self.model_revision or not self.model_manifest_path or not self.model_manifest_sha256:
            raise ValueError(
                "TTS model load requires an immutable revision and hash-pinned file manifest"
            )
        return str(
            verify_model_snapshot(
                "tts",
                self.model_id,
                self.model_revision,
                self.model_manifest_path,
                self.model_manifest_sha256,
            )
        )

    def load(self) -> None:
        if self._model is not None:
            return
        with self._load_lock:
            if self._model is not None:
                return
            model_path = self._model_load_path()
            try:
                from qwen_tts import Qwen3TTSModel
            except ImportError as exc:
                raise RuntimeError(
                    "qwen-tts is not installed. Install with `pip install -r voice/requirements.txt`."
                ) from exc

            kwargs = {
                "device_map": self.device_map,
                "dtype": self.dtype,
            }
            if self.attn_implementation:
                kwargs["attn_implementation"] = self.attn_implementation
            self._model = Qwen3TTSModel.from_pretrained(model_path, **kwargs)
            self._loaded_revision = self.model_revision

    def _prune_cache(self, protected: Path) -> None:
        lock_path = self.cache_dir / ".cache-retention.lock"
        descriptor = os.open(
            lock_path,
            os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0),
            0o600,
        )
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX)
            entries = [
                path
                for path in self.cache_dir.glob("qwen3_tts_*.wav")
                if path != protected and path.is_file() and not path.is_symlink()
            ]
            entries.sort(key=lambda path: path.stat().st_mtime, reverse=True)
            protected_size = protected.stat().st_size if protected.exists() else 0
            kept_files = 1
            kept_bytes = protected_size
            for path in entries:
                size = path.stat().st_size
                if kept_files < self.cache_max_files and kept_bytes + size <= self.cache_max_bytes:
                    kept_files += 1
                    kept_bytes += size
                    continue
                path.unlink(missing_ok=True)
            directory_fd = os.open(self.cache_dir, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            fcntl.flock(descriptor, fcntl.LOCK_UN)
            os.close(descriptor)

    def _cache_path(
        self,
        text: str,
        mode: TTSMode,
        suffix: str = ".wav",
        *,
        voice_instruct: str | None = None,
        speaker: str | None = None,
        ref_audio: str | None = None,
        ref_text: str | None = None,
    ) -> Path:
        effective_voice_instruct = voice_instruct or self.voice_instruct
        effective_speaker = speaker or self.speaker or ""
        effective_ref_audio = ref_audio or self.ref_audio or ""
        effective_ref_text = ref_text or self.ref_text or ""
        key = hashlib.sha256(
            (
                f"{self.model_id}@{self.model_revision or 'missing'}|"
                f"{self.language}|{mode}|{effective_voice_instruct}|"
                f"{effective_speaker}|{effective_ref_audio}|{effective_ref_text}|{text}"
            ).encode("utf-8")
        ).hexdigest()[:20]
        return self.cache_dir / f"qwen3_tts_{key}{suffix}"

    def synthesize(
        self,
        text: str,
        output_path: str | Path | None = None,
        use_cache: bool = False,
        mode: TTSMode | None = None,
        voice_instruct: str | None = None,
        ref_audio: str | None = None,
        ref_text: str | None = None,
    ) -> TTSResult:
        synth_mode: TTSMode = mode or self.mode
        transient = output_path is None and not use_cache
        if transient:
            descriptor, transient_name = tempfile.mkstemp(
                dir=self.cache_dir,
                prefix=".tts-request-",
                suffix=".wav",
            )
            os.close(descriptor)
            output = Path(transient_name)
            output.unlink(missing_ok=True)
        else:
            output = (
                Path(output_path)
                if output_path
                else self._cache_path(
                    text,
                    synth_mode,
                    voice_instruct=voice_instruct,
                    ref_audio=ref_audio,
                    ref_text=ref_text,
                )
            )
        output.parent.mkdir(parents=True, exist_ok=True)
        if use_cache and output.exists() and output.stat().st_size > 0:
            if output.parent.resolve() == self.cache_dir.resolve():
                self._prune_cache(output)
            return TTSResult(
                output,
                sample_rate=0,
                duration_sec=0.0,
                model=self.model_id,
                cached=True,
                mode=synth_mode,
                model_revision=self.model_revision,
            )

        if output.parent.resolve() == self.cache_dir.resolve():
            lock_bucket = hashlib.sha256(output.name.encode("utf-8")).hexdigest()[:2]
            lock_path = self.cache_dir / f".synthesis-{lock_bucket}.lock"
        else:
            lock_path = output.with_suffix(f"{output.suffix}.lock")
        lock_descriptor = os.open(
            lock_path,
            os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0),
            0o600,
        )
        fcntl.flock(lock_descriptor, fcntl.LOCK_EX)
        temporary_path: Path | None = None
        synthesis_complete = False
        try:
            if use_cache and output.exists() and output.stat().st_size > 0:
                if output.parent.resolve() == self.cache_dir.resolve():
                    self._prune_cache(output)
                return TTSResult(
                    output,
                    sample_rate=0,
                    duration_sec=0.0,
                    model=self.model_id,
                    cached=True,
                    mode=synth_mode,
                    model_revision=self.model_revision,
                )

            self.load()
            assert self._model is not None
            try:
                import soundfile as sf
            except ImportError as exc:
                raise RuntimeError("soundfile is not installed. Install voice/requirements.txt.") from exc

            start = time.perf_counter()
            if synth_mode == "custom":
                speaker = self.speaker
                if speaker not in SUPPORTED_TTS_SPEAKERS:
                    raise RuntimeError("Configured CustomVoice speaker is not supported")
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
            with tempfile.NamedTemporaryFile(
                dir=output.parent,
                prefix=f".{output.name}.",
                suffix=".tmp.wav",
                delete=False,
            ) as temporary:
                temporary_path = Path(temporary.name)
            sf.write(str(temporary_path), wavs[0], sample_rate)
            with temporary_path.open("rb") as stream:
                os.fsync(stream.fileno())
            os.replace(temporary_path, output)
            temporary_path = None
            directory_fd = os.open(output.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
            if not transient and output.parent.resolve() == self.cache_dir.resolve():
                self._prune_cache(output)
            synthesis_complete = True
            return TTSResult(
                output,
                sample_rate=sample_rate,
                duration_sec=elapsed,
                model=self.model_id,
                cached=False,
                mode=synth_mode,
                model_revision=self._loaded_revision,
                transient=transient,
            )
        finally:
            if temporary_path is not None:
                temporary_path.unlink(missing_ok=True)
            if transient and not synthesis_complete:
                output.unlink(missing_ok=True)
            fcntl.flock(lock_descriptor, fcntl.LOCK_UN)
            os.close(lock_descriptor)
