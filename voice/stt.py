from __future__ import annotations

import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import torch

from voice.intents import classify_intent


@dataclass(frozen=True)
class STTResult:
    transcript: str
    intent: str
    score: float
    slots: dict[str, Any]
    language: str | None
    duration_sec: float
    segments: list[dict[str, Any]]
    model: str


class LocalSTTEngine:
    """Lazy faster-whisper wrapper for local GPU/CPU inference."""

    def __init__(
        self,
        model_size: str = "medium",
        device: str | None = None,
        compute_type: str | None = None,
        language: str = "ko",
    ) -> None:
        self.model_size = model_size
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.compute_type = compute_type or ("float16" if self.device == "cuda" else "int8")
        self.language = language
        self._model = None

    def load(self) -> None:
        if self._model is not None:
            return
        try:
            from faster_whisper import WhisperModel
        except ImportError as exc:
            raise RuntimeError(
                "faster-whisper is not installed. Install with `pip install -r requirements-voice.txt`."
            ) from exc
        self._model = WhisperModel(self.model_size, device=self.device, compute_type=self.compute_type)

    def transcribe_file(self, audio_path: str | Path, beam_size: int = 5) -> STTResult:
        self.load()
        assert self._model is not None
        start = time.perf_counter()
        segments_iter, info = self._model.transcribe(
            str(audio_path),
            language=self.language,
            beam_size=beam_size,
            vad_filter=True,
        )
        segments = []
        transcript_parts = []
        for segment in segments_iter:
            text = segment.text.strip()
            transcript_parts.append(text)
            segments.append(
                {
                    "start": segment.start,
                    "end": segment.end,
                    "text": text,
                    "avg_logprob": getattr(segment, "avg_logprob", None),
                    "no_speech_prob": getattr(segment, "no_speech_prob", None),
                }
            )
        elapsed = time.perf_counter() - start
        transcript = " ".join(part for part in transcript_parts if part).strip()
        intent = classify_intent(transcript)
        return STTResult(
            transcript=transcript,
            intent=intent.intent,
            score=intent.score,
            slots=intent.slots,
            language=getattr(info, "language", None),
            duration_sec=elapsed,
            segments=segments,
            model=self.model_size,
        )
