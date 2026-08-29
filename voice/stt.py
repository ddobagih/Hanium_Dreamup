"""Lazy local speech transcription and rule-based intent classification.

Faster-Whisper segment diagnostics are reduced into explicit acoustic evidence.
The API combines that evidence with the rule score before allowing an action.
"""

from __future__ import annotations

import time
import math
from dataclasses import dataclass
from pathlib import Path
from threading import Lock
from typing import Any

import torch

from voice.intents import classify_intent
from voice.model_integrity import verify_model_snapshot


MIN_EXECUTION_AVG_LOGPROB = -1.0
MAX_EXECUTION_NO_SPEECH_PROBABILITY = 0.6


@dataclass(frozen=True)
class STTAcousticEvidence:
    avg_logprob: float | None
    no_speech_probability: float | None
    confidence: float
    execution_allowed: bool


def summarize_acoustic_evidence(segments: list[dict[str, Any]]) -> STTAcousticEvidence:
    """Return a conservative, testable execution gate from spoken segments."""
    spoken_segments = [segment for segment in segments if str(segment.get("text") or "").strip()]
    logprobs = [
        float(value)
        for segment in spoken_segments
        if isinstance((value := segment.get("avg_logprob")), (int, float)) and math.isfinite(value)
    ]
    no_speech_values = [
        float(value)
        for segment in spoken_segments
        if isinstance((value := segment.get("no_speech_prob")), (int, float))
        and math.isfinite(value)
        and 0 <= value <= 1
    ]
    avg_logprob = sum(logprobs) / len(logprobs) if logprobs else None
    no_speech_probability = max(no_speech_values) if no_speech_values else None
    if avg_logprob is None or no_speech_probability is None:
        return STTAcousticEvidence(avg_logprob, no_speech_probability, 0.0, False)

    logprob_score = min(1.0, max(0.0, (avg_logprob - MIN_EXECUTION_AVG_LOGPROB) / -MIN_EXECUTION_AVG_LOGPROB))
    confidence = min(1.0, max(0.0, logprob_score * (1 - no_speech_probability)))
    return STTAcousticEvidence(
        avg_logprob=avg_logprob,
        no_speech_probability=no_speech_probability,
        confidence=confidence,
        execution_allowed=(
            avg_logprob >= MIN_EXECUTION_AVG_LOGPROB
            and no_speech_probability <= MAX_EXECUTION_NO_SPEECH_PROBABILITY
        ),
    )


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
    model_revision: str
    acoustic: STTAcousticEvidence


class LocalSTTEngine:
    """Lazy faster-whisper wrapper for local GPU/CPU inference."""

    def __init__(
        self,
        model_size: str = "medium",
        device: str | None = None,
        compute_type: str | None = None,
        language: str = "ko",
        model_revision: str | None = None,
        model_manifest_path: str | Path | None = None,
        model_manifest_sha256: str | None = None,
    ) -> None:
        self.model_size = model_size
        self.model_revision = model_revision
        self.model_manifest_path = model_manifest_path
        self.model_manifest_sha256 = model_manifest_sha256
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.compute_type = compute_type or ("float16" if self.device == "cuda" else "int8")
        self.language = language
        self._model = None
        self._loaded_revision: str | None = None
        self._load_lock = Lock()

    def load(self) -> None:
        if self._model is not None:
            return
        with self._load_lock:
            if self._model is not None:
                return
            if not self.model_revision or not self.model_manifest_path or not self.model_manifest_sha256:
                raise ValueError(
                    "STT model load requires an immutable revision and hash-pinned file manifest"
                )
            model_path = verify_model_snapshot(
                "stt",
                self.model_size,
                self.model_revision,
                self.model_manifest_path,
                self.model_manifest_sha256,
            )
            try:
                from faster_whisper import WhisperModel
            except ImportError as exc:
                raise RuntimeError(
                    "faster-whisper is not installed. Install with `pip install -r voice/requirements.txt`."
                ) from exc
            self._model = WhisperModel(str(model_path), device=self.device, compute_type=self.compute_type)
            self._loaded_revision = self.model_revision

    def transcribe_file(self, audio_path: str | Path, beam_size: int = 5) -> STTResult:
        self.load()
        assert self._model is not None
        if self._loaded_revision is None:
            raise RuntimeError("STT model revision is not bound to the loaded model")
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
        acoustic = summarize_acoustic_evidence(segments)
        return STTResult(
            transcript=transcript,
            intent=intent.intent,
            score=intent.score,
            slots=intent.slots,
            language=getattr(info, "language", None),
            duration_sec=elapsed,
            segments=segments,
            model=self.model_size,
            model_revision=self._loaded_revision,
            acoustic=acoustic,
        )
